package com.newsapp.data.model

import java.util.UUID

data class NewsItem(
    val title: String,
    val description: String?,
    val link: String = "",
    val id: String = UUID.randomUUID().toString(),
    val sourceName: String = "",
    val imageUrl: String? = null,
    val editedText: String? = null,
    val isRewriting: Boolean = false,
    val isPublishing: Boolean = false
)
