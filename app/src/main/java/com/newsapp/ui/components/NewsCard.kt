package com.newsapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newsapp.data.model.NewsItem

@Composable
fun NewsCard(
    item: NewsItem,
    onPublish: (NewsItem, String) -> Unit,
    onRewrite: (NewsItem) -> Unit
) {
    var textState by remember(item.id) { mutableStateOf(item.editedText) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Джерело та заголовок
            Text(
                text = item.sourceName.uppercase(),
                fontSize = 12.sp,
                color = Color(0xFF8E8E93)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.title,
                fontSize = 18.sp,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Текстове поле для редагування перед публікацією
            OutlinedTextField(
                value = textState,
                onValueChange = { textState = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF0A84FF),
                    unfocusedBorderColor = Color(0xFF38383A),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.LightGray
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Кнопки дій: Рерайт та Опублікувати
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка ШІ-Рерайту
                Button(
                    onClick = { onRewrite(item) },
                    enabled = !item.isRewriting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158))
                ) {
                    if (item.isRewriting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("✨ AI Рерайт", color = Color.Black)
                    }
                }

                // Кнопка Опублікувати в Telegram
                Button(
                    onClick = { onPublish(item, textState) },
                    enabled = !item.isPublishing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
                ) {
                    if (item.isPublishing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("✈️ В Канал", color = Color.White)
                    }
                }
            }
        }
    }
}
