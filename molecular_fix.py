import os

def robust_replace(filepath, start_marker, end_marker, replacement):
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()
        
    start_idx = content.find(start_marker)
    if start_idx == -1: 
        print(f"❌ Не знайдено start_marker у {filepath}")
        return
        
    end_idx_raw = content.find(end_marker, start_idx)
    if end_idx_raw == -1:
        print(f"❌ Не знайдено end_marker у {filepath}")
        return
        
    end_idx = end_idx_raw + len(end_marker)
    new_content = content[:start_idx] + replacement + content[end_idx:]
    
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)
    print(f"✅ Успішно оновлено {filepath}")

# 1. Залізобетонно додаємо 3-денний фільтр
start_filter = "val freshNews = uniqueRawNews.filter { item ->"
end_filter = "!isTitleDuplicate && !isLinkDuplicate\n        }"
new_filter = """val maxAgeMillis = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)
        val freshNews = uniqueRawNews.filter { item ->
            val normTitle = item.title.trim().lowercase()
            val origTitle = item.originalTitle.trim().lowercase()
            val normLink = item.link.normalizeUrl()

            val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
            val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)
            val isRecent = item.timestamp > maxAgeMillis

            !isTitleDuplicate && !isLinkDuplicate && isRecent
        }"""

robust_replace("app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt", start_filter, end_filter, new_filter)
robust_replace("app/src/main/java/com/newsapp/service/NewsWorker.kt", start_filter, end_filter, new_filter)

# 2. Залізобетонно додаємо кулдауни на помилки Gemini (у т.ч. 503)
start_ai = "if (response.status.value == 200) {"
end_ai = "} catch (e: Exception) {"
new_ai = """if (response.status.value == 200) {
                JSONObject(response.bodyAsText()).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotEmpty() }
            } else {
                val respBody = try { response.bodyAsText() } catch (e: Exception) { "" }
                val errBody = respBody.lowercase()
                
                if (response.status.value == 401) {
                    LogManager.log("AI_ERR", "Ключ №$keyNum недійсний (401). Видаляємо з ротації.")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                } else if (response.status.value == 429) {
                    if (errBody.contains("per day") || errBody.contains("quota")) {
                        val resetTime = getNextQuotaResetTime()
                        LogManager.log("AI_ERR", "Ключ №$keyNum вичерпав денний ліміт. Блок до 10:00 ранку.")
                        keyCooldowns[apiKey] = resetTime
                    } else {
                        LogManager.log("AI_WARN", "Ключ №$keyNum перевантажений (RPM). Пауза 2 хв...")
                        keyCooldowns[apiKey] = System.currentTimeMillis() + (2 * 60 * 1000L)
                    }
                } else if (response.status.value == 503 || errBody.contains("unavailable") || errBody.contains("high demand")) {
                    LogManager.log("AI_WARN", "Ключ №$keyNum перевантажений (HTTP 503 High Demand). Пауза 2 хв...")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (2 * 60 * 1000L)
                } else {
                    LogManager.log("AI_ERR", "Gemini HTTP ${response.status.value}: $respBody")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + 30_000L
                }
                null
            }
        } catch (e: Exception) {"""

robust_replace("app/src/main/java/com/newsapp/data/api/AiRewriter.kt", start_ai, end_ai, new_ai)
