package com.newsapp.data

import android.util.Log

class AiRewriter {

    companion object {
        private const val TAG = "AiRewriterTag"
    }

    suspend fun rewrite(text: String, targetLanguage: String): String? {
        return try {
            Log.d(TAG, "AI_REWRITE: Обробка тексту українською: $text")
            // Тут міститься виклик до вашого AI API
            text
        } catch (e: Exception) {
            Log.e(TAG, "AI_REWRITE_ERROR: Помилка рерайту", e)
            null
        }
    }
}
