package com.newsapp.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.newsapp.data.LogManager
import com.newsapp.data.api.AiRewriter
import com.newsapp.data.api.ScriptGenerator
import com.newsapp.data.api.VoiceMode
import com.newsapp.model.NewsItem
import kotlinx.coroutines.launch

private fun extractCleanVoiceText(fullScript: String): String {
    LogManager.log("TRACE", "Викликано функцію: extractCleanVoiceText")
    var text = fullScript
    val block1Index = text.indexOf("Блок 1")
    val block2Index = text.indexOf("Блок 2")
    
    if (block1Index != -1 && block2Index != -1 && block2Index > block1Index) {
        text = text.substring(block1Index, block2Index)
    }

    // Видаляємо часові маркери (наприклад: 0:00-0:08)
    text = text.replace(Regex("[\\(\\[]?\\d{1,2}:\\d{2}\\s*[-–—]\\s*\\d{1,2}:\\d{2}[\\)\\]]?"), "")
    
    // Видаляємо markdown
    text = text.replace("**", "").replace("*", "")

    // Видаляємо технічні рядки
    val lines = text.split("\n").filter { 
        !it.contains("Кількість слів", ignoreCase = true) && 
        !it.contains("Кількість символів", ignoreCase = true) &&
        !it.contains("Блок 1", ignoreCase = true) &&
        !it.contains("Текст сценарію", ignoreCase = true)
    }

    return lines.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}

@Composable
fun NewsActionDialog(
    item: NewsItem,
    onPublish: (NewsItem) -> Unit,
    onToggleEdit: (String) -> Unit,
    onRewrite: (NewsItem) -> Unit,
    onDismiss: () -> Unit
) {
    LogManager.log("TRACE", "Викликано функцію: NewsActionDialog")
    var activeTab by remember { mutableStateOf("MENU") }
    var selectedVoiceMode by remember { mutableStateOf(VoiceMode.OWN_VOICE) }
    
    var resultText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
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
                        text = if (activeTab == "MENU") "⚡ Дії з новиною" else "📄 Результат",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    TextButton(onClick = {
                        if (activeTab != "MENU") {
                            activeTab = "MENU"
                            resultText = null
                        } else {
                            onDismiss()
                        }
                    }) {
                        Text(if (activeTab != "MENU") "← Назад" else "Закрити", color = Color(0xFF818CF8))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (activeTab == "MENU") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                onPublish(item)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🚀 Опублікувати в Telegram", fontWeight = FontWeight.Bold)
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("🎬 Створити вірусний сценарій", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    VoiceMode.values().forEach { mode ->
                                        FilterChip(
                                            selected = (selectedVoiceMode == mode),
                                            onClick = { selectedVoiceMode = mode },
                                            label = { Text(if (mode == VoiceMode.OWN_VOICE) "🎙️ Власний (800)" else "🤖 ElevenLabs (600)", fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Color(0xFF818CF8),
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        activeTab = "SCRIPT"
                                        isLoading = true
                                        scope.launch {
                                            resultText = ScriptGenerator.generateScript(item, selectedVoiceMode)
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                                ) {
                                    Text("Згенерувати сценарій")
                                }
                            }
                        }

                        Button(
                            onClick = {
                                activeTab = "FULL_TRANSLATION"
                                isLoading = true
                                scope.launch {
                                    resultText = AiRewriter.translateFullArticle(item)
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("📖 Повний переклад статті", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                onRewrite(item)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("⟳ Перекласти зараз", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                onToggleEdit(item.id)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("✏️ Редагувати / Скоротити текст", color = Color(0xFFCBD5E1))
                        }

                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Article Link", item.link)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "🔗 Посилання скопійовано!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🔗 Скопіювати посилання", color = Color(0xFF38BDF8))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF020617), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color(0xFF818CF8), modifier = Modifier.align(Alignment.Center))
                        } else {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text(
                                    text = resultText ?: "Не вдалося згенерувати відповідь.",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    if (resultText != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Copied Text", resultText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Скопійовано все!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("📋 Все", color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val cleanText = extractCleanVoiceText(resultText!!)
                                    val clip = ClipData.newPlainText("ElevenLabs Text", cleanText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Чистий текст скопійовано!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("🤖 ElevenLabs", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
