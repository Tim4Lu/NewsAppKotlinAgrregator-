#!/bin/bash

OUTPUT_FILE="/sdcard/Нова папка/full_code_export_199.txt"
echo "Збір повного коду проєкту (v199) в $OUTPUT_FILE..." > "$OUTPUT_FILE"

find . -type f \( -name "*.kt" -o -name "*.xml" -o -name "*.gradle.kts" -o -name "*.properties" \) \
  ! -path "*/build/*" \
  ! -path "*/.git/*" \
  ! -path "*/.gradle/*" \
  ! -path "*/.idea/*" | while read -r file; do
    echo -e "\n=== FILE: $file ===" >> "$OUTPUT_FILE"
    cat "$file" >> "$OUTPUT_FILE"
done

echo "Готово! Файл збережено в: $OUTPUT_FILE"
