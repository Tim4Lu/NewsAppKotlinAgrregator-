package com.newsapp.data.repository

import com.newsapp.data.model.NewsItem

class NewsRepository {
    suspend fun fetchNews(): List<NewsItem> {
        // Заглушка, щоб код успішно скомпілювався. 
        // Пізніше тут буде виклик вашого RssParser
        return emptyList()
    }
}
