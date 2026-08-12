package com.newsapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newsapp.data.model.NewsItem
import com.newsapp.ui.viewmodel.NewsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(
    viewModel: NewsViewModel
) {
    val newsList by viewModel.newsList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedSource by remember { mutableStateOf("Усі") }
    var editingItem by remember { mutableStateOf<NewsItem?>(null) }
    val sources = listOf("Усі", "NASA", "Space.com", "Space Daily", "Universe Today")

    val filteredNews = if (selectedSource == "Усі") {
        newsList
    } else {
        newsList.filter { it.source.equals(selectedSource, ignoreCase = true) }
    }

    LaunchedEffect(Unit) {
        if (newsList.isEmpty()) {
            viewModel.fetchNews()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NewsApp") },
                actions = {
                    Button(onClick = { viewModel.fetchNews() }) {
                        Text("Оновити")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp)
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                items(sources) { source ->
                    FilterChip(
                        selected = (selectedSource == source),
                        onClick = { selectedSource = source },
                        label = { Text(source) }
                    )
                }
            }

            if (isLoading && newsList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredNews, key = { it.id }) { news ->
                        NewsCard(
                            news = news,
                            onEdit = { editingItem = news },
                            onPublish = {
                                viewModel.publishToTelegram(news) { _ -> }
                            }
                        )
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        EditNewsDialog(
            item = item,
            onDismiss = { editingItem = null },
            onSave = { updatedItem ->
                viewModel.updateNewsItem(updatedItem)
                editingItem = null
            }
        )
    }
}

@Composable
fun NewsCard(
    news: NewsItem,
    onEdit: () -> Unit,
    onPublish: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "[${news.source}] ${news.title}",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = news.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Статус: ${news.status}",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit) {
                        Text("Редагувати")
                    }
                    Button(onClick = onPublish) {
                        Text("У Telegram")
                    }
                }
            }
        }
    }
}

@Composable
fun EditNewsDialog(
    item: NewsItem,
    onDismiss: () -> Unit,
    onSave: (NewsItem) -> Unit
) {
    var title by remember { mutableStateOf(item.title) }
    var caption by remember {
        mutableStateOf(item.telegramCaption.ifEmpty { "${item.title}\n\n${item.description}" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редагувати допис") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Заголовок") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("Текст для Telegram") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        item.copy(
                            title = title,
                            telegramCaption = caption
                        )
                    )
                }
            ) {
                Text("Зберегти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Скасувати")
            }
        }
    )
}
