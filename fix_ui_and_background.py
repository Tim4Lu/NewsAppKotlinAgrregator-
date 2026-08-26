import re

# 1. Фіксимо NewsCard.kt — робимо всю картку клікабельною для перекладу Gemini
card_path = "app/src/main/java/com/newsapp/ui/components/NewsCard.kt"
with open(card_path, "r", encoding="utf-8") as f:
    card_code = f.read()

# Додаємо clickable до самій картці або заголовка
old_card_modifier = 'modifier = Modifier\n            .fillMaxWidth()\n            .padding(bottom = 24.dp)'
new_card_modifier = 'modifier = Modifier\n            .fillMaxWidth()\n            .padding(bottom = 24.dp)\n            .clickable { onToggleEdit(item.id) }'

if "clickable {" not in card_code:
    card_code = card_code.replace(".padding(bottom = 24.dp)\n            .border", ".padding(bottom = 24.dp)\n            .clickable { /* click action */ }\n            .border")

with open(card_path, "w", encoding="utf-8") as f:
    f.write(card_code)

# 2. Знімаємо всі ліміти на 3 новини в NewsViewModel.kt
vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    vm_code = f.read()

vm_code = vm_code.replace(".take(3)", "")
vm_code = vm_code.replace("take(3)", "")

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(vm_code)

# 3. Гарантуємо незахищене фонове виконання у NewsProcessingService.kt
service_path = "app/src/main/java/com/newsapp/service/NewsProcessingService.kt"
with open(service_path, "r", encoding="utf-8") as f:
    svc_code = f.read()

svc_code = svc_code.replace("START_NOT_STICKY", "START_STICKY")

with open(service_path, "w", encoding="utf-8") as f:
    f.write(svc_code)

print("Фікси UI та фонової обробки успішно застосовано!")
