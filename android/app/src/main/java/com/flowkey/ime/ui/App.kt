package com.flowkey.ime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import com.flowkey.ime.R
import com.flowkey.ime.RecognizerManager

/** A scrolling page with the standard padding; moves up above the keyboard. */
@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

private enum class Tab(val label: Int, val icon: Int) {
    HOME(R.string.tab_home, R.drawable.ic_home),
    HISTORY(R.string.tab_history, R.drawable.ic_history),
    DICTIONARY(R.string.tab_dictionary, R.drawable.ic_book),
    STYLES(R.string.tab_styles, R.drawable.ic_palette),
    SETTINGS(R.string.tab_settings, R.drawable.ic_tune),
}

/** The FlowKey app: five tabs. [tick] changes on every resume so screens re-read settings. */
@Composable
fun FlowKeyApp(tick: Int) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state by RecognizerManager.state.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.ime_name), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                val (text, color) = modelStatus(state)
                StatusPill(text, color)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(painterResource(t.icon), null) },
                        label = { Text(stringResource(t.label), maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (Tab.entries[tab]) {
                Tab.HOME -> HomeScreen(tick, onOpenHistory = { tab = Tab.HISTORY.ordinal },
                    onOpenSettings = { tab = Tab.SETTINGS.ordinal })
                Tab.HISTORY -> HistoryScreen(tick)
                Tab.DICTIONARY -> DictionaryScreen(tick)
                Tab.STYLES -> StylesScreen(tick)
                Tab.SETTINGS -> SettingsScreen(tick)
            }
        }
    }
}
