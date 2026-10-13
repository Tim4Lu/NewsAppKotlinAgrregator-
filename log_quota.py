import os, tempfile

path = 'app/src/main/java/com/newsapp/data/api/AiRewriter.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

old = '''                } else if (response.status.value == 429) {
                    val usageCount = if (isInitialized) prefs.getInt("key_count_$keyIndex", 0) else 0'''

new = '''                } else if (response.status.value == 429) {
                    LogManager.log("AI_QUOTA", "ПОВНЕ 429 (${respBody.length}): ${respBody}")
                    val usageCount = if (isInitialized) prefs.getInt("key_count_$keyIndex", 0) else 0'''

if old in content:
    content = content.replace(old, new, 1)
else:
    print("NOT FOUND")
    raise SystemExit(1)

fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path))
with os.fdopen(fd, 'w', encoding='utf-8') as f:
    f.write(content)
os.replace(tmp, path)
print("OK")
