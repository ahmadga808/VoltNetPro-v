#!/usr/bin/env bash
set -u

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR" || exit 1

SDK="$HOME/android-sdk"
LOG="$HOME/volt_release_v40_2.23_github_updates.log"
APK="app/build/outputs/apk/release/app-release.apk"
DOWNLOADS="$HOME/storage/shared/Download"
FINAL_APK="$DOWNLOADS/VoltNetPro-v40-2.23-GitHub-Auto-Updates.apk"
EXPECTED_CERT="c3c82eab29b300f2940e67ec2d98bd3607a3346c50bbdae3e99f9efb8e866fb6"

echo "VoltNet Pro 2.23 — GitHub auto-updates build"
echo "Project: $PROJECT_DIR"
echo "Log: $LOG"

if ! command -v gradle >/dev/null 2>&1; then
  echo "ERROR: Gradle غير موجود في Termux."
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java غير موجود في Termux."
  exit 1
fi
if [ ! -f "$SDK/platforms/android-34/android.jar" ]; then
  echo "ERROR: Android SDK Platform 34 غير موجود في $SDK"
  exit 1
fi
if [ ! -x "/data/data/com.termux/files/usr/bin/aapt2" ]; then
  echo "ERROR: أداة aapt2 غير موجودة. تحقق من تثبيتها في Termux."
  exit 1
fi

# Ensure the SDK AIDL entry points to Termux's real AIDL tool when the wrapper is absent.
AIDL_WRAPPER="$SDK/build-tools/34.0.0/aidl"
if [ ! -x "$AIDL_WRAPPER" ]; then
  if [ -x "/data/data/com.termux/files/usr/bin/aidl" ]; then
    printf '%s\n' '#!/data/data/com.termux/files/usr/bin/sh' 'exec /data/data/com.termux/files/usr/bin/aidl "$@"' > "$AIDL_WRAPPER"
    chmod 755 "$AIDL_WRAPPER"
  else
    echo "ERROR: أداة aidl غير موجودة. نفّذ: pkg install aidl"
    exit 1
  fi
fi

# Restore the original signing files if they exist in the user's known backup
# or previous project. Never generate a new key automatically.
for candidate in \
  "$HOME/VoltNetPro-private-backup" \
  "$HOME/storage/shared/Download/VoltNetPro-Android-v30-Release-Log-Tabs/SubscriptionManager"
do
  if [ ! -f "$PROJECT_DIR/keystore.properties" ] && [ -f "$candidate/keystore.properties" ]; then
    cp "$candidate/keystore.properties" "$PROJECT_DIR/keystore.properties"
    chmod 600 "$PROJECT_DIR/keystore.properties" 2>/dev/null || true
  fi
  if [ ! -f "$PROJECT_DIR/voltnet-release.jks" ] && [ -f "$candidate/voltnet-release.jks" ]; then
    cp "$candidate/voltnet-release.jks" "$PROJECT_DIR/voltnet-release.jks"
    chmod 600 "$PROJECT_DIR/voltnet-release.jks" 2>/dev/null || true
  fi
done

if [ ! -f "$PROJECT_DIR/keystore.properties" ]; then
  echo "ERROR: keystore.properties غير موجود. انسخ ملف الإعدادات الأصلي إلى مجلد المشروع."
  exit 1
fi
if [ ! -f "$PROJECT_DIR/voltnet-release.jks" ]; then
  echo "ERROR: voltnet-release.jks غير موجود. لا تنشئ مفتاحاً جديداً؛ انسخ المفتاح الأصلي."
  exit 1
fi

STORE_FILE="$(awk -F= '$1=="storeFile" {sub(/^[^=]*=/,""); print; exit}' "$PROJECT_DIR/keystore.properties")"
if [ -z "$STORE_FILE" ] || [ ! -f "$PROJECT_DIR/$STORE_FILE" ]; then
  echo "ERROR: storeFile في keystore.properties لا يشير إلى ملف موجود داخل مجلد المشروع."
  echo "المتوقع عادةً: storeFile=voltnet-release.jks"
  exit 1
fi

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock >/dev/null 2>&1 || true
fi
cleanup() {
  if command -v termux-wake-unlock >/dev/null 2>&1; then
    termux-wake-unlock >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "بدء بناء Release؛ اترك Termux مفتوحاً..."
gradle assembleRelease --stacktrace > "$LOG" 2>&1
BUILD_STATUS=$?

if [ "$BUILD_STATUS" -ne 0 ]; then
  echo "BUILD FAILED. آخر سجل البناء:"
  tail -60 "$LOG"
  exit "$BUILD_STATUS"
fi

if [ ! -s "$APK" ]; then
  echo "ERROR: Gradle أعلن النجاح لكن ملف APK غير موجود في المسار المتوقع."
  tail -40 "$LOG"
  exit 2
fi

APKSIGNER="$SDK/build-tools/34.0.0/apksigner"
if [ ! -x "$APKSIGNER" ]; then
  echo "ERROR: apksigner غير موجود؛ لم يتم نسخ APK."
  exit 3
fi
CERT_OUTPUT="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)" || {
  printf '%s\n' "$CERT_OUTPUT"
  echo "ERROR: فشل التحقق من توقيع APK."
  exit 3
}
printf '%s\n' "$CERT_OUTPUT"
CERT_SHA="$(printf '%s\n' "$CERT_OUTPUT" | awk '/Signer #1 certificate SHA-256 digest:/ {print tolower($NF); exit}' | tr -d ':')"
if [ "$CERT_SHA" != "$EXPECTED_CERT" ]; then
  echo "ERROR: بصمة شهادة APK لا تطابق مفتاح VoltNet Pro الأصلي المعروف؛ لن يتم نسخ الملف."
  echo "Expected: $EXPECTED_CERT"
  echo "Actual:   ${CERT_SHA:-unavailable}"
  exit 4
fi

mkdir -p "$DOWNLOADS"
cp "$APK" "$FINAL_APK" || exit 5
echo
echo "BUILD SUCCESSFUL — signature matches the original VoltNet Pro certificate."
ls -lh "$APK" "$FINAL_APK"
echo "سجل البناء: $LOG"
echo "اختبر التطبيق بعد التثبيت؛ نجاح البناء لا يثبت عمل اتصال SAS/MikroTik."
