files = [
    "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt",
    "app/src/main/java/com/newsapp/service/NewsWorker.kt"
]

for path in files:
    with open(path, "r", encoding="utf-8") as f:
        code = f.read()
    
    # Замінюємо 1970 рік на поточний час у fallback парсері
    code = code.replace("var ts = 0L", "var ts = System.currentTimeMillis()")
    
    with open(path, "w", encoding="utf-8") as f:
        f.write(code)
print("✅ Резервні парсери полагоджено!")
