import re

# 1. Виправляємо NewsViewModel.kt
vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    code = f.read()

# Фікс синтаксису let
old_status = """status = let {
                                val s = obj.optString("status", "Готово")
                                if (s == "В черзі" || s == "Переклад...") "Не перекладено" else s
                            },"""
new_status = """status = obj.optString("status", "Готово").let { s ->
                                if (s == "В черзі" || s == "Переклад...") "Не перекладено" else s
                            },"""
code = code.replace(old_status, new_status)

# Фікс подвійного фільтра
code = code.replace(".filter { it.timestamp > thirtyDaysAgo }.filter { it.timestamp > thirtyDaysAgo }", 
                    ".filter { it.timestamp > thirtyDaysAgo }")

# Фікс логіки черги: об'єднуємо обробку нових та старих новин
old_load_logic = """if (freshNews.isNotEmpty()) {
                    val freshInitial = freshNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                    _newsList.value = (freshInitial + _newsList.value).sortedByDescending { it.timestamp }
                    saveNewsToDisk(_newsList.value)
                    _isLoading.value = false

                    processNewsWithScraperAndAi(freshNews)
                } else {
                    _isLoading.value = false
                    checkAndRetryUntranslatedNews()
                }"""

new_load_logic = """if (freshNews.isNotEmpty()) {
                    val freshInitial = freshNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                    _newsList.value = (freshInitial + _newsList.value).sortedByDescending { it.timestamp }
                    saveNewsToDisk(_newsList.value)
                }
                
                _isLoading.value = false
                checkAndRetryUntranslatedNews()"""
code = code.replace(old_load_logic, new_load_logic)

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(code)

# 2. Виправляємо AiRewriter.kt (захист від вічного зависання при помилці)
ai_path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(ai_path, "r", encoding="utf-8") as f:
    ai_code = f.read()
    
old_catch = """} catch (e: Exception) {
                    LogManager.log("AI_CRITICAL", "Збій: ${e.message}")
                } finally {"""
new_catch = """} catch (e: Exception) {
                    LogManager.log("AI_CRITICAL", "Збій: ${e.message}")
                    onItemProcessed(item.copy(status = "Не перекладено"))
                } finally {"""
ai_code = ai_code.replace(old_catch, new_catch)

with open(ai_path, "w", encoding="utf-8") as f:
    f.write(ai_code)

print("Логіку черги та синтаксис успішно виправлено!")
