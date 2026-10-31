package com.subsmgr.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val SIGNAL_WEAK_DBM = -75
private fun alertPrefs(ctx: Context) = ctx.getSharedPreferences("volt_telegram_alerts", Context.MODE_PRIVATE)

private fun telegramSend(ctx: Context, message: String): String {
    val p = alertPrefs(ctx)
    val token = p.getString("bot_token", "").orEmpty().trim()
    val chatId = p.getString("chat_id", "").orEmpty().trim()
    require(token.isNotBlank() && chatId.isNotBlank()) { "أدخل Bot Token و Chat ID أولاً." }
    val body = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&text=${URLEncoder.encode(message, "UTF-8")}&disable_web_page_preview=true"
    val conn = (URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection)
    return try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299 || !response.contains("\"ok\":true")) error("رفض Telegram الطلب (HTTP $code). تحقق من التوكن وChat ID.")
        "تم إرسال رسالة الاختبار إلى Telegram."
    } finally { conn.disconnect() }
}

/** Telegram alerts are generated from real monitoring values already read by the app. */
@Composable
fun TelegramAssistantSection(signals: List<SignalRow>, states: Map<String, Boolean>, devices: List<MonDevice>, stats: RouterStats?, section: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val prefs = remember { alertPrefs(ctx) }
    var token by remember { mutableStateOf(prefs.getString("bot_token", "").orEmpty()) }
    var chat by remember { mutableStateOf(prefs.getString("chat_id", "").orEmpty()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Seed state on first run; notify only on subsequent changes to avoid an alert storm.
    LaunchedEffect(signals, states, devices) {
        if (signals.isEmpty() && states.isEmpty()) return@LaunchedEffect
        val p = alertPrefs(ctx)
        val initialized = p.getBoolean("baseline_initialized", false)
        val current = buildMap {
            devices.forEach { d -> states[d.ip]?.let { put("device:${d.ip}", if (it) "up" else "down") } }
            signals.forEach { r -> r.signal?.let { put("signal:${r.mac}", if (it < SIGNAL_WEAK_DBM) "weak:$it" else "ok:$it") } }
        }
        if (initialized && p.getString("bot_token", "").orEmpty().isNotBlank() && p.getString("chat_id", "").orEmpty().isNotBlank()) {
            val changes = mutableListOf<String>()
            current.forEach { (key, value) ->
                val old = p.getString("state:$key", null)
                if (old != null && old != value) {
                    when {
                        key.startsWith("device:") && value == "down" -> changes += "🔴 انقطاع جهاز: ${key.removePrefix("device:")}"
                        key.startsWith("device:") && value == "up" && old == "down" -> changes += "🟢 عاد الجهاز للعمل: ${key.removePrefix("device:")}"
                        key.startsWith("signal:") && value.startsWith("weak:") && !old.startsWith("weak:") -> changes += "⚠️ إشارة ضعيفة: ${key.removePrefix("signal:")} (${value.removePrefix("weak:")} dBm)"
                        key.startsWith("signal:") && !value.startsWith("weak:") && old.startsWith("weak:") -> changes += "🟢 تحسنت الإشارة: ${key.removePrefix("signal:")} (${value.substringAfter(':')} dBm)"
                    }
                }
            }
            if (changes.isNotEmpty()) {
                val msg = "VoltNet Pro — تنبيه الشبكة\n" + changes.joinToString("\n") + "\nالوقت: ${java.time.LocalDateTime.now()}"
                runCatching { withContext(Dispatchers.IO) { telegramSend(ctx, msg) } }
                    .onFailure { status = "تعذر إرسال التنبيه: ${it.message ?: "خطأ اتصال"}" }
            }
        }
        current.forEach { (k, v) -> p.edit().putString("state:$k", v).apply() }
        p.edit().putBoolean("baseline_initialized", true).apply()
    }

    if (section == null || section == "telegram") {
    SectionTitle("تنبيهات Telegram")
    Text("أدخل بيانات بوت Telegram. تُرسل التنبيهات عند اكتشاف تغيّر الحالة أثناء تحديث بيانات مراقبة الشبكة؛ هذه النسخة لا تراقب في الخلفية بعد إغلاق الشاشة.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    OutlinedTextField(token, { token = it }, label = { Text("Telegram Bot Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(chat, { chat = it }, label = { Text("Chat ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            if (token.isBlank() || chat.isBlank()) { status = "أدخل Bot Token وChat ID." }
            else {
                prefs.edit().putString("bot_token", token.trim()).putString("chat_id", chat.trim()).apply()
                status = "حُفظت الإعدادات على هذا الجهاز."
            }
        }, modifier = Modifier.weight(1f)) { Text("حفظ الإعدادات") }
        OutlinedButton(enabled = !busy, onClick = {
            prefs.edit().putString("bot_token", token.trim()).putString("chat_id", chat.trim()).apply()
            busy = true; status = "جارٍ إرسال الاختبار…"
            scope.launch {
                status = runCatching { withContext(Dispatchers.IO) { telegramSend(ctx, "✅ اختبار VoltNet Pro\nتم التحقق من إرسال رسالة Telegram.") } }.getOrElse { "فشل الاختبار: ${it.message ?: "خطأ غير معروف"}" }
                busy = false
            }
        }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(5.dp)); Text("اختبار") }
    }
    if (status.isNotBlank()) Text(status, color = if (status.contains("فشل") || status.contains("تعذر")) cs.error else cs.primary, fontSize = 12.sp)
    Text("تنبيه أمني: Bot Token سرّي؛ لا تشاركه مع أحد. يُحفظ محلياً في بيانات التطبيق. التنبيه يتطلب اتصال إنترنت وقت الإرسال.", fontSize = 11.sp, color = cs.onSurfaceVariant)

    }
    if (section == null || section == "assistant") {
    SectionTitle("المساعد الذكي للشبكة")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Icon(Icons.Rounded.Psychology, null, tint = cs.primary); Spacer(Modifier.width(8.dp)); Text("مساعد تشخيص محلي", fontWeight = FontWeight.Bold) }
            Text("يحلل القراءات الموجودة في التطبيق دون إرسال بيانات المشتركين إلى خدمة خارجية أو الحاجة إلى مفتاح API مدفوع.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            val weak = signals.filter { it.signal != null && it.signal!! < SIGNAL_WEAK_DBM }
            val down = devices.filter { states[it.ip] == false }
            val advice = buildList {
                if (stats == null) add("اضغط «تحديث حالة الشبكة» أولاً للحصول على قراءة حديثة من الراوتر.")
                stats?.let {
                    if (it.cpu >= 85) add("استخدام المعالج ${it.cpu}% مرتفع؛ افحص العمليات والجلسات والتحميل على الراوتر.")
                    if (it.memPct >= 85) add("استخدام الذاكرة ${it.memPct}% مرتفع؛ راجع استهلاك الذاكرة وسجل الراوتر.")
                    if (it.pppActive == 0 && it.hsActive == 0) add("لم تُقرأ جلسات PPPoE أو HotSpot نشطة؛ تحقق من إعدادات إدارة المشتركين وصحة الاتصال بالراوتر.")
                }
                if (down.isNotEmpty()) add("أجهزة غير مستجيبة (${down.size}): ${down.joinToString { it.name }}. تحقق من الطاقة والرابط وعنوان IP وVPN.")
                if (weak.isNotEmpty()) add("توجد ${weak.size} إشارة ضعيفة أقل من -75 dBm. افحص المحاذاة والتشويش والكابلات وخط النظر، ولا تعتمد على RSSI وحده.")
                if (signals.isEmpty()) add("لا توجد قراءات إشارة لاسلكية حالياً؛ قد لا يعرض MikroTik إشارات أجهزة airMAX المستقلة. افحص جهاز Ubiquiti أو نظام إدارته.")
                if (isEmpty()) add("لا تظهر مشكلة واضحة ضمن القراءات المتاحة. هذا فحص إرشادي وليس تشخيصاً نهائياً.")
            }
            advice.forEach { Text("• $it", fontSize = 13.sp) }
            Text("هذا مساعد قائم على قواعد تشخيصية محلية، وليس نموذج محادثة سحابياً؛ توصياته تعتمد فقط على البيانات التي قرأها التطبيق.", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }
    }
}
