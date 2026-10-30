@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.subsmgr.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ======================= رسم الإيرادات والمصاريف (آخر 6 أشهر) =======================
@Composable
fun MonthlyChart(db: Db, rev: Int) {
    val cs = MaterialTheme.colorScheme
    val data = remember(rev) { db.monthlyTotals(6) }
    val maxV = (data.maxOfOrNull { maxOf(it.second, it.third) } ?: 0L).coerceAtLeast(1L)
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                data.forEach { (m, r, e) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.width(13.dp).height((110f * r / maxV).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(4.dp)).background(Ok))
                            Box(Modifier.width(13.dp).height((110f * e / maxV).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(4.dp)).background(Bad))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(m.takeLast(2), fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("الإيرادات", Ok); Pill("المصاريف", Bad)
                Text("الأعلى: ${fmt(maxV)}", fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

// ======================= أعمار الديون =======================
@Composable
fun AgingCard(db: Db, rev: Int) {
    val cs = MaterialTheme.colorScheme
    val rows = remember(rev) { db.debtAging() }
    val tot = LongArray(4)
    rows.forEach { r -> for (i in 0..3) tot[i] += r.buckets[i] }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) Text("لا توجد ديون", color = cs.onSurfaceVariant)
            else {
                listOf("حتى 30 يوماً" to Ok, "31 - 60 يوماً" to Warn, "61 - 90 يوماً" to Bad, "أكثر من 90 يوماً" to Bad).forEachIndexed { i, (label, col) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Pill(label, col); Text(fmt(tot[i]), fontWeight = FontWeight.SemiBold)
                    }
                }
                HorizontalDivider()
                Text("أكبر المدينين", fontWeight = FontWeight.Bold)
                rows.take(10).forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(r.sub.name, fontWeight = FontWeight.SemiBold)
                            Text("أقدم دين منذ ${r.oldestDays} يوم", fontSize = 11.sp, color = cs.onSurfaceVariant)
                        }
                        Text(fmt(r.sub.bal), color = Bad, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ======================= مرشحون للقطع (مولدة) =======================
@Composable
fun CutCandidatesCard(db: Db, rev: Int, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var months by remember { mutableStateOf(db.setting("cut_months", "2")) }
    var r by remember { mutableIntStateOf(0) }
    val m = numOf(months) ?: 2.0
    val list = remember(rev, r, m) { db.cutCandidates(m) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldInput(F("m", "قطع من تجاوز دينه (أشهر اشتراك)", options = listOf("1", "2", "3", "4"), free = true, number = true), months) {
                months = it; if (numOf(it) != null) db.setSetting("cut_months", it)
            }
            if (list.isEmpty()) Text("لا يوجد مرشحون للقطع", color = cs.onSurfaceVariant)
            list.forEach { (s, price) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        Text("الدين ${fmt(s.bal)} (${g(Math.round(s.bal * 10.0 / price) / 10.0)} شهر)", fontSize = 11.sp, color = Bad)
                    }
                    FilledTonalButton(onClick = { safe(ctx) { db.setCut(s.id, true); r++; refresh() } }, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.PowerOff, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("قطع")
                    }
                }
            }
        }
    }
}

// ======================= صندوق اليوم =======================
@Composable
fun CashDialog(db: Db, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var date by remember { mutableStateOf(today()) }
    val rep = remember(date) { runCatching { db.dayReport(date) }.getOrNull() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("صندوق اليوم", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Box(Modifier.padding(horizontal = 12.dp)) { FieldInput(F("d", "التاريخ", date = true), date) { date = it.ifEmpty { today() } } }
                if (rep == null) Text("تاريخ غير صحيح", color = Bad, modifier = Modifier.padding(12.dp))
                rep?.let { r ->
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatCard(Icons.Rounded.Payments, "المقبوض", fmt(r.total), Ok, Modifier.weight(1f))
                                StatCard(Icons.Rounded.AccountBalanceWallet, "المصاريف", fmt(r.expenses), Bad, Modifier.weight(1f))
                                StatCard(Icons.Rounded.Savings, "الصافي", fmt(r.net), if (r.net >= 0) cs.primary else Bad, Modifier.weight(1f))
                            }
                        }
                        item {
                            AppCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("حسب المحصّل", fontWeight = FontWeight.Bold)
                                    if (r.byCollector.isEmpty()) Text("لا توجد دفعات", color = cs.onSurfaceVariant)
                                    r.byCollector.forEach { (n, v) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(n); Text(fmt(v), fontWeight = FontWeight.SemiBold, color = Ok) }
                                    }
                                    HorizontalDivider()
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("خصومات اليوم", color = cs.onSurfaceVariant); Text(fmt(r.discounts)) }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("ديون وقيود جديدة", color = cs.onSurfaceVariant); Text(fmt(r.charges)) }
                                }
                            }
                        }
                        item { SectionTitle("الدفعات (${r.payments.size})") }
                        items(r.payments) { p ->
                            AppCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, fontWeight = FontWeight.SemiBold)
                                        Text(listOfNotNull(p.collector, p.note?.takeIf { it.isNotEmpty() }).joinToString("  •  "), fontSize = 11.sp, color = cs.onSurfaceVariant)
                                    }
                                    Text(fmt(p.amount), color = Ok, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Surface(shadowElevation = 8.dp, color = cs.surface) {
                        Button(onClick = {
                            val lines = ArrayList<String>()
                            lines.add(db.setting("network_name", "الشبكة")); lines.add("إغلاق صندوق ${r.date}"); lines.add("――――――――――――")
                            r.byCollector.forEach { (n, v) -> lines.add("$n: ${fmt(v)}") }
                            lines.add("إجمالي المقبوض: ${fmt(r.total)} دينار (${r.payments.size} دفعة)")
                            lines.add("الخصومات: ${fmt(r.discounts)}"); lines.add("قيود وديون جديدة: ${fmt(r.charges)}")
                            lines.add("المصاريف: ${fmt(r.expenses)}"); lines.add("الصافي النقدي: ${fmt(r.net)} دينار")
                            shareText(ctx, lines.joinToString("\n"))
                        }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(14.dp).height(50.dp)) {
                            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("مشاركة ملخص الإغلاق")
                        }
                    }
                }
            }
        }
    }
}

// ======================= إضافات الإعدادات =======================
@Composable
fun SettingsExtras(db: Db, rev: Int, bump: () -> Unit, section: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var local by remember { mutableIntStateOf(0) }
    var collector by remember(rev) { mutableStateOf(db.setting("collector")) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var pickPrinter by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) safe(ctx) {
            val res = ctx.contentResolver.openInputStream(uri)?.use { db.importCsv(it) }
            if (res != null) {
                importMsg = "تمت إضافة ${res.added} مشترك، وتخطّي ${res.skipped} (مكرر أو بلا اسم)." +
                    (if (res.notes.isNotEmpty()) "\n" + res.notes.joinToString("\n") else "")
                bump()
            }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pickPrinter = true else toast(ctx, "صلاحية البلوتوث مطلوبة لاختيار الطابعة")
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) safe(ctx) { AutoBackup.setDir(ctx, uri); toast(ctx, "تم تفعيل النسخ اليومي"); local++ }
    }

    when (section) {
        "account" -> { SubscriptionCard(db); CloudAccountCard(db, bump) }
        "links" -> {
            if (db.isGenerator()) Text("ربط الراوترات والسكترات متاح لنشاط الإنترنت فقط، وهنا يبقى نفق OpenVPN ولوحة SAS.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            RouterCard(db); SectorsCard(db); SasCard(db, bump); OpenVpnCard()
        }
        "auto" -> { NotifyChannelCard(db); AutoSmsCard(db) }
        "general" -> {}
    }

    if (section == "general") {
    // --- المحصّل ---
    SectionTitle("المحصّل")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("يُسجَّل اسمه على كل دفعة ويظهر في كشف الحساب وصندوق اليوم. غيّره عند تبديل من يحصّل على هذا الهاتف.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            OutlinedTextField(collector, { collector = it }, label = { Text("اسم المحصّل") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            Button(onClick = { db.setSetting("collector", collector.trim()); toast(ctx, "تم الحفظ") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("حفظ") }
        }
    }
    }

    if (section == "data") {
    // --- الاستيراد ---
    SectionTitle("استيراد مشتركين (Excel)")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("احفظ ملف Excel بصيغة CSV (UTF-8) واختره هنا. السطر الأول عناوين الأعمدة: الاسم، الهاتف، المنطقة، العنوان، الباقة، تاريخ الانتهاء، الدين، ملاحظات (وللمولدة: الأمبير). عمود الاسم إلزامي، والمكرر يُتخطّى، والدين يُسجَّل كدين سابق.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("اختيار ملف CSV")
            }
            Text("لنقل بيانات برنامج سطح المكتب كاملة (مشتركين وحركات): انسخ ملف قاعدة بياناته إلى الهاتف واستخدم زر \"استعادة / استيراد\" في قسم النسخ الاحتياطي بعد اختيار النشاط المناسب.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
    }

    if (section == "general") {
    // --- الطابعة ---
    SectionTitle("طابعة الوصولات (بلوتوث)")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val mac = db.setting("printer_mac")
            Text(if (mac.isEmpty()) "لم تُحدَّد طابعة. اقرن الطابعة أولاً من إعدادات بلوتوث الهاتف ثم اخترها هنا." else "الطابعة المحددة: $mac", fontSize = 12.sp, color = cs.onSurfaceVariant)
            val widthLabel = if (db.setting("printer_dots", "384") == "576") "80 مم" else "58 مم"
            FieldInput(F("w", "عرض الورق", options = listOf("58 مم", "80 مم")), widthLabel) { db.setSetting("printer_dots", if (it == "80 مم") "576" else "384"); local++ }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (Printer.hasPermission(ctx)) pickPrinter = true else permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Print, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("اختيار الطابعة") }
                OutlinedButton(onClick = {
                    Printer.print(ctx, "اختبار الطباعة\n" + db.setting("network_name", "الشبكة") + "\n1234567890\n――――――――――――") { err -> toast(ctx, err ?: "تمت الطباعة") }
                }, enabled = mac.isNotEmpty(), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("طباعة تجريبية") }
            }
        }
    }
    }

    if (section == "general") {
    // --- الأمان (PIN) ---
    SectionTitle("الأمان")
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pinDialog = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("تعيين PIN")
            }
            OutlinedButton(onClick = { db.setPin(""); toast(ctx, "تمت إزالة الرمز"); bump() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.LockOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("إزالة PIN")
            }
        }
    }

    // --- البصمة ---
    SectionTitle("قفل البصمة")
    AppCard(Modifier.fillMaxWidth()) {
        val canBio = remember { BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS }
        var bio by remember(rev) { mutableStateOf(db.setting("bio") == "1") }
        val hasPin = db.hasPin()
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("فتح التطبيق بالبصمة")
                Switch(checked = bio && hasPin, enabled = canBio && hasPin, onCheckedChange = { bio = it; db.setSetting("bio", if (it) "1" else "0") })
            }
            Text(if (!canBio) "جهازك لا يدعم البصمة أو لم تُسجَّل بصمة فيه." else if (!hasPin) "عيّن رمز PIN أولاً من قسم الأمان، وتبقى البصمة بديلاً عنه." else "تُطلب البصمة عند الفتح، ويبقى PIN بديلاً.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
        StaffCard(db, bump)
    }

    if (section == "data") {
    // --- نسخ احتياطي تلقائي ---
    SectionTitle("نسخ احتياطي يومي تلقائي")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val on = remember(local, rev) { AutoBackup.dirUri(ctx) != null }
            Text(if (on) "مفعّل. آخر نسخة: ${AutoBackup.lastRun(ctx).ifEmpty { "لم تُنفَّذ بعد" }}. يُحتفظ بآخر 7 نسخ لكل نشاط." else "اختر مجلداً (مثلاً في Google Drive أو بطاقة الذاكرة) ليُنسخ إليه كل يوم تلقائياً.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { treeLauncher.launch(null) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text(if (on) "تغيير المجلد" else "اختيار مجلد") }
                if (on) OutlinedButton(onClick = { safe(ctx) { val n = AutoBackup.run(ctx); toast(ctx, "تم نسخ $n قاعدة"); local++ } }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("نسخ الآن") }
            }
            if (on) TextButton(onClick = { AutoBackup.disable(ctx); local++ }) { Text("إيقاف النسخ التلقائي", color = Bad) }
        }
    }
    
        LicenseAdminCard()
    }

    if (pinDialog) AskDialog("تعيين PIN", "رمز جديد (4 أرقام أو أكثر)", number = true, onDismiss = { pinDialog = false }) { p ->
        if (p.length < 4) throw IllegalArgumentException("الرمز قصير جداً")
        db.setPin(p); toast(ctx, "تم تعيين الرمز. سيُطلب عند فتح البرنامج."); bump()
    }
    importMsg?.let { InfoDialog(it) { importMsg = null } }
    if (pickPrinter) {
        val devices = remember { Printer.bonded(ctx) }
        AlertDialog(
            onDismissRequest = { pickPrinter = false },
            title = { Text("اختر الطابعة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (devices.isEmpty()) Text("لا توجد أجهزة مقترنة. اقرن الطابعة من إعدادات بلوتوث الهاتف أولاً.")
                    devices.forEach { d ->
                        TextButton(onClick = { db.setSetting("printer_mac", d.address); pickPrinter = false; local++; bump() }, modifier = Modifier.fillMaxWidth()) {
                            Text("${Printer.nameOf(d)}  (${d.address})")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickPrinter = false }) { Text("إغلاق") } },
        )
    }
}
