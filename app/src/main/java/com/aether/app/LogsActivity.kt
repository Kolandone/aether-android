package com.aether.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogsActivity : AppCompatActivity() {
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvLogs: TextView

    companion object {
        private val logBuffer = StringBuilder()
        private const val MAX_BUFFER = 50_000
        private var instance: LogsActivity? = null

        fun appendLog(line: String) {
            synchronized(logBuffer) {
                logBuffer.appendLine(line)
                if (logBuffer.length > MAX_BUFFER) {
                    logBuffer.delete(0, logBuffer.length - MAX_BUFFER)
                }
            }
            instance?.runOnUiThread {
                instance?.refreshLogs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        scrollLogs = findViewById(R.id.scrollLogs)
        tvLogs = findViewById(R.id.tvLogs)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Aether Logs", tvLogs.text))
            Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.btnClear).setOnClickListener {
            synchronized(logBuffer) { logBuffer.clear() }
            tvLogs.text = ""
        }

        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        instance = this
        refreshLogs()
    }

    override fun onPause() {
        instance = null
        super.onPause()
    }

    private fun refreshLogs() {
        synchronized(logBuffer) {
            tvLogs.text = logBuffer.toString()
        }
        scrollLogs.post { scrollLogs.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
