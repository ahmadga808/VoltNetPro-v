# تحديث VoltNet Pro عبر GitHub Releases

- المستودع العام: `ahmadga808/VoltNetPro-v`
- واجهة أحدث إصدار مستقر: `https://api.github.com/repos/ahmadga808/VoltNetPro-v/releases/latest`
- التطبيق يتجاهل Draft وPrerelease، ويتطلب وجود APK ضمن Assets.
- التحقق من سلامة التنزيل يستخدم `digest` (SHA-256) الذي تعيده GitHub لأصل APK. إن لم يوجد، يقبل ملف checksum نصياً باسم `.sha256` أو `SHA256SUMS.txt` أو `checksums.txt`.
- عند عدم توفر بصمة SHA-256 يرفض التطبيق متابعة التثبيت.
- يقارن `versionName` المنشور، ويدعم قراءة `Build: <versionCode>` من ملاحظات الإصدار.
- Android Package Installer يفرض توافق `applicationId` وversionCode وشهادة التوقيع قبل تثبيت التحديث. التثبيت يتطلب موافقة المستخدم ولا يوجد تثبيت صامت.

## قالب إصدار جديد
1. `tag`: مثل `V2.24`، ويجب أن يطابق versionName.
2. `title`: `VoltNet Pro V2.24`.
3. أرفق APK واحداً باسم مثل `VoltNetPro-v2.24.apk`.
4. ابدأ وصف الإصدار بسطر `Build: 28` (يساوي versionCode في Gradle)، ثم اكتب ملاحظات التغييرات.
5. انشره كـ Release مستقر وليس prerelease.
6. تأكد أن APK موقّع بالـ keystore الأصلي نفسه المستخدم للتطبيق المثبت.

لا ترفع ملف keystore أو كلمات مروره أو Tokens إلى المستودع. GitHub يعرض digest لكل asset عبر Releases API؛ لا حاجة لإضافة token إلى التطبيق.
