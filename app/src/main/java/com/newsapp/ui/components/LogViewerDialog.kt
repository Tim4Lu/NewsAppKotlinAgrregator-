package com.newsapp.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.newsapp.data.LogManager
import com.newsapp.data.api.AiRewriter

@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    val logs by LogManager.logs.collectAsState()
    val stats = remember { AiRewriter.getStats() }
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F172A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📜 Логи",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val logsText = "СТАТИСТИКА:\n$stats\n\nЛОГИ:\n${logs.joinToString("\n")}"
                            val clip = ClipData.newPlainText("Logs", logsText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Логи скопійовано!", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Копіювати", color = Color(0xFF38BDF8))
                        }
                        TextButton(onClick = { LogManager.clear() }) {
                            Text("Очистити", color = Color(0xFFEF4444))
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Закрити", color = Color(0xFF818CF8))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                // Блок зі статистикою ключів
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), shape = RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = stats,
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF020617), shape = RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Логів поки немає...",
                            color = Color.Gray,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(logs) { log ->
                                Text(
                                    text = log,
                                    color = if (log.contains("ERR") || log.contains("error")) Color(0xFFF87171) else Color(0xFF4ADE80),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
