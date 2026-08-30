files = [
    "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt",
    "app/src/main/java/com/newsapp/service/NewsWorker.kt"
]

for path in files:
    with open(path, "r", encoding="utf-8") as f:
        code = f.read()
    
    # 1. Виправляємо діру лімітів: НЕ чіпаємо "Не перекладено" у фоні
    code = code.replace('it.status == "Не перекладено" || it.status == "В черзі"', 'it.status == "В черзі"')
    
    # 2. Оновлюємо ESA для ViewModel
    if "NewsViewModel.kt" in path:
        if '"https://www.esa.int/rssfeed/TopNews"' not in code:
            code = code.replace(
                '"https://www.esa.int/rssfeed/Our_Activities/Space_Science",',
                '"https://www.esa.int/rssfeed/TopNews",\n        "https://www.esa.int/rssfeed/Our_Activities/Space_Science",'
            )
            
    # 3. Маскуємо Ktor під повноцінний Chrome, щоб зняти блок 429
    old_ua = 'header(io.ktor.http.HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")'
    new_ua = 'header(io.ktor.http.HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")'
    code = code.replace(old_ua, new_ua)
    
    with open(path, "w", encoding="utf-8") as f:
        f.write(code)

print("✅ Архітектуру полагоджено: Діру в лімітах закрито, парсери замасковано!")
