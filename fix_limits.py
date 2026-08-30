import os

path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

old_429 = """            } else if (response.status.value == 429) {
                LogManager.log("AI_WARN", "Ключ №$keyNum ліміт (429). Охолодження 60 сек, перемикаємось...")
                keyCooldowns[apiKey] = System.currentTimeMillis() + 60_000L
                null"""
                
new_429 = """            } else if (response.status.value == 429) {
                val errBody = try { response.bodyAsText().lowercase() } catch (e: Exception) { "" }
                if (errBody.contains("per day") || errBody.contains("quota")) {
                    LogManager.log("AI_ERR", "Ключ №$keyNum вичерпав ДЕННИЙ ліміт. Блок на 24 год.")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                } else {
                    LogManager.log("AI_WARN", "Ключ №$keyNum перевантажений (RPM). Пауза 5 хв...")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (5 * 60 * 1000L)
                }
                null"""
code = code.replace(old_429, new_429)

old_catch = '} catch (e: Exception) { LogManager.log("AI_ERR", "Мережевий збій Gemini: ${e.message}"); null }'
new_catch = """} catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("EOF")) LogManager.log("AI_WARN", "Мережа: обрив з'єднання (EOF). Повтор...")
                else LogManager.log("AI_ERR", "Мережевий збій Gemini: $msg")
                null 
            }"""
code = code.replace(old_catch, new_catch)

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("Логіку денних лімітів та EOF успішно оновлено!")
