package com.flowkey.ime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Loads and owns the (expensive) sherpa-onnx recognizer as a process-wide singleton.
 *
 * Default engine: multilingual Whisper via OfflineRecognizer (99 languages + auto-detect).
 * Optional engine: English streaming Zipformer via OnlineRecognizer (live partials).
 */
object RecognizerManager {

    const val SAMPLE_RATE = 16000

    private const val TAG = "FlowKeyRecognizer"

    sealed class Engine {
        class Whisper(val recognizer: OfflineRecognizer, val variant: String) : Engine()
        class Streaming(val recognizer: OnlineRecognizer) : Engine()
    }

    sealed class State {
        data object NotLoaded : State()
        data object Loading : State()
        data class Ready(val engine: Engine) : State()
        data class ModelMissing(val searchedPath: String, val streamingMode: Boolean) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotLoaded)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var whisperFiles: ModelLocator.WhisperModel? = null
    private var loadedStreamingMode: Boolean? = null
    private var loadedLanguage: String? = null

    /**
     * Dictation sessions that may still use the engine (a cancelled session can still be inside
     * a native decode). A replaced engine is released right away only when this is zero, and
     * Whisper's language is only switched (setConfig) while no session decodes.
     */
    private val activeSessions = AtomicInteger(0)

    val sessionActive: Boolean get() = activeSessions.get() > 0

    /** Call BEFORE reading the engine from [state] for a new dictation session. */
    fun sessionStarted() {
        activeSessions.incrementAndGet()
    }

    /** Call exactly once per [sessionStarted], when that session has fully finished. */
    fun sessionEnded(context: Context) {
        if (activeSessions.decrementAndGet() <= 0) {
            activeSessions.set(0)
            if (languageDirty) applyLanguage(context)
        }
    }

    /** A language change arrived while a session was decoding; apply it when it ends. */
    @Volatile private var languageDirty = false
    private val languageMutex = Mutex()

    /** Crash flags describe the PREVIOUS process, so they are read once, before any load. */
    @Volatile private var crashFlagsChecked = false

    /** The engine currently published as Ready (state is Loading while it is replaced). */
    @Volatile private var lastEngine: Engine? = null

    /** A forced reload asked for while a load was running; re-run once that load ends. */
    @Volatile private var reloadRequested = false

    /**
     * Idempotent: does nothing if the right engine is already loaded or loading.
     * Pass [force] to retry after an error / missing model, or after switching engines.
     */
    @Synchronized
    fun initialize(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!crashFlagsChecked) {
            // Once per process: while THIS process loads, the flags are legitimately set, and
            // reading them again then would mistake a load in progress for a crash.
            crashFlagsChecked = true
            checkPreviousCrash(appContext)
        }
        val wantStreaming = Prefs.isStreamingMode(appContext)
        val wantVariant = Prefs.getWhisperVariant(appContext)
        when (val current = _state.value) {
            is State.Loading -> {
                // Don't drop a forced reload (e.g. two quick word edits): redo it afterwards.
                if (force) reloadRequested = true
                return
            }
            is State.Ready -> {
                // Compare with the variant a load would pick NOW (wanted, else the fallback),
                // so a fallback engine isn't reloaded on every keyboard open.
                val variantOk = wantStreaming ||
                    (current.engine as? Engine.Whisper)?.variant ==
                    ModelLocator.locateWhisper(appContext, wantVariant, smallAllowed(appContext))?.variant
                if (loadedStreamingMode == wantStreaming && variantOk && !force) {
                    if (!wantStreaming) applyLanguage(appContext)
                    return
                }
            }
            is State.Error -> if (!force) return // avoid crash loops; retry via Setup
            else -> Unit // NotLoaded / ModelMissing: re-checking the disk is cheap
        }
        // Old engine (if any) is intentionally NOT released here: a dictation session
        // may still hold it. The Kotlin wrappers free native memory in finalize().
        _state.value = State.Loading
        scope.launch { load(appContext, wantStreaming) }
    }

    /**
     * Applies the persisted language to a loaded Whisper engine (no model reload). Changes are
     * applied one at a time with the LATEST preference (rapid taps can't land out of order),
     * and never while a session decodes: sherpa's setConfig is not safe during decode().
     */
    fun applyLanguage(context: Context) {
        val appContext = context.applicationContext
        if (sessionActive) {
            languageDirty = true
            return
        }
        scope.launch {
            languageMutex.withLock {
                if (sessionActive) {
                    languageDirty = true
                    return@withLock
                }
                languageDirty = false
                val engine = (_state.value as? State.Ready)?.engine as? Engine.Whisper ?: return@withLock
                val files = whisperFiles ?: return@withLock
                val lang = Prefs.whisperLanguage(appContext)
                if (lang == loadedLanguage) return@withLock
                try {
                    engine.recognizer.setConfig(whisperConfig(files, lang))
                    loadedLanguage = lang
                } catch (t: Throwable) {
                    Log.w(TAG, "setConfig failed; rebuilding recognizer for language '$lang'", t)
                    // Fallback: rebuild the recognizer with the new language baked in.
                    initialize(appContext, force = true)
                }
            }
        }
    }

    private fun smallAllowed(context: Context) = !Prefs.isWhisperSmallBlocked(context)

    /** The crash guards: a load that never finished means native code killed the process. */
    private fun checkPreviousCrash(context: Context) {
        // Hotwords first: a crash with hotwords sets both flags, and only hotwords are to blame.
        if (Prefs.isHotwordsLoadPending(context)) {
            Log.e(TAG, "Previous streaming load with hotwords crashed; disabling hotwords")
            Prefs.disableHotwordsAfterCrash(context)
            Prefs.setStreamingLoadPending(context, false)
        }
        if (Prefs.isStreamingLoadPending(context)) {
            Log.e(TAG, "Previous streaming model load crashed; falling back to Whisper")
            Prefs.disableStreamingAfterCrash(context)
        }
        if (Prefs.isWhisperSmallLoadPending(context)) {
            // Usually out of memory: Whisper small needs ~400 MB on top of the keyboard.
            Log.e(TAG, "Previous Whisper small load crashed; blocking it and using base")
            Prefs.blockWhisperSmallAfterCrash(context)
        }
    }

    private fun load(context: Context, streaming: Boolean) {
        try {
            if (streaming) loadStreaming(context) else loadWhisper(context)
        } catch (t: Throwable) {
            // Also catches UnsatisfiedLinkError when the sherpa-onnx AAR is missing.
            Log.e(TAG, "Failed to load recognizer (streaming=$streaming)", t)
            _state.value = State.Error(t.message ?: t.javaClass.simpleName)
        } finally {
            if (reloadRequested) {
                reloadRequested = false
                initialize(context, force = true)
            }
        }
    }

    /**
     * The user's words or dictionary changed. They are the streaming engine's hotwords, which
     * are baked in when the recognizer is built, so rebuild it if streaming is active.
     */
    fun onUserTermsChanged(context: Context) {
        val appContext = context.applicationContext
        Prefs.clearHotwordsDisabled(appContext) // each edit earns one more try
        if (Prefs.isStreamingMode(appContext)) initialize(appContext, force = true)
    }

    /**
     * Writes the hotwords file for the streaming engine, or returns null when hotwords can't
     * or shouldn't be used. Every condition here guards a native exit() in sherpa-onnx:
     * a missing hotwords/vocab file, or hotwords for a model the vocab doesn't belong to.
     */
    private fun prepareHotwords(context: Context, model: ModelLocator.ZipformerModel): Pair<String, String>? {
        if (Prefs.isHotwordsDisabledAfterCrash(context)) {
            Log.w(TAG, "Hotwords disabled after a crash; using plain greedy search")
            return null
        }
        val lines = Hotwords.format(UserData.userTerms(context))
        if (lines.isEmpty()) return null
        val vocab = ModelLocator.bpeVocab(context) ?: return null
        if (!ModelLocator.vocabMatchesTokens(vocab, File(model.tokens))) {
            Log.w(TAG, "Streaming model tokens don't match the bundled BPE vocab; no hotwords")
            return null
        }
        return try {
            val dir = ModelLocator.hotwordsDir(context).apply { mkdirs() }
            val out = File(dir, "hotwords.txt")
            val tmp = File(dir, "hotwords.txt.part")
            tmp.writeText(lines.joinToString("\n", postfix = "\n"))
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IOException("rename failed")
            if (out.isFile && vocab.isFile) out.absolutePath to vocab.absolutePath else null
        } catch (e: IOException) {
            Log.w(TAG, "Could not write hotwords file; continuing without hotwords", e)
            null
        }
    }

    private fun loadWhisper(context: Context) {
        val model = ModelLocator.locateWhisper(context, Prefs.getWhisperVariant(context), smallAllowed(context))
        if (model == null) {
            _state.value = State.ModelMissing(ModelLocator.whisperDir(context).absolutePath, false)
            return
        }
        whisperFiles = model
        val lang = Prefs.whisperLanguage(context)
        val small = model.variant == Prefs.VARIANT_SMALL
        if (small) Prefs.setWhisperSmallLoadPending(context, true)
        val recognizer = try {
            OfflineRecognizer(config = whisperConfig(model, lang))
        } finally {
            if (small) Prefs.setWhisperSmallLoadPending(context, false)
        }
        val previous = lastEngine
        loadedStreamingMode = false
        loadedLanguage = lang
        Log.i(TAG, "Whisper ${model.variant} loaded")
        val engine = Engine.Whisper(recognizer, model.variant)
        lastEngine = engine
        _state.value = State.Ready(engine)
        releaseIfIdle(previous)
    }

    private fun loadStreaming(context: Context) {
        val model = ModelLocator.locateZipformer(context)
        if (model == null) {
            _state.value = State.ModelMissing(ModelLocator.zipformerDir(context).absolutePath, true)
            return
        }
        val hot = prepareHotwords(context, model)
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = model.encoder,
                    decoder = model.decoder,
                    joiner = model.joiner,
                ),
                tokens = model.tokens,
                numThreads = 2,
                provider = "cpu",
                // The bundled sherpa-onnx-streaming-zipformer-en-2023-06-26 is a Zipformer2
                // model ("model_type" metadata). "zipformer" makes sherpa-onnx look for
                // Zipformer1 metadata and exit the process when it is missing.
                modelType = "zipformer2",
                // Hotwords need the BPE vocab; an empty modelingUnit with hotwords is a native exit.
                modelingUnit = if (hot != null) "bpe" else "",
                bpeVocab = hot?.second ?: "",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 2.0f, 0.0f),   // ~2 s trailing silence after speech
                rule3 = EndpointRule(false, 0.0f, 30.0f),
            ),
            enableEndpoint = true,
            // Hotwords only work with beam search (greedy search ignores them).
            decodingMethod = if (hot != null) "modified_beam_search" else "greedy_search",
            maxActivePaths = 4,
            hotwordsFile = hot?.first ?: "",
            hotwordsScore = Hotwords.SCORE,
        )
        Log.i(TAG, "Loading streaming engine (hotwords=${hot != null}, decoding=${config.decodingMethod})")
        Prefs.setStreamingLoadPending(context, true)
        if (hot != null) Prefs.setHotwordsLoadPending(context, true)
        val recognizer = try {
            OnlineRecognizer(config = config)
        } finally {
            // Reached on success or on a Java-level error; a native abort never gets here.
            Prefs.setStreamingLoadPending(context, false)
            if (hot != null) Prefs.setHotwordsLoadPending(context, false)
        }
        val previous = lastEngine
        loadedStreamingMode = true
        loadedLanguage = "en"
        val engine = Engine.Streaming(recognizer)
        lastEngine = engine
        _state.value = State.Ready(engine)
        releaseIfIdle(previous)
    }

    /**
     * Frees a replaced engine's native memory now (Whisper small alone is ~400 MB) instead of
     * waiting for finalize(). Skipped while a dictation session may still be decoding with it.
     */
    private fun releaseIfIdle(engine: Engine?) {
        if (engine == null || sessionActive) return
        try {
            when (engine) {
                is Engine.Whisper -> engine.recognizer.release()
                is Engine.Streaming -> engine.recognizer.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Releasing the previous engine failed", t)
        }
    }

    private fun whisperConfig(
        model: ModelLocator.WhisperModel,
        language: String,
    ) = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = model.encoder,
                decoder = model.decoder,
                language = language, // "" = per-utterance auto-detect
                task = "transcribe",
                // Zero padding after the audio, in frames. sherpa's default (1000 = 10 s) makes
                // every short phrase cost a 10 s+ encoder pass. 500 measured as accurate as 1000
                // (300 misheard a word) and nearly as fast as 300.
                tailPaddings = 500,
            ),
            tokens = model.tokens,
            numThreads = 2,
            provider = "cpu",
            modelType = "whisper",
        ),
        decodingMethod = "greedy_search",
    )
}
