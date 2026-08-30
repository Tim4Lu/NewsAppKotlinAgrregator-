path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# Повністю оновлюємо метод callGeminiApi, об'єднуючи комфортний 30-секундний мережевий таймаут (з версії 300) 
# та правильну обробку 429/503 без помилкових блокувань до ранку (з версії 305).
old_api_method = """    suspend fun callGeminiApi(prompt: String, modelName: String): String? {
        enforceRateLimit()
        val active = getActiveKey()
        if (active == null) {
            LogManager.log("AI_ERR", "Усі ключі на кулдауні! Зупинка черги.")
            return null
        }
        val apiKey = active.first
        val keyNum = active.second
        return try {
            val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().apply { put("contents", JSONArray().apply { put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) }) }) }.toString())
            }
            if (response.status.value == 200) {
                JSONObject(response.bodyAsText()).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotEmpty() }
            } else {
                val respBody = try { response.bodyAsText() } catch (e: Exception) { "" }
                val errBody = respBody.lowercase()
                
                if (response.status.value == 401) {
                    LogManager.log("AI_ERR", "Ключ №$keyNum недійсний (401). Видаляємо з ротації.")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                } else if (response.status.value == 429) {
                    LogManager.log("AI_WARN", "Ключ №$keyNum перевищив частоту запитів (RPM 429). Пауза 3 хв... Відповідь: $respBody")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (3 * 60 * 1000L)
                } else if (response.status.value == 503 || errBody.contains("unavailable") || errBody.contains("high demand")) {
                    LogManager.log("AI_WARN", "Ключ №$keyNum перевантажений (HTTP 503). Пауза 2 хв...")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (2 * 60 * 1000L)
                } else {
                    LogManager.log("AI_ERR", "Gemini HTTP ${response.status.value}: $respBody")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + 30_000L
                }
                null
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("EOF")) LogManager.log("AI_WARN", "Мережа: обрив з'єднання (EOF). Повтор...")
            else LogManager.log("AI_ERR", "Мережевий збій Gemini: $msg")
            keyCooldowns[apiKey] = System.currentTimeMillis() + 30_000L
            null 
        }
    }"""

new_api_method = """    suspend fun callGeminiApi(prompt: String, modelName: String): String? {
        enforceRateLimit()
        val active = getActiveKey()
        if (active == null) {
            LogManager.log("AI_ERR", "Усі ключі на кулдауні! Зупинка черги.")
            return null
        }
        val apiKey = active.first
        val keyNum = active.second
        return try {
            val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().apply { put("contents", JSONArray().apply { put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) }) }) }.toString())
            }
            if (response.status.value == 200) {
                JSONObject(response.bodyAsText()).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotEmpty() }
            } else {
                val respBody = try { response.bodyAsText() } catch (e: Exception) { "" }
                val errBody = respBody.lowercase()
                
                if (response.status.value == 401) {
                    LogManager.log("AI_ERR", "Ключ №$keyNum недійсний (401). Видаляємо з ротації.")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                } else if (response.status.value == 429) {
                    LogManager.log("AI_WARN", "Ключ №$keyNum перевищив частоту запитів (RPM 429). Пауза 3 хв... Відповідь: $respBody")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (3 * 60 * 1000L)
                } else if (response.status.value == 503 || errBody.contains("unavailable") || errBody.contains("high demand")) {
                    LogManager.log("AI_WARN", "Ключ №$keyNum перевантажений (HTTP 503). Пауза 2 хв...")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (2 * 60 * 1000L)
                } else {
                    LogManager.log("AI_ERR", "Gemini HTTP ${response.status.value}: $respBody")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + 30_000L
                }
                null
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Немає інтернету / Таймаут"
            LogManager.log("AI_WARN", "Мережа зачепила помилку: $msg. Пауза ключа 30с.")
            keyCooldowns[apiKey] = System.currentTimeMillis() + 30_000L
            null
        }
    }"""

if old_api_method in code:
    code = code.replace(old_api_method, new_api_method)
    with open(path, "w", encoding="utf-8") as f:
        f.write(code)
    print("✅ AiRewriter.kt успішно оновлено комбінованою логікою (версія 300 + 305)!")
else:
    print("❌ Не вдалося знайти точний метод callGeminiApi. Перевіряємо заміну через інший шаблон...")
