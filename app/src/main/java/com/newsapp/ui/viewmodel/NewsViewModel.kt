package com.newsapp.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.api.AiRewriter
import com.newsapp.data.api.TelegramBotService
import com.newsapp.data.model.NewsItem
import com.newsapp.data.repository.NewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NewsViewModel : ViewModel() {
    private val repository = NewsRepository()

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val botToken = "" 
    private val chatId = ""

    fun loadNews() {
        viewModelScope.launch {
            Log.d("NewsViewModel", "[LOG] Початок завантаження космічних новин...")
            _isLoading.value = true
            try {
                _newsList.value = repository.fetchNews()
                Log.d("NewsViewModel", "[LOG] Успішно завантажено новин: ${_newsList.value.size}")
            } catch (e: Exception) {
                Log.e("NewsViewModel", "[LOG] Помилка завантаження: ${e.message}")
            }
            _isLoading.value = false
        }
    }

    fun sendNews(news: NewsItem) {
        viewModelScope.launch {
            try {
                Log.d("NewsViewModel", "[LOG] Відправка новини: ${news.title}")
                val originalText = "${news.title}\n\n${news.description ?: ""}"
                val rewritten = AiRewriter.rewriteNews(originalText)
                
                if (botToken.isNotEmpty() && chatId.isNotEmpty()) {
                    TelegramBotService.sendMessage(botToken, chatId, rewritten)
                } else {
                    Log.e("NewsViewModel", "[LOG] Токени Telegram не вказані!")
                }
            } catch (e: Exception) {
                Log.e("NewsViewModel", "[LOG] Помилка відправки: ${e.message}")
            }
        }
    }
}
