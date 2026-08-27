path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# 1. Замінюємо блокування ключів на правильну диференціацію 401 та 429
old_block = """            } else if (response.status.value == 429 || response.status.value == 401) {
                markKeyOnCooldown(apiKey, keyNum)
                null
            }"""

new_block = """            } else if (response.status.value == 401) {
                LogManager.log("AI_ERR", "Ключ №$keyNum недійсний (401). Видаляємо з ротації.")
                keyCooldowns[apiKey] = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                null
            } else if (response.status.value == 429) {
                LogManager.log("AI_WARN", "Ключ №$keyNum ліміт (429). Чекаємо 10 сек...")
                delay(10_000)
                null
            }"""

if old_block in code:
    code = code.replace(old_block, new_block)
    with open(path, "w", encoding="utf-8") as f:
        f.write(code)
    print("Логіку ключів 401/429 успішно відкориговано!")
else:
    print("Блок відповідей має інший вигляд, застосовуємо пряму заміну status.value == 401")
    code = code.replace("response.status.value == 429 || response.status.value == 401", "response.status.value == 401")
    with open(path, "w", encoding="utf-8") as f:
        f.write(code)

