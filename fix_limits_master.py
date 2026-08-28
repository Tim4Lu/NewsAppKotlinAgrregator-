import os, re

print("1. Оновлюємо AiRewriter: Глобальне блокування та EOF-паузи...")
ai_path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(ai_path, "r", encoding="utf-8") as f:
    ai_code = f.read()

block_methods = """
    fun isGloballyBlocked(): Boolean {
        val keys = apiKeys
        if (keys.isEmpty()) return false
        val nextTime = keys.map { keyCooldowns[it] ?: 0L }.minOrNull() ?: 0L
        return System.currentTimeMillis() < nextTime
    }

    fun getBlockTimeFormatted(): String {
        val keys = apiKeys
        if (keys.isEmpty()) return ""
        val nextTime = keys.map { keyCooldowns[it] ?: 0L }.minOrNull() ?: 0L
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(nextTime))
    }

    suspend fun callGeminiApi"""

if "isGloballyBlocked" not in ai_code:
    ai_code = ai_code.replace("    private suspend fun callGeminiApi", block_methods)

old_catch = """} catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("EOF")) LogManager.log("AI_WARN", "Мережа: обрив з'єднання (EOF). Повтор...")
                else LogManager.log("AI_ERR", "Мережевий збій Gemini: $msg")
                null 
            }"""
new_catch = """} catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("EOF")) LogManager.log("AI_WARN", "Мережа: обрив з'єднання (EOF). Повтор...")
                else LogManager.log("AI_ERR", "Мережевий збій Gemini: $msg")
                keyCooldowns[apiKey] = System.currentTimeMillis() + 30_000L // Пауза 30с від спаму
                null 
            }"""
ai_code = ai_code.replace(old_catch, new_catch)

with open(ai_path, "w", encoding="utf-8") as f:
    f.write(ai_code)


print("2. Рефакторинг ScriptGenerator: Підкоряємо його загальним лімітам...")
script_path = "app/src/main/java/com/newsapp/data/api/ScriptGenerator.kt"
with open(script_path, "r", encoding="utf-8") as f:
    script_code = f.read()

script_code = re.sub(
    r'var result: String\? = null\s*var attempts = 0[\s\S]*',
    """var result: String? = null
        var attempts = 0

        while (result == null && attempts < 5) {
            if (AiRewriter.isGloballyBlocked()) {
                com.newsapp.data.LogManager.log("AI_ERR", "Ліміти вичерпано! Дочекайтесь ${AiRewriter.getBlockTimeFormatted()}")
                break
            }
            result = AiRewriter.callGeminiApi(prompt, "gemini-3.6-flash")
            if (result == null) {
                attempts++
                kotlinx.coroutines.delay(2000)
            }
        }
        return result
    }
}""",
    script_code
)
with open(script_path, "w", encoding="utf-8") as f:
    f.write(script_code)


print("3. Навчаємо NewsWorker зупинятися до 10:00...")
worker_path = "app/src/main/java/com/newsapp/service/NewsWorker.kt"
with open(worker_path, "r", encoding="utf-8") as f:
    worker_code = f.read()

old_worker_ai = """            saveToCache(enrichedNews)

            AiRewriter.processAllNewsWithAi(enrichedNews, appContext) { item ->"""
new_worker_ai = """            saveToCache(enrichedNews)

            if (AiRewriter.isGloballyBlocked()) {
                LogManager.log("WORKER", "ШІ заблоковано до ${AiRewriter.getBlockTimeFormatted()}. Новини додано в чергу.")
            } else {
                AiRewriter.processAllNewsWithAi(enrichedNews, appContext) { item ->"""
worker_code = worker_code.replace(old_worker_ai, new_worker_ai).replace("showNewsNotification(item)\n            }", "showNewsNotification(item)\n                }\n            }")
with open(worker_path, "w", encoding="utf-8") as f:
    f.write(worker_code)


print("4. Навчаємо NewsViewModel зупинятися до 10:00...")
vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    vm_code = f.read()

old_vm = """        if (untranslated.isNotEmpty()) {
            LogManager.log("AI_AUTO_RETRY", "Автоперевірка: знайдено ${untranslated.size} неперекладених новин. Запуск ШІ...")
            viewModelScope.launch(Dispatchers.IO) {"""
new_vm = """        if (untranslated.isNotEmpty()) {
            if (AiRewriter.isGloballyBlocked()) {
                LogManager.log("AI_AUTO", "ШІ чекає до ${AiRewriter.getBlockTimeFormatted()}.")
                return
            }
            LogManager.log("AI_AUTO_RETRY", "Автоперевірка: знайдено ${untranslated.size} неперекладених новин. Запуск ШІ...")
            viewModelScope.launch(Dispatchers.IO) {"""
vm_code = vm_code.replace(old_vm, new_vm)
with open(vm_path, "w", encoding="utf-8") as f:
    f.write(vm_code)

print("Успіх! Усі дірки залатано.")
