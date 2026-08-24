import re

vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    code = f.read()

# Прибираємо дублювання рядка val thirtyDaysAgo
code = re.sub(r'(\s*val thirtyDaysAgo = System\.currentTimeMillis\(\) - \(30L \* 24 \* 60 \* 60 \* 1000\))+', '\n                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)', code)

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(code)

print("Дублювання зміної успішно прибрано!")
