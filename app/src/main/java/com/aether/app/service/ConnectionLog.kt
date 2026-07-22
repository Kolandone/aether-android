package com.aether.app.service

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory connection log (max 200 entries) that the UI can poll.
 */
object ConnectionLog {
    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun record(message: String) {
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast("${fmt.format(Date())}  $message")
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
