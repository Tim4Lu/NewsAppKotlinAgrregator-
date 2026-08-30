path = ".github/workflows/build.yml"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

if "workflow_dispatch:" not in content:
    # Замінюємо 'on: push' або додаємо workflow_dispatch під блок on:
    if "on:" in content:
        content = content.replace("on:", "on:\n  workflow_dispatch:", 1)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print("workflow_dispatch успішно додано!")
    else:
        print("Не вдалося знайти блок 'on:' у build.yml")
else:
    print("workflow_dispatch вже присутній")
