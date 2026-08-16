package com.newsapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(tag: String, message: String) {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: log")
        val time = timeFormat.format(Date())
        val newLog = "[$time] [$tag]: $message"
        _logs.value = listOf(newLog) + _logs.value.take(100) // Зберігаємо останні 100 логів
        android.util.Log.d(tag, message)
    }

    fun clear() {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: clear")
        _logs.value = emptyList()
    }
}
