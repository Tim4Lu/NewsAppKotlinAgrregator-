import re

# 1. Знімаємо ліміт у NewsViewModel
vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    vm_code = f.read()

vm_code = vm_code.replace("}.take(3)", "}")

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(vm_code)

# 2. Знімаємо ліміт в AiRewriter
ai_path = "app/src/main/java/com/newsapp/data/api/AiRewriter.kt"
with open(ai_path, "r", encoding="utf-8") as f:
    ai_code = f.read()

ai_code = ai_code.replace("&& newsToProcess.size < 3", "")

with open(ai_path, "w", encoding="utf-8") as f:
    f.write(ai_code)

print("Штучний ліміт черги знято! Тепер ШІ оброблятиме всі новини до кінця.")
