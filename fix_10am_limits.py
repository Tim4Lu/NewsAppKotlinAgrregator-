import os, re

path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# 1. Додаємо функцію вирахування наступної 10-ї ранку за Києвом
if "getNextQuotaResetTime" not in code:
    func_code = """
    private fun getNextQuotaResetTime(): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Kiev"))
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) >= 10) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 10)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private suspend fun callGeminiApi"""
    code = code.replace("    private suspend fun callGeminiApi", func_code)

# 2. Замінюємо 24-годинний блок на розумний блок до 10:00
code = re.sub(
    r'if\s*\(\s*errBody\.contains\("per day"\)\s*\|\|\s*errBody\.contains\("quota"\)\s*\)\s*\{[\s\S]*?\}',
    """if (errBody.contains("per day") || errBody.contains("quota")) {
                val resetTime = getNextQuotaResetTime()
                LogManager.log("AI_ERR", "Ключ №$keyNum вичерпав денний ліміт. Блок до 10:00 ранку.")
                keyCooldowns[apiKey] = resetTime
            }""",
    code
)

# 3. Робимо жорстку зупинку черги замість спаму помилок
old_loop = "newsToProcess.forEach { item ->"
new_loop = """var isQueueStopped = false
        for (item in newsToProcess) {
            if (isQueueStopped) {
                processingNewsIds.remove(item.id)
                continue
            }"""
code = code.replace(old_loop, new_loop)

old_break = 'if (getActiveKey() == null) { LogManager.log("AI_ERR", "Усі ключі на кулдауні! Зупинка черги."); break }'
new_break = 'if (getActiveKey() == null) { LogManager.log("AI_ERR", "Ключі вичерпано! Зупиняємо всю чергу."); isQueueStopped = true; break }'
code = code.replace(old_break, new_break)

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("Розумні ліміти (до 10:00) та запобіжник черги успішно застосовано!")
