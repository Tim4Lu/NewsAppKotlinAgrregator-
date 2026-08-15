package com.newsapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newsapp.ui.components.LogViewerDialog
import com.newsapp.ui.components.NewsCard
import com.newsapp.ui.viewmodel.NewsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(viewModel: NewsViewModel) {
    val newsList by viewModel.newsList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showLogsDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFF0F172A),
        topBar = {
            TopAppBar(
                title = { Text("Наука та Космос", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E293B)),
                actions = {
                    IconButton(onClick = { viewModel.loadNews() }) {
                        Text("🔄", fontSize = 18.sp)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showLogsDialog = true },
                containerColor = Color(0xFF818CF8)
            ) {
                Text("📜", modifier = Modifier.padding(16.dp))
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                            onPublish = { viewModel.sendNews(it) },
                            onUpdateText = { id, text -> viewModel.updateNewsText(id, text) },
                            onToggleEdit = { id -> viewModel.toggleEdit(id) },
                            onRewrite = { newsItem -> viewModel.rewriteSingleNews(newsItem) }
                        )
                    }
                }
            }

            if (showLogsDialog) {
                LogViewerDialog(onDismiss = { showLogsDialog = false })
            }
        }
    }
}
