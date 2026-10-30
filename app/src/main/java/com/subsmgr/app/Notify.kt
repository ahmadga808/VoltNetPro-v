package com.subsmgr.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * قناة إشعارات المشترك (وصل التجديد، إيصال الدفعة، تذكير الدين، تنبيه الانتهاء، الرسائل الجماعية).
 * تُضبط من الإعدادات وتحدد كل مكان في التطبيق يُرسل رسالة لمشترك.
 *  - sms    : SMS مباشر فقط (بدون فتح تطبيق الرسائل)
 *  - wa     : واتساب فقط (تُفتح المحادثة جاهزة ويضغط المستخدم إرسال)
 *  - sms_wa : SMS أولاً، فإذا تعذّر (لا صلاحية/فشل) فواتساب
 *  - wa_sms : واتساب أولاً، فإذا لم يكن مثبتاً فـ SMS
 * الإرسال التلقائي بالخلفية لا يعمل إلا عبر SMS لأن واتساب لا يسمح به.
 */
object Notify {
    const val SMS = "sms"
    const val WA = "wa"
    const val SMS_WA = "sms_wa"
    const val WA_SMS = "wa_sms"

    fun channel(db: Db): String = db.setting("notify_channel", WA_SMS).let { if (it in setOf(SMS, WA, SMS_WA, WA_SMS)) it else WA_SMS }
    fun setChannel(db: Db, v: String) = db.setSetting("notify_channel", v)
    fun usesSms(ch: String) = ch != WA
    fun order(ch: String): List<String> = when (ch) {
        SMS -> listOf(SMS)
        WA -> listOf(WA)
        SMS_WA -> listOf(SMS, WA)
        else -> listOf(WA, SMS)
    }

    fun buttonLabel(ch: String): String = when (ch) {
        SMS -> "إرسال SMS"
        WA -> "إرسال عبر واتساب"
        SMS_WA -> "إرسال (SMS ثم واتساب)"
        else -> "إرسال (واتساب ثم SMS)"
    }

    fun hasSmsPerm(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun waInstalled(ctx: Context): Boolean = listOf("com.whatsapp", "com.whatsapp.w4b").any {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
    }

    /** مدير SMS حسب الشريحة المختارة في الإعدادات (أو الافتراضية). */
    @Suppress("DEPRECATION")
    fun smsManager(ctx: Context, db: Db): android.telephony.SmsManager {
        val sub = db.setting("sms_sim", "-1").toIntOrNull() ?: -1
        if (sub >= 0) try { return android.telephony.SmsManager.getSmsManagerForSubscriptionId(sub) } catch (e: Exception) { }
        return if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java) else android.telephony.SmsManager.getDefault()
    }

    /** الشرائح الفعّالة (تحتاج READ_PHONE_STATE): (معرّف الاشتراك، الاسم). */
    fun simList(ctx: Context): List<Pair<Int, String>> = try {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) emptyList()
        else {
            val sm = ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
            @Suppress("MissingPermission")
            (sm.activeSubscriptionInfoList ?: emptyList()).map { Pair(it.subscriptionId, "الشريحة ${it.simSlotIndex + 1} - ${it.displayName}") }
        }
    } catch (e: Exception) { emptyList() }

    private fun statusIntent(ctx: Context, mode: String, id: Long, part: Int, delivered: Boolean): PendingIntent {
        val i = Intent(ctx, SmsStatusReceiver::class.java).setAction("com.subsmgr.app.SMS_STATUS")
            .setData(android.net.Uri.parse("smsstatus://$mode/$id/$part/${if (delivered) "d" else "s"}"))
            .putExtra("id", id).putExtra("mode", mode).putExtra("delivered", delivered)
        return PendingIntent.getBroadcast(ctx.applicationContext, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * يرسل SMS الآن مباشرة (بلا فتح تطبيق الرسائل) ويسجّله بحالة "pending"؛ تتحدث الحالة إلى
     * أُرسلت / سُلّمت / فشلت عبر SmsStatusReceiver. يرجع true عند تسليمه للنظام.
     */
    fun sendSmsNow(ctx: Context, db: Db, name: String, phone: String, text: String,
                   via: String = "sms", kind: String = "manual", retries: Int = 0): Boolean {
        val num = try { db.formatPhone(phone) } catch (e: Exception) { phone.filter { it.isDigit() || it == '+' } }
        if (num.isBlank()) return false
        var id = -1L
        return try {
            val sm = smsManager(ctx, db)
            val parts = sm.divideMessage(text)
            id = db.logMsg(via, name, phone, text, "pending", parts.size, kind, retries)
            val mode = db.currentMode()
            val sentI = ArrayList<PendingIntent>(); val delI = ArrayList<PendingIntent>()
            for (i in parts.indices) { sentI.add(statusIntent(ctx, mode, id, i, false)); delI.add(statusIntent(ctx, mode, id, i, true)) }
            sm.sendMultipartTextMessage(num, null, parts, sentI, delI)
            true
        } catch (e: Exception) { if (id > 0) db.setMsgStatus(id, "failed"); false }
    }

    /** يعيد إرسال رسالة SMS فاشلة. */
    fun retry(ctx: Context, db: Db, m: MsgLog): Boolean {
        if (!hasSmsPerm(ctx)) return false
        val ok = sendSmsNow(ctx, db, m.name, m.phone, m.body, if (m.via == "auto") "auto" else "sms", m.kind.ifEmpty { "manual" }, m.retries + 1)
        if (ok) db.setMsgStatus(m.id, "retried")
        return ok
    }

    /**
     * يرسل رسالة واحدة حسب القناة المختارة. إن لزمت صلاحية SMS لأول مرة يستدعي askPerm
     * ثم يُعاد الاستدعاء بـ permAsked=true بعد قرار المستخدم.
     */
    fun deliver(ctx: Context, db: Db, name: String, phone: String, text: String, permAsked: Boolean, askPerm: () -> Unit) {
        if (phone.isBlank()) { toast(ctx, "لا يوجد رقم هاتف لهذا المشترك"); return }
        val ord = order(channel(db))
        for ((i, r) in ord.withIndex()) {
            val last = i == ord.lastIndex
            if (r == SMS) {
                if (!hasSmsPerm(ctx)) {
                    if (!permAsked) { askPerm(); return }
                    if (last) { toast(ctx, "لم تُمنح صلاحية إرسال SMS"); return }
                    continue
                }
                if (sendSmsNow(ctx, db, name, phone, text)) { toast(ctx, "أُرسلت SMS إلى $name"); return }
                if (last) { toast(ctx, "تعذّر إرسال SMS. تحقق من الشريحة والرصيد."); return }
            } else {
                if (!last && !waInstalled(ctx)) continue
                db.logMsg("wa", name, phone, text)
                openUrl(ctx, db.waUrl(phone, text))
                return
            }
        }
    }
}

/** يستقبل نتيجة إرسال/تسليم كل رسالة SMS ويحدّث سجل الرسائل. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id <= 0) return
        applySmsResult(ctx, intent.getStringExtra("mode") ?: "internet", id, intent.getBooleanExtra("delivered", false), resultCode == Activity.RESULT_OK)
    }
}

/** يُرجع دالة إرسال (الاسم، الرقم، النص) تطبّق قناة الإعدادات وتطلب صلاحية SMS عند الحاجة. */
@Composable
fun rememberNotifier(db: Db): (String, String, String) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val p = pending; pending = null
        if (p != null) Notify.deliver(ctx, db, p.first, p.second, p.third, true) { }
    }
    return { name, phone, text ->
        Notify.deliver(ctx, db, name, phone, text, false) {
            pending = Triple(name, phone, text)
            launcher.launch(Manifest.permission.SEND_SMS)
        }
    }
}

/** بطاقة اختيار قناة الإشعارات في الإعدادات. */
@Composable
fun NotifyChannelCard(db: Db) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var ch by remember { mutableStateOf(Notify.channel(db)) }
    var granted by remember { mutableStateOf(Notify.hasSmsPerm(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var phoneOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) }
    val phonePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { phoneOk = it }
    var simSel by remember { mutableStateOf(db.setting("sms_sim", "-1")) }
    var price by remember { mutableStateOf(db.setting("sms_price", "0")) }
    val options = listOf(
        Triple(Notify.SMS, "SMS فقط", "يُرسل مباشرة بدون فتح تطبيق الرسائل"),
        Triple(Notify.WA, "واتساب فقط", "تُفتح المحادثة جاهزة وتضغط إرسال بنفسك"),
        Triple(Notify.SMS_WA, "SMS أولاً ثم واتساب", "إن تعذّر SMS (لا صلاحية أو فشل) يُفتح واتساب"),
        Triple(Notify.WA_SMS, "واتساب أولاً ثم SMS", "إن لم يكن واتساب مثبتاً يُرسل SMS مباشرة"),
    )
    SectionTitle("قناة إشعارات المشتركين")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("تُطبَّق على كل ما يصل المشترك: وصل التجديد، إيصال الدفعة، تذكير الدين، تنبيه الانتهاء، والرسائل الجماعية.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            options.forEach { (k, title, sub) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                        ch = k; Notify.setChannel(db, k)
                        if (Notify.usesSms(k) && !granted) perm.launch(Manifest.permission.SEND_SMS)
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = ch == k, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(sub, fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
            if (Notify.usesSms(ch) && !granted) OutlinedButton(onClick = { perm.launch(Manifest.permission.SEND_SMS) },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("السماح بإرسال SMS") }
            if (Notify.usesSms(ch)) {
                Text("الشريحة المستخدمة للإرسال", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                if (!phoneOk) OutlinedButton(onClick = { phonePerm.launch(Manifest.permission.READ_PHONE_STATE) },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("عرض الشرائح واختيار إحداها") }
                else {
                    val sims = remember(phoneOk) { Notify.simList(ctx) }
                    if (sims.size < 2) Text("لا توجد أكثر من شريحة فعّالة، تُستخدم الشريحة الافتراضية للرسائل.", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    else (listOf(Pair(-1, "تلقائية (الافتراضية للرسائل)")) + sims).forEach { (sid, label) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { simSel = sid.toString(); db.setSetting("sms_sim", sid.toString()) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = simSel == sid.toString(), onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
                val st = remember(ch, price) { db.smsStats() }
                val cost = (price.toLongOrNull() ?: 0L) * st.second
                Text("هذا الشهر: ${st.first} رسالة (${st.second} جزء)" + (if (st.third > 0) " • فشلت ${st.third}" else "") +
                    (if (cost > 0) " • التكلفة التقريبية ${fmt(cost)} دينار" else ""), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(price, { price = it.filter { c -> c.isDigit() }; db.setSetting("sms_price", price.ifEmpty { "0" }) },
                    label = { Text("سعر الجزء الواحد (دينار) لحساب التكلفة") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            }
            Text("رسائل SMS قد تُحتسب من رصيد خطك. الإرسال التلقائي بالخلفية يعمل عبر SMS فقط لأن واتساب لا يسمح به.",
                fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }
}
