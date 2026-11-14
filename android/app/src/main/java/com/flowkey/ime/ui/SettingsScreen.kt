package com.flowkey.ime.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flowkey.ime.Languages
import com.flowkey.ime.ModelDownloader
import com.flowkey.ime.ModelLocator
import com.flowkey.ime.Prefs
import com.flowkey.ime.R
import com.flowkey.ime.RecognizerManager
import com.flowkey.ime.core.LlmClient
import com.flowkey.ime.core.LlmConfig
import com.flowkey.ime.data.AiPrefs
import com.flowkey.ime.data.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Text and colour for the engine state (top bar pill and the Engine card). */
@Composable
fun modelStatus(state: RecognizerManager.State): Pair<String, Color> = when (state) {
    is RecognizerManager.State.Ready -> stringResource(when (val e = state.engine) {
        is RecognizerManager.Engine.Streaming -> R.string.pill_streaming
        is RecognizerManager.Engine.Whisper ->
            if (e.variant == Prefs.VARIANT_SMALL) R.string.pill_whisper_small else R.string.pill_whisper
    }) to Tokens.Success
    RecognizerManager.State.Loading, RecognizerManager.State.NotLoaded ->
        stringResource(R.string.status_loading) to Tokens.Warning
    is RecognizerManager.State.ModelMissing -> stringResource(R.string.setup_model_missing) to MaterialTheme.colorScheme.error
    is RecognizerManager.State.Error -> stringResource(R.string.pill_error) to MaterialTheme.colorScheme.error
}

@Composable
fun SettingsScreen(tick: Int) {
    ScreenColumn {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        LanguagesCard(tick)
        EngineCard(tick)
        DownloadCard()
        DictationCard(tick)
        TextCard(tick)
        AiCard(tick)
        AboutCard()
    }
}

// ------------------------------------------------------------------ languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagesCard(tick: Int) {
    val ctx = LocalContext.current
    var changes by remember { mutableIntStateOf(0) }
    val enabled = remember(tick, changes) { Prefs.getEnabledLanguages(ctx) }
    val current = remember(tick, changes) { Prefs.getLanguage(ctx) }
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val streaming = remember(tick) { Prefs.isStreamingMode(ctx) }
    @Composable fun label(code: String) =
        if (code == Languages.AUTO) stringResource(R.string.lang_auto_detect) else Languages.displayName(code)

    SectionCard(stringResource(R.string.setup_languages_title), subtitle = stringResource(R.string.setup_languages_desc)) {
        if (streaming) Text(stringResource(R.string.status_streaming_en_only), style = MaterialTheme.typography.bodySmall,
            color = Tokens.Warning)
        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
            OutlinedTextField(
                value = label(current), onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.setup_language_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                enabled.forEach { code ->
                    DropdownMenuItem(text = { Text(label(code)) }, onClick = {
                        menu = false
                        if (code != Prefs.getLanguage(ctx)) {
                            Prefs.setLanguage(ctx, code)
                            RecognizerManager.applyLanguage(ctx)
                            changes++
                        }
                    })
                }
            }
        }
        enabled.filter { it != Languages.AUTO }.forEach { code ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.setup_language_row, code.uppercase(), Languages.displayName(code)),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = {
                    if (Prefs.removeLanguage(ctx, code)) {
                        RecognizerManager.applyLanguage(ctx)
                        changes++
                    } else {
                        Toast.makeText(ctx, R.string.setup_languages_keep_one, Toast.LENGTH_SHORT).show()
                    }
                }) { AppIcon(R.drawable.ic_close, stringResource(R.string.action_remove_language, Languages.displayName(code))) }
            }
        }
        OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.setup_languages_add)) }
    }

    if (adding) {
        var query by remember { mutableStateOf("") }
        val choices = remember(enabled) {
            Languages.WHISPER_CODES.filter { it !in enabled }.sortedBy { Languages.displayName(it) }
        }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.setup_languages_pick)) },
            text = {
                Column {
                    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                        placeholder = { Text(stringResource(R.string.settings_search_language)) })
                    LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                        items(choices.filter {
                            query.isBlank() || Languages.displayName(it).contains(query, true) || it.contains(query, true)
                        }) { code ->
                            Text(Languages.displayName(code) + "  ·  " + code.uppercase(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (Prefs.addLanguage(ctx, code)) {
                                        Toast.makeText(ctx, ctx.getString(R.string.setup_languages_added,
                                            Languages.displayName(code)), Toast.LENGTH_LONG).show()
                                        changes++
                                    }
                                    adding = false
                                }.padding(vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

// --------------------------------------------------------------------- engine

@Composable
private fun EngineCard(tick: Int) {
    val ctx = LocalContext.current
    val state by RecognizerManager.state.collectAsState()
    var changes by remember { mutableIntStateOf(0) }
    val streaming = remember(tick, changes) { Prefs.isStreamingMode(ctx) }
    val liveText = remember(tick, changes) { Prefs.isLiveTextEnabled(ctx) }
    val vad = remember { runCatching { ctx.assets.list("vad")?.contains("silero_vad.onnx") == true }.getOrDefault(false) }
    var help by remember { mutableStateOf(false) }
    val dir = if (streaming) ModelLocator.zipformerDir(ctx) else ModelLocator.whisperDir(ctx)

    SectionCard(stringResource(R.string.setup_engine_title)) {
        val (text, color) = modelStatus(state)
        StatusPill(text, color)
        val detail = when (val s = state) {
            is RecognizerManager.State.Ready -> stringResource(when (val e = s.engine) {
                is RecognizerManager.Engine.Streaming -> R.string.setup_model_ready_streaming
                is RecognizerManager.Engine.Whisper ->
                    if (e.variant == Prefs.VARIANT_SMALL) R.string.setup_model_ready_whisper_small
                    else R.string.setup_model_ready_whisper
            })
            is RecognizerManager.State.Error -> stringResource(R.string.setup_model_error, s.message)
            is RecognizerManager.State.ModelMissing -> stringResource(R.string.setup_model_missing)
            else -> null
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
        Text(dir.absolutePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { RecognizerManager.initialize(ctx, force = true) }) { Text(stringResource(R.string.setup_recheck)) }
            OutlinedButton(onClick = { help = true }) { Text(stringResource(R.string.setup_instructions)) }
        }
        HorizontalDivider()
        SettingSwitch(stringResource(R.string.setup_live_text_switch), liveText && vad && !streaming, {
            Prefs.setLiveTextEnabled(ctx, it); changes++
        }, subtitle = stringResource(when {
            !vad -> R.string.setup_live_text_note_missing
            streaming -> R.string.setup_live_text_note_streaming
            else -> R.string.setup_live_text_note
        }), enabled = vad && !streaming)
        SettingSwitch(stringResource(R.string.setup_streaming_switch), streaming, {
            Prefs.setStreamingMode(ctx, it)
            RecognizerManager.initialize(ctx, force = true)
            changes++
        }, subtitle = stringResource(R.string.setup_streaming_desc))
    }

    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.model_help_title)) },
            text = {
                Text(stringResource(if (streaming) R.string.model_help_zipformer else R.string.model_help_whisper,
                    dir.absolutePath), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = {
                TextButton(onClick = { ctx.copyText(dir.absolutePath) }) { Text(stringResource(R.string.model_help_copy_path)) }
            },
        )
    }
}

// ------------------------------------------------------------------- download

@Composable
private fun DownloadCard() {
    val ctx = LocalContext.current
    val state by ModelDownloader.state.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) } // "download" | "delete"
    var variantChanges by remember { mutableIntStateOf(0) }
    val installed = remember(state) { ModelDownloader.isInstalled(ctx) }
    val busy = state is ModelDownloader.DlState.Running || state is ModelDownloader.DlState.Verifying
    val partial = !installed && remember(state) { ModelDownloader.hasPartial(ctx) }
    val useSmall = remember(state, variantChanges) { installed && Prefs.getWhisperVariant(ctx) == Prefs.VARIANT_SMALL }
    val mb = { b: Long -> (b / 1_000_000).toInt() }

    SectionCard(stringResource(R.string.setup_dl_title), subtitle = stringResource(R.string.setup_dl_desc)) {
        val s = state
        if (s is ModelDownloader.DlState.Running && s.total > 0) {
            LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
        } else if (s is ModelDownloader.DlState.Verifying) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(when (s) {
            is ModelDownloader.DlState.Running -> stringResource(R.string.setup_dl_running, mb(s.done), mb(s.total))
            ModelDownloader.DlState.Verifying -> stringResource(R.string.setup_dl_verifying)
            is ModelDownloader.DlState.Failed -> when (s.reason) {
                ModelDownloader.Reason.STORAGE -> stringResource(R.string.dl_error_storage)
                ModelDownloader.Reason.SPACE -> stringResource(R.string.dl_error_space, s.detail)
                ModelDownloader.Reason.NETWORK -> stringResource(R.string.dl_error_network, s.detail)
                ModelDownloader.Reason.CHECKSUM -> stringResource(R.string.dl_error_checksum, s.detail)
            }
            else -> when {
                installed -> stringResource(R.string.setup_dl_installed, mb(ModelDownloader.TOTAL_BYTES))
                partial -> stringResource(R.string.setup_dl_partial)
                else -> stringResource(R.string.setup_dl_not_installed)
            }
        }, style = MaterialTheme.typography.bodyMedium)
        val buttonText = stringResource(when {
            busy -> R.string.setup_dl_cancel
            installed -> R.string.setup_dl_delete
            partial || s is ModelDownloader.DlState.Failed -> R.string.setup_dl_resume
            else -> R.string.setup_dl_download
        })
        OutlinedButton(onClick = {
            when {
                busy -> ModelDownloader.cancel()
                installed -> confirm = "delete"
                else -> confirm = "download"
            }
        }) { Text(buttonText) }
        SettingSwitch(stringResource(R.string.setup_dl_use_switch), useSmall, {
            Prefs.setWhisperVariant(ctx, if (it) Prefs.VARIANT_SMALL else Prefs.VARIANT_BASE)
            RecognizerManager.initialize(ctx, force = true)
            variantChanges++
        }, enabled = installed && !busy)
    }

    when (confirm) {
        "download" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.setup_dl_confirm_title)) },
            text = { Text(stringResource(R.string.setup_dl_confirm_message)) },
            confirmButton = { TextButton(onClick = { ModelDownloader.start(ctx); confirm = null }) {
                Text(stringResource(R.string.setup_dl_download)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
        "delete" -> AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.setup_dl_delete_confirm)) },
            confirmButton = { TextButton(onClick = { ModelDownloader.delete(ctx); confirm = null; variantChanges++ }) {
                Text(stringResource(R.string.setup_dl_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

// ------------------------------------------------------------ dictation / text

@Composable
private fun DictationCard(tick: Int) {
    val ctx = LocalContext.current
    var changes by remember { mutableIntStateOf(0) }
    val autoStop = remember(tick, changes) { Prefs.isAutoStopEnabled(ctx) }
    val autoStopMs = remember(tick, changes) { Prefs.getAutoStopMs(ctx) }
    val haptics = remember(tick, changes) { Prefs.isHapticsEnabled(ctx) }
    val sound = remember(tick, changes) { Prefs.isSoundEnabled(ctx) }
    SectionCard(stringResource(R.string.setup_settings_title)) {
        SettingSwitch(stringResource(R.string.setup_auto_stop_switch), autoStop, { Prefs.setAutoStopEnabled(ctx, it); changes++ })
        Text(stringResource(R.string.setup_auto_stop_duration), style = MaterialTheme.typography.labelLarge)
        Segmented(Prefs.AUTO_STOP_CHOICES_MS.map { it to "%.1f s".format(it / 1000.0).replace(".0 s", " s") },
            autoStopMs, { Prefs.setAutoStopMs(ctx, it); changes++ }, enabled = autoStop)
        SettingSwitch(stringResource(R.string.setup_haptics_switch), haptics, { Prefs.setHapticsEnabled(ctx, it); changes++ })
        SettingSwitch(stringResource(R.string.setup_sound_switch), sound, { Prefs.setSoundEnabled(ctx, it); changes++ })
    }
}

@Composable
private fun TextCard(tick: Int) {
    val ctx = LocalContext.current
    var changes by remember { mutableIntStateOf(0) }
    val punct = remember(tick, changes) { Prefs.isPunctuationCommandsEnabled(ctx) }
    val fillers = remember(tick, changes) { Prefs.isFillerRemovalEnabled(ctx) }
    val correction = remember(tick, changes) { Prefs.isWordCorrectionEnabled(ctx) }
    SectionCard(stringResource(R.string.settings_text_title)) {
        SettingSwitch(stringResource(R.string.setup_punctuation_switch), punct,
            { Prefs.setPunctuationCommandsEnabled(ctx, it); changes++ }, subtitle = stringResource(R.string.setup_punctuation_desc))
        SettingSwitch(stringResource(R.string.setup_fillers_switch), fillers, { Prefs.setFillerRemovalEnabled(ctx, it); changes++ })
        SettingSwitch(stringResource(R.string.setup_word_correction_switch), correction,
            { Prefs.setWordCorrectionEnabled(ctx, it); changes++ }, subtitle = stringResource(R.string.settings_word_correction_desc))
    }
}

// ------------------------------------------------------------------------- AI

@Composable
private fun AiCard(tick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(tick) { mutableStateOf(AiPrefs.enabled(ctx)) }
    var tone by remember(tick) { mutableStateOf(AiPrefs.toneMatching(ctx)) }
    var cfg by remember(tick) { mutableStateOf(AiPrefs.config(ctx)) }
    var keyInput by remember { mutableStateOf("") }
    var keyChanges by remember { mutableIntStateOf(0) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val slot = LlmClient.keySlot(cfg)
    val hasKey = remember(slot, keyChanges, tick) { KeyVault.has(ctx, slot) }
    val cloud = cfg.provider != "ollama"

    fun save(new: LlmConfig) { cfg = new; AiPrefs.save(ctx, new); testResult = null }

    SectionCard(stringResource(R.string.ai_title), subtitle = stringResource(R.string.ai_desc)) {
        SettingSwitch(stringResource(R.string.ai_enable), enabled, { enabled = it; AiPrefs.setEnabled(ctx, it) })
        Text(stringResource(R.string.ai_provider), style = MaterialTheme.typography.labelLarge)
        Segmented(listOf("ollama" to stringResource(R.string.ai_provider_ollama),
                         "openai" to stringResource(R.string.ai_provider_openai),
                         "anthropic" to stringResource(R.string.ai_provider_anthropic)),
            cfg.provider, { save(cfg.copy(provider = it)); keyInput = "" })

        when (cfg.provider) {
            "ollama" -> {
                ConfigField(stringResource(R.string.ai_ollama_url), cfg.url, "http://192.168.1.20:11434",
                    KeyboardType.Uri) { save(cfg.copy(url = it)) }
                ConfigField(stringResource(R.string.ai_model), cfg.model, LlmClient.DEFAULT_MODELS.getValue("ollama")) {
                    save(cfg.copy(model = it)) }
            }
            "openai" -> {
                ConfigField(stringResource(R.string.ai_api_base), cfg.apiBase, LlmClient.OPENAI_BASE, KeyboardType.Uri) {
                    save(cfg.copy(apiBase = it)) }
                ConfigField(stringResource(R.string.ai_model), cfg.apiModel, LlmClient.DEFAULT_MODELS.getValue("openai")) {
                    save(cfg.copy(apiModel = it)) }
            }
            else -> ConfigField(stringResource(R.string.ai_model), cfg.anthropicModel,
                LlmClient.DEFAULT_MODELS.getValue("anthropic")) { save(cfg.copy(anthropicModel = it)) }
        }

        if (cloud) {
            OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it },
                label = { Text(stringResource(R.string.ai_key)) },
                placeholder = { Text(stringResource(if (hasKey) R.string.ai_key_saved else R.string.ai_key_hint)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.ai_key_where, LlmClient.hostOf(
                    if (cfg.provider == "openai") LlmClient.openaiBase(cfg) else LlmClient.ANTHROPIC_URL))) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = keyInput.isNotBlank(), onClick = {
                    val k = keyInput
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { KeyVault.set(ctx, slot, k) }
                        keyChanges++
                        if (saved) {
                            keyInput = ""; testResult = null
                        } else {
                            testResult = false to ctx.getString(R.string.ai_key_save_failed)
                        }
                    }
                }) { Text(stringResource(R.string.ai_key_save)) }
                if (hasKey) OutlinedButton(onClick = {
                    KeyVault.delete(ctx, slot); keyChanges++; testResult = null
                }) { Text(stringResource(R.string.action_remove)) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = !testing, onClick = {
                testing = true
                scope.launch {
                    testResult = withContext(Dispatchers.IO) { AiPrefs.client(ctx).test() }
                    testing = false
                }
            }) { Text(stringResource(if (testing) R.string.ai_testing else R.string.ai_test)) }
            testResult?.let { (ok, msg) ->
                Text(msg, style = MaterialTheme.typography.bodyMedium,
                    color = if (ok) Tokens.Success else MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            }
        }
        SettingSwitch(stringResource(R.string.ai_tone), tone, { tone = it; AiPrefs.setToneMatching(ctx, it) },
            subtitle = stringResource(R.string.ai_tone_desc))
        Text(stringResource(if (cloud) R.string.ai_privacy_cloud else R.string.ai_privacy_local),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A text setting saved when it changes; [placeholder] shows the default. */
@Composable
private fun ConfigField(label: String, value: String, placeholder: String,
                        type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
    )
}

// ---------------------------------------------------------------------- about

@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    SectionCard(stringResource(R.string.about_title)) {
        Text(stringResource(R.string.about_version, appVersion(ctx)), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ctx.getString(R.string.about_url)))) }
        }) { Text(stringResource(R.string.about_source)) }
    }
}

private fun appVersion(ctx: android.content.Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
