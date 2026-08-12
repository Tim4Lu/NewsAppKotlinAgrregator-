package com.newsapp.data.model

data class NewsItem(
    val id: String,
    val title: String,
    val description: String,
    val link: String,
    val pubDate: String,
    val imageUrl: String? = null,
    val source: String = "NASA"
)
