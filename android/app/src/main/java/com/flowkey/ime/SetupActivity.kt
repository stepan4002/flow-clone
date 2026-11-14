package com.flowkey.ime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.res.stringResource
import com.flowkey.ime.ui.FlowKeyApp
import com.flowkey.ime.ui.FlowKeyTheme

/**
 * The FlowKey app (launcher entry): Home with setup and insights, History, Dictionary, Styles
 * and Settings, in Compose. The class name stays SetupActivity so launcher shortcuts and the
 * keyboard's "open setup" key keep working.
 */
class SetupActivity : ComponentActivity() {

    /** Bumped on every resume: screens re-read settings the keyboard may have changed. */
    private val tick = mutableIntStateOf(0)

    /** Crash notices (title, message) waiting to be shown, one at a time. */
    private val notices = mutableStateListOf<Pair<Int, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FlowKeyTheme {
                FlowKeyApp(tick.intValue)
                notices.firstOrNull()?.let { (title, message) ->
                    AlertDialog(
                        onDismissRequest = { notices.removeAt(0) },
                        title = { Text(stringResource(title)) },
                        text = { Text(stringResource(message)) },
                        confirmButton = {
                            TextButton(onClick = { notices.removeAt(0) }) { Text(stringResource(android.R.string.ok)) }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RecognizerManager.initialize(this)
        if (Prefs.consumeStreamingCrashNotice(this)) {
            notices += R.string.streaming_crash_title to R.string.streaming_crash_message
        }
        if (Prefs.consumeWhisperSmallCrashNotice(this)) {
            notices += R.string.small_crash_title to R.string.small_crash_message
        }
        if (Prefs.consumeHotwordsCrashNotice(this)) {
            notices += R.string.hotwords_crash_title to R.string.hotwords_crash_message
        }
        tick.intValue++
    }

    /** The keyboard picker is a dialog (no onResume): re-check setup when it closes. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) tick.intValue++
    }
}
