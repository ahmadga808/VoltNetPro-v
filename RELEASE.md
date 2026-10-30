# إصدار نسخة موقّعة (Release)

## 1) أنشئ مفتاح التوقيع (مرة واحدة فقط) واحفظه بأمان
```
keytool -genkeypair -v -keystore release.jks -alias subsmgr -keyalg RSA -keysize 2048 -validity 10000
```
ضع الملف release.jks في مجلد المشروع الرئيسي (بجانب settings.gradle.kts).
**احتفظ بنسخة منه وبكلمتي المرور في مكانين آمنين.** إن ضاع لن تستطيع تحديث التطبيق لمن ثبّته.

## 2) أنشئ keystore.properties
انسخ keystore.properties.example إلى keystore.properties واملأ كلمتي المرور. لا تشاركه ولا ترفعه (مستثنى في .gitignore).

## 3) ابنِ الـAPK
من Android Studio: Build > Generate Signed App Bundle / APK > APK > release.
أو من الطرفية: ./gradlew assembleRelease  ثم app/build/outputs/apk/release/app-release.apk

## 4) في كل تحديث
ارفع versionCode (رقم صحيح أكبر) وغيّر versionName في app/build.gradle.kts قبل البناء.


## v33
راجع `RELEASE-NOTES-v33-ar.md` لمركز الشبكة الجديد وحدود القراءات الفعلية.


## v34
راجع `RELEASE-NOTES-v34-ar.md` لتحسين وضوح حداثة بيانات مركز الشبكة.


## v35 — 2.17-network-history (Build 20)

أضيف سجل محلي لآخر 24 قراءة ناجحة لأداء MikroTik، مع عرض مختصر ومسح يدوي للسجل. لا تُدّعى مراقبة خلفية مستمرة أو تحليل ذروة آلي. راجع `RELEASE-NOTES-v35-ar.md`.


## v36 — Network Analytics
ملخص محلي لمتوسط وأعلى CPU وRAM ضمن العينات المتاحة. التحليل وصفي ولا ينفذ تغييرات تلقائية. راجع `RELEASE-NOTES-v36-ar.md`.


## v38 — Network Intelligence
- الإصدار 2.20 / build 23.
- إضافة جمع دوري لعينات أداء MikroTik إلى سجل الشبكة المحلي باستخدام عامل المراقبة الموجود.
- سجل يصل إلى 96 عينة، مع توضيح أن التوقيت الفعلي يخضع لجدولة Android.
- لم يتم التحقق من بناء APK؛ يلزم بناؤه واختباره في بيئة Android/Gradle.

## GitHub Releases — تحديث التطبيق من داخل VoltNet Pro

المستودع المعتمد هو `ahmadga808/VoltNetPro-v`. راجع `GITHUB-RELEASES-AR.md` لمعرفة أسماء الأصول المطلوبة وشروط التوقيع. بعد إنشاء APK موقّع، أنشئ ملف SHA-256 بالأمر:

```bash
./prepare-github-release.sh app/build/outputs/apk/release/app-release.apk
```

ارفع APK وملف `.sha256` الناتج إلى Release مستقر، واستخدم Tag مطابقاً لـ `versionName` مثل `v2.24`. لا ترفع ملف keystore أو `keystore.properties` أو كلمات المرور أو GitHub Token.
