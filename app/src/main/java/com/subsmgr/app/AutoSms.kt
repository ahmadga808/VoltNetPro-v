package com.subsmgr.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class AutoSmsWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val c = applicationContext
        return try {
            val db = Db(c)
            if (AutoSms.active(db)) { AutoSms.run(c, db); AutoSms.dailyReport(c, db) }
            Result.success()
        } catch (e: Exception) { Result.success() }
    }
}

/**
 * رسائل SMS تلقائية بالخلفية: تذكير قبل الانتهاء، إشعار بعد الانتهاء، تذكير دين متكرر، ووصل بعد التجديد.
 * تُفحص كل 30 دقيقة وتُرسل كل نوع بعد الساعة التي يحددها المستخدم (0 = فور الانتهاء بعد منتصف الليل)،
 * ولا تتكرر لنفس الاشتراك. واتساب لا يسمح بالإرسال التلقائي، لذلك لا تعمل إلا عبر SMS.
 */
object AutoSms {
    class Plan(val sub: Sub, val kind: String, val text: String)

    private fun gp(ctx: Context) = ctx.getSharedPreferences("autosms", Context.MODE_PRIVATE)
    fun on(db: Db) = db.setting("auto_sms_on") == "1"
    /** القناة الحالية تسمح بـ SMS (أي ليست "واتساب فقط"). */
    fun allowed(db: Db) = Notify.usesSms(Notify.channel(db))
    fun active(db: Db) = on(db) && allowed(db)
    fun hasPerm(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun hour(db: Db, kind: String): Int =
        (db.setting("auto_${kind}_hour", if (kind == "expired") "0" else "10").toIntOrNull() ?: 10).coerceIn(0, 23)

    fun setOn(ctx: Context, db: Db, v: Boolean) {
        db.setSetting("auto_sms_on", if (v) "1" else "0")
        gp(ctx).edit().putBoolean("on", v).apply()
        schedule(ctx)
    }

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        wm.cancelUniqueWork("auto_sms")   // الجدولة القديمة (مرة يومياً الساعة 10)
        if (!gp(ctx).getBoolean("on", false)) { wm.cancelUniqueWork("auto_sms2"); return }
        val req = PeriodicWorkRequestBuilder<AutoSmsWorker>(30, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("auto_sms2", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun send(ctx: Context, db: Db, name: String, phone: String, text: String, kind: String = "auto"): Boolean =
        Notify.sendSmsNow(ctx, db, name, phone, text, "auto", kind)

    /** ساعات الهدوء: لا تُرسل الرسائل التلقائية المجدولة خلالها (تُؤجَّل إلى انتهائها). */
    fun inQuiet(db: Db, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (db.setting("quiet_on", "0") != "1") return false
        val f = (db.setting("quiet_from", "21").toIntOrNull() ?: 21).coerceIn(0, 23)
        val t = (db.setting("quiet_to", "8").toIntOrNull() ?: 8).coerceIn(0, 23)
        val h = now.hour
        return when { f == t -> false; f < t -> h in f until t; else -> h >= f || h < t }
    }

    private fun sentToday(db: Db): Int {
        val t = LocalDate.now().toString()
        return db.msgLog(1000).count { it.via == "auto" && it.ts.startsWith(t) }
    }

    private fun debtDue(db: Db, s: Sub, every: Long, today: LocalDate): Boolean {
        val last = db.setting("auto_debt_last_${s.id}")
        if (last.isEmpty()) return true
        return runCatching { !LocalDate.parse(last).plusDays(every).isAfter(today) }.getOrDefault(true)
    }

    /** ما سيُرسل الآن (بدون تكرار، وبحد أقصى يومي). ignoreHour=true للمعاينة والإرسال اليدوي. */
    fun plan(db: Db, ignoreHour: Boolean = false): List<Plan> {
        val days = (db.setting("auto_soon_days", "3").toLongOrNull() ?: 3L).coerceIn(1, 30)
        val every = (db.setting("auto_debt_days", "7").toLongOrNull() ?: 7L).coerceIn(1, 90)
        val limit = (db.setting("auto_limit", "30").toIntOrNull() ?: 30).coerceIn(1, 200)
        if (!ignoreHour && inQuiet(db)) return emptyList()
        val maxDebt = (db.setting("auto_debt_max", "4").toIntOrNull() ?: 4).coerceIn(0, 20)
        val nowH = LocalDateTime.now().hour
        fun due(kind: String) = ignoreHour || nowH >= hour(db, kind)
        val a = db.alerts(days)
        val today = LocalDate.now()
        val out = ArrayList<Plan>()
        fun done(kind: String, s: Sub) = db.setting("auto_${kind}_${s.id}") == (s.endDate ?: "")
        if (!db.isGenerator() && db.setting("auto_soon", "1") == "1" && due("soon"))
            a.expiring.filter { !it.phone.isNullOrBlank() && !it.noMsg && !done("soon", it) }.forEach { out.add(Plan(it, "soon", db.reminderText(it, "soon"))) }
        if (!db.isGenerator() && db.setting("auto_expired", "1") == "1" && due("expired"))
            a.expired.filter { s ->
                !s.phone.isNullOrBlank() && !s.noMsg && !done("expired", s) &&
                    runCatching { !LocalDate.parse(s.endDate).isBefore(today.minusDays(3)) }.getOrDefault(false)
            }.forEach { out.add(Plan(it, "expired", db.reminderText(it, "expired"))) }
        if (db.setting("auto_debt", "0") == "1" && due("debt")) {
            val planned = out.map { it.sub.id }.toSet()
            db.listSubs().filter {
                it.bal > 0 && !it.phone.isNullOrBlank() && !it.noMsg && it.id !in planned && !db.promiseActive(it) && debtDue(db, it, every, today)
            }.forEach {
                val cnt = db.setting("auto_debt_cnt_${it.id}", "0").toIntOrNull() ?: 0
                if (maxDebt == 0 || cnt < maxDebt)   // بعد بلوغ الحد الأقصى يتوقف التذكير التلقائي لهذا الدين
                    out.add(Plan(it, "debt", db.reminderText(it, when (cnt) { 0 -> "debt"; 1 -> "debt2"; else -> "debt3" })))
            }
        }
        return out.take((limit - sentToday(db)).coerceAtLeast(0))
    }

    private fun mark(db: Db, p: Plan) {
        if (p.kind == "debt") {
            db.setSetting("auto_debt_last_${p.sub.id}", LocalDate.now().toString())
            db.setSetting("auto_debt_cnt_${p.sub.id}", ((db.setting("auto_debt_cnt_${p.sub.id}", "0").toIntOrNull() ?: 0) + 1).toString())
        }
        else db.setSetting("auto_${p.kind}_${p.sub.id}", p.sub.endDate ?: "")
    }

    /** ينفّذ الإرسال الآن ويرجع ملخصاً. */
    fun run(ctx: Context, db: Db, ignoreHour: Boolean = false): String {
        if (!allowed(db)) return "قناة الإشعارات الحالية واتساب فقط، والإرسال التلقائي يحتاج SMS"
        if (!hasPerm(ctx)) return "صلاحية إرسال SMS غير ممنوحة"
        val retried = if (ignoreHour || !inQuiet(db)) retryFailed(ctx, db) else 0
        val plan = plan(db, ignoreHour)
        var sent = 0; var failed = 0
        plan.forEachIndexed { i, p ->
            if (send(ctx, db, p.sub.name, p.sub.phone ?: "", p.text, p.kind)) { mark(db, p); sent++ } else failed++
            if (i < plan.size - 1) Thread.sleep(2500)
        }
        if (plan.isEmpty() && retried == 0 && !ignoreHour) return db.setting("auto_last")
        val msg = LocalDate.now().toString() + ": أُرسلت $sent رسالة" + (if (failed > 0) "، فشلت $failed" else "") + (if (retried > 0) "، أُعيدت $retried فاشلة" else "")
        db.setSetting("auto_last", msg)
        return msg
    }

    /** يعيد محاولة الرسائل التلقائية الفاشلة (آخر يومين، حتى مرتين لكل رسالة). */
    private fun retryFailed(ctx: Context, db: Db): Int {
        val cutoff = LocalDate.now().minusDays(1).toString()
        val rows = db.failedMsgs(60).filter { it.via == "auto" && it.retries < 2 && it.ts >= cutoff }.take(20)
        var n = 0
        rows.forEachIndexed { i, m -> if (Notify.retry(ctx, db, m)) n++; if (i < rows.size - 1) Thread.sleep(2500) }
        return n
    }

    /** إشعار للمالك مرة يومياً بملخص نتائج الرسائل (في الساعة المحددة). */
    fun dailyReport(ctx: Context, db: Db) {
        if (db.setting("report_on", "1") != "1") return
        val h = (db.setting("report_hour", "20").toIntOrNull() ?: 20).coerceIn(0, 23)
        val today = LocalDate.now().toString()
        if (LocalDateTime.now().hour < h || db.setting("report_last") == today) return
        db.setSetting("report_last", today)
        val rows = db.msgLog(1000).filter { it.ts.startsWith(today) && (it.via == "auto" || it.via == "sms") }
        if (rows.isEmpty()) return
        val failed = rows.filter { it.status == "failed" }
        val text = "أُرسلت ${rows.size} رسالة SMS: سُلّمت ${rows.count { it.status == "delivered" }}" +
            (if (failed.isNotEmpty()) "، فشلت ${failed.size} (${failed.take(3).joinToString("، ") { it.name }})" else "") +
            "\nدين: ${rows.count { it.kind == "debt" }} • قبل الانتهاء: ${rows.count { it.kind == "soon" }} • بعد الانتهاء: ${rows.count { it.kind == "expired" }} • وصولات: ${rows.count { it.kind == "receipt" }}"
        DailyReminder.post(ctx, 1002, "msg_report", "تقرير الرسائل", "تقرير رسائل اليوم", text)
    }

    /** وصل SMS تلقائي بعد تجديد الاشتراك (فقط إن كانت القناة تسمح بـ SMS). */
    fun receipt(ctx: Context, db: Db, sid: Long) {
        try {
            if (!active(db) || db.setting("auto_receipt", "1") != "1" || !hasPerm(ctx)) return
            val s = db.getSub(sid) ?: return
            if (s.phone.isNullOrBlank() || s.noMsg) return
            send(ctx, db, s.name, s.phone, db.renewSlip(sid), "receipt")
        } catch (e: Exception) { }
    }
}

@Composable
fun AutoSmsCard(db: Db) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val gen = db.isGenerator()
    val allowed = AutoSms.allowed(db)
    var on by remember { mutableStateOf(AutoSms.on(db)) }
    var soon by remember { mutableStateOf(db.setting("auto_soon", "1") == "1") }
    var expired by remember { mutableStateOf(db.setting("auto_expired", "1") == "1") }
    var debt by remember { mutableStateOf(db.setting("auto_debt", "0") == "1") }
    var receipt by remember { mutableStateOf(db.setting("auto_receipt", "1") == "1") }
    var days by remember { mutableStateOf(db.setting("auto_soon_days", "3")) }
    var debtDays by remember { mutableStateOf(db.setting("auto_debt_days", "7")) }
    var hSoon by remember { mutableStateOf(db.setting("auto_soon_hour", "10")) }
    var hExp by remember { mutableStateOf(db.setting("auto_expired_hour", "0")) }
    var hDebt by remember { mutableStateOf(db.setting("auto_debt_hour", "10")) }
    var limit by remember { mutableStateOf(db.setting("auto_limit", "30")) }
    var debtMax by remember { mutableStateOf(db.setting("auto_debt_max", "4")) }
    var quietOn by remember { mutableStateOf(db.setting("quiet_on", "0") == "1") }
    var qFrom by remember { mutableStateOf(db.setting("quiet_from", "21")) }
    var qTo by remember { mutableStateOf(db.setting("quiet_to", "8")) }
    var repOn by remember { mutableStateOf(db.setting("report_on", "1") == "1") }
    var repHour by remember { mutableStateOf(db.setting("report_hour", "20")) }
    var granted by remember { mutableStateOf(AutoSms.hasPerm(ctx)) }
    var info by remember { mutableStateOf(db.setting("auto_last")) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)

    fun save() {
        db.setSetting("auto_soon", if (soon) "1" else "0")
        db.setSetting("auto_expired", if (expired) "1" else "0")
        db.setSetting("auto_debt", if (debt) "1" else "0")
        db.setSetting("auto_receipt", if (receipt) "1" else "0")
        db.setSetting("auto_soon_days", (days.toIntOrNull() ?: 3).coerceIn(1, 30).toString())
        db.setSetting("auto_debt_days", (debtDays.toIntOrNull() ?: 7).coerceIn(1, 90).toString())
        db.setSetting("auto_soon_hour", (hSoon.toIntOrNull() ?: 10).coerceIn(0, 23).toString())
        db.setSetting("auto_expired_hour", (hExp.toIntOrNull() ?: 0).coerceIn(0, 23).toString())
        db.setSetting("auto_debt_hour", (hDebt.toIntOrNull() ?: 10).coerceIn(0, 23).toString())
        db.setSetting("auto_limit", (limit.toIntOrNull() ?: 30).coerceIn(1, 200).toString())
        db.setSetting("auto_debt_max", (debtMax.toIntOrNull() ?: 4).coerceIn(0, 20).toString())
        db.setSetting("quiet_on", if (quietOn) "1" else "0")
        db.setSetting("quiet_from", (qFrom.toIntOrNull() ?: 21).coerceIn(0, 23).toString())
        db.setSetting("quiet_to", (qTo.toIntOrNull() ?: 8).coerceIn(0, 23).toString())
        db.setSetting("report_on", if (repOn) "1" else "0")
        db.setSetting("report_hour", (repHour.toIntOrNull() ?: 20).coerceIn(0, 23).toString())
    }

    SectionTitle("الرسائل التلقائية (SMS بالخلفية)")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تُرسل بدون تدخل منك: يفحص التطبيق كل 30 دقيقة ويرسل كل نوع بعد الساعة التي تحددها، ولا تتكرر لنفس الاشتراك. قد يتأخر الإرسال قليلاً بسبب توفير الطاقة في الهاتف.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            if (!allowed) Surface(shape = RoundedCornerShape(12.dp), color = cs.errorContainer) {
                Text("قناة الإشعارات الحالية \"واتساب فقط\" والإرسال التلقائي لا يعمل إلا عبر SMS. غيّر القناة من البطاقة أعلاه ليعمل.",
                    fontSize = 12.sp, color = cs.onErrorContainer, modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("تفعيل الإرسال التلقائي")
                Switch(checked = on, onCheckedChange = {
                    save(); on = it; AutoSms.setOn(ctx, db, it)
                    if (it && !granted) perm.launch(Manifest.permission.SEND_SMS)
                })
            }
            if (!granted) OutlinedButton(onClick = { perm.launch(Manifest.permission.SEND_SMS) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("السماح بإرسال SMS") }

            if (!gen) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(soon, { soon = it; save() }); Text("تذكير قبل انتهاء الاشتراك") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(days, { days = it; save() }, label = { Text("قبل الانتهاء بـ (يوم)") }, singleLine = true,
                        keyboardOptions = num, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                    OutlinedTextField(hSoon, { hSoon = it; save() }, label = { Text("الساعة (0-23)") }, singleLine = true,
                        keyboardOptions = num, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(expired, { expired = it; save() }); Text("إشعار بعد الانتهاء (آخر 3 أيام)") }
                OutlinedTextField(hExp, { hExp = it; save() }, label = { Text("الساعة (0-23)") }, singleLine = true, keyboardOptions = num,
                    supportingText = { Text("0 = فور الانتهاء (بعد منتصف الليل مباشرة)") },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            }

            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(debt, { debt = it; save() }); Text("تذكير بالدين (يتكرر حتى التسديد)") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(debtDays, { debtDays = it; save() }, label = { Text("كل كم يوم") }, singleLine = true,
                    keyboardOptions = num, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                OutlinedTextField(hDebt, { hDebt = it; save() }, label = { Text("الساعة (0-23)") }, singleLine = true,
                    keyboardOptions = num, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            }
            OutlinedTextField(debtMax, { debtMax = it; save() }, label = { Text("أقصى عدد تذكيرات للدين الواحد (0 = بلا حد)") }, singleLine = true,
                keyboardOptions = num, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            Text("التذكير الأول والثاني والأخير بنصوص مختلفة (تبويب القوالب)، ويتوقف بعد الحد الأقصى. لا يُرسل لمن له وعد بالدفع ساري، ويُستأنف بعد تاريخ الوعد.", fontSize = 11.sp, color = cs.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(receipt, { receipt = it; save() }); Text("وصل تلقائي بعد كل تجديد") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ساعات الهدوء (لا رسائل مجدولة)")
                Switch(checked = quietOn, onCheckedChange = { quietOn = it; save() })
            }
            if (quietOn) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(qFrom, { qFrom = it; save() }, label = { Text("من الساعة") }, singleLine = true, keyboardOptions = num, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                    OutlinedTextField(qTo, { qTo = it; save() }, label = { Text("إلى الساعة") }, singleLine = true, keyboardOptions = num, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                }
                Text("تُؤجَّل الرسائل المجدولة (ومنها إشعار الانتهاء الليلي) إلى نهاية الهدوء. وصل التجديد يُرسل فوراً.", fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("تقرير يومي بنتائج الإرسال (إشعار لك)")
                Switch(checked = repOn, onCheckedChange = { repOn = it; save() })
            }
            if (repOn) OutlinedTextField(repHour, { repHour = it; save() }, label = { Text("ساعة التقرير (0-23)") }, singleLine = true, keyboardOptions = num,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(limit, { limit = it; save() }, label = { Text("أقصى عدد رسائل في اليوم") }, singleLine = true,
                keyboardOptions = num, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    save()
                    val p = AutoSms.plan(db, ignoreHour = true)
                    fun lab(k: String) = when (k) { "soon" -> "قبل الانتهاء"; "expired" -> "بعد الانتهاء"; else -> "تذكير دين" }
                    info = if (p.isEmpty()) "لا توجد رسائل مستحقة الآن." else "المستحق الآن ${p.size} رسالة:\n" + p.take(6).joinToString("\n") { "• ${it.sub.name} (${lab(it.kind)})" } + if (p.size > 6) "\n..." else ""
                }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("معاينة") }
                Button(onClick = { save(); if (!granted) perm.launch(Manifest.permission.SEND_SMS) else confirm = true },
                    enabled = !busy && allowed, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("إرسال الآن") }
            }
            if (info.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant) {
                Text(info, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("إرسال الآن") },
        text = { Text("ستُرسل الرسائل المستحقة فوراً بغض النظر عن الساعة، وقد تُحتسب من رصيد خطك. جرّب المعاينة أولاً. متابعة؟") },
        confirmButton = {
            Button(onClick = {
                confirm = false; busy = true
                scope.launch { info = try { withContext(Dispatchers.IO) { AutoSms.run(ctx, db, ignoreHour = true) } } catch (e: Exception) { e.message ?: "خطأ" }; busy = false }
            }) { Text("إرسال") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } },
    )
}
