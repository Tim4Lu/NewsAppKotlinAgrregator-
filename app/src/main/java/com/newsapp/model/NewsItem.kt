package com.newsapp.model
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

import java.util.UUID

data class NewsItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val link: String,
    val description: String,
    val source: String = "Новина",
    val image: String = "",
    val status: String = "В черзі",
    val isEditing: Boolean = false,
    val telegramCaption: String = ""
)
