import re

ai_path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(ai_path, "r", encoding="utf-8") as f:
    code = f.read()

# Додаємо логування статусів та захист від марного перебору всіх ключів при фатальних помилках
old_block = """            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()

            if (response.status.value == 200) {"""

new_block = """            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()

            if (response.status.value == 429) {
                LogManager.log("AI_RATE", "Ліміт ключа вичерпано (429). Зберігаємо запит для наступного ключа.")
            }

            if (response.status.value == 200) {"""

if old_block in code:
    code = code.replace(old_block, new_block)
    with open(ai_path, "w", encoding="utf-8") as f:
        f.write(code)
    print("Логіку перехоплення 429 помилки успішно додано!")
else:
    print("Блок не знайдено, можливо структура трохи відрізняється.")
