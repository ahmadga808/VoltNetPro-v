package com.subsmgr.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** مستلم رسالة: مشترك أو جهة اتصال من الهاتف. المفتاح = آخر 10 أرقام (يمنع التكرار). */
data class Recip(val key: String, val name: String, val phone: String, val sub: Sub?)

private val WaGreen = Color(0xFF25D366)

private fun keyOf(phone: String): String =
    phone.filter { it.isDigit() }.map { Character.digit(it, 10).toString() }.joinToString("").takeLast(10)

private fun subRecip(s: Sub) = Recip(keyOf(s.phone ?: ""), s.name, s.phone ?: "", s)

/** يقرأ الرقم والاسم من نتيجة منتقي جهات الاتصال (لا يحتاج صلاحية؛ النظام يمنح إذناً مؤقتاً). */
fun readPickedContact(ctx: Context, uri: Uri): Pair<String, String>? = try {
    ctx.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
        null, null, null,
    )?.use { c -> if (c.moveToFirst()) Pair(c.getString(0) ?: "", c.getString(1) ?: "") else null }
} catch (e: Exception) { null }

/** كل جهات الهاتف (اسم، رقم) بلا تكرار. تحتاج صلاحية READ_CONTACTS. */
private fun loadContacts(ctx: Context): List<Pair<String, String>> {
    val out = LinkedHashMap<String, Pair<String, String>>()
    try {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: ""
                val num = c.getString(1) ?: ""
                val k = keyOf(num)
                if (k.length >= 7 && !out.containsKey(k)) out[k] = Pair(name, num)
            }
        }
    } catch (e: Exception) { }
    return out.values.toList()
}

private fun fillVars(db: Db, text: String, r: Recip): String {
    val s = r.sub
    return text.replace("{name}", r.name)
        .replace("{net}", db.setting("network_name", "الشبكة"))
        .replace("{debt}", if (s != null) fmt(s.bal) else "")
        .replace("{end}", s?.endDate ?: "")
        .replace("{pkg}", s?.pkg ?: "")
}

private fun smsNumber(db: Db, phone: String): String =
    try { db.formatPhone(phone) } catch (e: Exception) { phone.filter { it.isDigit() || it == '+' } }

private fun sendOne(ctx: Context, db: Db, r: Recip, text: String) {
    val msg = fillVars(db, text, r)
    db.logMsg("wa", r.name, r.phone, msg)
    openUrl(ctx, db.waUrl(r.phone, msg))
}

/** إرسال SMS مباشر بلا فتح تطبيق الرسائل (يحتاج صلاحية SEND_SMS). يرجع false عند الفشل. */
fun autoSms(ctx: Context, db: Db, r: Recip, text: String): Boolean =
    Notify.sendSmsNow(ctx, db, r.name, r.phone, fillVars(db, text, r), "sms", "bulk")

// ======================= الإرسال الجماعي =======================
@Composable
fun BulkSendDialog(db: Db, subs: List<Sub>, onChanged: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var step by remember { mutableIntStateOf(1) }
    val chosen = remember { mutableStateMapOf<String, Recip>() }
    val sent = remember { mutableStateMapOf<String, Boolean>() }
    var via by remember { mutableStateOf(if (Notify.channel(db) == Notify.SMS || Notify.channel(db) == Notify.SMS_WA) "auto" else "wa") }
    var text by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text(if (step == 1) "رسالة جماعية: اختر الأشخاص" else "رسالة جماعية: اكتب وأرسل", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showLog = true }) { Text("السجل") }
                }
                if (step == 1) PickStep(db, subs, chosen, onChanged, { step = 2 }, Modifier.weight(1f))
                else ComposeStep(db, chosen, sent, via, { via = it }, text, { text = it }, { step = 1 }, Modifier.weight(1f))
            }
        }
    }
    if (showLog) MsgLogDialog(db) { showLog = false }
}

@Composable
private fun MsgLogDialog(db: Db, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val log = remember(rev) { db.msgLog() }
    val failed = remember(rev) { db.failedMsgs() }
    val st = remember(rev) { db.smsStats() }
    val cost = (db.setting("sms_price", "0").toLongOrNull() ?: 0L) * st.second
    fun statusPill(m: MsgLog): Pair<String, Color>? = when (m.status) {
        "pending" -> "قيد الإرسال" to cs.outline
        "sent" -> "أُرسلت" to cs.primary
        "delivered" -> "سُلّمت" to Ok
        "failed" -> "فشلت" to Bad
        "retried" -> "أُعيدت" to Muted
        else -> null
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("سجل الرسائل", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { rev++ }) { Icon(Icons.Rounded.Refresh, "تحديث الحالات") }
                }
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("هذا الشهر: ${st.first} رسالة SMS (${st.second} جزء)" + (if (st.third > 0) " • فشلت ${st.third}" else "") +
                        (if (cost > 0) " • التكلفة التقريبية ${fmt(cost)} دينار" else ""), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    if (failed.isNotEmpty()) Button(
                        onClick = {
                            if (!Notify.hasSmsPerm(ctx)) { toast(ctx, "صلاحية إرسال SMS غير ممنوحة"); return@Button }
                            busy = true
                            scope.launch {
                                var ok = 0
                                for (m in failed.take(20)) { if (withContext(Dispatchers.IO) { Notify.retry(ctx, db, m) }) ok++; kotlinx.coroutines.delay(2500) }
                                toast(ctx, "أُعيد إرسال $ok رسالة"); busy = false; rev++
                            }
                        },
                        enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    ) { Text("إعادة إرسال الفاشلة (${failed.size})") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (log.isEmpty()) item { EmptyState(Icons.Rounded.Chat, "لا توجد رسائل مسجّلة بعد") }
                    items(log, key = { it.id }) { m ->
                        AppCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(m.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    statusPill(m)?.let { (t, c) -> Pill(t, c); Spacer(Modifier.width(6.dp)) }
                                    Pill(when (m.via) { "wa" -> "واتساب"; "auto" -> "SMS تلقائي"; else -> "SMS مباشر" }, if (m.via == "wa") WaGreen else cs.primary)
                                    if (m.status == "failed") IconButton(onClick = { if (Notify.retry(ctx, db, m)) { toast(ctx, "أُعيد الإرسال"); rev++ } else toast(ctx, "تعذّر الإرسال") }) {
                                        Icon(Icons.Rounded.Refresh, "إعادة المحاولة", tint = Bad)
                                    }
                                }
                                Text(m.ts + "  •  \u200e" + m.phone, fontSize = 11.sp, color = cs.onSurfaceVariant)
                                Text(m.body, fontSize = 12.sp, maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipRow(r: Recip, chosen: SnapshotStateMap<String, Recip>) {
    val on = chosen.containsKey(r.key)
    fun toggle() { if (chosen.containsKey(r.key)) { chosen.remove(r.key) } else { chosen[r.key] = r } }
    Row(Modifier.fillMaxWidth().clickable { toggle() }.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = { toggle() })
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("\u200e" + r.phone, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickStep(db: Db, subs: List<Sub>, chosen: SnapshotStateMap<String, Recip>, onChanged: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var flt by remember { mutableIntStateOf(0) }

    val withPhone = remember(subs) { subs.filter { !it.phone.isNullOrBlank() } }
    val noPhone = subs.size - withPhone.size
    val subList = remember(withPhone, q, flt) {
        withPhone.filter { s ->
            val okQ = q.isBlank() || s.name.contains(q, ignoreCase = true) || (s.phone ?: "").contains(q)
            val st = db.statusOf(s)
            val okF = when (flt) {
                1 -> st == "فعّال" || st == "مسدّد"
                2 -> st == "منتهي"
                3 -> s.bal > 0
                else -> true
            }
            okQ && okF
        }
    }

    // جهات الهاتف (تحتاج صلاحية القراءة)
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var contacts by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(granted, tab) {
        if (granted && tab == 1 && contacts == null) contacts = withContext(Dispatchers.IO) { loadContacts(ctx) }
    }
    val cList = remember(contacts, q) {
        (contacts ?: emptyList()).filter { c -> q.isBlank() || c.first.contains(q, ignoreCase = true) || c.second.contains(q) }
    }

    Column(modifier) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("المشتركون (${withPhone.size})") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("جهات الهاتف / واتساب") })
        }
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, label = { Text("بحث بالاسم أو الرقم") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (tab == 0) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = flt == 0, onClick = { flt = 0 }, label = { Text("الكل") })
                FilterChip(selected = flt == 1, onClick = { flt = 1 }, label = { Text("فعّال") })
                FilterChip(selected = flt == 2, onClick = { flt = 2 }, label = { Text("منتهي") })
                FilterChip(selected = flt == 3, onClick = { flt = 3 }, label = { Text("عليه دين") })
            }
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (tab == 0) subList.forEach { s -> val r = subRecip(s); chosen[r.key] = r }
                else cList.forEach { c -> val k = keyOf(c.second); chosen[k] = Recip(k, c.first.ifBlank { c.second }, c.second, null) }
            }) { Text("تحديد الكل (${if (tab == 0) subList.size else cList.size})") }
            TextButton(onClick = { chosen.clear() }, enabled = chosen.isNotEmpty()) { Text("إلغاء التحديد") }
            if (tab == 0 && noPhone > 0) Text("بدون رقم: $noPhone", fontSize = 11.sp, color = Bad)
        }
        LazyColumn(Modifier.weight(1f)) {
            if (tab == 0) {
                if (subList.isEmpty()) item { EmptyState(Icons.Rounded.People, "لا يوجد مشتركون مطابقون") }
                items(subList, key = { it.id }) { s -> RecipRow(subRecip(s), chosen) }
            } else if (!granted) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("للاختيار من جهات الهاتف (ومنها أرقام واتساب المحفوظة) اسمح للتطبيق بقراءة جهات الاتصال.", fontSize = 13.sp)
                        Button(onClick = { permLauncher.launch(Manifest.permission.READ_CONTACTS) }, shape = RoundedCornerShape(14.dp)) { Text("السماح بالوصول لجهات الاتصال") }
                    }
                }
            } else if (contacts == null) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else {
                if (cList.isEmpty()) item { EmptyState(Icons.Rounded.Contacts, "لا توجد جهات اتصال مطابقة") }
                items(cList, key = { keyOf(it.second) }) { c ->
                    RecipRow(Recip(keyOf(c.second), c.first.ifBlank { c.second }, c.second, null), chosen)
                }
            }
        }
        val newOnes = chosen.values.filter { it.sub == null }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (newOnes.isNotEmpty()) OutlinedButton(
                    onClick = {
                        safe(ctx) {
                            val (a, d, b) = db.importContacts(newOnes.map { Pair(it.name, it.phone) })
                            toast(ctx, "أُضيف $a مشترك" + (if (d > 0) "، مكرر $d" else "") + (if (b > 0) "، رقم غير صالح $b" else ""))
                            newOnes.forEach { chosen.remove(it.key) }
                            onChanged()
                        }
                    },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("إضافة ${newOnes.size} من جهات الهاتف كمشتركين") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("المحدّدون: ${chosen.size}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Button(onClick = onNext, enabled = chosen.isNotEmpty(), shape = RoundedCornerShape(14.dp)) { Text("التالي: كتابة الرسالة") }
                }
            }
        }
    }
}

@Composable
private fun ComposeStep(
    db: Db, chosen: SnapshotStateMap<String, Recip>, sent: SnapshotStateMap<String, Boolean>,
    via: String, setVia: (String) -> Unit, text: String, setText: (String) -> Unit,
    onBack: () -> Unit, modifier: Modifier,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val list = chosen.values.sortedBy { it.name }
    val remaining = list.filter { sent[it.key] != true }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var askAuto by remember { mutableStateOf(false) }
    var smsGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> smsGranted = ok }
    fun startAuto() {
        val todo = remaining.toList()
        val t = text
        running = true
        job = scope.launch {
            try {
                for (r in todo) {
                    if (autoSms(ctx, db, r, t)) sent[r.key] = true else toast(ctx, "فشل الإرسال إلى ${r.name}")
                    kotlinx.coroutines.delay(3000)
                }
            } finally { running = false }
        }
    }
    if (askAuto) AlertDialog(
        onDismissRequest = { askAuto = false },
        title = { Text("إرسال SMS تلقائي") },
        text = { Text("سيرسل التطبيق ${remaining.size} رسالة بالتتابع بفاصل 3 ثوانٍ دون فتح تطبيق الرسائل، وقد تُحتسب كل رسالة من رصيد خطك. أندرويد قد يطلب تأكيداً بعد 30 رسالة. هل تريد المتابعة؟") },
        confirmButton = { Button(onClick = { askAuto = false; startAuto() }) { Text("ابدأ") } },
        dismissButton = { TextButton(onClick = { askAuto = false }) { Text("إلغاء") } },
    )
    val presets = listOf(
        "تذكير دين" to "مرحباً {name}، نذكّرك بأن عليك مبلغ {debt} دينار لدى {net}. يرجى التسديد. شكراً لك.",
        "ينتهي قريباً" to "مرحباً {name}، نذكّرك بأن اشتراكك في {net} ينتهي بتاريخ {end}. للتجديد يرجى التواصل معنا. شكراً لك.",
        "رسالة عامة" to "مرحباً {name}، ",
    )
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = via == "wa", onClick = { setVia("wa") }, label = { Text("واتساب") })
                    FilterChip(selected = via == "auto", onClick = { setVia("auto") }, label = { Text("SMS مباشر") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBack) { Text("رجوع للتحديد") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (lab, t) -> AssistChip(onClick = { setText(t) }, label = { Text(lab) }) }
                }
                OutlinedTextField(
                    value = text, onValueChange = setText, label = { Text("نص الرسالة") },
                    minLines = 4, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("{name}", "{net}", "{debt}", "{end}", "{pkg}").forEach { v ->
                        AssistChip(onClick = { setText(text + v) }, label = { Text(v) })
                    }
                }
                Text("{name} اسم الشخص، {net} اسم الشبكة، {debt} الدين، {end} تاريخ الانتهاء، {pkg} الباقة (للمشتركين فقط).",
                    fontSize = 11.sp, color = cs.onSurfaceVariant)
                if (text.isNotBlank() && list.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant) {
                        Column(Modifier.padding(12.dp)) {
                            Text("معاينة (${list.first().name})", fontSize = 11.sp, color = cs.onSurfaceVariant)
                            Text(fillVars(db, text, list.first()), fontSize = 13.sp)
                        }
                    }
                }
                if (via == "auto") {
                    if (running) {
                        Text("جارٍ الإرسال... تم ${sent.size} من ${list.size}", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { job?.cancel(); job = null; running = false }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("إيقاف") }
                    } else {
                        Button(
                            onClick = { if (smsGranted) askAuto = true else smsPerm.launch(Manifest.permission.SEND_SMS) },
                            enabled = text.isNotBlank() && remaining.isNotEmpty(),
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) {
                            Icon(Icons.Rounded.Sms, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("إرسال SMS للكل (${remaining.size})")
                        }
                    }
                    Text("يُرسل بدون فتح أي تطبيق وبفاصل 3 ثوانٍ بين الرسائل. للأعداد الكبيرة قسّم الإرسال على دفعات (حوالي 30 رسالة) لتجنب حظر الخط.",
                        fontSize = 11.sp, color = cs.onSurfaceVariant)
                } else {
                    Button(
                        onClick = { remaining.firstOrNull()?.let { r -> sent[r.key] = true; sendOne(ctx, db, r, text) } },
                        enabled = text.isNotBlank() && remaining.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (via == "wa") WaGreen else cs.primary, contentColor = Color.White),
                    ) {
                        Icon(if (via == "wa") Icons.Rounded.Chat else Icons.Rounded.Sms, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("إرسال للتالي (${remaining.size} متبقٍ)")
                    }
                    Text("تُفتح الرسالة جاهزة لكل شخص في واتساب، فتضغط إرسال ثم ترجع للتطبيق وتكمل التالي.",
                        fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                if (sent.isNotEmpty()) TextButton(onClick = { sent.clear() }) { Text("إعادة تعيين حالة الإرسال") }
            }
        }
        if (list.isEmpty()) item { EmptyState(Icons.Rounded.People, "لم يتبقَّ أحد في القائمة") }
        items(list, key = { it.key }) { r ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(r.name, cs.primary, 36)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("\u200e" + r.phone, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    if (sent[r.key] == true) Pill("تم", Ok)
                    IconButton(onClick = {
                        if (via == "wa") { sent[r.key] = true; sendOne(ctx, db, r, text) }
                        else if (smsGranted) {
                            if (autoSms(ctx, db, r, text)) { sent[r.key] = true; toast(ctx, "أُرسلت SMS إلى ${r.name}") } else toast(ctx, "فشل الإرسال إلى ${r.name}")
                        } else smsPerm.launch(Manifest.permission.SEND_SMS)
                    }, enabled = text.isNotBlank() && !running) {
                        Icon(if (via == "wa") Icons.Rounded.Chat else Icons.Rounded.Sms, "إرسال", tint = if (text.isBlank()) Muted else if (via == "wa") WaGreen else cs.primary)
                    }
                    IconButton(onClick = { chosen.remove(r.key); sent.remove(r.key) }) { Icon(Icons.Rounded.Close, "إزالة") }
                }
            }
        }
    }
}
