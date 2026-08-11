package com.newsapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.newsapp.ui.components.LogViewerDialog
import com.newsapp.ui.components.NewsCard
import com.newsapp.ui.viewmodel.NewsViewModel

@Composable
fun HomeScreen(viewModel: NewsViewModel) {
    val newsList by viewModel.newsList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showLogsDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Верхня панель із кнопкою логів
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Новини Космосу",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )

                Button(
                    onClick = { showLogsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF312E81)),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("📜 Логи", color = Color(0xFFA5B4FC))
                }
            }

            if (isLoading && newsList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF818CF8))
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
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
        }

        if (showLogsDialog) {
            LogViewerDialog(onDismiss = { showLogsDialog = false })
        }
    }
}
