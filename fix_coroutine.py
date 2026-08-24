import re

# 1. Фіксимо NewsViewModel.kt — переносимо sendNews у viewModelScope
vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    vm_code = f.read()

old_send = """suspend fun sendNews(newsItem: NewsItem) {
        if (newsItem.status == "Опубліковано" || newsItem.status == "Відправляється...") {
            LogManager.log("TELEGRAM", "Блокування подвійного кліку: новина вже ${newsItem.status}")
            return
        }

        if (newsItem.status == "В черзі" || newsItem.status == "Переклад...") {
            LogManager.log("TELEGRAM", "Блокування: новина ще обробляється ШІ, зачекайте.")
            return
        }

        _newsList.value = _newsList.value.map { 
            if (it.id == newsItem.id) it.copy(status = "Відправляється...") else it 
        }

        withContext(Dispatchers.IO) {"""

new_send = """fun sendNews(newsItem: NewsItem) {
        if (newsItem.status == "Опубліковано" || newsItem.status == "Відправляється...") {
            LogManager.log("TELEGRAM", "Блокування подвійного кліку: новина вже ${newsItem.status}")
            return
        }

        if (newsItem.status == "В черзі" || newsItem.status == "Переклад...") {
            LogManager.log("TELEGRAM", "Блокування: новина ще обробляється ШІ, зачекайте.")
            return
        }

        _newsList.value = _newsList.value.map { 
            if (it.id == newsItem.id) it.copy(status = "Відправляється...") else it 
        }

        viewModelScope.launch(Dispatchers.IO) {"""

if old_send in vm_code:
    vm_code = vm_code.replace(old_send, new_send)
    with open(vm_path, "w", encoding="utf-8") as f:
        f.write(vm_code)

# 2. Фіксимо NewsActionDialog.kt — прибираємо suspend та scope.launch на кнопку
dialog_path = "app/src/main/java/com/newsapp/ui/components/NewsActionDialog.kt"
with open(dialog_path, "r", encoding="utf-8") as f:
    dialog_code = f.read()

dialog_code = dialog_code.replace("onPublish: suspend (NewsItem) -> Unit", "onPublish: (NewsItem) -> Unit")
old_btn = """Button(
                            onClick = {
                                scope.launch {
                                    onPublish(item)
                                    onDismiss()
                                }
                            },"""
new_btn = """Button(
                            onClick = {
                                onPublish(item)
                                onDismiss()
                            },"""
dialog_code = dialog_code.replace(old_btn, new_btn)
with open(dialog_path, "w", encoding="utf-8") as f:
    f.write(dialog_code)

# 3. Фіксимо NewsCard.kt — прибираємо suspend з параметрів
card_path = "app/src/main/java/com/newsapp/ui/components/NewsCard.kt"
with open(card_path, "r", encoding="utf-8") as f:
    card_code = f.read()

card_code = card_code.replace("onPublish: suspend (NewsItem) -> Unit", "onPublish: (NewsItem) -> Unit")
with open(card_path, "w", encoding="utf-8") as f:
    f.write(card_code)

print("Скоуп корутини успішно перенесено у ViewModel!")
