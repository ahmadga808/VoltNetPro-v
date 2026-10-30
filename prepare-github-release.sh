#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
if [[ ! -f "$APK" ]]; then
  echo "لم أجد ملف APK: $APK" >&2
  exit 1
fi
if ! command -v sha256sum >/dev/null 2>&1; then
  echo "الأمر sha256sum غير متوفر. ثبّت coreutils في Termux." >&2
  exit 1
fi
sha256sum "$APK" > "$APK.sha256"
echo "تم إنشاء ملف البصمة: $APK.sha256"
echo "ارفع ملف APK وملف .sha256 معاً إلى GitHub Release."
