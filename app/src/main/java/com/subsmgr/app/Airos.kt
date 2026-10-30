package com.subsmgr.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class Sector(val name: String, val ip: String)

data class AirosStation(
    val mac: String, val name: String, val ip: String, val signal: Int?, val ccq: Int?,
    val tx: String, val rx: String, val distance: String, val uptime: Long?, val quality: Int?, val capacity: Int?,
)

data class AirosInfo(
    val model: String, val uptime: Long?, val essid: String, val channel: String, val quality: Int?, val capacity: Int?,
    val stations: List<AirosStation>,
)

private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k, "")
private fun JSONObject.i(k: String): Int? = if (isNull(k) || !has(k)) null else when (val v = opt(k)) {
    is Number -> v.toInt()
    is String -> v.toDoubleOrNull()?.toInt()
    else -> null
}

/**
 * دخول مباشر من الهاتف إلى أجهزة Ubiquiti airOS (السكترات) على عنوان IP الجهاز.
 * الأسلوب المعروف في airOS: POST إلى login.cgi (أو /api/auth في الإصدارات الحديثة) فيرجع cookie،
 * ثم GET على status.cgi و sta.cgi. لم يُجرَّب على جهاز حقيقي: إن اختلف إصدارك فأرسل لي ردّ الجهاز.
 * يجب أن يصل الهاتف إلى عنوان الجهاز (نفس الشبكة أو عبر نفق OpenVPN).
 */
object Airos {
    private fun p(ctx: Context) = ctx.getSharedPreferences("airos", Context.MODE_PRIVATE)

    fun user(ctx: Context) = Secure.dec(p(ctx).getString("user", "") ?: "")
    fun pass(ctx: Context) = Secure.dec(p(ctx).getString("pass", "") ?: "")
    fun saveCreds(ctx: Context, u: String, pw: String) { p(ctx).edit().putString("user", Secure.enc(u.trim())).putString("pass", Secure.enc(pw)).apply() }
    fun monitorOn(ctx: Context) = p(ctx).getBoolean("mon", true)
    fun setMonitor(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("mon", on).apply() }

    fun sectors(ctx: Context): List<Sector> {
        val a = try { JSONArray(p(ctx).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Sector(it.s("name"), it.s("ip")) }.filter { it.ip.isNotBlank() }
    }

    private fun saveSectors(ctx: Context, l: List<Sector>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("name", it.name).put("ip", it.ip)) }
        p(ctx).edit().putString("list", a.toString()).apply()
    }

    fun addSector(ctx: Context, name: String, ip: String) {
        val host = ip.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        require(host.isNotEmpty()) { "أدخل عنوان السكتر" }
        val l = sectors(ctx).filter { it.ip != host }
        saveSectors(ctx, l + Sector(name.trim().ifEmpty { host }, host))
        schedule(ctx)
    }

    fun removeSector(ctx: Context, ip: String) { saveSectors(ctx, sectors(ctx).filter { it.ip != ip }) }

    // ربط MAC الصحن بمشترك (للصحون بوضع Bridge)
    fun bindings(ctx: Context): Map<String, String> {
        val o = try { JSONObject(p(ctx).getString("bind", "{}")) } catch (e: Exception) { JSONObject() }
        return o.keys().asSequence().associateWith { o.s(it) }
    }
    fun bind(ctx: Context, mac: String, subName: String?) {
        val o = try { JSONObject(p(ctx).getString("bind", "{}")) } catch (e: Exception) { JSONObject() }
        if (subName == null) o.remove(mac.uppercase()) else o.put(mac.uppercase(), subName)
        p(ctx).edit().putString("bind", o.toString()).apply()
    }

    // ---------- الشبكة ----------
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            // أجهزة airOS تستعمل غالباً شهادة ذاتية؛ نقبلها لهذا الاتصال فقط (عنوان أضفته بنفسك).
            val sc = SSLContext.getInstance("TLS"); sc.init(null, trustAll, SecureRandom())
            c.sslSocketFactory = sc.socketFactory
            c.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        c.connectTimeout = 6000; c.readTimeout = 10000
        c.instanceFollowRedirects = false
        return c
    }

    private fun cookies(c: HttpURLConnection): List<String> =
        (c.headerFields["Set-Cookie"] ?: c.headerFields["set-cookie"] ?: emptyList()).map { it.substringBefore(';') }

    private fun login(base: String, user: String, pass: String): String {
        // 1) الطريقة الكلاسيكية login.cgi
        val b = "----vnp" + System.currentTimeMillis()
        fun part(n: String, v: String) = "--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n"
        val body = part("username", user) + part("password", pass) + part("uri", "/") + "--$b--\r\n"
        val c = open("$base/login.cgi")
        val ck = try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code == 200 && cookies(c).isNotEmpty()) {
                // 200 مع صفحة تسجيل الدخول نفسها = بيانات خاطئة في بعض الإصدارات
                val txt = (c.inputStream.bufferedReader().use { it.readText() })
                if (txt.contains("login.cgi", true) && txt.contains("password", true)) throw SasException("اسم المستخدم أو كلمة مرور الصحن غير صحيحة")
            }
            Pair(code, cookies(c))
        } finally { c.disconnect() }
        if ((ck.first == 302 || ck.first == 200) && ck.second.isNotEmpty()) return ck.second.joinToString("; ")
        // 2) إصدارات airOS 8.7+ : /api/auth بصيغة JSON
        val c2 = open("$base/api/auth")
        try {
            c2.requestMethod = "POST"; c2.doOutput = true
            c2.setRequestProperty("Content-Type", "application/json")
            c2.outputStream.use { it.write(JSONObject().put("username", user).put("password", pass).toString().toByteArray()) }
            val code = c2.responseCode
            val k = cookies(c2)
            if (code in 200..399 && k.isNotEmpty()) return k.joinToString("; ")
            if (code == 401 || code == 403 || ck.first == 200) throw SasException("اسم المستخدم أو كلمة مرور الصحن غير صحيحة")
        } finally { c2.disconnect() }
        throw SasException("تعذّر تسجيل الدخول إلى الجهاز (رمز ${ck.first})")
    }

    private fun get(url: String, cookie: String): String {
        val c = open(url)
        try {
            c.setRequestProperty("Cookie", cookie)
            val code = c.responseCode
            if (code == 302 || code == 401 || code == 403) throw SasException("رفض الجهاز الطلب ($code). تحقق من صلاحيات الحساب")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** يقرأ السكتر. يجرّب https ثم http. */
    fun fetch(ctx: Context, ip: String): AirosInfo {
        val u = user(ctx); val pw = pass(ctx)
        if (u.isBlank()) throw SasException("أدخل مستخدم وكلمة مرور أجهزة ubnt أولاً")
        var last: Exception? = null
        for (scheme in listOf("https", "http")) {
            try { return fetchOn("$scheme://$ip", u, pw) }
            catch (e: SasException) { if (e.message?.contains("غير صحيحة") == true || e.message?.contains("رفض") == true) throw e; last = e }
            catch (e: IOException) { last = e }
            catch (e: Exception) { last = e }
        }
        throw SasException("ما وصلنا للسكتر $ip. لازم يكون الهاتف على نفس الشبكة أو متصلاً بنفق VPN. (${last?.message ?: ""})")
    }

    private fun fetchOn(base: String, u: String, pw: String): AirosInfo {
        val ck = login(base, u, pw)
        val st = try { JSONObject(get("$base/status.cgi", ck)) } catch (e: org.json.JSONException) { JSONObject() }
        val staText = get("$base/sta.cgi", ck)
        val arr = try { JSONArray(staText) } catch (e: Exception) { throw SasException("رد غير متوقع من الجهاز (sta.cgi)") }
        val stations = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val am = o.optJSONObject("airmax")
            AirosStation(
                mac = o.s("mac").uppercase(), name = o.s("name").ifEmpty { o.optJSONObject("remote")?.s("hostname") ?: "" },
                ip = o.s("lastip"), signal = o.i("signal"), ccq = o.i("ccq"),
                tx = o.s("tx"), rx = o.s("rx"), distance = o.s("distance"),
                uptime = o.optLong("uptime", -1).takeIf { it >= 0 },
                quality = am?.i("quality"), capacity = am?.i("capacity"),
            )
        }.sortedWith(compareBy<AirosStation> { it.signal == null }.thenBy { it.signal ?: 0 })
        val host = st.optJSONObject("host"); val wl = st.optJSONObject("wireless"); val poll = wl?.optJSONObject("polling")
        return AirosInfo(
            model = host?.s("devmodel") ?: "", uptime = host?.optLong("uptime", -1)?.takeIf { it >= 0 },
            essid = wl?.s("essid") ?: "", channel = wl?.let { w -> w.s("frequency").ifEmpty { w.s("channel") } } ?: "",
            quality = poll?.i("quality"), capacity = poll?.i("capacity"), stations = stations,
        )
    }

    // ---------- المراقبة التلقائية ----------
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (sectors(ctx).isEmpty() || !monitorOn(ctx)) { wm.cancelUniqueWork("airos_monitor"); return }
        val req = PeriodicWorkRequestBuilder<AirosWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("airos_monitor", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** يفحص كل السكترات ويرجع التنبيهات. أول فحص لكل سكتر يسجّل الحالة الطبيعية فقط. */
    fun checkAll(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val sp = p(ctx)
        for (s in sectors(ctx)) {
            val k = s.ip
            val seen = sp.getBoolean("seen_$k", false)
            val wasUp = sp.getBoolean("up_$k", true)
            val prevCnt = sp.getInt("cnt_$k", 0)
            val wasWeak = sp.getBoolean("weak_$k", false)
            val e = sp.edit()
            try {
                val info = fetch(ctx, s.ip)
                val cnt = info.stations.size
                val weak = info.stations.count { (it.signal ?: 0) < -75 && it.signal != null }
                val ccqs = info.stations.mapNotNull { it.ccq }
                val avgCcq = if (ccqs.isEmpty()) null else ccqs.average()
                val isWeak = cnt > 0 && weak >= maxOf(3, cnt / 5) || (avgCcq != null && avgCcq < 70)
                if (seen) {
                    if (!wasUp) out.add("رجع السكتر ${s.name} للعمل ($cnt صحن متصل)")
                    if (prevCnt >= 5 && cnt <= prevCnt * 0.6) out.add("نزل عدد الصحون المتصلة بالسكتر ${s.name} من $prevCnt إلى $cnt")
                    if (isWeak && !wasWeak) out.add("ضعف إشارة/CCQ في السكتر ${s.name}: $weak صحن إشارته ضعيفة" + (avgCcq?.let { ", متوسط CCQ ${it.toInt()}%" } ?: ""))
                }
                e.putBoolean("up_$k", true).putInt("cnt_$k", cnt).putBoolean("weak_$k", isWeak)
            } catch (ex: Exception) {
                if (seen && wasUp) out.add("انطفأ السكتر ${s.name} (${s.ip}) أو تعذّر الوصول إليه")
                e.putBoolean("up_$k", false)
            }
            e.putBoolean("seen_$k", true).apply()
        }
        return out
    }

    fun notify(ctx: Context, alerts: List<String>) {
        if (alerts.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("sector_alerts", "تنبيهات السكترات", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = alerts.joinToString("\n")
        nm.notify(2002, NotificationCompat.Builder(ctx, "sector_alerts").setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("تنبيه السكترات").setContentText(alerts.first()).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class AirosWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        try { Airos.notify(applicationContext, Airos.checkAll(applicationContext)) } catch (e: Exception) { }
        return Result.success()
    }
}

private fun airSigColor(s: Int?): Color = when { s == null -> Muted; s >= -65 -> Ok; s >= -75 -> Warn; else -> Bad }

private fun fmtUp(sec: Long?): String {
    if (sec == null) return ""
    val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
    return if (d > 0) "${d}ي ${h}س" else "${h}س ${m}د"
}

/** بطاقة الإعدادات: دخول ubnt + قائمة السكترات + المراقبة. */
@Composable
fun SectorsCard(db: Db) {
    if (db.isGenerator()) return
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var user by remember { mutableStateOf(Airos.user(ctx)) }
    var pass by remember { mutableStateOf(Airos.pass(ctx)) }
    var list by remember { mutableStateOf(Airos.sectors(ctx)) }
    var nm by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var mon by remember { mutableStateOf(Airos.monitorOn(ctx)) }
    var show by remember { mutableStateOf(false) }

    SectionTitle("أبراج وسكترات Ubiquiti (airOS)")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("يدخل التطبيق مباشرة من هاتفك إلى كل سكتر، ويعرض الصحون المتصلة بإشارتها وCCQ وسرعتها، ويراقب السكترات في الخلفية ويرسل تنبيه عند انطفاء سكتر أو ضعف الإشارة. " +
                "لازم يصل الهاتف إلى عنوان السكتر (نفس الشبكة أو نفق OpenVPN). الدخول مشفّر ومحفوظ على هاتفك فقط.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text("مستخدم ubnt") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nm, { nm = it }, label = { Text("اسم السكتر") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                OutlinedTextField(ip, { ip = it }, label = { Text("عنوان IP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            }
            Button(onClick = {
                Airos.saveCreds(ctx, user, pass)
                try { Airos.addSector(ctx, nm, ip); list = Airos.sectors(ctx); nm = ""; ip = "" } catch (e: Exception) { toast(ctx, e.message ?: "خطأ") }
            }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("إضافة سكتر وحفظ الدخول")
            }
            list.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SettingsInputAntenna, null, tint = cs.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { Text(s.name, fontWeight = FontWeight.Bold); Text("\u200e" + s.ip, fontSize = 11.sp, color = cs.onSurfaceVariant) }
                    IconButton(onClick = { Airos.removeSector(ctx, s.ip); list = Airos.sectors(ctx); Airos.schedule(ctx) }) { Icon(Icons.Rounded.Delete, "حذف", tint = Bad) }
                }
            }
            Button(onClick = { Airos.saveCreds(ctx, user, pass); show = true }, enabled = list.isNotEmpty(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text("عرض السكترات والصحون")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("مراقبة تلقائية وتنبيهات")
                    Text("كل 15 دقيقة في الخلفية. إن لم تصل التنبيهات فاسمح للتطبيق بالعمل في الخلفية من إعدادات البطارية.", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Switch(checked = mon, onCheckedChange = { mon = it; Airos.setMonitor(ctx, it); Airos.schedule(ctx) })
            }
        }
    }
    if (show) SectorsDialog(db) { show = false }
}

@Composable
fun SectorsDialog(db: Db, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sectors = remember { Airos.sectors(ctx) }
    var sel by remember { mutableStateOf(sectors.firstOrNull()) }
    var info by remember { mutableStateOf<AirosInfo?>(null) }
    var err by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var binds by remember { mutableStateOf(Airos.bindings(ctx)) }
    var bindFor by remember { mutableStateOf<AirosStation?>(null) }
    val subs = remember { db.listSubs() }

    LaunchedEffect(sel) {
        val s = sel ?: return@LaunchedEffect
        info = null; err = ""
        while (true) {
            try { info = withContext(Dispatchers.IO) { Airos.fetch(ctx, s.ip) }; err = "" }
            catch (e: Exception) { err = e.message ?: "خطأ" }
            delay(10000)
        }
    }

    val all = info?.stations ?: emptyList()
    val shown = all.filter { q.isBlank() || it.mac.contains(q, true) || it.name.contains(q, true) || it.ip.contains(q) || (binds[it.mac] ?: "").contains(q, true) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("السكترات والصحون", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sectors.forEach { s -> FilterChip(selected = sel == s, onClick = { sel = s }, label = { Text(s.name) }) }
                }
                if (info == null && err.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                if (err.isNotBlank()) Text(err, color = Bad, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                info?.let { i ->
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard(Icons.Rounded.SettingsInputAntenna, "الصحون", all.size.toString(), cs.primary, Modifier.weight(1f))
                        StatCard(Icons.Rounded.SignalCellularAlt, "ضعيفة", all.count { it.signal != null && it.signal < -75 }.toString(), Bad, Modifier.weight(1f))
                        if (i.quality != null) StatCard(Icons.Rounded.Speed, "airMax", "${i.quality}%", Ok, Modifier.weight(1f))
                    }
                    val meta = listOf(i.model, i.essid, i.channel.takeIf { it.isNotBlank() }?.let { "قناة $it" }, fmtUp(i.uptime).takeIf { it.isNotBlank() }?.let { "تشغيل $it" })
                        .filterNotNull().filter { it.isNotBlank() }.joinToString("  •  ")
                    if (meta.isNotBlank()) Text(meta, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp))
                }
                OutlinedTextField(q, { q = it }, placeholder = { Text("ابحث بالاسم أو MAC أو IP") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(12.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (info != null && shown.isEmpty()) item { EmptyState(Icons.Rounded.SettingsInputAntenna, "لا توجد صحون متصلة") }
                    items(shown, key = { it.mac }) { r ->
                        val col = airSigColor(r.signal)
                        AppCard(Modifier.fillMaxWidth(), onClick = { bindFor = r }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(binds[r.mac] ?: r.name.ifBlank { "صحن غير معروف" }, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text("\u200e" + r.mac + (if (r.ip.isNotBlank()) "  •  " + r.ip else ""), fontSize = 11.sp, color = cs.onSurfaceVariant)
                                    val d = listOf(r.rx.takeIf { it.isNotBlank() }?.let { "RX $it" }, r.tx.takeIf { it.isNotBlank() }?.let { "TX $it" },
                                        r.ccq?.let { "CCQ $it%" }, r.distance.takeIf { it.isNotBlank() }?.let { "مسافة $it م" }, fmtUp(r.uptime).takeIf { it.isNotBlank() }?.let { "متصل $it" })
                                        .filterNotNull().joinToString("  •  ")
                                    if (d.isNotBlank()) Text("\u200e" + d, fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(if (r.signal != null) "\u200e${r.signal} dBm" else "—", color = col, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    if (binds[r.mac] != null) Pill("مربوط", Ok)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    bindFor?.let { st ->
        var bq by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { bindFor = null }, confirmButton = { TextButton(onClick = { bindFor = null }) { Text("إغلاق") } },
            dismissButton = if (binds[st.mac] != null) ({ TextButton(onClick = { Airos.bind(ctx, st.mac, null); binds = Airos.bindings(ctx); bindFor = null }) { Text("إلغاء الربط", color = Bad) } }) else null,
            title = { Text("ربط الصحن بمشترك") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\u200e" + st.mac, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    OutlinedTextField(bq, { bq = it }, placeholder = { Text("ابحث عن المشترك") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    subs.filter { bq.isNotBlank() && it.name.contains(bq, true) }.take(6).forEach { sb ->
                        TextButton(onClick = { Airos.bind(ctx, st.mac, sb.name); binds = Airos.bindings(ctx); bindFor = null }, modifier = Modifier.fillMaxWidth()) { Text(sb.name) }
                    }
                }
            })
    }
}
