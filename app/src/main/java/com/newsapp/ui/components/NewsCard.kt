package com.newsapp.ui.components

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.newsapp.model.NewsItem
import kotlinx.coroutines.launch

@Composable
fun NewsCard(
    item: NewsItem,
    onPublish: suspend (NewsItem) -> Unit,
    onUpdateText: (String, String) -> Unit,
    onToggleEdit: (String) -> Unit,
    onRewrite: (NewsItem) -> Unit
) {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: NewsCard")
    var localText by remember(item.id, item.description) { mutableStateOf(item.description) }
    var isPublishing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val isPublished = item.status == "Опубліковано"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column {
            AsyncImage(
                model = item.image,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(192.dp)
                    .background(Color(0xFF334155))
            )

            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.source.uppercase(),
                        color = Color(0xFF818CF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isPublished) Color(0x3310B981)
                                else Color(0xFF334155)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = item.status,
                            color = if (isPublished) Color(0xFF34D399) else Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = item.title,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (item.isEditing) {
                    OutlinedTextField(
                        value = localText,
                        onValueChange = { localText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp)
                            .padding(bottom = 12.dp),
                        textStyle = LocalTextStyle.current.copy(color = Color(0xFFE2E8F0)),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF475569),
                            unfocusedBorderColor = Color(0xFF475569)
                        )
                    )
                    Button(
                        onClick = {
                            onUpdateText(item.id, localText)
                            onToggleEdit(item.id)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Зберегти текст", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = item.description,
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "✏️ Редагувати",
                            color = Color(0xFF818CF8),
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { onToggleEdit(item.id) }
                        )
                        Text(
                            text = "🔄 Перекласти заново",
                            color = Color(0xFF38BDF8),
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { onRewrite(item) }
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(bottom = 16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = {
                            if (item.link.isNotEmpty()) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Оригінал", color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isPublishing = true
                                onPublish(item)
                                isPublishing = false
                            }
                        },
                        enabled = !isPublished && !isPublishing,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPublished) Color(0xFF475569) else Color(0xFF059669),
                            disabledContainerColor = Color(0xFF475569)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isPublishing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (isPublished) "✓ В каналі" else "🚀 В Telegram",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
