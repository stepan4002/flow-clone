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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Voice-only input method. Default flow (Whisper offline engine):
 * tap mic -> record (pulsing UI, "..." composing placeholder) -> tap again or
 * auto-stop on trailing silence -> transcribe on-device -> cleaned text committed.
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
        const val BACKSPACE_FIRST_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_MS = 50L
    }

    /** Cleanup options captured once per dictation session. */
    private data class SessionOptions(
        val autoStop: Boolean,
        val autoStopMs: Long,
        val removeFillers: Boolean,
        val punctuationCommands: Boolean,
        val english: Boolean,
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
    private var options = SessionOptions(
        autoStop = true,
        autoStopMs = 2500L,
        removeFillers = true,
        punctuationCommands = true,
        english = true,
    )

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
        stopDictation(commitPending = true)
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
        options = SessionOptions(
            autoStop = Prefs.isAutoStopEnabled(this),
            autoStopMs = Prefs.getAutoStopMs(this),
            removeFillers = Prefs.isFillerRemovalEnabled(this),
            punctuationCommands = Prefs.isPunctuationCommandsEnabled(this),
            english = Prefs.isStreamingMode(this) || Prefs.getLanguage(this) == "en",
        )

        val record = createAudioRecord()
        if (record == null) {
            setStatus(getString(R.string.status_audio_error))
            return
        }

        recording = true
        commitOnStop = true
        setRecordingUi(true)
        playCue(start = true)
        record.startRecording()

        sessionJob = when (engine) {
            is RecognizerManager.Engine.Whisper -> {
                setStatus(getString(R.string.status_listening_tap))
                launchWhisperSession(record, engine.recognizer)
            }
            is RecognizerManager.Engine.Streaming -> {
                setStatus(getString(R.string.status_listening))
                launchStreamingSession(record, engine.recognizer)
            }
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

    /** Default engine: record the whole take, then transcribe once. */
    private fun launchWhisperSession(record: AudioRecord, recognizer: OfflineRecognizer): Job =
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
            val text = try {
                val all = FloatArray(chunks.sumOf { it.size })
                var pos = 0
                for (c in chunks) {
                    c.copyInto(all, pos)
                    pos += c.size
                }
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(all, SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text
                } finally {
                    stream.release()
                }
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
                if (text.isBlank()) clearPlaceholder() else commitSegment(text)
                refreshIdleStatus()
            }
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
                    val text = recognizer.getResult(stream).text
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
                    val text = recognizer.getResult(stream).text
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

    private fun commitSegment(raw: String) {
        val ic = currentInputConnection ?: return
        val cleaned = TextCleanup.process(
            raw,
            capitalizeNext,
            dictionary,
            snippets,
            removeFillers = options.removeFillers,
            punctuationCommands = options.punctuationCommands,
            capitalizeEnglishI = options.english,
        )
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
