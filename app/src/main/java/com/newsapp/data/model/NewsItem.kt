package com.newsapp.data.model

import android.util.Log

/**
 * Модель даних для окремої новини.
 */
data class NewsItem(
    val id: String,
    val title: String,
    val description: String,
    val link: String,
    val sourceName: String,
    val imageUrl: String? = null,
    val publishedAt: String? = null,
    var editedText: String = "$title\n\n$description",
    var isPublishing: Boolean = false,
    var isRewriting: Boolean = false
) {
    fun logDetails(tag: String = "NewsItemLog") {
        Log.d(tag, "[LOG] NewsItem ID: $id | Джерело: $sourceName | Заголовок: ${title.take(30)}...")
    }
}
