package com.flowkey.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.flowkey.ime.databinding.ActivitySetupBinding
import kotlinx.coroutines.launch

/** Checklist-style onboarding, dictation settings, and dictionary/snippet management. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatuses()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonRecheck.setOnClickListener {
            RecognizerManager.initialize(this, force = true)
        }
        binding.buttonModelHelp.setOnClickListener { showModelHelp() }
        binding.buttonGrantMic.setOnClickListener {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        binding.buttonEnableKeyboard.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.buttonSwitchKeyboard.setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
        binding.buttonHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        setupSettings()
        setupUserLists()
        setupDownload()
        binding.buttonAddLanguage.setOnClickListener { showAddLanguage() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecognizerManager.state.collect { updateModelStatus(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RecognizerManager.initialize(this)
        if (Prefs.consumeStreamingCrashNotice(this)) {
            binding.switchStreaming.setOnCheckedChangeListener(null)
            binding.switchStreaming.isChecked = false
            setupStreamingSwitchListener()
            AlertDialog.Builder(this)
                .setTitle(R.string.streaming_crash_title)
                .setMessage(R.string.streaming_crash_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        if (Prefs.consumeWhisperSmallCrashNotice(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.small_crash_title)
                .setMessage(R.string.small_crash_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        if (Prefs.consumeHotwordsCrashNotice(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.hotwords_crash_title)
                .setMessage(R.string.hotwords_crash_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        refreshStatuses()
        renderLists()
        renderLanguages()
        rebuildLanguageSpinner() // the keyboard's language key may have changed it
        renderDownload(ModelDownloader.state.value)
    }

    // ------------------------------------------------------------- settings

    private fun setupSettings() {
        rebuildLanguageSpinner()

        // Auto-stop duration spinner (positions align with Prefs.AUTO_STOP_CHOICES_MS).
        binding.spinnerAutoStop.adapter = ArrayAdapter.createFromResource(
            this, R.array.auto_stop_entries, android.R.layout.simple_spinner_dropdown_item
        )
        binding.spinnerAutoStop.setSelection(
            Prefs.AUTO_STOP_CHOICES_MS.indexOf(Prefs.getAutoStopMs(this))
        )
        binding.spinnerAutoStop.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Prefs.setAutoStopMs(
                    this@SetupActivity,
                    Prefs.AUTO_STOP_CHOICES_MS.getOrElse(position) { 2500L },
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.switchAutoStop.isChecked = Prefs.isAutoStopEnabled(this)
        binding.switchAutoStop.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoStopEnabled(this, checked)
            binding.spinnerAutoStop.isEnabled = checked
        }
        binding.spinnerAutoStop.isEnabled = binding.switchAutoStop.isChecked

        binding.switchHaptics.isChecked = Prefs.isHapticsEnabled(this)
        binding.switchHaptics.setOnCheckedChangeListener { _, checked ->
            Prefs.setHapticsEnabled(this, checked)
        }

        binding.switchSound.isChecked = Prefs.isSoundEnabled(this)
        binding.switchSound.setOnCheckedChangeListener { _, checked ->
            Prefs.setSoundEnabled(this, checked)
        }

        binding.switchPunctuation.isChecked = Prefs.isPunctuationCommandsEnabled(this)
        binding.switchPunctuation.setOnCheckedChangeListener { _, checked ->
            Prefs.setPunctuationCommandsEnabled(this, checked)
        }

        binding.switchWordCorrection.isChecked = Prefs.isWordCorrectionEnabled(this)
        binding.switchWordCorrection.setOnCheckedChangeListener { _, checked ->
            Prefs.setWordCorrectionEnabled(this, checked)
        }

        binding.switchFillers.isChecked = Prefs.isFillerRemovalEnabled(this)
        binding.switchFillers.setOnCheckedChangeListener { _, checked ->
            Prefs.setFillerRemovalEnabled(this, checked)
        }

        binding.switchStreaming.isChecked = Prefs.isStreamingMode(this)
        setupStreamingSwitchListener()

        binding.switchLiveText.isChecked = Prefs.isLiveTextEnabled(this)
        binding.switchLiveText.setOnCheckedChangeListener { _, checked ->
            Prefs.setLiveTextEnabled(this, checked)
        }
        refreshLiveTextSwitch()
    }

    /**
     * (Re)builds the language spinner from the enabled languages. The listener is detached
     * while the adapter and selection change, so a rebuild never writes a language back.
     */
    private fun rebuildLanguageSpinner() {
        val spinner = binding.spinnerLanguage
        spinner.onItemSelectedListener = null
        val codes = Prefs.getEnabledLanguages(this)
        val labels = codes.map {
            if (it == Languages.AUTO) getString(R.string.lang_auto_detect) else Languages.displayName(it)
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(codes.indexOf(Prefs.getLanguage(this)).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lang = Prefs.getEnabledLanguages(this@SetupActivity)
                    .getOrElse(position) { Languages.AUTO }
                if (lang != Prefs.getLanguage(this@SetupActivity)) {
                    Prefs.setLanguage(this@SetupActivity, lang)
                    RecognizerManager.applyLanguage(this@SetupActivity)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupStreamingSwitchListener() {
        binding.switchStreaming.setOnCheckedChangeListener { _, checked ->
            Prefs.setStreamingMode(this, checked)
            RecognizerManager.initialize(this, force = true)
            refreshStatuses()
        }
    }

    /** Live text is a Whisper feature and needs the bundled voice detector. */
    private fun refreshLiveTextSwitch() {
        val vad = try {
            assets.list("vad")?.contains("silero_vad.onnx") == true
        } catch (_: java.io.IOException) {
            false
        }
        val streaming = Prefs.isStreamingMode(this)
        binding.switchLiveText.isEnabled = vad && !streaming
        binding.textLiveTextNote.setText(
            when {
                !vad -> R.string.setup_live_text_note_missing
                streaming -> R.string.setup_live_text_note_streaming
                else -> R.string.setup_live_text_note
            }
        )
    }

    private fun setupUserLists() {
        binding.buttonAddDict.setOnClickListener {
            val from = binding.editDictFrom.text.toString().trim()
            val to = binding.editDictTo.text.toString().trim()
            if (from.isNotEmpty() && to.isNotEmpty()) {
                UserData.addDictionary(this, from, to)
                binding.editDictFrom.text?.clear()
                binding.editDictTo.text?.clear()
                onUserTermsChanged()
            }
        }
        binding.buttonAddWord.setOnClickListener {
            val word = binding.editWord.text.toString().trim()
            if (word.isEmpty()) return@setOnClickListener
            if (UserData.addWord(this, word)) {
                binding.editWord.text?.clear()
                onUserTermsChanged()
            } else {
                Toast.makeText(this, R.string.setup_word_not_added, Toast.LENGTH_SHORT).show()
            }
        }
        binding.buttonAddSnippet.setOnClickListener {
            val trigger = binding.editSnippetTrigger.text.toString().trim()
            val expansion = binding.editSnippetExpansion.text.toString().trim()
            if (trigger.isNotEmpty() && expansion.isNotEmpty()) {
                UserData.addSnippet(this, trigger, expansion)
                binding.editSnippetTrigger.text?.clear()
                binding.editSnippetExpansion.text?.clear()
                renderLists()
            }
        }
    }

    // ------------------------------------------------------------ statuses

    private fun refreshStatuses() {
        val micGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        binding.textMicStatus.text = getString(
            if (micGranted) R.string.setup_mic_granted else R.string.setup_mic_missing
        )
        binding.buttonGrantMic.isEnabled = !micGranted

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        binding.textEnableStatus.text = getString(
            if (enabled) R.string.setup_ime_enabled else R.string.setup_ime_disabled
        )

        updateModelStatus(RecognizerManager.state.value)
        refreshLiveTextSwitch()
    }

    private fun updateModelStatus(state: RecognizerManager.State) {
        val streaming = Prefs.isStreamingMode(this)
        val dir = if (streaming) ModelLocator.zipformerDir(this) else ModelLocator.whisperDir(this)
        binding.textModelPath.text = dir.absolutePath

        binding.textModelStatus.text = when (state) {
            is RecognizerManager.State.Ready -> getString(
                when (val engine = state.engine) {
                    is RecognizerManager.Engine.Streaming -> R.string.setup_model_ready_streaming
                    is RecognizerManager.Engine.Whisper ->
                        if (engine.variant == Prefs.VARIANT_SMALL) R.string.setup_model_ready_whisper_small
                        else R.string.setup_model_ready_whisper
                }
            )
            RecognizerManager.State.Loading,
            RecognizerManager.State.NotLoaded -> getString(R.string.status_loading)
            is RecognizerManager.State.ModelMissing -> getString(R.string.setup_model_missing)
            is RecognizerManager.State.Error ->
                getString(R.string.setup_model_error, state.message)
        }
    }

    private fun showModelHelp() {
        val streaming = Prefs.isStreamingMode(this)
        val dir = if (streaming) ModelLocator.zipformerDir(this) else ModelLocator.whisperDir(this)
        val message = getString(
            if (streaming) R.string.model_help_zipformer else R.string.model_help_whisper,
            dir.absolutePath
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.model_help_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.model_help_copy_path) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("model path", dir.absolutePath))
            }
            .show()
    }

    // ------------------------------------------------- optional model download

    private fun setupDownload() {
        binding.buttonDl.setOnClickListener { onDownloadButton() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloader.state.collect { renderDownload(it) }
            }
        }
    }

    private fun onDownloadButton() {
        val state = ModelDownloader.state.value
        when {
            state is ModelDownloader.DlState.Running || state is ModelDownloader.DlState.Verifying ->
                ModelDownloader.cancel()
            ModelDownloader.isInstalled(this) -> AlertDialog.Builder(this)
                .setMessage(R.string.setup_dl_delete_confirm)
                .setPositiveButton(R.string.setup_dl_delete) { _, _ ->
                    ModelDownloader.delete(this)
                    renderDownload(ModelDownloader.state.value)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.setup_dl_confirm_title)
                .setMessage(R.string.setup_dl_confirm_message)
                .setPositiveButton(R.string.setup_dl_download) { _, _ -> ModelDownloader.start(this) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Everything is derived from the files on disk + the live state, so it survives restarts. */
    private fun renderDownload(state: ModelDownloader.DlState) {
        val installed = ModelDownloader.isInstalled(this)
        val busy = state is ModelDownloader.DlState.Running || state is ModelDownloader.DlState.Verifying
        val partial = !installed && ModelDownloader.hasPartial(this)
        val mb = { bytes: Long -> (bytes / 1_000_000).toInt() }

        binding.progressDl.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressDl.isIndeterminate = state is ModelDownloader.DlState.Verifying
        if (state is ModelDownloader.DlState.Running && state.total > 0) {
            binding.progressDl.setProgressCompat((state.done * 1000 / state.total).toInt(), true)
        }
        binding.textDlStatus.text = when (state) {
            is ModelDownloader.DlState.Running -> getString(R.string.setup_dl_running, mb(state.done), mb(state.total))
            ModelDownloader.DlState.Verifying -> getString(R.string.setup_dl_verifying)
            is ModelDownloader.DlState.Failed -> when (state.reason) {
                ModelDownloader.Reason.STORAGE -> getString(R.string.dl_error_storage)
                ModelDownloader.Reason.SPACE -> getString(R.string.dl_error_space, state.detail)
                ModelDownloader.Reason.NETWORK -> getString(R.string.dl_error_network, state.detail)
                ModelDownloader.Reason.CHECKSUM -> getString(R.string.dl_error_checksum, state.detail)
            }
            else -> when {
                installed -> getString(R.string.setup_dl_installed, mb(ModelDownloader.TOTAL_BYTES))
                partial -> getString(R.string.setup_dl_partial)
                else -> getString(R.string.setup_dl_not_installed)
            }
        }
        binding.buttonDl.setText(
            when {
                busy -> R.string.setup_dl_cancel
                installed -> R.string.setup_dl_delete
                partial || state is ModelDownloader.DlState.Failed -> R.string.setup_dl_resume
                else -> R.string.setup_dl_download
            }
        )

        binding.switchUseSmall.setOnCheckedChangeListener(null)
        binding.switchUseSmall.isEnabled = installed && !busy
        binding.switchUseSmall.isChecked = installed && Prefs.getWhisperVariant(this) == Prefs.VARIANT_SMALL
        binding.switchUseSmall.setOnCheckedChangeListener { _, checked ->
            Prefs.setWhisperVariant(this, if (checked) Prefs.VARIANT_SMALL else Prefs.VARIANT_BASE)
            RecognizerManager.initialize(this, force = true)
        }
    }

    // ----------------------------------------------------- dictionary lists

    // ------------------------------------------------------------- languages

    private fun renderLanguages() {
        val container = binding.listLanguages
        container.removeAllViews()
        val codes = Prefs.getEnabledLanguages(this).filter { it != Languages.AUTO }
        for (code in codes) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = getString(R.string.setup_language_row, code.uppercase(), Languages.displayName(code))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = getString(R.string.setup_list_remove)
                setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.flowkey_accent))
                setPadding(dp(12), dp(4), 0, dp(4))
                setOnClickListener {
                    if (Prefs.removeLanguage(this@SetupActivity, code)) {
                        RecognizerManager.applyLanguage(this@SetupActivity)
                        rebuildLanguageSpinner()
                        renderLanguages()
                    } else {
                        Toast.makeText(this@SetupActivity, R.string.setup_languages_keep_one, Toast.LENGTH_SHORT).show()
                    }
                }
            })
            container.addView(row)
        }
    }

    private fun showAddLanguage() {
        val enabled = Prefs.getEnabledLanguages(this).toSet()
        val choices = Languages.WHISPER_CODES.filter { it !in enabled }
            .sortedBy { Languages.displayName(it) }
        val labels = choices.map { Languages.displayName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_languages_pick)
            .setItems(labels) { _, which ->
                val code = choices[which]
                if (Prefs.addLanguage(this, code)) {
                    rebuildLanguageSpinner()
                    renderLanguages()
                    Toast.makeText(
                        this,
                        getString(R.string.setup_languages_added, Languages.displayName(code)),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderLists() {
        renderList(binding.listDictionary, UserData.getDictionary(this)) { index ->
            UserData.removeDictionary(this, index)
            onUserTermsChanged()
        }
        renderList(binding.listWords, UserData.getWords(this).map { UserData.Entry(it, "") }) { index ->
            UserData.removeWord(this, index)
            onUserTermsChanged()
        }
        renderList(binding.listSnippets, UserData.getSnippets(this)) { index ->
            UserData.removeSnippet(this, index)
            renderLists()
        }
    }

    /** Words and dictionary targets feed the streaming engine's hotwords: reload it. */
    private fun onUserTermsChanged() {
        renderLists()
        RecognizerManager.onUserTermsChanged(this)
    }

    private fun renderList(
        container: LinearLayout,
        entries: List<UserData.Entry>,
        onRemove: (Int) -> Unit,
    ) {
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.setup_list_empty)
                alpha = 0.6f
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = if (entry.to.isEmpty()) {
                    entry.from
                } else {
                    getString(R.string.setup_list_entry, entry.from, entry.to)
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(TextView(this).apply {
                text = getString(R.string.setup_list_remove)
                setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.flowkey_accent))
                setPadding(dp(12), dp(4), 0, dp(4))
                setOnClickListener { onRemove(index) }
            })
            container.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
