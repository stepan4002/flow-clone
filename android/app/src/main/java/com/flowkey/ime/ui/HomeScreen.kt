package com.flowkey.ime.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.flowkey.ime.R
import com.flowkey.ime.RecognizerManager
import com.flowkey.ime.core.HistoryEntry
import com.flowkey.ime.core.StatsCalc
import com.flowkey.ime.data.AiPrefs
import com.flowkey.ime.data.HistoryStore
import com.flowkey.ime.data.StatsStore
import com.flowkey.ime.data.StoreScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Live setup state, re-read on every resume ([tick]). */
data class SetupState(val model: Boolean, val mic: Boolean, val enabled: Boolean, val selected: Boolean) {
    val done get() = model && mic && enabled && selected
}

fun readSetup(ctx: Context): SetupState {
    val mic = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    // packageName-based, so the .debug build checks itself
    val enabled = imm?.enabledInputMethodList?.any { it.packageName == ctx.packageName } == true
    val current = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
    val selected = current.startsWith(ctx.packageName + "/")
    val model = RecognizerManager.state.value.let {
        it is RecognizerManager.State.Ready || it is RecognizerManager.State.Loading ||
            it == RecognizerManager.State.NotLoaded
    }
    return SetupState(model, mic, enabled, selected)
}

@Composable
fun HomeScreen(tick: Int, onOpenHistory: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val version by StoreScope.version.collectAsState()
    val modelState by RecognizerManager.state.collectAsState()
    var localTick by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { localTick++ }
    val setup = remember(tick, localTick, modelState) { readSetup(ctx) }
    val stats by produceState<StatsCalc?>(null, version, tick) {
        value = withContext(Dispatchers.IO) { StatsStore.load(ctx) }
    }
    val recent by produceState<List<HistoryEntry>>(emptyList(), version, tick) {
        value = withContext(Dispatchers.IO) { HistoryStore.entries(ctx).take(5) }
    }
    val missingKey = remember(tick) { AiPrefs.missingKey(ctx) }

    ScreenColumn {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!setup.done) {
            SectionCard(stringResource(R.string.home_setup_title), subtitle = stringResource(R.string.home_setup_desc)) {
                val modelText = when (modelState) {
                    is RecognizerManager.State.ModelMissing -> stringResource(R.string.setup_model_missing)
                    is RecognizerManager.State.Error -> stringResource(R.string.setup_model_error,
                        (modelState as RecognizerManager.State.Error).message)
                    else -> null
                }
                ChecklistRow(setup.model, stringResource(R.string.home_step_model), modelText) {
                    if (!setup.model) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_fix)) }
                }
                ChecklistRow(setup.mic, stringResource(R.string.home_step_mic), null) {
                    if (!setup.mic) FilledTonalButton(onClick = {
                        micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }) { Text(stringResource(R.string.home_allow)) }
                }
                ChecklistRow(setup.enabled, stringResource(R.string.home_step_enable), null) {
                    if (!setup.enabled) FilledTonalButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }) { Text(stringResource(R.string.home_turn_on)) }
                }
                ChecklistRow(setup.selected, stringResource(R.string.home_step_select),
                    stringResource(R.string.home_step_select_detail)) {
                    if (!setup.selected && setup.enabled) FilledTonalButton(onClick = {
                        ctx.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                    }) { Text(stringResource(R.string.home_choose)) }
                }
            }
        }

        if (missingKey) {
            SectionCard(stringResource(R.string.home_ai_key_title), subtitle = stringResource(R.string.home_ai_key_desc)) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_open_ai_settings)) }
            }
        }

        TryItCard()

        val s = stats
        StatsGrid(s)
        ChartCard(s)
        if (s != null && s.topApps().isNotEmpty()) TopAppsCard(s)
        RecentCard(recent, onOpenHistory)
        TipsCard()
    }
}

@Composable
private fun TryItCard() {
    var text by rememberSaveable { mutableStateOf("") }
    SectionCard(stringResource(R.string.home_try_title)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.home_try_hint)) },
            minLines = 2,
        )
    }
}

@Composable
private fun StatsGrid(s: StatsCalc?) {
    val (cur, best) = s?.streak() ?: (0 to 0)
    val wpm = s?.wpm()
    val saved = s?.timeSavedMinutes() ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Modifier.weight(1f), "%,d".format(s?.data?.totalWords ?: 0),
                stringResource(R.string.stat_words), stringResource(R.string.stat_words_today, s?.today() ?: 0))
            StatTile(Modifier.weight(1f), "$cur 🔥", stringResource(R.string.stat_streak),
                stringResource(R.string.stat_streak_best, best))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Modifier.weight(1f), wpm?.toString() ?: "—", stringResource(R.string.stat_wpm),
                stringResource(if (wpm == null) R.string.stat_wpm_needs else R.string.stat_wpm_detail))
            StatTile(Modifier.weight(1f), formatMinutes(saved), stringResource(R.string.stat_saved),
                stringResource(R.string.stat_saved_detail))
        }
    }
}

@Composable
private fun formatMinutes(m: Int): String =
    if (m < 60) stringResource(R.string.unit_minutes, m)
    else stringResource(R.string.unit_hours_minutes, m / 60, m % 60)

@Composable
private fun StatTile(modifier: Modifier, value: String, label: String, detail: String) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) {}) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChartCard(s: StatsCalc?) {
    val series = s?.daysSeries(30) ?: emptyList()
    val total = series.sumOf { it.second }
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = stringResource(R.string.chart_description, total)
    SectionCard(stringResource(R.string.chart_title), subtitle = stringResource(R.string.chart_total, total)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .semantics { contentDescription = description },
        ) {
            if (series.isEmpty()) return@Canvas
            val max = (series.maxOf { it.second }).coerceAtLeast(1)
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (series.size - 1)) / series.size
            series.forEachIndexed { i, (_, words) ->
                val x = i * (w + gap)
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
                if (words > 0) {
                    val h = (size.height * words / max).coerceAtLeast(w)
                    val color = if (i == series.lastIndex) accent else accent.copy(alpha = 0.55f)
                    drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2))
                }
            }
        }
        if (series.isNotEmpty()) {
            val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            Row(Modifier.fillMaxWidth()) {
                Text(LocalDate.parse(series.first().first).format(fmt), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.chart_today), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TopAppsCard(s: StatsCalc) {
    val ctx = LocalContext.current
    val apps = s.topApps(5)
    val max = apps.maxOf { it.second }.coerceAtLeast(1)
    SectionCard(stringResource(R.string.top_apps_title)) {
        apps.forEach { (pkg, words) ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ctx.appLabel(pkg), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.words_count, words), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { words.toFloat() / max },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}

@Composable
private fun RecentCard(recent: List<HistoryEntry>, onOpenHistory: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard(stringResource(R.string.recent_title)) {
        if (recent.isEmpty()) {
            EmptyState(stringResource(R.string.recent_empty))
        } else {
            recent.forEach { e ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.action_copy)) {
                        ctx.copyText(e.text)
                    },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(e.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(relativeLabel(e) + " · " + ctx.appLabel(e.app), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.recent_see_all)) }
        }
    }
}

@Composable
private fun TipsCard() {
    SectionCard(stringResource(R.string.tips_title)) {
        Text(stringResource(R.string.tip_mic), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.tip_edit), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.tip_style), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.tip_commands), style = MaterialTheme.typography.bodyMedium)
    }
}

/** "14:32" today, otherwise a short date + time. */
fun relativeLabel(e: HistoryEntry): String {
    val millis = e.epochMillis()
    if (millis <= 0) return e.ts
    val dt = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    return if (dt.toLocalDate() == LocalDate.now()) dt.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    else dt.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
}
