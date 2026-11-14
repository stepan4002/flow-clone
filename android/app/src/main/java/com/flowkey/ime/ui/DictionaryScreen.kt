package com.flowkey.ime.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.flowkey.ime.R
import com.flowkey.ime.RecognizerManager
import com.flowkey.ime.UserData

private enum class DictTab { WORDS, CORRECTIONS, SNIPPETS }

/** Which entry the editor dialog is open for: index -1 = a new entry. */
private data class Editing(val tab: DictTab, val index: Int, val from: String, val to: String)

@Composable
fun DictionaryScreen(tick: Int) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var changes by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    val current = DictTab.entries[tab]

    // Re-read on resume and after every change (SharedPreferences: cheap, in memory).
    val words = remember(tick, changes) { UserData.getWords(ctx) }
    val corrections = remember(tick, changes) { UserData.getDictionary(ctx) }
    val snippets = remember(tick, changes) { UserData.getSnippets(ctx) }

    fun changed(termsChanged: Boolean) {
        changes++
        // Words and correction targets feed the recognizers: reload like the old screen did.
        if (termsChanged) RecognizerManager.onUserTermsChanged(ctx)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.dict_title), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.padding(top = 8.dp)) {
                listOf(R.string.dict_tab_words, R.string.dict_tab_corrections, R.string.dict_tab_snippets)
                    .forEachIndexed { i, res ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res), maxLines = 1) })
                    }
            }
            val (desc, rows) = when (current) {
                DictTab.WORDS -> stringResource(R.string.setup_words_desc) to words.map { it to null }
                DictTab.CORRECTIONS -> stringResource(R.string.setup_dictionary_desc) to corrections.map { it.from to it.to }
                DictTab.SNIPPETS -> stringResource(R.string.setup_snippets_desc) to snippets.map { it.from to it.to }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                if (rows.isEmpty()) item { EmptyState(stringResource(R.string.dict_empty)) }
                itemsIndexed(rows) { i, (from, to) ->
                    DictRow(from, to,
                        onEdit = { editing = Editing(current, i, from, to.orEmpty()) },
                        onDelete = {
                            when (current) {
                                DictTab.WORDS -> UserData.removeWord(ctx, i)
                                DictTab.CORRECTIONS -> UserData.removeDictionary(ctx, i)
                                DictTab.SNIPPETS -> UserData.removeSnippet(ctx, i)
                            }
                            changed(current != DictTab.SNIPPETS)
                        })
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Editing(current, -1, "", "") },
            icon = { AppIcon(R.drawable.ic_add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) },
            text = { Text(stringResource(R.string.setup_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { ed ->
        val title = stringResource(when (ed.tab) {
            DictTab.WORDS -> if (ed.index < 0) R.string.dict_add_word else R.string.dict_edit_word
            DictTab.CORRECTIONS -> if (ed.index < 0) R.string.dict_add_correction else R.string.dict_edit_correction
            DictTab.SNIPPETS -> if (ed.index < 0) R.string.dict_add_snippet else R.string.dict_edit_snippet
        })
        val fields = when (ed.tab) {
            DictTab.WORDS -> listOf(stringResource(R.string.setup_word_hint) to ed.from)
            DictTab.CORRECTIONS -> listOf(stringResource(R.string.setup_from_hint) to ed.from,
                stringResource(R.string.setup_to_hint) to ed.to)
            DictTab.SNIPPETS -> listOf(stringResource(R.string.setup_trigger_hint) to ed.from,
                stringResource(R.string.setup_expansion_hint) to ed.to)
        }
        TextInputDialog(title, fields, onDismiss = { editing = null }, multilineLast = ed.tab == DictTab.SNIPPETS,
            onConfirm = { v ->
                val ok = when (ed.tab) {
                    DictTab.WORDS -> if (ed.index < 0) UserData.addWord(ctx, v[0]) else UserData.updateWord(ctx, ed.index, v[0])
                    DictTab.CORRECTIONS -> {
                        if (ed.index < 0) UserData.addDictionary(ctx, v[0], v[1])
                        else UserData.updateDictionary(ctx, ed.index, v[0], v[1])
                        true
                    }
                    DictTab.SNIPPETS -> {
                        if (ed.index < 0) UserData.addSnippet(ctx, v[0], v[1])
                        else UserData.updateSnippet(ctx, ed.index, v[0], v[1])
                        true
                    }
                }
                if (ok) {
                    editing = null
                    changed(ed.tab != DictTab.SNIPPETS)
                } else {
                    Toast.makeText(ctx, R.string.setup_word_not_added, Toast.LENGTH_SHORT).show()
                }
            })
    }
}

@Composable
private fun DictRow(from: String, to: String?, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.action_edit), onClick = onEdit)) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(from, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (to != null) Text("→ $to", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDelete) { AppIcon(R.drawable.ic_delete, stringResource(R.string.action_delete)) }
        }
    }
}
