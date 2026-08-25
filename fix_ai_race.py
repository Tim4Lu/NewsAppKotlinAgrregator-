import re

file_path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(file_path, "r", encoding="utf-8") as f:
    code = f.read()

# 1. Переносимо вибір ключа ВСЕРЕДИНУ callGeminiApi (після очікування)
old_call = """private suspend fun callGeminiApi(prompt: String, apiKey: String, keyNum: Int, modelName: String): String? {
        enforceRateLimit()
        return try {"""
    
new_call = """private suspend fun callGeminiApi(prompt: String, modelName: String): String? {
        enforceRateLimit()
        val active = getActiveKey()
        if (active == null) {
            LogManager.log("AI_ERR", "Усі ключі на кулдауні! Зупинка черги.")
            return null
        }
        val apiKey = active.first
        val keyNum = active.second
        return try {"""

code = code.replace(old_call, new_call)

# 2. Оновлюємо виклик для повного перекладу
old_full = """while (translatedText == null && attempts < apiKeys.size) {
            val active = getActiveKey()
            if (active == null) { LogManager.log("AI_ERR", "Усі ключі на кулдауні!"); break }
            translatedText = callGeminiApi(prompt, active.first, active.second, "gemini-3.6-flash")
            if (translatedText == null) attempts++
        }"""
    
new_full = """while (translatedText == null && attempts < apiKeys.size) {
            if (getActiveKey() == null) { LogManager.log("AI_ERR", "Усі ключі на кулдауні!"); break }
            translatedText = callGeminiApi(prompt, "gemini-3.6-flash")
            if (translatedText == null) attempts++
        }"""
    
code = code.replace(old_full, new_full)

# 3. Оновлюємо виклик для фонової обробки стрічки
old_process = """while (translatedText == null && attempts < apiKeys.size) {
                        val active = getActiveKey()
                        if (active == null) { LogManager.log("AI_ERR", "Усі ключі на кулдауні! Зупинка черги."); break }
                        translatedText = callGeminiApi(prompt, active.first, active.second, "gemini-3.6-flash")
                        if (translatedText == null) attempts++
                    }"""

new_process = """while (translatedText == null && attempts < apiKeys.size) {
                        if (getActiveKey() == null) { LogManager.log("AI_ERR", "Усі ключі на кулдауні! Зупинка черги."); break }
                        translatedText = callGeminiApi(prompt, "gemini-3.6-flash")
                        if (translatedText == null) attempts++
                    }"""

code = code.replace(old_process, new_process)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(code)

print("Гонитву ключів успішно ліквідовано!")
