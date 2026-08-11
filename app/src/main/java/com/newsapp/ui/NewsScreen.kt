package com.newsapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.newsapp.ui.components.NewsCard
import com.newsapp.ui.viewmodel.NewsViewModel

@Composable
fun NewsScreen(viewModel: NewsViewModel) {
    val newsList by viewModel.newsList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Новини Космосу та Науки",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(color = Color(0xFF818CF8))
                }

                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "Невідома помилка",
                            color = Color.Red,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadNews() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                        ) {
                            Text("Спробувати знову", color = Color.White)
                        }
                    }
                }

                newsList.isEmpty() -> {
                    Text(text = "Завантаження...", color = Color(0xFF94A3B8))
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(newsList) { item ->
                            NewsCard(
                                item = item,
                                onPublish = { viewModel.sendNews(it) },
                                onUpdateText = { id, text -> viewModel.updateNewsText(id, text) },
                                onToggleEdit = { id -> viewModel.toggleEdit(id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
