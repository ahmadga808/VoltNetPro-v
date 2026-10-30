package com.subsmgr.app

import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDate

// ======================= PDF: كروت الهوتسبوت =======================
object VoucherPdf {
    fun write(db: Db, out: OutputStream, list: List<Voucher>) {
        val doc = PdfDocument()
        val w = 595; val h = 842; val cols = 3; val rows = 8; val m = 24f
        val cw = (w - 2 * m) / cols
        val ch = (h - 2 * m) / rows
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = AColor.BLACK }
        val box = Paint().apply { style = Paint.Style.STROKE; color = AColor.GRAY; strokeWidth = 0.8f; pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f) }
        val net = db.setting("network_name", "الشبكة")
        var page: PdfDocument.Page? = null
        var cv: Canvas? = null
        list.forEachIndexed { i, v ->
            val pos = i % (cols * rows)
            if (pos == 0) {
                page?.let { doc.finishPage(it) }
                page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i / (cols * rows) + 1).create())
                cv = page!!.canvas
            }
            val c = cv!!
            val x0 = w - m - ((pos % cols) + 1) * cw
            val y0 = m + (pos / cols) * ch
            c.drawRect(x0 + 3, y0 + 3, x0 + cw - 3, y0 + ch - 3, box)
            p.isFakeBoldText = true; p.textSize = 11f
            c.drawText(net, x0 + cw / 2, y0 + 20, p)
            p.isFakeBoldText = false; p.textSize = 9f
            c.drawText(v.profile + (if (v.price > 0) "  •  " + fmt(v.price) + " د.ع" else ""), x0 + cw / 2, y0 + 35, p)
            p.isFakeBoldText = true; p.textSize = 16f
            c.drawText(v.code, x0 + cw / 2, y0 + 62, p)
            if (v.pass != v.code) { p.textSize = 12f; c.drawText(v.pass, x0 + cw / 2, y0 + 80, p) }
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }
}

// ======================= PDF: وصل =======================
object ReceiptPdf {
    fun write(db: Db, out: OutputStream, title: String, text: String) {
        val doc = PdfDocument()
        val w = 420; val h = 595
        val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; color = AColor.BLACK }
        val line = Paint().apply { color = AColor.LTGRAY; strokeWidth = 0.8f }
        var y = 40f
        p.isFakeBoldText = true; p.textSize = 18f
        c.drawText(db.setting("network_name", "الشبكة"), w - 30f, y, p); y += 22f
        p.textSize = 12f; p.color = AColor.DKGRAY
        c.drawText(title, w - 30f, y, p); y += 14f
        c.drawLine(30f, y, w - 30f, y, line); y += 22f
        p.isFakeBoldText = false; p.textSize = 12f; p.color = AColor.BLACK
        for (raw in text.lines()) {
            val t = raw.trim()
            if (t.isEmpty() || t.all { it == '―' || it == '-' || it == '_' || it == '=' }) continue
            if (y > h - 70) break
            var s = t
            while (s.isNotEmpty() && y <= h - 70) {
                val n = p.breakText(s, true, w - 60f, null).coerceAtLeast(1)
                c.drawText(s.substring(0, n), w - 30f, y, p); y += 20f
                s = s.substring(n)
            }
        }
        y = h - 40f
        c.drawLine(30f, y - 16f, w - 30f, y - 16f, line)
        p.textSize = 10f; p.color = AColor.GRAY; p.textAlign = Paint.Align.CENTER
        c.drawText("شكراً لتعاملكم معنا", w / 2f, y, p)
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

// ======================= الكروت =======================
@Composable
fun VouchersDialog(db: Db, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    var profile by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("20") }
    var len by remember { mutableStateOf("8") }
    var price by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableIntStateOf(0) }
    var del by remember { mutableStateOf<Voucher?>(null) }
    var quick by remember { mutableStateOf(false) }
    var sendMsg by remember { mutableStateOf<SendMsg?>(null) }
    val list = remember(rev, filter) { db.vouchers(when (filter) { 0 -> false; 1 -> true; else -> null }) }
    val allV = remember(rev) { db.vouchers(null) }
    val pdfOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) safe(ctx) {
            val l = db.vouchers(false)
            if (l.isEmpty()) throw IllegalArgumentException("لا توجد كروت غير مباعة")
            ctx.contentResolver.openOutputStream(uri)?.use { VoucherPdf.write(db, it, l) }
            toast(ctx, "تم حفظ ملف الكروت PDF")
        }
    }

    fun generate() {
        val c = Router.cfg(ctx)
        if (c == null) { toast(ctx, "اربط الراوتر أولاً من الإعدادات"); return }
        if (profile.isBlank()) { toast(ctx, "اختر أو اكتب اسم البروفايل"); return }
        val n = (count.toIntOrNull() ?: 0).coerceIn(1, 500)
        val l = (len.toIntOrNull() ?: 8).coerceIn(4, 16)
        val pr = price.toLongOrNull() ?: 0L
        val batch = "app-" + LocalDate.now() + "-" + (1000..9999).random()
        val existing = allV.map { it.code }.toHashSet()
        val items = ArrayList<Pair<String, String>>()
        while (items.size < n) {
            val code = prefix.trim() + Voucher.gen(l)
            if (code !in existing && items.none { it.first == code }) items.add(code to code)
        }
        busy = true; msg = ""
        scope.launch {
            val res = try { withContext(Dispatchers.IO) { Router.createVouchers(c, items, profile.trim(), limit.trim(), batch) } }
            catch (e: Exception) { busy = false; msg = e.message ?: "خطأ"; return@launch }
            res.first.forEach { db.addVoucher(it, it, profile.trim(), batch, pr) }
            busy = false; rev++
            msg = "أُنشئ ${res.first.size} كرتاً على الراوتر" + (if (res.second.isNotEmpty()) "\nأخطاء: " + res.second.take(3).joinToString(" | ") else "")
            if (res.first.isNotEmpty()) { tab = 1; filter = 0 }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("كروت الهوتسبوت", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("توليد كروت") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("المخزون (${allV.count { !it.sold }})") })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                if (tab == 0) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("تُنشأ الكروت كمستخدمي Hotspot على الراوتر مباشرة وتُحفظ هنا لتبيعها وتطبعها. اسم المستخدم وكلمة المرور متطابقان.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        OutlinedTextField(profile, { profile = it }, label = { Text("بروفايل الهوتسبوت") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            val c = Router.cfg(ctx)
                            if (c == null) toast(ctx, "اربط الراوتر أولاً") else {
                                busy = true
                                scope.launch {
                                    try { profiles = withContext(Dispatchers.IO) { Router.hotspotProfiles(c) } } catch (e: Exception) { toast(ctx, e.message ?: "خطأ") }
                                    busy = false
                                }
                            }
                        }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("جلب البروفايلات من الراوتر") }
                        if (profiles.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            profiles.forEach { p -> FilterChip(selected = profile == p, onClick = { profile = p }, label = { Text(p) }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(count, { count = it }, label = { Text("العدد (حتى 500)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                            OutlinedTextField(len, { len = it }, label = { Text("طول الرمز") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(price, { price = it }, label = { Text("سعر الكرت (دينار)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                            OutlinedTextField(limit, { limit = it }, label = { Text("المدة (مثل 1d أو 30d)") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                        }
                        OutlinedTextField(prefix, { prefix = it }, label = { Text("بادئة للرمز (اختياري)") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                        Button(onClick = { generate() }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("إنشاء الكروت") }
                        if (msg.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant) { Text(msg, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(10.dp)) }
                    }
                } else {
                    val soldTotal = allV.filter { it.sold }.sumOf { it.price }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("غير مباع") })
                        FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("مباع") })
                        FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("الكل") })
                    }
                    Text("مباع: ${allV.count { it.sold }}  •  إيراد المبيعات: ${fmt(soldTotal)} دينار", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp))
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { quick = true }, shape = RoundedCornerShape(14.dp)) { Text("بيع سريع") }
                        OutlinedButton(onClick = { pdfOut.launch("vouchers_${LocalDate.now()}.pdf") }, shape = RoundedCornerShape(14.dp)) { Text("PDF للطباعة") }
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (list.isEmpty()) item { EmptyState(Icons.Rounded.ConfirmationNumber, "لا توجد كروت") }
                        items(list, key = { it.id }) { v ->
                            AppCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("\u200e" + v.code, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(v.profile + (if (v.price > 0) "  •  ${fmt(v.price)}" else "") + "  •  ${v.created}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                                    }
                                    if (v.sold) Pill("مباع", Ok) else TextButton(onClick = { db.markVoucherSold(v.id); rev++ }) { Text("بيع") }
                                    IconButton(onClick = { shareText(ctx, "كرت إنترنت\n${db.setting("network_name", "الشبكة")}\nالمستخدم: ${v.code}\nكلمة المرور: ${v.pass}") }) { Icon(Icons.Rounded.Share, "مشاركة") }
                                    IconButton(onClick = { del = v }) { Icon(Icons.Rounded.Delete, "حذف", tint = Bad) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (quick) {
        val groups = allV.filter { !it.sold }.groupBy { it.profile }
        AlertDialog(
            onDismissRequest = { quick = false },
            title = { Text("بيع سريع: اختر الباقة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (groups.isEmpty()) Text("لا توجد كروت غير مباعة. ولّد كروتاً أولاً.")
                    groups.forEach { (prof, l) ->
                        TextButton(onClick = {
                            val v = l.last()   // الأقدم أولاً
                            db.markVoucherSold(v.id); rev++; quick = false
                            sendMsg = SendMsg("كرت إنترنت: $prof",
                                "${db.setting("network_name", "الشبكة")}\nكرت إنترنت ($prof)\nالمستخدم: ${v.code}\nكلمة المرور: ${v.pass}" + (if (v.price > 0) "\nالسعر: ${fmt(v.price)} دينار" else ""), null)
                        }, modifier = Modifier.fillMaxWidth()) { Text("$prof  (${l.size} متوفر)" + (l.first().price.takeIf { it > 0 }?.let { "  •  ${fmt(it)}" } ?: "")) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { quick = false }) { Text("إغلاق") } },
        )
    }
    sendMsg?.let { m -> SendDialog(m, db) { sendMsg = null } }
    del?.let { v -> ConfirmDialog("حذف الكرت ${v.code} من القائمة هنا؟ (لا يُحذف من الراوتر)", "حذف", { del = null }) { db.deleteVoucher(v.id); del = null; rev++ } }
}

// ======================= رؤى ذكية =======================
@Composable
fun InsightsCard(db: Db) {
    if (db.isGenerator()) return
    val cs = MaterialTheme.colorScheme
    val ins = remember { db.insights() }
    SectionTitle("رؤى ذكية")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("تجديدات الأسبوع القادم: ${ins.weekCount} اشتراك ≈ ${fmt(ins.weekRevenue)} دينار", fontWeight = FontWeight.SemiBold)
            Text("خلال 30 يوماً: ${ins.monthCount} اشتراك ≈ ${fmt(ins.monthRevenue)} دينار", fontSize = 13.sp)
            ins.collectionRate?.let { Text("نسبة التحصيل هذا الشهر: $it%", fontSize = 13.sp, color = if (it >= 80) Ok else Warn) }
            if (ins.atRisk.isNotEmpty()) {
                Text("معرّضون للفقدان: ${ins.atRisk.size} (انتهى اشتراكهم قبل 4 إلى 30 يوماً ولم يجددوا)", fontSize = 13.sp, color = Bad)
                Text(ins.atRisk.take(6).joinToString("، ") { it.name } + if (ins.atRisk.size > 6) "..." else "", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}
