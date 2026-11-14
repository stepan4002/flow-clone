package com.flowkey.ime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.flowkey.ime.R
import com.flowkey.ime.RecognizerManager
import com.flowkey.ime.UserData
import com.flowkey.ime.core.HistoryEntry
import com.flowkey.ime.data.HistoryStore
import com.flowkey.ime.data.StoreScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(tick: Int) {
    val ctx = LocalContext.current
    val version by StoreScope.version.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var total by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var addWordFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, version, tick) {
        if (entries != null) delay(200) // debounce typing
        val (found, all) = withContext(Dispatchers.IO) {
            val all = HistoryStore.entries(ctx)
            com.flowkey.ime.core.HistoryFormat.search(all, query) to all.size
        }
        entries = found
        total = all
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.history_count, total), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (total > 0) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear)) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { AppIcon(R.drawable.ic_search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { AppIcon(R.drawable.ic_close, stringResource(R.string.action_clear_search)) } }
            } else null,
            placeholder = { Text(stringResource(R.string.history_search)) },
            shape = MaterialTheme.shapes.medium,
        )
        val list = entries
        when {
            list == null -> Unit
            total == 0 -> EmptyState(stringResource(R.string.history_empty_long))
            list.isEmpty() -> EmptyState(stringResource(R.string.history_no_results, query))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { e ->
                    HistoryRow(e,
                        onCopy = { ctx.copyText(e.text) },
                        onDelete = { HistoryStore.delete(ctx, e.id) },
                        onAddWord = { addWordFor = e.text })
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_message)) },
            confirmButton = {
                TextButton(onClick = { HistoryStore.clear(ctx); confirmClear = false }) {
                    Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    addWordFor?.let { source ->
        TextInputDialog(
            title = stringResource(R.string.history_add_word_title),
            message = stringResource(R.string.history_add_word_message),
            fields = listOf(stringResource(R.string.setup_word_hint) to ""),
            onDismiss = { addWordFor = null },
            onConfirm = { values ->
                if (UserData.addWord(ctx, values[0])) {
                    RecognizerManager.onUserTermsChanged(ctx)
                    Toast.makeText(ctx, ctx.getString(R.string.word_added, values[0].trim()), Toast.LENGTH_SHORT).show()
                    addWordFor = null
                } else {
                    Toast.makeText(ctx, R.string.setup_word_not_added, Toast.LENGTH_SHORT).show()
                }
            },
            supporting = source,
        )
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onCopy: () -> Unit, onDelete: () -> Unit, onAddWord: () -> Unit) {
    val ctx = LocalContext.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Text(e.text, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(relativeLabel(e), ctx.appLabel(e.app).takeIf { e.app.isNotBlank() },
                        stringResource(R.string.words_count, e.words)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onAddWord) { AppIcon(R.drawable.ic_book, stringResource(R.string.action_add_word)) }
                IconButton(onClick = onCopy) { AppIcon(R.drawable.ic_copy, stringResource(R.string.action_copy)) }
                IconButton(onClick = onDelete) { AppIcon(R.drawable.ic_delete, stringResource(R.string.action_delete)) }
            }
        }
    }
}

/**
 * A dialog with one or more text fields ([fields] = label to initial value). [onConfirm] gets the
 * trimmed values and closes the dialog itself (so it can refuse invalid input).
 */
@Composable
fun TextInputDialog(
    title: String,
    fields: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    message: String? = null,
    supporting: String? = null,
    multilineLast: Boolean = false,
) {
    val values = remember { androidx.compose.runtime.mutableStateListOf(*fields.map { it.second }.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
                if (supporting != null) Text("“$supporting”", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                fields.forEachIndexed { i, (label, _) ->
                    val multi = multilineLast && i == fields.lastIndex
                    OutlinedTextField(
                        value = values[i], onValueChange = { values[i] = it },
                        label = { Text(label) }, singleLine = !multi, minLines = if (multi) 3 else 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(values.map { it.trim() }) },
                enabled = values.all { it.isNotBlank() },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
