path = "app/src/main/java/com/newsapp/data/RssParsers.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

code = code.replace("var timestamp = 0L", "var timestamp = System.currentTimeMillis()")

with open(path, "w", encoding="utf-8") as f:
    f.write(code)
print("✅ ESA парсер полагоджено!")
