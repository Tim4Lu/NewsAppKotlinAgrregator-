package com.newsapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newsapp.data.model.NewsItem

@Composable
fun NewsCard(
    news: NewsItem,
    onRewrite: (String) -> Unit = {},
    onPublish: (String) -> Unit = {}
) {
    // Головний фікс: захист від null (?: "") рятує OutlinedTextField від крашу
    var textValue by remember { mutableStateOf(news.editedText ?: news.description ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = news.title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it }, // Тепер 'it' ідеально розпізнається
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Опис") }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { onRewrite(textValue) },
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) {
                    Text("Рерайт")
                }
                Button(
                    onClick = { onPublish(textValue) },
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) {
                    Text("В канал")
                }
            }
        }
    }
}
