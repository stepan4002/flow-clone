package com.flowkey.ime.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowkey.ime.R
import com.flowkey.ime.TextCleanup
import com.flowkey.ime.core.StyleConfig
import com.flowkey.ime.core.Styles
import com.flowkey.ime.data.StyleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun styleLabel(style: String) = stringResource(when (style) {
    Styles.VERBATIM -> R.string.style_verbatim
    Styles.CASUAL -> R.string.style_casual
    Styles.FORMAL -> R.string.style_formal
    else -> R.string.style_clean
})

@Composable
private fun categoryLabel(category: String) = stringResource(when (category) {
    "messaging" -> R.string.style_cat_messaging
    "email" -> R.string.style_cat_email
    "code" -> R.string.style_cat_code
    else -> R.string.style_cat_documents
})

@Composable
private fun styleOptions() = Styles.ALL.map { it to styleLabel(it) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StylesScreen(tick: Int) {
    val ctx = LocalContext.current
    var cfg by remember(tick) { mutableStateOf(StyleStore.load(ctx)) }
    var picking by remember { mutableStateOf<String?>(null) } // category to add an app to

    fun update(new: StyleConfig) { cfg = new; StyleStore.save(ctx, new) }

    ScreenColumn {
        Text(stringResource(R.string.styles_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.styles_desc), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard {
            SettingSwitch(stringResource(R.string.styles_enable), cfg.enabled, { update(cfg.copy(enabled = it)) },
                subtitle = stringResource(R.string.styles_enable_desc))
            Text(stringResource(R.string.styles_default), style = MaterialTheme.typography.labelLarge)
            Segmented(styleOptions(), cfg.default, { update(cfg.copy(default = it)) }, enabled = cfg.enabled)
        }

        cfg.categories.forEach { (name, cat) ->
            SectionCard(categoryLabel(name)) {
                Segmented(styleOptions(), cat.style, { update(cfg.withStyle(name, it)) }, enabled = cfg.enabled)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    cat.apps.forEach { pkg ->
                        InputChip(
                            selected = false,
                            onClick = { update(cfg.withoutApp(pkg)) },
                            label = { Text(ctx.appLabel(pkg), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { AppIcon(R.drawable.ic_close, stringResource(R.string.action_remove)) },
                            enabled = cfg.enabled,
                        )
                    }
                    AssistChip(
                        onClick = { picking = name },
                        label = { Text(stringResource(R.string.styles_add_app)) },
                        leadingIcon = { AppIcon(R.drawable.ic_add, null) },
                        enabled = cfg.enabled,
                    )
                }
            }
        }

        PreviewCard()
    }

    picking?.let { category ->
        AppPickerDialog(
            exclude = cfg.categories[category]?.apps.orEmpty().map { it.lowercase() }.toSet(),
            onDismiss = { picking = null },
            onPick = { pkg -> update(cfg.withApp(category, pkg)); picking = null },
        )
    }
}

@Composable
private fun PreviewCard() {
    var style by rememberSaveable { mutableStateOf(Styles.CASUAL) }
    val raw = stringResource(R.string.styles_preview_sample)
    val out = remember(style, raw) {
        val cleaned = TextCleanup.process(raw, true, emptyList(), emptyList(),
            removeFillers = style != Styles.VERBATIM, punctuationCommands = true, commandLanguage = "en")
        Styles.finish(cleaned, style, wholeDictation = true)
    }
    SectionCard(stringResource(R.string.styles_preview), subtitle = stringResource(R.string.styles_preview_desc)) {
        Segmented(styleOptions(), style, { style = it })
        Text(stringResource(R.string.styles_preview_said), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(raw, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.styles_preview_typed), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(out, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private data class InstalledApp(val pkg: String, val label: String)

private fun launcherApps(ctx: Context): List<InstalledApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != ctx.packageName }
        .map { InstalledApp(it, ctx.appLabel(it)) }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun AppPickerDialog(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<InstalledApp>?>(null) { value = withContext(Dispatchers.IO) { launcherApps(ctx) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.styles_pick_app)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                    placeholder = { Text(stringResource(R.string.styles_search_apps)) })
                val list = apps?.filter {
                    it.pkg.lowercase() !in exclude &&
                        (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
                }
                if (list == null) {
                    Text(stringResource(R.string.status_loading), modifier = Modifier.padding(16.dp))
                } else if (list.isEmpty()) {
                    EmptyState(stringResource(R.string.styles_no_apps))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                        items(list, key = { it.pkg }) { app ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(app.pkg) }.padding(vertical = 10.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.pkg, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
