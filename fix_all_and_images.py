import os, re

print("1. Фіксимо стиснення фото для Telegram (100% -> 75%)...")
tg_path = "app/src/main/java/com/newsapp/data/api/TelegramBotService.kt"
with open(tg_path, "r", encoding="utf-8") as f:
    tg_code = f.read()
tg_code = re.sub(r'compress\s*\(\s*Bitmap\.CompressFormat\.JPEG\s*,\s*100\s*,', 'compress(Bitmap.CompressFormat.JPEG, 75,', tg_code)
with open(tg_path, "w", encoding="utf-8") as f:
    f.write(tg_code)


print("2. Фіксимо ротацію ключів при 429 та логування в AiRewriter...")
ai_path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(ai_path, "r", encoding="utf-8") as f:
    ai_code = f.read()

old_429 = r"""            } else if (response.status.value == 429) {
                LogManager.log("AI_WARN", "Ключ №$keyNum ліміт (429). Чекаємо 10 сек...")
                delay(10_000)
                null"""
new_429 = r"""            } else if (response.status.value == 429) {
                LogManager.log("AI_WARN", "Ключ №$keyNum ліміт (429). Охолодження 60 сек, перемикаємось...")
                keyCooldowns[apiKey] = System.currentTimeMillis() + 60_000L
                null"""
if old_429 in ai_code:
    ai_code = ai_code.replace(old_429, new_429)

old_catch = r"""                } catch (e: Exception) {
                    LogManager.log("AI_CRITICAL", "Збій: ${e.message}")"""
new_catch = r"""                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    LogManager.log("AI_CRITICAL", "Збій: ${e.message}")"""
if old_catch in ai_code:
    ai_code = ai_code.replace(old_catch, new_catch)

with open(ai_path, "w", encoding="utf-8") as f:
    f.write(ai_code)


print("3. Покращуємо якість картинок (Phys.org та WordPress) в RssParsers...")
rss_path = "app/src/main/java/com/newsapp/data/RssParsers.kt"
with open(rss_path, "r", encoding="utf-8") as f:
    rss_code = f.read()

old_img_block = r"""            if (encMatch != null) {
                img = encMatch.groupValues.getOrNull(1)
            } else if (rawDesc.isNotEmpty()) {
                val imgMatch = Regex("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").find(block)
                if (imgMatch != null) img = imgMatch.groupValues.getOrNull(1)
            }"""

new_img_block = r"""            if (encMatch != null) {
                img = encMatch.groupValues.getOrNull(1)
            } else if (rawDesc.isNotEmpty()) {
                val imgMatch = Regex("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").find(block)
                if (imgMatch != null) img = imgMatch.groupValues.getOrNull(1)
            }
            
            // Відновлення оригінальної якості картинок (Phys.org + WP)
            if (img != null) {
                img = img.replace("/tmb/", "/")
                         .replace(Regex("-\\d{2,4}x\\d{2,4}(?=\\.[a-zA-Z]+)"), "")
            }"""
if old_img_block in rss_code:
    rss_code = rss_code.replace(old_img_block, new_img_block)
with open(rss_path, "w", encoding="utf-8") as f:
    f.write(rss_code)

print("Всі 4 фікси успішно застосовано!")
