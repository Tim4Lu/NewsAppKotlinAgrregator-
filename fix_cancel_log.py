path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

old_catch = """                } catch (e: Exception) {
                    LogManager.log("AI_CRITICAL", "Збій обробки новини: ${e.message}")
                }"""

new_catch = """                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    LogManager.log("AI_CRITICAL", "Збій обробки новини: ${e.message}")
                }"""

if old_catch in code:
    code = code.replace(old_catch, new_catch)
    with open(path, "w", encoding="utf-8") as f:
        f.write(code)
    print("Пропускання CancellationException успішно додано!")
else:
    print("Блок catch має інший вигляд.")
