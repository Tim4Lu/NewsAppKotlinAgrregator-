package com.newsapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.newsapp.data.LogManager
import com.newsapp.model.NewsItem

@Composable
fun NewsCard(
    item: NewsItem,
    onPublish: (NewsItem) -> Unit,
    onUpdateText: (String, String) -> Unit,
    onToggleEdit: (String) -> Unit,
    onRewrite: (NewsItem) -> Unit
) {
    LogManager.log("TRACE", "Викликано функцію: NewsCard")
    var showActionDialog by remember { mutableStateOf(false) }
    var localText by remember(item.id, item.description) { mutableStateOf(item.description) }
    val isPublished = item.status == "Опубліковано"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
            .clickable { showActionDialog = true },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column {
            if (item.image.isNotEmpty()) {
                AsyncImage(
                    model = item.image,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFF334155))
                )
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.source.uppercase(),
                        color = Color(0xFF818CF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isPublished) Color(0x3310B981) else Color(0xFF334155))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = item.status,
                            color = if (isPublished) Color(0xFF34D399) else Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Заголовок тепер виглядає так само, як у Telegram
                Text(
                    text = "🚀 ${item.title} 🚀",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (item.isEditing) {
                    OutlinedTextField(
                        value = localText,
                        onValueChange = { localText = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        textStyle = LocalTextStyle.current.copy(color = Color(0xFFE2E8F0))
                    )
                    Button(
                        onClick = {
                            onUpdateText(item.id, localText)
                            onToggleEdit(item.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                    ) {
                        Text("Зберегти змінений текст")
                    }
                } else {
                    // Текст новини тепер показується повністю (без maxLines) та світлішим кольором
                    Text(
                        text = item.description,
                        color = Color(0xFFE2E8F0),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    if (showActionDialog) {
        NewsActionDialog(
            item = item,
            onPublish = onPublish,
            onToggleEdit = onToggleEdit,
            onRewrite = onRewrite,
            onDismiss = { showActionDialog = false }
        )
    }
}
