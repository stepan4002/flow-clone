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
        refreshStatuses()
        renderLists()
    }

    // ------------------------------------------------------------- settings

    private fun setupSettings() {
        // Language spinner (positions align with Prefs.LANGUAGES).
        binding.spinnerLanguage.adapter = ArrayAdapter.createFromResource(
            this, R.array.language_entries, android.R.layout.simple_spinner_dropdown_item
        )
        binding.spinnerLanguage.setSelection(Prefs.LANGUAGES.indexOf(Prefs.getLanguage(this)))
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lang = Prefs.LANGUAGES.getOrElse(position) { "auto" }
                if (lang != Prefs.getLanguage(this@SetupActivity)) {
                    Prefs.setLanguage(this@SetupActivity, lang)
                    RecognizerManager.applyLanguage(this@SetupActivity)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

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

        binding.switchFillers.isChecked = Prefs.isFillerRemovalEnabled(this)
        binding.switchFillers.setOnCheckedChangeListener { _, checked ->
            Prefs.setFillerRemovalEnabled(this, checked)
        }

        binding.switchStreaming.isChecked = Prefs.isStreamingMode(this)
        setupStreamingSwitchListener()
    }

    private fun setupStreamingSwitchListener() {
        binding.switchStreaming.setOnCheckedChangeListener { _, checked ->
            Prefs.setStreamingMode(this, checked)
            RecognizerManager.initialize(this, force = true)
            refreshStatuses()
        }
    }

    private fun setupUserLists() {
        binding.buttonAddDict.setOnClickListener {
            val from = binding.editDictFrom.text.toString().trim()
            val to = binding.editDictTo.text.toString().trim()
            if (from.isNotEmpty() && to.isNotEmpty()) {
                UserData.addDictionary(this, from, to)
                binding.editDictFrom.text?.clear()
                binding.editDictTo.text?.clear()
                renderLists()
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
    }

    private fun updateModelStatus(state: RecognizerManager.State) {
        val streaming = Prefs.isStreamingMode(this)
        val dir = if (streaming) ModelLocator.zipformerDir(this) else ModelLocator.whisperDir(this)
        binding.textModelPath.text = dir.absolutePath

        binding.textModelStatus.text = when (state) {
            is RecognizerManager.State.Ready -> getString(
                if (state.engine is RecognizerManager.Engine.Streaming)
                    R.string.setup_model_ready_streaming
                else
                    R.string.setup_model_ready_whisper
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

    // ----------------------------------------------------- dictionary lists

    private fun renderLists() {
        renderList(binding.listDictionary, UserData.getDictionary(this)) { index ->
            UserData.removeDictionary(this, index)
            renderLists()
        }
        renderList(binding.listSnippets, UserData.getSnippets(this)) { index ->
            UserData.removeSnippet(this, index)
            renderLists()
        }
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
                text = getString(R.string.setup_list_entry, entry.from, entry.to)
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
