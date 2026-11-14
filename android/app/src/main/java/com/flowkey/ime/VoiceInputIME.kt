package com.flowkey.ime

import android.animation.ObjectAnimator
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
import com.flowkey.ime.core.HistoryFormat
import com.flowkey.ime.core.LlmClient
import com.flowkey.ime.core.PressEnter
import com.flowkey.ime.core.Privacy
import com.flowkey.ime.core.Styles
import com.flowkey.ime.data.AiPrefs
import com.flowkey.ime.data.StatsStore
import com.flowkey.ime.data.StyleStore
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
import java.util.concurrent.atomic.AtomicInteger
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
        const val VAD_MIN_SILENCE_MS = 1000L           // Silero minSilenceDuration below
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
    /** Decoding / AI after recording ended. Set from any thread; the UI follows on main. */
    @Volatile private var transcribing = false
        set(value) {
            field = value
            uiHandler.post { updateVisualState() }
        }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var statusText: TextView? = null
    private var actionButton: TextView? = null
    private var micButton: ImageButton? = null
    private var langButton: TextView? = null
    private var waveform: WaveformView? = null
    private var editChip: TextView? = null
    private var undoChip: TextView? = null
    private var rawChip: TextView? = null
    private var styleChip: TextView? = null
    /** The undo chip hides at this uptime (accessibility-adjusted timeout). */
    private var undoDeadline = 0L
    private val hideUndo = Runnable { updateChips() }
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

    /** The composing region holds only the "…" placeholder, not dictated text. */
    private var composingIsPlaceholder = false

    /** The text field a session started in; text is never committed into another one. */
    private var sessionField: Pair<String?, Int>? = null

    /** Bumped for every new input connection: a late result never lands in another field. */
    private var inputGeneration = 0
    private var sessionGeneration = 0

    /** Per-session context: target app, style, privacy, AI. */
    private var sessionPkg: String? = null
    private var sessionStyle = Styles.CLEAN
    private var sessionPrivate = false
    private var sessionAi = false
    private var sessionTone = true

    /** The AI call in flight (tap the mic to skip it), or null. */
    @Volatile private var aiCall: LlmClient? = null
    @Volatile private var polishing = false
        set(value) {
            field = value
            uiHandler.post { updateVisualState() }
        }

    /** The cursor/selection the editor reported last (onUpdateSelection). */
    private var selStart = -1
    private var selEnd = -1

    /** What the last dictation inserted, for the undo chip. */
    private data class LastInsert(
        val inserted: String,      // exactly what was committed (prefix + text + space)
        val alternative: String?,  // the rule-cleaned text when the AI rewrote it ("Raw")
        val restore: String?,      // voice edit: the original selection to put back
        var cursor: Int,           // cursor right after the insert (-1 until reported)
        val generation: Int,
    )
    private var lastInsert: LastInsert? = null
        set(value) {
            field = value
            if (value != null) {
                undoDeadline = SystemClock.uptimeMillis() + undoTimeoutMs()
                uiHandler.removeCallbacks(hideUndo)
                uiHandler.postDelayed(hideUndo, undoTimeoutMs() + 50)
            }
            updateChips()
        }

    /** Voice edit: what was selected when it started (null selection = write new text). */
    private data class EditTarget(val selection: String?, val start: Int, val end: Int, val generation: Int)
    private var editTarget: EditTarget? = null

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
        waveform = view.findViewById(R.id.waveform)
        editChip = view.findViewById(R.id.editChip)
        undoChip = view.findViewById(R.id.undoChip)
        rawChip = view.findViewById(R.id.rawChip)
        styleChip = view.findViewById(R.id.styleChip)
        editChip?.setOnClickListener { haptic(it); startVoiceEdit() }
        undoChip?.setOnClickListener { haptic(it); undoLast(useRuleText = false) }
        rawChip?.setOnClickListener { haptic(it); undoLast(useRuleText = true) }
        styleChip?.setOnClickListener { haptic(it); cycleStyle() }

        micButton?.setOnClickListener {
            haptic(it)
            onMicTap()
        }
        micButton?.setOnLongClickListener {
            haptic(it)
            startVoiceEdit()
            true
        }
        micButton?.let { mic ->
            androidx.core.view.ViewCompat.addAccessibilityAction(mic, getString(R.string.a11y_voice_edit)) { _, _ ->
                startVoiceEdit(); true
            }
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

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            inputGeneration++
            lastInsert = null
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        lastInsert?.let { if (it.cursor == -1 && candidatesStart == -1) it.cursor = newSelStart }
        onSelectionChanged()
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        RecognizerManager.initialize(this)
        updateLangButton()
        updateStyleChip()
        updateVisualState()
        stateJob?.cancel()
        stateJob = mainScope.launch {
            RecognizerManager.state.collect { refreshIdleStatus() }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        aiCall?.cancel()   // the rule-cleaned text is kept instead
        lastInsert = null
        if (sessionJob?.isCompleted == false && liveSession) {
            // Live session (recording or still finishing): keep what is already shown in THIS
            // field and drop pending audio, instead of committing later into whatever field
            // gets focus next. A bare "…" placeholder is removed, never kept.
            val ic = currentInputConnection
            if (composingIsPlaceholder) ic?.commitText("", 1) else ic?.finishComposingText()
            composingIsPlaceholder = false
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
        if (polishing) {
            aiCall?.cancel()  // skip the AI: the rule-cleaned text is inserted right away
            return
        }
        if (transcribing) return
        lastInsert = null
        if (recording) {
            playCue(start = false)
            stopDictation(commitPending = true)
            return
        }
        if (!hasMicPermission()) {
            showActionableStatus(getString(R.string.status_no_permission))
            return
        }
        if (sessionJob?.isCompleted == false) {
            // The previous (cancelled) session is still inside a decode; never run two at once.
            setStatus(getString(R.string.status_transcribing))
            return
        }
        when (RecognizerManager.state.value) {
            is RecognizerManager.State.Ready -> {
                // Count the session BEFORE taking the engine, so a reload finishing right now
                // can't free it; then take the newest one.
                RecognizerManager.sessionStarted()
                val engine = (RecognizerManager.state.value as? RecognizerManager.State.Ready)?.engine
                if (engine == null || !startDictation(engine)) RecognizerManager.sessionEnded(this)
            }
            is RecognizerManager.State.Loading,
            RecognizerManager.State.NotLoaded -> {
                setStatus(getString(R.string.status_loading))
                RecognizerManager.initialize(this)
            }
            is RecognizerManager.State.ModelMissing ->
                showActionableStatus(getString(R.string.status_no_model))
            is RecognizerManager.State.Error -> showActionableStatus(
                getString(
                    R.string.status_engine_error,
                    (RecognizerManager.state.value as? RecognizerManager.State.Error)?.message.orEmpty(),
                )
            )
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
        lastInsert = null
        if (!sendDefaultEditorAction(true)) {
            currentInputConnection?.commitText("\n", 1)
        }
        capitalizeNext = true
        needLeadingSpace = false
    }

    private fun onSpace() {
        if (recording || transcribing) return
        lastInsert = null
        currentInputConnection?.commitText(" ", 1)
        needLeadingSpace = false
    }

    private fun sendBackspace() {
        lastInsert = null
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

    /** Starts a session; returns false if none was started. Caller did sessionStarted(). */
    private fun startDictation(engine: RecognizerManager.Engine): Boolean {
        if (recording || sessionJob?.isCompleted == false) return false

        val before = currentInputConnection?.getTextBeforeCursor(16, 0)?.toString().orEmpty()
        val trimmed = before.trimEnd(' ')
        capitalizeNext = trimmed.isEmpty() || trimmed.last() in ".!?\n"
        needLeadingSpace = before.isNotEmpty() && !before.last().isWhitespace()
        dictionary = UserData.getDictionary(this)
        snippets = UserData.getSnippets(this)
        userTerms = UserData.userTerms(this)
        val info = currentInputEditorInfo
        sessionPkg = info?.packageName
        sessionPrivate = info != null && Privacy.isPrivateField(info.inputType, info.imeOptions)
        sessionStyle = Styles.styleFor(sessionPkg, StyleStore.load(this))
        val verbatim = sessionStyle == Styles.VERBATIM
        sessionTone = AiPrefs.toneMatching(this)
        sessionAi = AiPrefs.enabled(this) && !sessionPrivate && !verbatim &&
            engine is RecognizerManager.Engine.Whisper && AiPrefs.client(this).configured()
        sessionGeneration = inputGeneration
        lastInsert = null
        options = SessionOptions(
            autoStop = Prefs.isAutoStopEnabled(this),
            autoStopMs = Prefs.getAutoStopMs(this),
            // Verbatim style keeps your words: no filler removal, no word correction.
            removeFillers = Prefs.isFillerRemovalEnabled(this) && !verbatim,
            punctuationCommands = Prefs.isPunctuationCommandsEnabled(this),
            correctWords = Prefs.isWordCorrectionEnabled(this) && !verbatim,
            liveText = Prefs.isLiveTextEnabled(this),
            language = if (Prefs.isStreamingMode(this)) "en" else Prefs.getLanguage(this),
        )
        sessionLang = null
        sessionField = info?.let { it.packageName to it.fieldId }

        val record = createAudioRecord()
        if (record == null) {
            setStatus(getString(R.string.status_audio_error))
            return false
        }

        recording = true
        commitOnStop = true
        transcribing = false
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
            val appContext = applicationContext
            val target = editTarget
            job.invokeOnCompletion {
                RecognizerManager.sessionEnded(appContext)
                // Only this session's target: a new voice edit may already have started.
                if (target != null) mainScope.launch { if (editTarget === target) editTarget = null }
            }
        }
        return true
    }

    private fun stopDictation(commitPending: Boolean) {
        if (!commitPending) {
            // Drop the session, also one that is still finishing after a stop tap.
            commitOnStop = false
            recording = false
            sessionJob?.cancel()
            return
        }
        if (!recording) return
        commitOnStop = true
        recording = false
        if (liveSession) {
            // Block Enter/Space/Backspace/language right away: the live session may need a
            // few seconds to finish its current decode before it notices the stop.
            transcribing = true
            setRecordingUi(false)
            setStatus(getString(R.string.status_transcribing))
        }
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
        // A voice edit is an instruction, not text for the field: always one batch decode.
        liveSession = editTarget == null && options.liveText && vadAvailable()
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
            val edit = editTarget
            // A voice edit never touches the field until the end: no "…" placeholder, which would
            // replace the selection (and clearing it would delete the selected text).
            val usePlaceholder = edit == null
            fun clearPlaceholderIfUsed() { if (usePlaceholder) clearPlaceholder() }
            val chunks = ArrayList<FloatArray>()
            val buffer = ShortArray(CHUNK)
            var heardVoice = false
            val startedAt = System.currentTimeMillis()
            var lastVoiceAt = startedAt
            try {
                if (usePlaceholder) withContext(Dispatchers.Main) { showPlaceholder() }
                while (recording && isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord.read returned $n")
                        break
                    }
                    if (n == 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768.0f }
                    chunks.add(samples)
                    feedWaveform(samples)

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
                    clearPlaceholderIfUsed()
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
                    clearPlaceholderIfUsed()
                    setStatus(getString(R.string.status_engine_error, t.message ?: ""))
                }
                return@launch
            }
            if (result.text.isBlank()) {
                withContext(NonCancellable + Dispatchers.Main) {
                    transcribing = false
                    clearPlaceholderIfUsed()
                    refreshIdleStatus()
                }
            } else {
                val seconds = chunks.sumOf { it.size }.toDouble() / SAMPLE_RATE
                if (edit != null) finishVoiceEdit(result.text, result.lang, seconds, edit)
                else finishWhole(result.text, result.lang, seconds)
            }
        }

    private fun newVad(): Vad = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/$VAD_ASSET_NAME",
                threshold = 0.5f,
                // A full second of silence ends a phrase, so phrases follow sentence pauses.
                minSilenceDuration = VAD_MIN_SILENCE_MS / 1000f,
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
            val queued = AtomicInteger(0) // chunks recorded but not processed yet
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
                        queued.incrementAndGet()
                        val samples = FloatArray(n) { buffer[it] / 32768.0f }
                        feedWaveform(samples)
                        audio.send(samples)
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
                    transcribing = false
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
            var finished = false         // the normal or error path ran its own UI teardown
            var earlyCommitted = false   // part of a long take was committed already
            var sinceVoice = 0L
            var sinceLastPartial = 0L
            var lastDecodeMs = 0L
            // Silero keeps "speech detected" on for its min-silence window after speech ends,
            // so silence counted from there is already that much late.
            val autoStopMs = (opts.autoStopMs - VAD_MIN_SILENCE_MS).coerceAtLeast(300L)

            /** Audio of a phrase, padded in front: Silero reports speech onsets ~0.5 s late. */
            fun phraseAudio(from: Long, to: Long): FloatArray =
                history.slice(maxOf(from - PRE_ROLL_SAMPLES, lastPhraseEnd), to)

            fun appendPhrase(text: String) {
                if (text.isBlank()) return
                // The break marks a phrase end for end-only commands ("Punkt" + pause).
                if (sessionRaw.isNotEmpty()) sessionRaw.append(' ').append(TextCleanup.PHRASE_BREAK).append(' ')
                sessionRaw.append(text)
            }

            /** Cleans off the main thread, then shows the result there. */
            suspend fun render(partial: String) {
                val raw = listOf(sessionRaw.toString(), partial).filter { it.isNotBlank() }
                    .joinToString(" " + TextCleanup.PHRASE_BREAK + " ")
                val shown = if (raw.isBlank()) "" else clean(raw, null)
                withContext(Dispatchers.Main) { showLive(shown, partial) }
            }

            suspend fun finishPhrases() {
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val segStart = segment.start.toLong()
                    val segEnd = segStart + segment.samples.size
                    val result = WhisperChunks.decodeAll(recognizer, phraseAudio(segStart, segEnd))
                    lastPhraseEnd = maxOf(lastPhraseEnd, segEnd)
                    onset = null
                    appendPhrase(result.text)
                    if (result.text.isNotBlank()) {
                        // Fix the auto-mode language from real speech only, never from noise.
                        withContext(Dispatchers.Main) { commandLanguage(result.lang) }
                    }
                    if (sessionRaw.length >= LivePolicy.MAX_COMPOSING_CHARS) {
                        val text = sessionRaw.toString()
                        sessionRaw.setLength(0)
                        earlyCommitted = true
                        withContext(Dispatchers.Main) {
                            commitSegment(text)
                            showPlaceholder()
                        }
                    } else {
                        render("")
                    }
                }
            }

            try {
                withContext(Dispatchers.Main) { showPlaceholder() }
                for (chunk in audio) {
                    queued.decrementAndGet()
                    history.add(chunk)
                    vad.acceptWaveform(chunk)
                    sinceVoice += chunk.size
                    sinceLastPartial += chunk.size

                    if (vad.isSpeechDetected()) {
                        heardVoice = true
                        sinceVoice = 0
                        if (onset == null) onset = history.end - chunk.size
                    }

                    // Partials only while listening AND caught up with the microphone: when
                    // decoding falls behind, finishing phrases matters more than previews.
                    val phraseStart = onset
                    if (recording && queued.get() == 0 && phraseStart != null &&
                        LivePolicy.ms(history.end - phraseStart) >= LivePolicy.MIN_PARTIAL_SPEECH_MS &&
                        LivePolicy.partialDue(sinceLastPartial, lastDecodeMs)
                    ) {
                        val from = maxOf(phraseStart, history.end - WhisperChunks.MAX_SAMPLES + PRE_ROLL_SAMPLES)
                        val t0 = SystemClock.elapsedRealtime()
                        val partial = WhisperChunks.decodeAll(recognizer, phraseAudio(from, history.end)).text
                        lastDecodeMs = SystemClock.elapsedRealtime() - t0
                        sinceLastPartial = 0
                        render(partial)
                    }

                    finishPhrases()

                    if (recording) {
                        val stop = (opts.autoStop &&
                            (LivePolicy.autoStop(heardVoice, sinceVoice, autoStopMs) ||
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
                    appendPhrase(WhisperChunks.decodeAll(recognizer, phraseAudio(pending, history.end)).text)
                }

                val shouldCommit = commitOnStop
                if (shouldCommit && sessionRaw.isNotBlank()) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        recording = false
                        setRecordingUi(false)
                    }
                    // The AI (if on) only rewrites a take that was never split by an early commit.
                    finishWhole(sessionRaw.toString(), null, history.end.toDouble() / SAMPLE_RATE,
                                allowAi = !earlyCommitted, onCommitted = { finished = true })
                    return@launch
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    finished = true
                    recording = false
                    setRecordingUi(false)
                    transcribing = false
                    when {
                        shouldCommit && !heardVoice -> {
                            clearPlaceholder()
                            setStatus(getString(R.string.status_no_speech)) // keep it visible
                        }
                        else -> {
                            clearPlaceholder()
                            refreshIdleStatus()
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "Live transcription failed", t)
                recording = false
                withContext(NonCancellable + Dispatchers.Main) {
                    finished = true
                    transcribing = false
                    setRecordingUi(false)
                    clearPlaceholder()
                    setStatus(getString(R.string.status_engine_error, t.message ?: ""))
                }
            } finally {
                producer.cancel()
                vad.release()
                if (!finished) {
                    // Cancelled (field closed, keyboard destroyed). No other session can have
                    // started meanwhile (startDictation waits for this job), so reset it all.
                    withContext(NonCancellable + Dispatchers.Main) {
                        recording = false
                        transcribing = false
                        setRecordingUi(false)
                        refreshIdleStatus()
                        // What was shown stays in the old field; keep the take in history too.
                        val text = sessionRaw.toString()
                        if (text.isNotBlank()) {
                            val cleaned = clean(text, null)
                            if (cleaned.isNotEmpty()) record(cleaned, text, history.end.toDouble() / SAMPLE_RATE)
                        }
                    }
                }
            }
        }

    /** Shows the cleaned live session (already computed off the main thread). */
    private fun showLive(shown: String, partial: String) {
        if (!inSessionField()) return
        val ic = currentInputConnection ?: return
        val prefix = if (needLeadingSpace) " " else ""
        composingIsPlaceholder = shown.isEmpty()
        ic.setComposingText(prefix + shown.ifEmpty { "…" }, 1)
        // After stop the status says "Transcribing…" until the end; don't flip it back.
        if (recording) setStatus(partial.ifBlank { getString(R.string.status_listening_tap) })
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
                    feedWaveform(samples)
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
        composingIsPlaceholder = true
        val ic = currentInputConnection ?: return
        val prefix = if (needLeadingSpace) " " else ""
        ic.setComposingText(prefix + "…", 1)
    }

    private fun clearPlaceholder() {
        composingIsPlaceholder = false
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

    /** True while the field is the session's AND no other input connection started meanwhile. */
    private fun stillSameField(): Boolean = inSessionField() && inputGeneration == sessionGeneration

    /**
     * A part of a dictation (a streaming segment, or an early commit of a long live take): rule
     * cleanup only; the casual style's trailing-period rule waits for a whole dictation.
     */
    private fun commitSegment(raw: String, detectedLang: String? = null) {
        composingIsPlaceholder = false
        val cleaned = clean(raw, detectedLang)
        val ic = currentInputConnection
        if (ic == null || !stillSameField()) {
            // Focus moved on: never type into another field, but keep the text in history.
            Log.w(TAG, "Input field changed or closed; transcript kept in history only")
            if (cleaned.isNotEmpty()) record(cleaned, raw, 0.0)
            return
        }
        if (cleaned.isEmpty()) {
            ic.commitText("", 1)
            return
        }
        val prefix = if (needLeadingSpace) " " else ""
        val inserted = prefix + cleaned + " "
        ic.commitText(inserted, 1)
        record(cleaned, raw, 0.0)
        lastInsert = LastInsert(inserted, null, null, -1, inputGeneration)
        capitalizeNext = cleaned.last() in ".!?\n"
        needLeadingSpace = false
    }

    /**
     * A whole dictation (batch take, or a live take): rules, the app's style, "press enter",
     * then — if on — the AI rewrite, off the main thread and skippable with a mic tap. Called
     * from the session coroutine; ends the session's UI state.
     */
    private suspend fun finishWhole(
        raw: String, detectedLang: String?, seconds: Double,
        allowAi: Boolean = true, onCommitted: () -> Unit = {},
    ) {
        val ruled = withContext(Dispatchers.Main) {
            Styles.finish(clean(raw, detectedLang), sessionStyle, wholeDictation = true)
        }
        val (text, pressEnter) = PressEnter.extract(ruled)
        var final = text
        var polished = false
        if (allowAi && sessionAi && text.isNotBlank()) {
            val client = AiPrefs.client(this)
            aiCall = client
            withContext(NonCancellable + Dispatchers.Main) {
                polishing = true
                setStatus(getString(R.string.status_polishing))
            }
            val instruction = Styles.llmInstruction(if (sessionTone) sessionStyle else Styles.CLEAN)
            val capitalize = capitalizeNext
            val out = try {
                withContext(Dispatchers.IO) { client.rewrite(text, instruction, budgetMs = 10_000) }
            } finally {
                aiCall = null
                withContext(NonCancellable + Dispatchers.Main) { polishing = false }
            }
            if (out != null) {
                final = LlmClient.matchCursor(out, capitalize)
                polished = true
            }
        }
        withContext(NonCancellable + Dispatchers.Main) {
            onCommitted()
            transcribing = false
            commitFinal(final, raw, seconds, pressEnter, if (polished) text else null)
            refreshIdleStatus()
        }
    }

    /** Main thread: insert the final text of a whole dictation and remember it. */
    private fun commitFinal(text: String, raw: String, seconds: Double, pressEnter: Boolean, ruleText: String?) {
        composingIsPlaceholder = false
        val ic = currentInputConnection
        if (ic == null || !stillSameField()) {
            Log.w(TAG, "Input field changed or closed; transcript kept in history only")
            if (text.isNotEmpty()) record(text, raw, seconds)
            return
        }
        if (text.isEmpty()) {
            ic.commitText("", 1)
            if (pressEnter && !sendDefaultEditorAction(true)) ic.commitText("\n", 1)
            return
        }
        val prefix = if (needLeadingSpace) " " else ""
        val inserted = prefix + text + if (pressEnter) "" else " "
        ic.commitText(inserted, 1)
        record(text, raw, seconds)
        lastInsert = if (pressEnter) null else LastInsert(inserted, ruleText, null, -1, inputGeneration)
        capitalizeNext = text.last() in ".!?\n"
        needLeadingSpace = false
        if (pressEnter) {
            if (!sendDefaultEditorAction(true)) ic.commitText("\n", 1)
            capitalizeNext = true
        }
        onInserted()
    }

    /** History + insights, never for private fields (passwords, incognito). Off the main thread. */
    private fun record(text: String, raw: String, seconds: Double, style: String = sessionStyle,
                       statsText: String = text) {
        if (sessionPrivate || text.isBlank()) return
        com.flowkey.ime.data.HistoryStore.add(
            this, HistoryFormat.newEntry(text, raw, sessionPkg, seconds, style))
        // A voice edit counts the spoken instruction, not the AI's output, as dictated words.
        StatsStore.add(this, statsText, seconds, sessionPkg)
    }

    /** Undo the last insertion (or swap in the rule text: [useRuleText]) if nothing changed since. */
    private fun undoLast(useRuleText: Boolean = false) {
        val last = lastInsert ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(last.inserted.length, 0)
        val cursor = if (last.cursor == -1) selStart else last.cursor
        if (!com.flowkey.ime.core.UndoPolicy.canUndo(
                last.inserted, before, selStart, selEnd, cursor, last.generation, inputGeneration)) {
            lastInsert = null
            onSelectionChanged()
            return
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(last.inserted.length, 0)
        when {
            useRuleText && last.alternative != null -> {
                val lead = last.inserted.takeWhile { it == ' ' }
                ic.commitText(lead + last.alternative + " ", 1)
            }
            last.restore != null -> ic.commitText(last.restore, 1)
        }
        ic.endBatchEdit()
        lastInsert = null
        onSelectionChanged()
    }

    // ------------------------------------------------------------- voice edit

    /**
     * Voice edit (Android's Command Mode): with text selected, say what to change ("make this
     * more formal", "translate to German"); with nothing selected, say what to write. Needs AI.
     */
    private fun startVoiceEdit() {
        if (recording || transcribing || sessionJob?.isCompleted == false) return
        val info = currentInputEditorInfo
        if (info != null && Privacy.isPrivateField(info.inputType, info.imeOptions)) {
            setStatus(getString(R.string.status_voice_edit_private)); return
        }
        if (!AiPrefs.enabled(this) || !AiPrefs.client(this).configured()) {
            showActionableStatus(getString(R.string.status_voice_edit_needs_ai)); return
        }
        if (!hasMicPermission()) {
            showActionableStatus(getString(R.string.status_no_permission)); return
        }
        val engine = (RecognizerManager.state.value as? RecognizerManager.State.Ready)?.engine
        if (engine == null) { onMicTap(); return }   // shows the loading / model status
        if (engine is RecognizerManager.Engine.Streaming) {
            setStatus(getString(R.string.status_voice_edit_whisper)); return
        }
        val ic = currentInputConnection ?: return
        val hasSelection = selStart >= 0 && selEnd > selStart
        val selected = if (hasSelection) ic.getSelectedText(0) else null
        if (hasSelection && !com.flowkey.ime.core.SelectionGuard.complete(selected, selStart, selEnd)) {
            // The app handed over only part of the selection: replacing it would delete the rest.
            setStatus(getString(R.string.status_selection_changed)); return
        }
        editTarget = EditTarget(selected?.toString(), selStart, selEnd, inputGeneration)
        RecognizerManager.sessionStarted()
        val ready = (RecognizerManager.state.value as? RecognizerManager.State.Ready)?.engine
        if (ready == null || !startDictation(ready)) {
            editTarget = null
            RecognizerManager.sessionEnded(this)
            return
        }
        setStatus(getString(if (hasSelection) R.string.status_voice_edit else R.string.status_voice_write))
    }

    private suspend fun finishVoiceEdit(spoken: String, detectedLang: String?, seconds: Double, target: EditTarget) {
        val instruction = withContext(Dispatchers.Main) {
            setStatus(getString(R.string.status_polishing))
            TextCleanup.process(spoken, true, dictionary, snippets, removeFillers = true,
                punctuationCommands = false, commandLanguage = commandLanguage(detectedLang))
        }
        val client = AiPrefs.client(this)
        aiCall = client
        withContext(NonCancellable + Dispatchers.Main) { polishing = true }
        val out = try {
            withContext(Dispatchers.IO) {
                client.rewrite(instruction, "", selection = target.selection, mode = "command", budgetMs = 15_000)
            }
        } finally {
            aiCall = null
            withContext(NonCancellable + Dispatchers.Main) { polishing = false }
        }
        withContext(NonCancellable + Dispatchers.Main) {
            transcribing = false
            val ic = currentInputConnection
            when {
                out == null -> setStatus(getString(R.string.status_ai_no_answer))
                ic == null || !stillSameField() ||
                    (target.selection != null && !com.flowkey.ime.core.SelectionGuard.unchanged(
                        target.start, target.end, selStart, selEnd, target.generation, inputGeneration)) ->
                    setStatus(getString(R.string.status_selection_changed))
                target.selection != null -> {
                    ic.beginBatchEdit()
                    ic.setSelection(target.start, target.end)
                    ic.commitText(out, 1)
                    ic.endBatchEdit()
                    lastInsert = LastInsert(out, null, target.selection, -1, inputGeneration)
                    record(out, spoken, seconds, "command", statsText = instruction)
                    onInserted()
                    refreshIdleStatus()
                }
                else -> {
                    val prefix = if (needLeadingSpace) " " else ""
                    val inserted = prefix + LlmClient.matchCursor(out, capitalizeNext) + " "
                    ic.commitText(inserted, 1)
                    lastInsert = LastInsert(inserted, null, null, -1, inputGeneration)
                    record(out, spoken, seconds, "command", statsText = instruction)
                    needLeadingSpace = false
                    onInserted()
                    refreshIdleStatus()
                }
            }
        }
    }

    private fun onInserted() = updateChips()
    private fun onSelectionChanged() = updateChips()

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
        micButton?.setBackgroundResource(if (active) R.drawable.bg_mic_recording else R.drawable.bg_mic)
        updateVisualState()
    }

    /** Waveform mode and chips follow recording / transcribing / polishing. Main thread. */
    private fun updateVisualState() {
        waveform?.mode = when {
            recording -> WaveformView.Mode.LISTENING
            transcribing || polishing -> WaveformView.Mode.WORKING
            else -> WaveformView.Mode.IDLE
        }
        updateChips()
    }

    /** Three ~33 ms levels per 100 ms chunk, so the bars move at ~30 Hz. Any thread. */
    private fun feedWaveform(samples: FloatArray) {
        val view = waveform ?: return
        val part = samples.size / 3
        if (part == 0) { view.pushRms(rms(samples)); return }
        for (i in 0 until 3) {
            var sum = 0.0
            for (j in i * part until (i + 1) * part) sum += (samples[j] * samples[j]).toDouble()
            view.pushRms(sqrt(sum / part).toFloat())
        }
    }

    private fun undoTimeoutMs(): Long {
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val base = 6000
        return (am?.getRecommendedTimeoutMillis(base,
            android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS) ?: base).toLong()
    }

    /** Edit chip with a selection; Undo / Raw for a few seconds after a dictation. */
    private fun updateChips() {
        val busy = recording || transcribing || polishing
        val info = currentInputEditorInfo
        val private = info != null && Privacy.isPrivateField(info.inputType, info.imeOptions)
        editChip?.visibility =
            if (!busy && !private && selStart >= 0 && selEnd > selStart) View.VISIBLE else View.GONE
        val last = lastInsert
        val undoable = !busy && last != null && last.generation == inputGeneration &&
            SystemClock.uptimeMillis() < undoDeadline &&
            (last.cursor == -1 || (selStart == last.cursor && selEnd == last.cursor))
        undoChip?.visibility = if (undoable) View.VISIBLE else View.GONE
        rawChip?.visibility = if (undoable && last?.alternative != null) View.VISIBLE else View.GONE
    }

    private fun styleName(style: String) = getString(when (style) {
        Styles.VERBATIM -> R.string.style_verbatim
        Styles.CASUAL -> R.string.style_casual
        Styles.FORMAL -> R.string.style_formal
        else -> R.string.style_clean
    })

    private fun categoryName(category: String?) = getString(when (category) {
        "messaging" -> R.string.style_cat_messaging
        "email" -> R.string.style_cat_email
        "code" -> R.string.style_cat_code
        "documents" -> R.string.style_cat_documents
        else -> R.string.style_cat_other
    })

    /** The style chip shows this app's style; hidden when styles are off or in private fields. */
    private fun updateStyleChip() {
        val chip = styleChip ?: return
        val info = currentInputEditorInfo
        val cfg = StyleStore.load(this)
        if (!cfg.enabled || (info != null && Privacy.isPrivateField(info.inputType, info.imeOptions))) {
            chip.visibility = View.GONE
            return
        }
        val name = styleName(Styles.styleFor(info?.packageName, cfg))
        chip.text = name
        chip.contentDescription = getString(R.string.cd_style_chip, name)
        chip.visibility = View.VISIBLE
    }

    /** Next style for this app's category (or for other apps), saved like in the app's Styles screen. */
    private fun cycleStyle() {
        if (recording || transcribing) return
        val pkg = currentInputEditorInfo?.packageName
        val cfg = StyleStore.load(this)
        val current = Styles.styleFor(pkg, cfg)
        val next = Styles.ALL[(Styles.ALL.indexOf(current) + 1) % Styles.ALL.size]
        val category = Styles.categoryFor(pkg, cfg)
        StyleStore.save(this, if (category != null) cfg.withStyle(category, next) else cfg.copy(default = next))
        updateStyleChip()
        setStatus(getString(R.string.status_style_set, categoryName(category), styleName(next)))
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
