package com.newsapp.data.repository

import android.util.Log
import com.newsapp.data.model.NewsItem

class NewsRepository {
    suspend fun fetchNews(): List<NewsItem> {
        Log.d("NewsRepository", "[LOG] Виклик fetchNews (заглушка)")
        return emptyList()
    }
}
