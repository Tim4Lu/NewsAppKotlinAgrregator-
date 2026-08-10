package com.newsapp.data.model

data class NewsItem(
    val title: String,
    val description: String?,
    val link: String = ""
)
