package com.newsapp.ui.screens

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    newsList: List<NewsItem>,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    var selectedSource by remember { mutableStateOf("Усі") }
    val sources = listOf("Усі", "NASA", "Space.com", "Space Daily", "Universe Today")

    val filteredNews = if (selectedSource == "Усі") {
        newsList
    } else {
        newsList.filter { it.source.equals(selectedSource, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Панель вибору джерела (Filter Chips)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            items(sources) { source ->
                FilterChip(
                    selected = (selectedSource == source),
                    onClick = { selectedSource = source },
                    label = { Text(source) }
                )
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredNews, key = { it.id }) { news ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(4.dp)
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
                                maxLines = 3
                            )
                        }
                    }
                }
            }
        }
    }
}
