ai_path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(ai_path, "r", encoding="utf-8") as f:
    code = f.read()

# Патчимо перевірку статусу в callGeminiApi: якщо status != 200, повертаємо null і логуємо
old_logic = """            if (response.status.value == 200) {
                val json = JSONObject(responseText)
                val candidates = json.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val resultText = parts?.optJSONObject(0)?.optString("text")

                if (!resultText.isNullOrEmpty()) resultText else null
            } else {
                null
            }"""

new_logic = """            if (response.status.value == 200) {
                val json = JSONObject(responseText)
                val candidates = json.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val resultText = parts?.optJSONObject(0)?.optString("text")

                if (!resultText.isNullOrEmpty()) resultText else null
            } else {
                LogManager.log("AI_ERR", "Gemini HTTP ${response.status.value}: $responseText")
                null
            }"""

if old_logic in code:
    code = code.replace(old_logic, new_logic)
    with open(ai_path, "w", encoding="utf-8") as f:
        f.write(code)
    print("Логування помилок 401/429 успішно додано!")
else:
    print("Структура трохи відрізняється, перевіряємо далі...")
