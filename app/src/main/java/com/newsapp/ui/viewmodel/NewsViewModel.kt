package com.newsapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.LogManager
import com.newsapp.data.UpdateManager
import com.newsapp.data.api.AiRewriter
import com.newsapp.data.api.NewsRepository
import com.newsapp.data.api.TelegramBotService
import com.newsapp.data.model.NewsItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NewsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NewsRepository()
    private val aiRewriter = AiRewriter()
    private val telegramBotService = TelegramBotService()
    private val updateManager = UpdateManager(application.applicationContext)

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // Автоматична перевірка оновлень при запуску ViewModel
        checkForAppUpdates()
    }

    private fun checkForAppUpdates() {
        viewModelScope.launch {
            try {
                val updateInfo = updateManager.checkForUpdate(currentBuildNumber = 1)
                if (updateInfo != null) {
                    LogManager.log("UPDATE", "Знайдено оновлення: ${updateInfo.tagName}. Початок завантаження...")
                    updateManager.downloadAndInstallApk(updateInfo.downloadUrl)
                }
            } catch (e: Exception) {
                LogManager.log("UPDATE_ERR", "Помилка автооновлення: ${e.message}")
            }
        }
    }

    fun fetchNews() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val news = repository.getNews()
                _newsList.value = news
                processNewsWithAi(news)
            } catch (e: Exception) {
                LogManager.log("VM_ERR", "Помилка завантаження новин: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun processNewsWithAi(rawNews: List<NewsItem>) {
        viewModelScope.launch {
            aiRewriter.processAllNewsWithAi(rawNews) { processedItem ->
                _newsList.value = _newsList.value.map { item ->
                    if (item.id == processedItem.id) processedItem else item
                }
            }
        }
    }

    fun publishToTelegram(item: NewsItem, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val caption = item.telegramCaption.ifEmpty { "${item.title}\n\n${item.description}" }
            val success = telegramBotService.sendToTelegram(caption, item.imageUrl)
            
            if (success) {
                _newsList.value = _newsList.value.map {
                    if (it.id == item.id) it.copy(status = "Опубліковано") else it
                }
            }
            onResult(success)
        }
    }

    fun updateNewsItem(updatedItem: NewsItem) {
        _newsList.value = _newsList.value.map {
            if (it.id == updatedItem.id) updatedItem else it
        }
    }
}
