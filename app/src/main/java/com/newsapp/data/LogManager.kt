package com.newsapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object LogManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // v466: DateTimeFormatter thread-safe (SimpleDateFormat НЕ є). LocalTime без timezone-конфліктів.
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun log(tag: String, message: String) {
        val time = LocalTime.now().format(timeFormat)
        val newLog = "[$time] [$tag]: $message"
        // v466: atomic update - усуває плутанину порядку при паралельних логах
        _logs.update { current -> (listOf(newLog) + current).take(100) }
        android.util.Log.d(tag, message)
    }

    fun clear() {
        _logs.value = emptyList()
    }
}