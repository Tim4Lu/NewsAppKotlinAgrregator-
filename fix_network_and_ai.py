import os, re

# 1. Шукаємо файл клієнта мережі або виклику Gemini (наприклад, AiRewriter.kt або NetworkModule)
for root, dirs, files in os.walk("app/src/main/java"):
    for file in files:
        if file.endswith(".kt"):
            path = os.path.join(root, file)
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
            
            # Шукаємо де налаштовується HttpClient або Ktor та додаємо таймаути
            if "HttpClient" in content and "HttpTimeout" not in content:
                # Можна додати імпорт та timeout block
                pass

print("Перевіряємо конфігурацію таймаутів...")
