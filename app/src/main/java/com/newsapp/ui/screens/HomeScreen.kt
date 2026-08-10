package com.newsapp.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newsapp.data.api.AiRewriter
import com.newsapp.data.api.NewsRepository
import com.newsapp.data.api.TelegramBotService
import com.newsapp.data.model.NewsItem
import com.newsapp.ui.components.NewsCard
import kotlinx.coroutines.launch

private const val TAG = "HomeScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newsList by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun loadNews() {
        scope.launch {
            isLoading = true
            Log.d(TAG, "[LOG] Користувач запустив оновлення стрічки новин...")
            newsList = NewsRepository.fetchAllNews()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadNews()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🚀 Агрегатор Новин", color = Color.White, fontSize = 20.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
                actions = {
                    IconButton(onClick = { loadNews() }) {
                        Text("🔄", fontSize = 18.sp)
                    }
                }
            )
        },
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF0A84FF)
                )
            } else if (newsList.isEmpty()) {
                Text(
                    text = "Немає доступних новин. Натисніть 🔄 для оновлення.",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(newsList, key = { it.id }) { news ->
                        NewsCard(
                            item = news,
                            onPublish = { item, currentText ->
                                scope.launch {
                                    Log.d(TAG, "[LOG] Натиснуто кнопку публікації для ID: ${item.id}")
                                    
                                    // Оновлюємо стан індикатора завантаження
                                    newsList = newsList.map { 
                                        if (it.id == item.id) it.copy(isPublishing = true) else it 
                                    }

                                    val success = TelegramBotService.sendNews(currentText, item.imageUrl)

                                    if (success) {
                                        Toast.makeText(context, "Успішно опубліковано в канал!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Помилка публікації в Telegram", Toast.LENGTH_SHORT).show()
                                    }

                                    newsList = newsList.map { 
                                        if (it.id == item.id) it.copy(isPublishing = false) else it 
                                    }
                                }
                            },
                            onRewrite = { item ->
                                scope.launch {
                                    Log.d(TAG, "[LOG] Натиснуто кнопку AI Рерайту для ID: ${item.id}")
                                    
                                    newsList = newsList.map { 
                                        if (it.id == item.id) it.copy(isRewriting = true) else it 
                                    }

                                    val rewritten = AiRewriter.rewriteNews(item.editedText)

                                    newsList = newsList.map { 
                                        if (it.id == item.id) it.copy(editedText = rewritten, isRewriting = false) else it 
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
