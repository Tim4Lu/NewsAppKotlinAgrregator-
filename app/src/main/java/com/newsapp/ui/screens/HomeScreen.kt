package com.newsapp.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import com.newsapp.ui.components.NewsCard
import com.newsapp.ui.viewmodel.NewsViewModel

@Composable
fun HomeScreen(viewModel: NewsViewModel) {
    val newsList by viewModel.newsList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val showLimitError by viewModel.showLimitError.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        if (isLoading && newsList.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF818CF8)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(newsList, key = { it.id }) { item ->
                    NewsCard(
                        item = item,
                        onPublish = { news -> viewModel.sendNews(news) },
                        onUpdateText = { id, text -> viewModel.updateNewsText(id, text) },
                        onToggleEdit = { id -> viewModel.toggleEdit(id) }
                    )
                }
            }
        }

        if (showLimitError) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissLimitError() },
                title = { Text("Увага") },
                text = { Text("Вичерпано ліміт API запитів. Використовуються резервні джерела.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissLimitError() }) {
                        Text("ОК")
                    }
                }
            )
        }
    }
}
