package com.flowkey.ime

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Voice-only input method. Default flow (Whisper offline engine, any language):
 * tap mic -> record -> words appear live (VAD phrases decoded as you speak) -> tap again or
 * auto-stop on trailing silence -> cleaned text committed. With live text off (or no VAD
 * bundled) the whole take is transcribed once after it ends.
 *
 * Optional "English streaming mode" (Zipformer online engine): live partial
 * results stream into the field as composing text; endpoint detection commits
 * each segment automatically for hands-free continuous dictation.
 */
class VoiceInputIME : InputMethodService() {

    private companion object {
        const val TAG = "FlowKeyIME"
        const val SAMPLE_RATE = RecognizerManager.SAMPLE_RATE
        const val CHUNK = 1600                    // 100 ms of audio at 16 kHz
        const val SILENCE_RMS = 0.012f            // normalized RMS below this counts as silence
        const val NO_SPEECH_TIMEOUT_MS = 8000L    // give up if nothing was ever said
        const val MAX_TAKE_MS = 60_000L           // hard cap per Whisper take (~3.7 MB audio)
        const val VAD_ASSET_NAME = "silero_vad.onnx"
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE / 2L  // audio kept before a detected onset
        const val HISTORY_SAMPLES = SAMPLE_RATE * 40L  // rolling audio kept for phrase decodes
        const val BACKSPACE_FIRST_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_MS = 50L
    }

    /** Cleanup options captured once per dictation session. */
    private data class SessionOptions(
        val autoStop: Boolean,
        val autoStopMs: Long,
        val removeFillers: Boolean,
        val punctuationCommands: Boolean,
        val correctWords: Boolean,
        val liveText: Boolean,
        /** Prefs language for this session ("auto" or a code); "en" in streaming mode. */
        val language: String,
    )

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var sessionJob: Job? = null

    @Volatile private var recording = false
    @Volatile private var commitOnStop = true
    @Volatile private var transcribing = false

    private var statusText: TextView? = null
    private var actionButton: TextView? = null
    private var micButton: ImageButton? = null
    private var langButton: TextView? = null
    private var pulse: ObjectAnimator? = null
    private var loadingAnim: ObjectAnimator? = null
    private var toneGenerator: ToneGenerator? = null

    // Sentence context carried across committed segments within one session.
    private var capitalizeNext = true
    private var needLeadingSpace = false

    private var dictionary: List<UserData.Entry> = emptyList()
    private var snippets: List<UserData.Entry> = emptyList()
    private var userTerms: List<String> = emptyList()
    private var options = SessionOptions(
        autoStop = true,
        autoStopMs = 2500L,
        removeFillers = true,
        punctuationCommands = true,
        correctWords = true,
        liveText = true,
        language = "en",
    )

    /** Auto mode: the language Whisper detected first in this session (sticky). */
    private var sessionLang: String? = null

    /** True while the running session is a live Whisper session. */
    private var liveSession = false

    /** The text field a session started in; text is never committed into another one. */
    private var sessionField: Pair<String?, Int>? = null

    override fun onCreate() {
        super.onCreate()
        RecognizerManager.initialize(this)
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusText = view.findViewById(R.id.statusText)
        actionButton = view.findViewById(R.id.actionButton)
        micButton = view.findViewById(R.id.micButton)
        langButton = view.findViewById(R.id.langButton)

        micButton?.setOnClickListener {
            haptic(it)
            onMicTap()
        }
        actionButton?.setOnClickListener { openSetup() }
        setupBackspace(view.findViewById(R.id.backspaceButton))
        view.findViewById<ImageButton>(R.id.enterButton).setOnClickListener {
            haptic(it)
            onEnter()
        }
        view.findViewById<TextView>(R.id.spaceButton).setOnClickListener {
            haptic(it)
            onSpace()
        }
        view.findViewById<ImageButton>(R.id.globeButton).setOnClickListener { switchKeyboard() }
        view.findViewById<ImageButton>(R.id.settingsButton).setOnClickListener { openSetup() }
        langButton?.setOnClickListener {
            haptic(it)
            onLanguageTap()
        }
        updateLangButton()
        return view
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        RecognizerManager.initialize(this)
        updateLangButton()
        stateJob?.cancel()
        stateJob = mainScope.launch {
            RecognizerManager.state.collect { refreshIdleStatus() }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (sessionJob?.isActive == true && liveSession) {
            // Live session: keep what is already shown in THIS field and drop pending audio,
            // instead of committing later into whatever field gets focus next.
            currentInputConnection?.finishComposingText()
            stopDictation(commitPending = false)
        } else {
            stopDictation(commitPending = true)
        }
        stateJob?.cancel()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopDictation(commitPending = false)
        mainScope.cancel()
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    // Keep the panel small; never switch to the fullscreen extract editor.
    override fun onEvaluateFullscreenMode(): Boolean = false

    // ------------------------------------------------------------------ keys

    private fun onMicTap() {
        if (transcribing) return
        if (recording) {
            playCue(start = false)
            stopDictation(commitPending = true)
            return
        }
        if (!hasMicPermission()) {
            showActionableStatus(getString(R.string.status_no_permission))
            return
        }
        when (val s = RecognizerManager.state.value) {
            is RecognizerManager.State.Ready -> startDictation(s.engine)
            is RecognizerManager.State.Loading,
            RecognizerManager.State.NotLoaded -> {
                setStatus(getString(R.string.status_loading))
                RecognizerManager.initialize(this)
            }
            is RecognizerManager.State.ModelMissing ->
                showActionableStatus(getString(R.string.status_no_model))
            is RecognizerManager.State.Error ->
                showActionableStatus(getString(R.string.status_engine_error, s.message))
        }
    }

    private fun onLanguageTap() {
        if (recording || transcribing) return
        if (Prefs.isStreamingMode(this)) {
            setStatus(getString(R.string.status_streaming_en_only))
            return
        }
        Prefs.nextLanguage(this)
        updateLangButton()
        RecognizerManager.applyLanguage(this)
        setStatus(getString(R.string.status_language_set, langLabel()))
    }

    private fun onEnter() {
        if (recording || transcribing) return
        if (!sendDefaultEditorAction(true)) {
            currentInputConnection?.commitText("\n", 1)
        }
        capitalizeNext = true
        needLeadingSpace = false
    }

    private fun onSpace() {
        if (recording || transcribing) return
        currentInputConnection?.commitText(" ", 1)
        needLeadingSpace = false
    }

    private fun sendBackspace() {
        // Key events handle selections and complex editors better than
        // deleteSurroundingText for a generic target field.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBackspace(button: ImageButton) {
        val repeater = object : Runnable {
            override fun run() {
                sendBackspace()
                button.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
        button.setOnClickListener { /* handled in touch listener; kept for a11y */ }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!recording && !transcribing) {
                        haptic(v)
                        sendBackspace()
                        v.postDelayed(repeater, BACKSPACE_FIRST_DELAY_MS)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(repeater)
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun switchKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToPreviousInputMethod()) imm.showInputMethodPicker()
        } else {
            @Suppress("DEPRECATION")
            val token = window?.window?.attributes?.token
            @Suppress("DEPRECATION")
            if (token == null || !imm.switchToLastInputMethod(token)) {
                imm.showInputMethodPicker()
            }
        }
    }

    private fun openSetup() {
        startActivity(
            Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        requestHideSelf(0)
    }

    // ------------------------------------------------------------- dictation

    private fun startDictation(engine: RecognizerManager.Engine) {
        if (recording || sessionJob?.isActive == true) return

        val before = currentInputConnection?.getTextBeforeCursor(16, 0)?.toString().orEmpty()
        val trimmed = before.trimEnd(' ')
        capitalizeNext = trimmed.isEmpty() || trimmed.last() in ".!?\n"
        needLeadingSpace = before.isNotEmpty() && !before.last().isWhitespace()
        dictionary = UserData.getDictionary(this)
        snippets = UserData.getSnippets(this)
        userTerms = UserData.userTerms(this)
        options = SessionOptions(
            autoStop = Prefs.isAutoStopEnabled(this),
            autoStopMs = Prefs.getAutoStopMs(this),
            removeFillers = Prefs.isFillerRemovalEnabled(this),
            punctuationCommands = Prefs.isPunctuationCommandsEnabled(this),
            correctWords = Prefs.isWordCorrectionEnabled(this),
            liveText = Prefs.isLiveTextEnabled(this),
            language = if (Prefs.isStreamingMode(this)) "en" else Prefs.getLanguage(this),
        )
        sessionLang = null
        sessionField = currentInputEditorInfo?.let { it.packageName to it.fieldId }

        val record = createAudioRecord()
        if (record == null) {
            setStatus(getString(R.string.status_audio_error))
            return
        }

        recording = true
        commitOnStop = true
        RecognizerManager.sessionActive = true
        setRecordingUi(true)
        playCue(start = true)
        record.startRecording()

        sessionJob = when (engine) {
            is RecognizerManager.Engine.Whisper -> {
                setStatus(getString(R.string.status_listening_tap))
                launchWhisperSession(record, engine.recognizer)
            }
            is RecognizerManager.Engine.Streaming -> {
                liveSession = false
                setStatus(getString(R.string.status_listening))
                launchStreamingSession(record, engine.recognizer)
            }
        }.also { job ->
            job.invokeOnCompletion { RecognizerManager.sessionActive = false }
        }
    }

    private fun stopDictation(commitPending: Boolean) {
        commitOnStop = commitPending
        if (!recording) return
        recording = false
        if (!commitPending) sessionJob?.cancel()
        // UI teardown and the final commit happen in the session coroutine.
    }

    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed: $minBuf")
            return null
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 2, SAMPLE_RATE),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${record.state})")
            record.release()
            return null
        }
        return record
    }

    /** Default engine: live text when the VAD is bundled and enabled, else one batch decode. */
    private fun launchWhisperSession(record: AudioRecord, recognizer: OfflineRecognizer): Job {
        liveSession = options.liveText && vadAvailable()
        return if (liveSession) {
            launchLiveWhisperSession(record, recognizer)
        } else {
            launchBatchWhisperSession(record, recognizer)
        }
    }

    private fun vadAvailable(): Boolean = try {
        assets.list("vad")?.contains(VAD_ASSET_NAME) == true
    } catch (e: java.io.IOException) {
        false
    }

    /** Record the whole take, then transcribe once (in <=28 s chunks, see [WhisperChunks]). */
    private fun launchBatchWhisperSession(record: AudioRecord, recognizer: OfflineRecognizer): Job =
        mainScope.launch(Dispatchers.Default) {
            val opts = options
            val chunks = ArrayList<FloatArray>()
            val buffer = ShortArray(CHUNK)
            var heardVoice = false
            val startedAt = System.currentTimeMillis()
            var lastVoiceAt = startedAt
            try {
                withContext(Dispatchers.Main) { showPlaceholder() }
                while (recording && isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord.read returned $n")
                        break
                    }
                    if (n == 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768.0f }
                    chunks.add(samples)

                    val now = System.currentTimeMillis()
                    if (rms(samples) > SILENCE_RMS) {
                        heardVoice = true
                        lastVoiceAt = now
                    }
                    if (opts.autoStop && heardVoice && now - lastVoiceAt >= opts.autoStopMs) break
                    if (opts.autoStop && !heardVoice && now - startedAt >= NO_SPEECH_TIMEOUT_MS) break
                    if (now - startedAt >= MAX_TAKE_MS) break
                }
            } finally {
                try {
                    record.stop()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioRecord.stop failed", e)
                }
                record.release()
            }

            val shouldCommit = commitOnStop
            withContext(NonCancellable + Dispatchers.Main) {
                if (recording) {
                    // Loop ended on its own (auto-stop / timeout), not via mic tap.
                    recording = false
                    playCue(start = false)
                }
                setRecordingUi(false)
            }
            if (!shouldCommit || !heardVoice) {
                withContext(NonCancellable + Dispatchers.Main) {
                    clearPlaceholder()
                    if (shouldCommit && !heardVoice) {
                        setStatus(getString(R.string.status_no_speech))
                    } else {
                        refreshIdleStatus()
                    }
                }
                return@launch
            }

            withContext(NonCancellable + Dispatchers.Main) {
                transcribing = true
                setStatus(getString(R.string.status_transcribing))
            }
            val result = try {
                WhisperChunks.decodeAll(recognizer, WhisperChunks.concat(chunks))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "Whisper transcription failed", t)
                withContext(NonCancellable + Dispatchers.Main) {
                    transcribing = false
                    clearPlaceholder()
                    setStatus(getString(R.string.status_engine_error, t.message ?: ""))
                }
                return@launch
            }
            withContext(NonCancellable + Dispatchers.Main) {
                transcribing = false
                if (result.text.isBlank()) clearPlaceholder() else commitSegment(result.text, result.lang)
                refreshIdleStatus()
            }
        }

    private fun newVad(): Vad = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/$VAD_ASSET_NAME",
                threshold = 0.5f,
                // A full second of silence ends a phrase, so phrases follow sentence pauses.
                minSilenceDuration = 1.0f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                // Not a hard cut (sherpa only tightens thresholds); decodes are chunked anyway.
                maxSpeechDuration = 20f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    /**
     * Live text with Whisper, in any language. The microphone is read by a producer coroutine
     * into an unbounded channel, so audio is never lost while Whisper decodes. The consumer
     * feeds the Silero VAD; each finished phrase is decoded and appended to the session's raw
     * text, and the in-progress phrase is re-decoded now and then as a partial.
     *
     * The WHOLE session is shown as composing text (cleaned again on every update) and
     * committed once when the session ends: "scratch that" keeps working across pauses,
     * spacing stays right, and history gets one entry. Past [LivePolicy.MAX_COMPOSING_CHARS]
     * the text is committed early at a phrase boundary.
     */
    private fun launchLiveWhisperSession(record: AudioRecord, recognizer: OfflineRecognizer): Job =
        mainScope.launch(Dispatchers.Default) {
            val opts = options
            val audio = Channel<FloatArray>(Channel.UNLIMITED)
            val producer = launch(Dispatchers.IO) {
                val buffer = ShortArray(CHUNK)
                try {
                    while (recording && isActive) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n < 0) {
                            Log.w(TAG, "AudioRecord.read returned $n")
                            break
                        }
                        if (n == 0) continue
                        audio.send(FloatArray(n) { buffer[it] / 32768.0f })
                    }
                } finally {
                    try {
                        record.stop()
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "AudioRecord.stop failed", e)
                    }
                    record.release()
                    audio.close()
                }
            }

            val vad = try {
                newVad()
            } catch (t: Throwable) {
                Log.e(TAG, "VAD failed to load", t)
                recording = false
                producer.cancel()
                withContext(NonCancellable + Dispatchers.Main) {
                    setRecordingUi(false)
                    clearPlaceholder()
                    setStatus(getString(R.string.status_engine_error, t.message ?: "VAD"))
                }
                return@launch
            }

            val sessionRaw = StringBuilder()
            val history = AudioHistory(HISTORY_SAMPLES)
            var onset: Long? = null      // absolute sample where the current phrase started
            var lastPhraseEnd = 0L       // never decode audio twice (no repeated words)
            var heardVoice = false
            var autoStopped = false
            var stopHandled = false
            var sinceVoice = 0L
            var sinceLastPartial = 0L
            var lastDecodeMs = 0L

            /** Audio of a phrase, padded in front: Silero reports speech onsets ~0.5 s late. */
            fun phraseAudio(from: Long, to: Long): FloatArray =
                history.slice(maxOf(from - PRE_ROLL_SAMPLES, lastPhraseEnd), to)

            suspend fun finishPhrases() {
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val segStart = segment.start.toLong()
                    val segEnd = segStart + segment.samples.size
                    val result = WhisperChunks.decodeAll(recognizer, phraseAudio(segStart, segEnd))
                    lastPhraseEnd = maxOf(lastPhraseEnd, segEnd)
                    onset = null
                    if (result.text.isNotBlank()) {
                        if (sessionRaw.isNotEmpty()) sessionRaw.append(' ')
                        sessionRaw.append(result.text)
                    }
                    withContext(Dispatchers.Main) {
                        commandLanguage(result.lang) // fixes the auto-mode language once known
                        if (sessionRaw.length >= LivePolicy.MAX_COMPOSING_CHARS) {
                            commitSegment(sessionRaw.toString())
                            sessionRaw.setLength(0)
                            showPlaceholder()
                        } else {
                            renderLive(sessionRaw, "")
                        }
                    }
                }
            }

            try {
                withContext(Dispatchers.Main) { showPlaceholder() }
                for (chunk in audio) {
                    history.add(chunk)
                    vad.acceptWaveform(chunk)
                    sinceVoice += chunk.size
                    sinceLastPartial += chunk.size

                    if (vad.isSpeechDetected()) {
                        heardVoice = true
                        sinceVoice = 0
                        if (onset == null) onset = history.end - chunk.size
                    }

                    // Partials only while still listening; after stop, finish quickly.
                    val phraseStart = onset
                    if (recording && phraseStart != null &&
                        LivePolicy.ms(history.end - phraseStart) >= LivePolicy.MIN_PARTIAL_SPEECH_MS &&
                        LivePolicy.partialDue(sinceLastPartial, lastDecodeMs)
                    ) {
                        val from = maxOf(phraseStart, history.end - WhisperChunks.MAX_SAMPLES + PRE_ROLL_SAMPLES)
                        val t0 = SystemClock.elapsedRealtime()
                        val partial = WhisperChunks.decodeAll(recognizer, phraseAudio(from, history.end)).text
                        lastDecodeMs = SystemClock.elapsedRealtime() - t0
                        sinceLastPartial = 0
                        withContext(Dispatchers.Main) { renderLive(sessionRaw, partial) }
                    }

                    finishPhrases()

                    if (recording) {
                        val stop = (opts.autoStop &&
                            (LivePolicy.autoStop(heardVoice, sinceVoice, opts.autoStopMs) ||
                                LivePolicy.noSpeech(heardVoice, history.end))) ||
                            LivePolicy.maxTake(history.end)
                        if (stop) {
                            autoStopped = true
                            recording = false // the producer ends and closes the channel
                        }
                    }
                    if (!recording && !stopHandled) {
                        stopHandled = true
                        withContext(Dispatchers.Main) {
                            if (autoStopped) playCue(start = false)
                            setRecordingUi(false)
                            transcribing = true
                            setStatus(getString(R.string.status_transcribing))
                        }
                    }
                }

                // Microphone closed and every queued chunk processed: finish the last phrase.
                vad.flush()
                finishPhrases()
                val pending = onset
                if (pending != null &&
                    LivePolicy.ms(history.end - pending) >= LivePolicy.MIN_PARTIAL_SPEECH_MS
                ) {
                    val text = WhisperChunks.decodeAll(recognizer, phraseAudio(pending, history.end)).text
                    if (text.isNotBlank()) {
                        if (sessionRaw.isNotEmpty()) sessionRaw.append(' ')
                        sessionRaw.append(text)
                    }
                }

                val shouldCommit = commitOnStop
                withContext(NonCancellable + Dispatchers.Main) {
                    if (!stopHandled) {
                        // The microphone ended on its own (read error): stop like a tap would.
                        recording = false
                        setRecordingUi(false)
                    }
                    transcribing = false
                    when {
                        shouldCommit && sessionRaw.isNotBlank() -> commitSegment(sessionRaw.toString())
                        shouldCommit && !heardVoice -> {
                            clearPlaceholder()
                            setStatus(getString(R.string.status_no_speech))
                        }
                        else -> clearPlaceholder()
                    }
                    refreshIdleStatus()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "Live transcription failed", t)
                recording = false
                withContext(NonCancellable + Dispatchers.Main) {
                    transcribing = false
                    setRecordingUi(false)
                    clearPlaceholder()
                    setStatus(getString(R.string.status_engine_error, t.message ?: ""))
                }
            } finally {
                producer.cancel()
                vad.release()
                withContext(NonCancellable + Dispatchers.Main) {
                    if (transcribing || recording) {
                        // Cancelled (field closed, keyboard destroyed): reset the UI.
                        recording = false
                        transcribing = false
                        setRecordingUi(false)
                        refreshIdleStatus()
                    }
                }
            }
        }

    /** Shows the whole live session (cleaned) plus the current partial as composing text. */
    private fun renderLive(sessionRaw: CharSequence, partial: String) {
        if (!inSessionField()) return
        val ic = currentInputConnection ?: return
        val raw = listOf(sessionRaw.toString(), partial).filter { it.isNotBlank() }.joinToString(" ")
        val shown = if (raw.isBlank()) "" else clean(raw, null)
        val prefix = if (needLeadingSpace) " " else ""
        ic.setComposingText(prefix + shown.ifEmpty { "…" }, 1)
        setStatus(partial.ifBlank { getString(R.string.status_listening_tap) })
    }

    /** Optional engine: streaming partials + endpoint-based hands-free segments. */
    private fun launchStreamingSession(record: AudioRecord, recognizer: OnlineRecognizer): Job =
        mainScope.launch(Dispatchers.Default) {
            val stream = recognizer.createStream()
            val buffer = ShortArray(CHUNK)
            try {
                while (recording && isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord.read returned $n")
                        break
                    }
                    if (n == 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    // The Zipformer emits UPPER CASE without punctuation; cleanup re-capitalizes.
                    val text = recognizer.getResult(stream).text.lowercase()
                    if (recognizer.isEndpoint(stream)) {
                        recognizer.reset(stream)
                        withContext(Dispatchers.Main) {
                            if (text.isNotBlank()) commitSegment(text)
                            setStatus(getString(R.string.status_listening))
                        }
                    } else if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) { showPartial(text) }
                    }
                }
                if (commitOnStop) {
                    // Flush buffered audio so trailing words are not lost.
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.lowercase()
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (text.isNotBlank()) commitSegment(text) else clearPlaceholder()
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "Streaming recognition failed", t)
                withContext(NonCancellable + Dispatchers.Main) {
                    setStatus(getString(R.string.status_engine_error, t.message ?: ""))
                }
            } finally {
                try {
                    record.stop()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioRecord.stop failed", e)
                }
                record.release()
                stream.release()
                withContext(NonCancellable + Dispatchers.Main) {
                    recording = false
                    setRecordingUi(false)
                    refreshIdleStatus()
                }
            }
        }

    // ------------------------------------------------------------- text I/O

    private fun showPlaceholder() {
        val ic = currentInputConnection ?: return
        val prefix = if (needLeadingSpace) " " else ""
        ic.setComposingText(prefix + "…", 1)
    }

    private fun clearPlaceholder() {
        // Committing "" atomically removes the composing region.
        currentInputConnection?.commitText("", 1)
    }

    private fun showPartial(partial: String) {
        val ic = currentInputConnection ?: return
        val prefix = if (needLeadingSpace) " " else ""
        ic.setComposingText(prefix + partial, 1)
        setStatus(partial)
    }

    /**
     * Language whose spoken commands apply. In auto mode it is the first language Whisper
     * detects in the session, kept for the rest of it (short phrases are often misdetected).
     */
    private fun commandLanguage(detectedLang: String?): String? {
        if (options.language != Languages.AUTO) return options.language
        if (sessionLang == null) sessionLang = Languages.normalizeDetected(detectedLang)
        return sessionLang
    }

    private fun clean(raw: String, detectedLang: String?): String = TextCleanup.process(
        raw,
        capitalizeNext,
        dictionary,
        snippets,
        removeFillers = options.removeFillers,
        punctuationCommands = options.punctuationCommands,
        commandLanguage = commandLanguage(detectedLang),
        words = userTerms,
        correctWords = options.correctWords,
    )

    /** True while the focused field is the one the current session started in. */
    private fun inSessionField(): Boolean {
        val field = sessionField ?: return true
        val now = currentInputEditorInfo ?: return false
        return now.packageName == field.first && now.fieldId == field.second
    }

    private fun commitSegment(raw: String, detectedLang: String? = null) {
        val cleaned = clean(raw, detectedLang)
        val ic = currentInputConnection
        if (ic == null || !inSessionField()) {
            // Focus moved on: never type into another field, but keep the text in history.
            Log.w(TAG, "Input field changed or closed; transcript kept in history only")
            if (cleaned.isNotEmpty()) HistoryStore.add(this, cleaned)
            return
        }
        if (cleaned.isEmpty()) {
            ic.commitText("", 1)
            return
        }
        val prefix = if (needLeadingSpace) " " else ""
        ic.commitText(prefix + cleaned + " ", 1)
        HistoryStore.add(this, cleaned)
        capitalizeNext = cleaned.last() in ".!?\n"
        needLeadingSpace = false
    }

    // ------------------------------------------------------------------- UI

    private fun setStatus(text: String) {
        actionButton?.visibility = View.GONE
        statusText?.text = text
    }

    /** Status message plus a visible "Open setup" chip. */
    private fun showActionableStatus(text: String) {
        statusText?.text = text
        actionButton?.text = getString(R.string.action_open_setup)
        actionButton?.visibility = View.VISIBLE
    }

    private fun refreshIdleStatus() {
        if (recording || transcribing) return
        val state = RecognizerManager.state.value
        setLoadingAnim(state is RecognizerManager.State.Loading || state == RecognizerManager.State.NotLoaded)
        when (state) {
            is RecognizerManager.State.Ready ->
                if (!hasMicPermission()) {
                    showActionableStatus(getString(R.string.status_no_permission))
                } else {
                    setStatus(getString(R.string.status_idle))
                }
            RecognizerManager.State.Loading,
            RecognizerManager.State.NotLoaded -> setStatus(getString(R.string.status_loading))
            is RecognizerManager.State.ModelMissing ->
                showActionableStatus(getString(R.string.status_no_model))
            is RecognizerManager.State.Error ->
                showActionableStatus(getString(R.string.status_engine_error, state.message))
        }
    }

    private fun setLoadingAnim(active: Boolean) {
        val target = statusText ?: return
        if (active) {
            if (loadingAnim == null) {
                loadingAnim = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0.35f).apply {
                    duration = 600
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
        } else {
            loadingAnim?.cancel()
            loadingAnim = null
            statusText?.alpha = 1f
        }
    }

    private fun setRecordingUi(active: Boolean) {
        val button = micButton ?: return
        if (active) {
            button.setBackgroundResource(R.drawable.bg_mic_recording)
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                button,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
            ).apply {
                duration = 500
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        } else {
            pulse?.cancel()
            pulse = null
            button.scaleX = 1f
            button.scaleY = 1f
            button.setBackgroundResource(R.drawable.bg_mic)
        }
    }

    private fun updateLangButton() {
        // Any Whisper code fits: "AUTO" (4 letters) is already the widest label.
        langButton?.text = if (Prefs.isStreamingMode(this)) "EN" else langLabel()
    }

    private fun langLabel(): String = Prefs.getLanguage(this).uppercase()

    // ------------------------------------------------------------- feedback

    private fun haptic(view: View) {
        if (Prefs.isHapticsEnabled(this)) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun playCue(start: Boolean) {
        if (!Prefs.isSoundEnabled(this)) return
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            }
            toneGenerator?.startTone(
                if (start) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2,
                90,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator unavailable", e)
            toneGenerator = null
        }
    }

    // ---------------------------------------------------------------- misc

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        return sqrt(sum / samples.size).toFloat()
    }
}
