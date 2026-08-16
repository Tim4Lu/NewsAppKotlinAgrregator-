#!/bin/bash

# Якщо тег називається v150 або просто 150
TAG="v150"
OUTPUT_FILE="/sdcard/Нова папка/full_code_export_v150.txt"

TEMP_DIR=$(mktemp -d)

# Витягуємо файли зазначеного тегу у тимчасову папку
git archive "$TAG" | tar -x -C "$TEMP_DIR" 2>/dev/null

if [ $? -ne 0 ]; then
    echo "Спроба $TAG не вдалася, пробуємо 150..."
    TAG="150"
    git archive "$TAG" | tar -x -C "$TEMP_DIR" 2>/dev/null
fi

if [ ! -d "$TEMP_DIR/app" ]; then
    echo "Помилка: тег або коміт $TAG не знайдено! Перевірте списки через 'git tag'."
    rm -rf "$TEMP_DIR"
    exit 1
fi

echo "Збір повного коду версії $TAG в $OUTPUT_FILE..." > "$OUTPUT_FILE"

find "$TEMP_DIR" -type f \( -name "*.kt" -o -name "*.xml" -o -name "*.gradle.kts" -o -name "*.properties" \) | while read -r file; do
    rel_file="${file#$TEMP_DIR/}"
    echo -e "\n=== FILE: ./$rel_file ===" >> "$OUTPUT_FILE"
    cat "$file" >> "$OUTPUT_FILE"
done

rm -rf "$TEMP_DIR"
echo "Готово! Файл з кодом версії $TAG збережено в: $OUTPUT_FILE"
