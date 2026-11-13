package com.flowkey.ime

import android.content.Context
import android.util.Log
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
 * Default engine: multilingual Whisper via OfflineRecognizer (EN/CS/FR/ES + auto-detect).
 * Optional engine: English streaming Zipformer via OnlineRecognizer (live partials).
 */
object RecognizerManager {

    const val SAMPLE_RATE = 16000

    private const val TAG = "FlowKeyRecognizer"

    sealed class Engine {
        class Whisper(val recognizer: OfflineRecognizer) : Engine()
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
     * Idempotent: does nothing if the right engine is already loaded or loading.
     * Pass [force] to retry after an error / missing model, or after switching engines.
     */
    @Synchronized
    fun initialize(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val wantStreaming = Prefs.isStreamingMode(appContext)
        when (val current = _state.value) {
            is State.Loading -> return
            is State.Ready -> {
                if (loadedStreamingMode == wantStreaming && !force) {
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

    /** Applies the persisted language to a loaded Whisper engine (no model reload). */
    fun applyLanguage(context: Context) {
        val appContext = context.applicationContext
        val ready = _state.value as? State.Ready ?: return
        val engine = ready.engine as? Engine.Whisper ?: return
        val lang = Prefs.whisperLanguage(appContext)
        if (lang == loadedLanguage) return
        val files = whisperFiles ?: return
        scope.launch {
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

    private fun load(context: Context, streaming: Boolean) {
        try {
            if (streaming) loadStreaming(context) else loadWhisper(context)
        } catch (t: Throwable) {
            // Also catches UnsatisfiedLinkError when the sherpa-onnx AAR is missing.
            Log.e(TAG, "Failed to load recognizer (streaming=$streaming)", t)
            _state.value = State.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun loadWhisper(context: Context) {
        val model = ModelLocator.locateWhisper(context)
        if (model == null) {
            _state.value = State.ModelMissing(ModelLocator.whisperDir(context).absolutePath, false)
            return
        }
        whisperFiles = model
        val lang = Prefs.whisperLanguage(context)
        val recognizer = OfflineRecognizer(config = whisperConfig(model, lang))
        loadedStreamingMode = false
        loadedLanguage = lang
        _state.value = State.Ready(Engine.Whisper(recognizer))
    }

    private fun loadStreaming(context: Context) {
        val model = ModelLocator.locateZipformer(context)
        if (model == null) {
            _state.value = State.ModelMissing(ModelLocator.zipformerDir(context).absolutePath, true)
            return
        }
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
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 2.0f, 0.0f),   // ~2 s trailing silence after speech
                rule3 = EndpointRule(false, 0.0f, 30.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        val recognizer = OnlineRecognizer(config = config)
        loadedStreamingMode = true
        loadedLanguage = "en"
        _state.value = State.Ready(Engine.Streaming(recognizer))
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
            ),
            tokens = model.tokens,
            numThreads = 2,
            provider = "cpu",
            modelType = "whisper",
        ),
        decodingMethod = "greedy_search",
    )
}
