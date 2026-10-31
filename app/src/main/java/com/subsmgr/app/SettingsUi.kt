package com.subsmgr.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** تبويبات الإعدادات: (الأيقونة، الاسم، الوصف المختصر). */
val SETTINGS_SECTIONS: List<Triple<ImageVector, String, String>> = listOf(
    Triple(Icons.Rounded.Tune, "عام", "النشاط والعملة والمحصّل والطابعة والأمان والموظفون"),
    Triple(Icons.Rounded.ForwardToInbox, "القوالب والرسائل", "قوالب واتساب وSMS وقنوات الإرسال والتذكيرات"),
    Triple(Icons.Rounded.Router, "الاتصال والربط", "إعدادات MikroTik وSAS والأبراج ونفق OpenVPN"),
    Triple(Icons.Rounded.CloudSync, "الحساب والنسخ", "الترخيص والحساب السحابي والنسخ الاحتياطي والاستيراد"),
    Triple(Icons.Rounded.SupportAgent, "الدعم والبلاغات", "إرسال مشكلة أو اقتراح إلى المطوّر"),
    Triple(Icons.Rounded.Info, "حول وتعليمات", "دليل تفصيلي لكل قسم وسجل التحديثات"),
)

/** شريط المظهر (نهاري / ليلي / تلقائي) — يُثبَّت في أعلى الإعدادات ويُطبَّق فوراً. */
@Composable
fun ThemeBar(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val cur = ThemePref.mode
    AppCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.Palette, cs.primary, 36)
            Spacer(Modifier.width(10.dp))
            Text("المظهر", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceVariant).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf(
                    Triple("light", "نهاري", Icons.Rounded.LightMode),
                    Triple("dark", "ليلي", Icons.Rounded.DarkMode),
                    Triple("system", "تلقائي", Icons.Rounded.BrightnessAuto),
                ).forEach { (k, label, icon) ->
                    val sel = cur == k
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) cs.primary else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { ThemePref.set(ctx, k) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, Modifier.size(16.dp), tint = if (sel) cs.onPrimary else cs.onSurfaceVariant)
                        if (sel) { Spacer(Modifier.width(4.dp)); Text(label, fontSize = 12.sp, color = cs.onPrimary, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

/** شبكة أيقونات إعدادات؛ كل عنصر يعرض الأيقونة والاسم أسفلها. */
@Composable
fun SettingsTabsRow(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(SETTINGS_SECTIONS.size) { i ->
            val (icon, label, _) = SETTINGS_SECTIONS[i]
            val active = selected == i
            Surface(modifier = Modifier.fillMaxWidth().clickable { onSelect(i) }, shape = RoundedCornerShape(16.dp),
                color = if (active) cs.primaryContainer else cs.surface, tonalElevation = if (active) 2.dp else 1.dp) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, null, Modifier.size(27.dp), tint = if (active) cs.onPrimaryContainer else cs.primary)
                    Text(label, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) cs.onPrimaryContainer else cs.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
                }
            }
        }
    }
}

/** عنوان تعريفي أعلى محتوى كل تبويب. */
@Composable
fun SettingsIntro(index: Int) {
    val cs = MaterialTheme.colorScheme
    val (icon, title, desc) = SETTINGS_SECTIONS[index]
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, cs.primary, 38)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(desc, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}



/** إعداد عملة العرض وأسعار الصرف اليدوية مقابل الدينار العراقي. القيم الأصلية في قاعدة البيانات تبقى بالدينار. */
@Composable
fun CurrencySettingsSection(db: Db, rev: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val codes = listOf("IQD", "EGP", "SAR")
    val labels = mapOf("IQD" to "الدينار العراقي", "EGP" to "الجنيه المصري", "SAR" to "الريال السعودي")
    var selected by remember(rev) { androidx.compose.runtime.mutableStateOf(db.setting("currency_code", "IQD")) }
    var egp by remember(rev) { androidx.compose.runtime.mutableStateOf(db.setting("rate_EGP_IQD", "42")) }
    var sar by remember(rev) { androidx.compose.runtime.mutableStateOf(db.setting("rate_SAR_IQD", "350")) }
    var preview by remember(rev) { androidx.compose.runtime.mutableStateOf("100000") }
    SectionTitle("العملة وأسعار الصرف")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("العملة الأساسية للحسابات والسجلات هي الدينار العراقي. أدخل قيمة الدينار العراقي المقابلة لوحدة واحدة من العملة الأخرى.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            codes.forEach { code ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == code, onClick = { selected = code })
                    Text(labels[code] ?: code, modifier = Modifier.weight(1f))
                    Text(code, color = cs.primary, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedTextField(egp, { egp = it }, label = { Text("1 جنيه مصري = كم دينار عراقي؟") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            OutlinedTextField(sar, { sar = it }, label = { Text("1 ريال سعودي = كم دينار عراقي؟") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            OutlinedTextField(preview, { preview = it }, label = { Text("مثال: مبلغ بالدينار العراقي") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            val rate = when (selected) { "EGP" -> egp.toDoubleOrNull(); "SAR" -> sar.toDoubleOrNull(); else -> 1.0 }
            val amount = preview.toDoubleOrNull()
            val converted = if (rate != null && rate > 0 && amount != null) amount / rate else null
            Text(if (converted != null) "الناتج التقريبي: ${String.format(java.util.Locale.US, "%.2f", converted)} ${selected}" else "تحقق من إدخال أسعار صرف موجبة وصحيحة", fontWeight = FontWeight.SemiBold, color = if (converted != null) cs.primary else cs.error)
            Text("سعر الصرف يدوي ويتغير حسب إدخالك. لا يوجد جلب تلقائي للأسعار. هذه الإعدادات ومعاينة التحويل لا تعني أن جميع شاشات الفواتير والتقارير قد حُوّلت بعد.", fontSize = 11.sp, color = cs.onSurfaceVariant)
            Button(onClick = {
                val e = egp.toDoubleOrNull(); val a = sar.toDoubleOrNull()
                if (e == null || e <= 0 || a == null || a <= 0) toast(ctx, "أدخل أسعار صرف صحيحة أكبر من صفر")
                else { db.setSetting("currency_code", selected); db.setSetting("rate_EGP_IQD", egp); db.setSetting("rate_SAR_IQD", sar); toast(ctx, "تم حفظ إعدادات العملة وأسعار الصرف"); bump() }
            }, modifier = Modifier.fillMaxWidth()) { Text("حفظ إعدادات العملة") }
        }
    }
}

/** صفحة مساعدة وتعريف الإصدار وسجل التغييرات. */
@Composable
fun AppInfoSection() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val versionName = remember {
        try {
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            info.versionName ?: "غير معروف"
        } catch (_: Exception) { "غير معروف" }
    }
    val versionCode = remember {
        try {
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
        } catch (_: Exception) { "غير معروف" }
    }

    SectionTitle("معلومات البرنامج")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VoltNet Pro", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            InfoLine("رقم النسخة", versionName)
            InfoLine("رقم البناء", versionCode)
            InfoLine("آخر تحديث ضمن هذا الإصدار", "29-10-2026")
            Text("تاريخ إعداد ملفات المصدر: 29-10-2026. يظهر هذا التاريخ في المصدر ولا يثبت وحده تثبيت التحديث على الهاتف.", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }

    SectionTitle("تعليمات الاستخدام التفصيلية لكل قسم")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HelpLine("١", "الرئيسية: تعرض ملخص اليوم وعدّادات المشتركين والفعالين والمنتهين والمتصلين وغير المتصلين والمدينين. اضغط أي عدّاد لفتح قائمة المشتركين بالفلتر المطابق.")
            HelpLine("٢", "المشتركون: ابحث بالاسم أو الهاتف، واستخدم الفلاتر للاتصال وحالة الاشتراك والانتهاء. افتح ملف المشترك لتجديده وتسجيل الدفعات والديون ومراجعة سجل الحركات.")
            HelpLine("٣", "الباقات والتجديدات: عرّف الباقات وأسعارها ومددها أولاً، ثم اختر الباقة عند التجديد لمراجعة السعر والمدة قبل الحفظ. تحفظ الأسعار التاريخية مع كل عملية.")
            HelpLine("٤", "الحسابات والديون: راجع الدفعات والمصاريف والأرصدة. الدين غير المحصل لا يُعد إيراداً، وتسديده لاحقاً يضيف دفعة ولا يمحو سجل الدين.")
            HelpLine("٥", "الشبكة: لوحاتي تحفظ روابط لوحات الإدارة فقط. فحص الربط يختبر الاتصالات المتاحة، ومراقبة الشبكة تعرض القراءات الفعلية، والراوترات والأبراج لإعداد MikroTik وSAS وأجهزة البث. تنبيهات Telegram والمساعد الذكي يعتمدان على القراءات المتاحة؛ ولا توجد مراقبة خلفية ما لم يذكر التطبيق ذلك صراحةً.")
            HelpLine("٦", "الإعدادات ← عام: اختر النشاط والمظهر والعملة واضبط المحصّل والطابعة والأمان. تغيير رمز العملة لا يحوّل القيمة الرقمية.")
            HelpLine("٧", "الإعدادات ← القوالب والرسائل: عدّل نصوص الرسائل ثم اختر قناة الإرسال واضبط التذكيرات. راجع المعاينة والأذونات قبل تفعيل الإرسال التلقائي.")
            HelpLine("٨", "الإعدادات ← الاتصال والربط: أدخل عنوان الراوتر وبيانات الاتصال الصحيحة، واحفظ إعدادات SAS وVPN عند توفر بيانات موثوقة. استخدم فحص الربط للتأكد؛ لا تعتبر حفظ الإعدادات نجاحاً للاتصال.")
            HelpLine("٩", "الإعدادات ← الحساب والنسخ: راجع الترخيص والحساب السحابي، وأنشئ نسخة احتياطية خارج الهاتف. الاستعادة قد تستبدل بيانات النشاط الحالي؛ اختر الملف والنشاط بعناية.")
            HelpLine("١٠", "الدعم والبلاغات: اختر نوع البلاغ واكتب التفاصيل وأرفق صورة اختيارياً. راجع الرسالة قبل فتح البريد والضغط على إرسال؛ حفظ المسودة لا يعني وصول البلاغ.")
            HelpLine("١١", "حول وتعليمات: راجع رقم النسخة وسجل الإضافات. عند ظهور تنبيه تحديث في الرئيسية اضغط عليه لقراءة تفاصيل التغييرات.")
        }
    }

    SectionTitle("الجديد في هذه النسخة")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChangelogLine("v2.24: إضافة شرح أسفل فلاتر المشتركين يوضح نوع النتائج وعددها حسب الفلتر المختار.")
            ChangelogLine("v2.24: مقارنة بيانات الترخيص بعد المزامنة، وإشعار المستخدم عند تغيير الخطة أو تاريخ الانتهاء أو حد المشتركين أو حالة الإيقاف، مع زر لتحديث الترخيص.")
            ChangelogLine("v2.24: تحويل أيقونات الإعدادات ومركز الشبكة إلى صفحات مستقلة لكل قسم، مع زر رجوع واضح إلى الصفحة الرئيسية للقسم.")
            ChangelogLine("v2.23: تنظيم الإعدادات في شبكة أيقونات، وتجميع أدوات الاتصال والمراقبة في مركز الشبكة.")
            ChangelogLine("v2.23: دمج القوالب والرسائل، وإضافة عرض سجل التغييرات عند بدء النسخة الجديدة ومن الرئيسية.")
            ChangelogLine("v2.23: جعل إنشاء الحساب اختيارياً مع تذكير عند التشغيل وشرح حفظ البيانات محلياً والنسخ السحابي.")
            ChangelogLine("v2.21: إضافة اختيار عملة العرض وإعداد أسعار صرف يدوية مقابل الدينار العراقي مع معاينة التحويل؛ لم تُفعّل بعد على جميع الحركات والتقارير.")
            ChangelogLine("v2.21: معالجة قراءة سجل مراقبة الشبكة الفارغ لمنع الانهيار عند عدم وجود عينات محفوظة.")
            ChangelogLine("الإصدار السابق: إضافة إعدادات Telegram ومساعد التشخيص المحلي وتنبيهات مراقبة الأجهزة وفق حالة الشاشة المفتوحة.")
            ChangelogLine("v2.12: إصلاح تخطيط أزرار ربط SAS على الشاشات الضيقة؛ توزيع الأزرار على صفّين لمنع ظهور الكلمات عمودية.")
            ChangelogLine("v2.12: نقل تبويب «حول وتعليمات» إلى آخر تبويب في شريط الإعدادات.")
            ChangelogLine("v2.12: تحديث رقم النسخة والبناء وتاريخ إعداد ملفات المصدر.")
            ChangelogLine("v2.12: توثيق إصلاحات الواجهة في سجل التغييرات بدلاً من عرض قائمة عامة فقط.")
            ChangelogLine("سابقاً: إضافة تبويب «لوحاتي» لحفظ روابط SAS وSAS3 وSAS4 وإيرثلينك وMikroTik وفتحها بضغطة.")
            ChangelogLine("سابقاً: إمكانية إضافة اللوحات وتعديلها وحذفها محلياً دون تخزين كلمات المرور.")
            ChangelogLine("سابقاً: الإبقاء على وظائف المشتركين والدفعات والتقارير وMikroTik ومراقبة الأجهزة الموجودة بالمشروع.")
            ChangelogLine("سابقاً: تجميع تعليمات الاستخدام ورقم النسخة وسجل التغييرات في هذا القسم.")
        }
    }

    SectionTitle("التحديثات المستقبلية")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("التحقق والتنزيل التلقائي للتحديثات غير مفعّل في هذه النسخة.", fontWeight = FontWeight.SemiBold)
            Text("يمكن إضافته لاحقاً عبر صفحة إصدارات رسمية (مثل GitHub Releases) أو خادم تحديثات خاص: يعرض التطبيق رقم النسخة الجديدة وسجل تغييراتها، ثم يفتح رابط التنزيل الرسمي بعد موافقتك. تثبيت APK خارجي قد يتطلب موافقة Android.", fontSize = 13.sp, color = cs.onSurfaceVariant)
            Text("لا توجد حالياً خدمة تحديث تلقائي أو رابط تحديث مدمج، لذلك لن يعرض التطبيق ادعاءً بوجود تحديث عند عدم إعداد مصدر رسمي.", fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
fun ReleaseNotesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.NewReleases, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("التعديلات والإضافات الجديدة — VoltNet Pro 2.24", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("أسفل فلاتر المشتركين يظهر شرح يوضح ما الذي يعرضه الفلتر وعدد النتائج الحالية.", fontSize = 13.sp)
                Text("عند اكتشاف تغيير في ترخيص الحساب من الخادم، يظهر إشعار بتفاصيل التغييرات في الخطة أو تاريخ الانتهاء أو حد المشتركين أو حالة الإيقاف، مع زر لمزامنة الترخيص الجديد.", fontSize = 13.sp)
                Text("واجهة الإعدادات أصبحت شبكة أيقونات مع اسم أسفل كل أيقونة.", fontSize = 13.sp)
                Text("تم تجميع لوحاتي وفحص الربط ومراقبة الشبكة والراوترات والأبراج وتنبيهات Telegram والمساعد الذكي داخل مركز الشبكة.", fontSize = 13.sp)
                Text("تم دمج القوالب والرسائل في قسم واحد داخل الإعدادات.", fontSize = 13.sp)
                Text("تظهر هذه النافذة عند تشغيل نسخة التطبيق لأول مرة، ويمكن فتح سجل التغييرات لاحقاً من التنبيه الموجود في الصفحة الرئيسية.", fontSize = 13.sp)
                Divider()
                Text("تذكير: ظهور أدوات الشبكة لا يعني نجاح اتصال SAS أو الراوتر. اعتمد على نتيجة الفحص الفعلي داخل كل أداة.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("فهمت") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HelpLine(number: String, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(number, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Text(text, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun ChangelogLine(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp)
    }
}
