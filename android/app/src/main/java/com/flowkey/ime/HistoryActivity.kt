package com.flowkey.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.flowkey.ime.databinding.ActivityHistoryBinding

/** Read-only list of the last dictated transcripts; tap an entry to copy it. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    HistoryStore.clear(this)
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val container = binding.listHistory
        container.removeAllViews()
        val entries = HistoryStore.getAll(this)
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                alpha = 0.6f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (entry in entries) {
            container.addView(TextView(this).apply {
                text = entry.text
                textSize = 15f
                setPadding(0, dp(10), 0, dp(2))
                setOnClickListener { copyToClipboard(entry.text) }
            })
            container.addView(TextView(this).apply {
                text = DateUtils.getRelativeTimeSpanString(
                    entry.timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
                textSize = 12f
                alpha = 0.55f
                setPadding(0, 0, 0, dp(8))
            })
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dictation", text))
        Toast.makeText(this, R.string.history_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
