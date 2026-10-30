package com.subsmgr.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class RouterCfg(
    val host: String, val port: Int, val user: String, val pass: String, val type: String, val ssl: Boolean,
    /** بصمة شهادة الراوتر المحفوظة (ثقة عند أول اتصال)، وتُستدعى onPin لحفظها أول مرة. */
    val fp: String = "", val onPin: ((String) -> Unit)? = null,
)
/** الحالة المطلوبة لحساب على الراوتر: تشغيل أو إيقاف، وبروفايل السرعة (اختياري). */
data class RTarget(val user: String, val enable: Boolean, val profile: String?)
data class RSyncResult(val total: Int, val enabled: Int, val disabled: Int, val profiled: Int, val missing: List<String>, val errors: List<String>)

data class RouterStats(
    val identity: String, val board: String, val version: String, val uptime: String, val cpu: Int, val memPct: Int,
    val pppActive: Int?, val hsActive: Int?, val hsUsers: Int?, val rxBps: Long?, val txBps: Long?,
)
data class MonDevice(val name: String, val ip: String)
data class RouterInterface(val id: String, val name: String, val type: String, val disabled: Boolean, val running: Boolean, val comment: String) {
    val wireless: Boolean get() = type.contains("wlan", true) || type.contains("wifi", true)
}
/** عميل لاسلكي متصل بسكتر/نقطة وصول: إشارة الصحن كما يراها الراوتر. user = اسم حساب PPPoE إن طابق. */
data class SignalRow(
    val iface: String, val mac: String, val signal: Int?, val txRate: String, val rxRate: String,
    val ccq: String, val uptime: String, val ip: String, val label: String, val user: String?,
)

class RouterException(msg: String) : Exception(msg)

/** عميل بسيط لواجهة MikroTik RouterOS API (المنفذ 8728، أو 8729 مع SSL). يعمل مع الإصدارين 6 و7. */
class RouterApi(private val cfg: RouterCfg) : Closeable {
    class Reply(val rows: List<Map<String, String>>, val done: Map<String, String>)

    private val sock: Socket
    private val inp: InputStream
    private val out: OutputStream

    init {
        try {
            val raw = Socket()
            raw.connect(InetSocketAddress(cfg.host, cfg.port), 8000)
            raw.soTimeout = 15000
            sock = if (cfg.ssl) {
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                    override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {
                        val cert = c?.firstOrNull() ?: throw java.security.cert.CertificateException("لا توجد شهادة")
                        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
                        if (cfg.fp.isEmpty()) cfg.onPin?.invoke(fp)
                        else if (cfg.fp != fp) throw java.security.cert.CertificateException("تغيّرت شهادة الراوتر! قد يكون الاتصال مخترَقاً. إن غيّرتها بنفسك فاضغط إعادة تعيين البصمة.")
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, arrayOf<TrustManager>(tm), SecureRandom())
                val s = sc.socketFactory.createSocket(raw, cfg.host, cfg.port, true) as SSLSocket
                s.startHandshake()
                s
            } else raw
            inp = BufferedInputStream(sock.getInputStream())
            out = BufferedOutputStream(sock.getOutputStream())
        } catch (e: IOException) {
            val cert = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is java.security.cert.CertificateException }?.message
            throw RouterException(cert ?: "تعذّر الاتصال بالراوتر (${cfg.host}:${cfg.port}). تأكد من العنوان، وأن خدمة API مفعّلة، وأن هاتفك على نفس الشبكة أو عبر VPN.")
        }
    }

    private fun writeLen(len: Int) {
        when {
            len < 0x80 -> out.write(len)
            len < 0x4000 -> { val l = len or 0x8000; out.write(l shr 8); out.write(l and 0xFF) }
            len < 0x200000 -> { val l = len or 0xC00000; out.write(l shr 16); out.write((l shr 8) and 0xFF); out.write(l and 0xFF) }
            len < 0x10000000 -> {
                val l = len or 0xE0000000.toInt()
                out.write((l ushr 24) and 0xFF); out.write((l shr 16) and 0xFF); out.write((l shr 8) and 0xFF); out.write(l and 0xFF)
            }
            else -> { out.write(0xF0); out.write((len ushr 24) and 0xFF); out.write((len shr 16) and 0xFF); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
    }

    private fun writeWord(w: String) {
        val b = w.toByteArray(Charsets.UTF_8)
        writeLen(b.size)
        out.write(b)
    }

    private fun rd(): Int {
        val b = inp.read()
        if (b < 0) throw RouterException("انقطع الاتصال بالراوتر")
        return b
    }

    private fun readLen(): Int {
        val c = rd()
        return when {
            (c and 0x80) == 0 -> c
            (c and 0xC0) == 0x80 -> { val b1 = rd(); ((c and 0x3F) shl 8) or b1 }
            (c and 0xE0) == 0xC0 -> { val b1 = rd(); val b2 = rd(); ((c and 0x1F) shl 16) or (b1 shl 8) or b2 }
            (c and 0xF0) == 0xE0 -> { val b1 = rd(); val b2 = rd(); val b3 = rd(); ((c and 0x0F) shl 24) or (b1 shl 16) or (b2 shl 8) or b3 }
            else -> { val b1 = rd(); val b2 = rd(); val b3 = rd(); val b4 = rd(); (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4 }
        }
    }

    private fun readWord(): String {
        val len = readLen()
        if (len == 0) return ""
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) throw RouterException("انقطع الاتصال بالراوتر")
            off += n
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun readSentence(): List<String> {
        val w = ArrayList<String>()
        while (true) {
            val x = readWord()
            if (x.isEmpty()) break
            w.add(x)
        }
        return w
    }

    /** يرسل أمراً ويقرأ الردود حتى !done. يرمي RouterException عند !trap. */
    fun talk(words: List<String>): Reply {
        try {
            words.forEach { writeWord(it) }
            out.write(0)
            out.flush()
            val rows = ArrayList<Map<String, String>>()
            var done: Map<String, String> = emptyMap()
            var trap: String? = null
            loop@ while (true) {
                val s = readSentence()
                if (s.isEmpty()) continue@loop
                val attrs = HashMap<String, String>()
                for (w in s.drop(1)) {
                    if (w.startsWith("=")) {
                        val i = w.indexOf('=', 1)
                        if (i > 0) attrs[w.substring(1, i)] = w.substring(i + 1) else attrs[w.substring(1)] = ""
                    }
                }
                when (s[0]) {
                    "!re" -> rows.add(attrs)
                    "!done" -> { done = attrs; break@loop }
                    "!trap" -> trap = attrs["message"] ?: "خطأ من الراوتر"
                    "!fatal" -> throw RouterException("خطأ من الراوتر: " + (s.getOrNull(1) ?: ""))
                }
            }
            if (trap != null) throw RouterException(trap)
            return Reply(rows, done)
        } catch (e: IOException) {
            throw RouterException("انقطع الاتصال بالراوتر: " + (e.message ?: ""))
        }
    }

    fun login() {
        val r = try { talk(listOf("/login", "=name=" + cfg.user, "=password=" + cfg.pass)) }
        catch (e: RouterException) { throw RouterException("اسم المستخدم أو كلمة المرور غير صحيحة (" + (e.message ?: "") + ")") }
        val ret = r.done["ret"]
        if (ret != null) {   // راوتر قديم (قبل 6.43): تحقق بالتحدي MD5
            val md = MessageDigest.getInstance("MD5")
            md.update(byteArrayOf(0))
            md.update(cfg.pass.toByteArray(Charsets.UTF_8))
            md.update(ret.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            val resp = md.digest().joinToString("") { "%02x".format(it) }
            talk(listOf("/login", "=name=" + cfg.user, "=response=00$resp"))
        }
    }

    override fun close() { try { sock.close() } catch (e: Exception) { } }
}

object Router {
    private fun p(ctx: Context) = ctx.getSharedPreferences("router", Context.MODE_PRIVATE)

    fun cfg(ctx: Context): RouterCfg? {
        val s = p(ctx)
        val host = s.getString("host", "") ?: ""
        if (host.isBlank()) return null
        val port = s.getInt("port", 8728)
        val key = "$host:$port"
        val fp = if (s.getString("fp_for", "") == key) s.getString("fp", "") ?: "" else ""
        return RouterCfg(host, port, s.getString("user", "") ?: "", Secure.dec(s.getString("pass", "") ?: ""),
            s.getString("type", "pppoe") ?: "pppoe", s.getBoolean("ssl", false), fp,
            { f -> s.edit().putString("fp", f).putString("fp_for", key).apply() })
    }

    fun resetPin(ctx: Context) { p(ctx).edit().remove("fp").remove("fp_for").apply() }

    fun migrate(ctx: Context) {
        val s = p(ctx)
        val v = s.getString("pass", "") ?: ""
        if (v.isNotEmpty() && !Secure.isEnc(v)) s.edit().putString("pass", Secure.enc(v)).apply()
    }

    fun save(ctx: Context, c: RouterCfg) {
        p(ctx).edit().putString("host", c.host).putInt("port", c.port).putString("user", c.user)
            .putString("pass", Secure.enc(c.pass)).putString("type", c.type).putBoolean("ssl", c.ssl).apply()
        scheduleMonitor(ctx)
    }

    fun autoOn(ctx: Context) = p(ctx).getBoolean("auto", false)
    fun enabled(ctx: Context) = autoOn(ctx) && cfg(ctx) != null
    fun grace(ctx: Context) = p(ctx).getInt("grace", 0)
    fun setGrace(ctx: Context, d: Int) { p(ctx).edit().putInt("grace", d.coerceIn(0, 60)).apply() }
    fun lastResult(ctx: Context): String = p(ctx).getString("last", "") ?: ""

    fun saveLast(ctx: Context, r: RSyncResult) {
        p(ctx).edit().putString("last", java.time.LocalDateTime.now().toString().take(16).replace('T', ' ') + "\n" + summary(r, false)).apply()
    }

    fun setAuto(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("auto", on).apply()
        if (on) schedule(ctx) else WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork("router_sync")
    }

    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        val req = PeriodicWorkRequestBuilder<RouterSyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("router_sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    // ---------- لوحة الراوتر ----------
    fun dashboard(c: RouterCfg, wanIf: String): RouterStats = guard {
        RouterApi(c).use { api ->
            api.login()
            val res = api.talk(listOf("/system/resource/print")).rows.firstOrNull() ?: emptyMap()
            val id = try { api.talk(listOf("/system/identity/print")).rows.firstOrNull()?.get("name") ?: "" } catch (e: RouterException) { "" }
            fun cnt(path: String): Int? = try { api.talk(listOf("$path/print", "=count-only=")).done["ret"]?.toIntOrNull() } catch (e: RouterException) { null }
            var rx: Long? = null
            var tx: Long? = null
            if (wanIf.isNotBlank()) {
                try {
                    val t = api.talk(listOf("/interface/monitor-traffic", "=interface=$wanIf", "=once=")).rows.firstOrNull()
                    rx = t?.get("rx-bits-per-second")?.toLongOrNull(); tx = t?.get("tx-bits-per-second")?.toLongOrNull()
                } catch (e: RouterException) { }
            }
            val total = res["total-memory"]?.toLongOrNull() ?: 0L
            val free = res["free-memory"]?.toLongOrNull() ?: 0L
            RouterStats(id, res["board-name"] ?: "", res["version"] ?: "", res["uptime"] ?: "", res["cpu-load"]?.toIntOrNull() ?: 0,
                if (total > 0) ((total - free) * 100 / total).toInt() else 0, cnt("/ppp/active"), cnt("/ip/hotspot/active"), cnt("/ip/hotspot/user"), rx, tx)
        }
    }

    fun interfaces(c: RouterCfg): List<String> = guard {
        RouterApi(c).use { api -> api.login(); api.talk(listOf("/interface/print", "=.proplist=name")).rows.mapNotNull { it["name"] } }
    }

    /** Read-only inventory for MikroTik hub/sector interfaces. */
    fun interfaceInventory(c: RouterCfg): List<RouterInterface> = guard {
        RouterApi(c).use { api ->
            api.login()
            api.talk(listOf("/interface/print", "=.proplist=.id,name,type,disabled,running,comment")).rows.mapNotNull { r ->
                val id = r[".id"].orEmpty()
                val name = r["name"].orEmpty()
                if (id.isBlank() || name.isBlank()) null else RouterInterface(
                    id, name, r["type"].orEmpty(), r["disabled"] == "true" || r["disabled"] == "yes",
                    r["running"] == "true" || r["running"] == "yes", r["comment"].orEmpty()
                )
            }.sortedBy { it.name.lowercase() }
        }
    }

    /** Change only wireless interfaces; refuses Ethernet/bridge/VLAN to reduce lockout risk. */
    fun setWirelessInterface(c: RouterCfg, iface: RouterInterface, enabled: Boolean): String = guard {
        if (!iface.wireless) throw RouterException("لأمان الشبكة، يسمح التطبيق بتشغيل/إيقاف واجهات Wi‑Fi فقط. لا يغيّر Ethernet أو Bridge أو VLAN.")
        RouterApi(c).use { api ->
            api.login()
            api.talk(listOf("/interface/set", "=.id=${iface.id}", "=disabled=${if (enabled) "no" else "yes"}"))
        }
        val verify = interfaceInventory(c).firstOrNull { it.name == iface.name }
            ?: throw RouterException("أُرسل الأمر لكن تعذّر التحقق من الواجهة")
        if (verify.disabled == enabled) throw RouterException("لم تتطابق الحالة بعد التغيير؛ تحقق من صلاحيات حساب الراوتر")
        "${iface.name}: ${if (enabled) "تم التشغيل" else "تم الإيقاف"}، والحالة مؤكدة من الراوتر"
    }
    fun wanIf(ctx: Context): String = p(ctx).getString("wan_if", "") ?: ""
    fun setWanIf(ctx: Context, n: String) { p(ctx).edit().putString("wan_if", n).apply() }

    // ---------- مراقبة الأجهزة والسكترات ----------
    fun monList(ctx: Context): List<MonDevice> = try {
        val a = org.json.JSONArray(p(ctx).getString("mon_json", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { MonDevice(it.optString("name"), it.optString("ip")) }
    } catch (e: Exception) { emptyList() }

    fun monSave(ctx: Context, l: List<MonDevice>) {
        val a = org.json.JSONArray()
        l.forEach { a.put(org.json.JSONObject().put("name", it.name).put("ip", it.ip)) }
        p(ctx).edit().putString("mon_json", a.toString()).apply()
        scheduleMonitor(ctx)
    }

    fun monState(ctx: Context): Map<String, Boolean> = try {
        val o = org.json.JSONObject(p(ctx).getString("mon_state", "{}"))
        o.keys().asSequence().associateWith { o.getBoolean(it) }
    } catch (e: Exception) { emptyMap() }

    fun monSaveState(ctx: Context, m: Map<String, Boolean>) {
        val o = org.json.JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        p(ctx).edit().putString("mon_state", o.toString()).putString("mon_at", java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')).apply()
    }
    fun monAt(ctx: Context): String = p(ctx).getString("mon_at", "") ?: ""

    /** يفحص الأجهزة بأمر ping من الراوتر نفسه (إن كان مربوطاً)، وإلا فحص مباشر من الهاتف. نتيجة فارغة = تعذّر الفحص. */
    fun monCheck(ctx: Context, list: List<MonDevice>): Map<String, Boolean> {
        val c = cfg(ctx)
        val out = HashMap<String, Boolean>()
        if (c != null) {
            try {
                RouterApi(c).use { api ->
                    api.login()
                    for (d in list) {
                        out[d.ip] = try {
                            api.talk(listOf("/ping", "=address=${d.ip}", "=count=3", "=interval=300ms")).rows.any { (it["received"]?.toIntOrNull() ?: 0) > 0 }
                        } catch (e: RouterException) { false }
                    }
                }
                return out
            } catch (e: RouterException) { return emptyMap() }
        }
        for (d in list) out[d.ip] = try { java.net.InetAddress.getByName(d.ip).isReachable(2000) } catch (e: Exception) { false }
        return out
    }

    fun scheduleMonitor(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        // Collect health samples even when no individual tower IPs have been configured.
        if (cfg(ctx) == null) { wm.cancelUniqueWork("monitor"); return }
        wm.enqueueUniquePeriodicWork("monitor", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES).build())
    }

    // ---------- إشارة الصحون والسكترات (جدول التسجيل اللاسلكي) ----------
    /** يقرأ جدول العملاء اللاسلكيين (RouterOS 6 wireless و7 wifi) ويطابق MAC مع جلسات PPPoE لمعرفة اسم الحساب. */
    fun signals(c: RouterCfg): List<SignalRow> = guard {
        RouterApi(c).use { api ->
            api.login()
            val byMac = HashMap<String, String>()
            try {
                api.talk(listOf("/ppp/active/print", "=.proplist=name,caller-id")).rows.forEach { r ->
                    val m = (r["caller-id"] ?: "").uppercase()
                    val n = r["name"] ?: ""
                    if (m.isNotEmpty() && n.isNotEmpty()) byMac[m] = n
                }
            } catch (e: RouterException) { }
            val rows = ArrayList<Map<String, String>>()
            var any = false
            for (path in listOf("/interface/wireless/registration-table/print", "/interface/wifi/registration-table/print")) {
                try { rows.addAll(api.talk(listOf(path)).rows); any = true } catch (e: RouterException) { }
            }
            if (!any) throw RouterException("لا توجد واجهة لاسلكية (wireless/wifi) على هذا الراوتر. اربط الراوتر الذي تعمل عليه نقاط الإرسال.")
            val num = Regex("-?\\d+")
            rows.map { r ->
                val sigText = r["signal-strength"] ?: r["signal"] ?: ""
                val mac = (r["mac-address"] ?: "").uppercase()
                SignalRow(
                    iface = r["interface"] ?: "", mac = mac, signal = num.find(sigText)?.value?.toIntOrNull(),
                    txRate = r["tx-rate"] ?: "", rxRate = r["rx-rate"] ?: "", ccq = r["tx-ccq"] ?: "",
                    uptime = r["uptime"] ?: "", ip = r["last-ip"] ?: "", label = r["radio-name"] ?: r["comment"] ?: "",
                    user = byMac[mac],
                )
            }.sortedWith(compareBy<SignalRow> { it.signal == null }.thenBy { it.signal ?: 0 })
        }
    }

    // ---------- تحكم يدوي فوري بحساب مشترك ----------
    /** يوقف أو يشغّل حساب المشترك على الراوتر فوراً (عند الإيقاف تُقطع جلسته). المزامنة التلقائية قد تعيد الحالة حسب الاشتراك. */
    fun setUserEnabled(c: RouterCfg, user: String, enable: Boolean): String = guard {
        RouterApi(c).use { api ->
            api.login()
            val b = base(c)
            val row = api.talk(listOf("$b/print", "=.proplist=.id,name", "?name=$user")).rows.firstOrNull()
                ?: throw RouterException("الحساب $user غير موجود على الراوتر")
            val id = row[".id"] ?: throw RouterException("تعذّر تحديد الحساب $user")
            api.talk(listOf("$b/set", "=.id=$id", "=disabled=" + if (enable) "no" else "yes"))
            if (!enable) kick(api, c, user)
            if (enable) "تم تشغيل الحساب $user على الراوتر" else "تم إيقاف الحساب $user وقطع جلسته"
        }
    }

    /** يقطع الجلسة الحالية فقط (يعيد المشترك الاتصال تلقائياً). */
    fun disconnectUser(c: RouterCfg, user: String): String = guard {
        RouterApi(c).use { api ->
            api.login()
            kick(api, c, user)
            "تم فصل جلسة $user، سيعيد الاتصال خلال لحظات"
        }
    }

    // ---------- المتصلون الآن ----------
    val online = mutableStateOf<Set<String>>(emptySet())
    val onlineAt = mutableStateOf(0L)

    fun refreshOnline(ctx: Context) {
        val c = cfg(ctx) ?: return
        val names = guard {
            RouterApi(c).use { api ->
                api.login()
                if (c.type == "hotspot") api.talk(listOf("/ip/hotspot/active/print", "=.proplist=user")).rows.mapNotNull { it["user"] }
                else api.talk(listOf("/ppp/active/print", "=.proplist=name")).rows.mapNotNull { it["name"] }
            }
        }
        Handler(Looper.getMainLooper()).post { online.value = names.toSet(); onlineAt.value = System.currentTimeMillis() }
    }

    /** ينشئ حساب مشترك على الراوتر (PPPoE أو Hotspot حسب الإعداد). */
    fun createUser(c: RouterCfg, name: String, pass: String, profile: String?): String = guard {
        RouterApi(c).use { api ->
            api.login()
            val w = ArrayList<String>()
            if (c.type == "hotspot") { w.add("/ip/hotspot/user/add") } else { w.add("/ppp/secret/add"); w.add("=service=pppoe") }
            w.add("=name=$name"); w.add("=password=$pass")
            if (!profile.isNullOrBlank()) w.add("=profile=$profile")
            try { api.talk(w); "تم إنشاء الحساب $name على الراوتر بكلمة المرور $pass" }
            catch (e: RouterException) { if ((e.message ?: "").contains("already", true)) "الحساب $name موجود مسبقاً على الراوتر" else throw e }
        }
    }

    fun hotspotProfiles(c: RouterCfg): List<String> = guard {
        RouterApi(c).use { api ->
            api.login()
            api.talk(listOf("/ip/hotspot/user/profile/print", "=.proplist=name")).rows.mapNotNull { it["name"] }.sorted()
        }
    }

    /** ينشئ كروت Hotspot على الراوتر. يرجع (الرموز التي أُنشئت، الأخطاء). */
    fun createVouchers(c: RouterCfg, items: List<Pair<String, String>>, profile: String, limit: String, comment: String): Pair<List<String>, List<String>> = guard {
        RouterApi(c).use { api ->
            api.login()
            val ok = ArrayList<String>()
            val errs = ArrayList<String>()
            for ((u, p) in items) {
                val w = arrayListOf("/ip/hotspot/user/add", "=name=$u", "=password=$p", "=profile=$profile", "=comment=$comment")
                if (limit.isNotBlank()) w.add("=limit-uptime=$limit")
                try { api.talk(w); ok.add(u) } catch (e: RouterException) { errs.add("$u: ${e.message}"); if (errs.size >= 5 && ok.isEmpty()) break }
            }
            Pair(ok, errs)
        }
    }

    private fun base(c: RouterCfg) = if (c.type == "hotspot") "/ip/hotspot/user" else "/ppp/secret"

    fun summary(r: RSyncResult, dry: Boolean): String {
        val sb = StringBuilder()
        sb.append(if (dry) "المعاينة (لم يُنفَّذ شيء): " else "النتيجة: ")
        sb.append("تفعيل ${r.enabled}، إيقاف ${r.disabled}، تغيير بروفايل ${r.profiled}")
        sb.append("\nمشتركون مربوطون: ${r.total}")
        if (r.missing.isNotEmpty()) sb.append("\nغير موجودين على الراوتر (${r.missing.size}): " + r.missing.take(8).joinToString("، ") + if (r.missing.size > 8) "..." else "")
        if (r.errors.isNotEmpty()) sb.append("\nأخطاء (${r.errors.size}): " + r.errors.take(5).joinToString(" | "))
        return sb.toString()
    }

    private fun <T> guard(f: () -> T): T = try { f() } catch (e: RouterException) { throw e } catch (e: IOException) {
        throw RouterException("تعذّر الاتصال بالراوتر: " + (e.message ?: ""))
    }

    fun test(c: RouterCfg): String = guard {
        RouterApi(c).use { api ->
            api.login()
            val n = api.talk(listOf(base(c) + "/print", "=.proplist=name")).rows.size
            val id = try { api.talk(listOf("/system/identity/print")).rows.firstOrNull()?.get("name") ?: "" } catch (e: RouterException) { "" }
            "تم الاتصال بنجاح بالراوتر ${id}\nعدد حسابات ${if (c.type == "hotspot") "الهوتسبوت" else "PPPoE"}: $n"
        }
    }

    fun listUsers(c: RouterCfg): List<String> = guard {
        RouterApi(c).use { api ->
            api.login()
            api.talk(listOf(base(c) + "/print", "=.proplist=name")).rows.mapNotNull { it["name"] }.filter { it.isNotBlank() }.sorted()
        }
    }

    /** يطابق حالة الراوتر مع المطلوب: يفعّل/يوقف الحسابات ويبدّل البروفايل، ويقطع الجلسة الجارية عند الحاجة. */
    fun apply(c: RouterCfg, targets: List<RTarget>, dry: Boolean): RSyncResult = guard {
        RouterApi(c).use { api ->
            api.login()
            val b = base(c)
            val rows = api.talk(listOf("$b/print", "=.proplist=.id,name,disabled,profile")).rows
            val byName = HashMap<String, Map<String, String>>()
            rows.forEach { r -> r["name"]?.let { byName[it] = r } }
            var en = 0; var dis = 0; var prof = 0
            val missing = ArrayList<String>()
            val errors = ArrayList<String>()
            for (t in targets.distinctBy { it.user }) {
                val row = byName[t.user]
                if (row == null) { missing.add(t.user); continue }
                val id = row[".id"] ?: continue
                val isDisabled = row["disabled"] == "true"
                val needDisable = !t.enable && !isDisabled
                val needEnable = t.enable && isDisabled
                val needProfile = t.enable && t.profile != null && row["profile"] != t.profile
                if (!needDisable && !needEnable && !needProfile) continue
                if (needDisable) dis++
                if (needEnable) en++
                if (needProfile) prof++
                if (dry) continue
                try {
                    val set = ArrayList<String>()
                    set.add("$b/set"); set.add("=.id=$id")
                    if (needDisable) set.add("=disabled=yes")
                    if (needEnable) set.add("=disabled=no")
                    if (needProfile) set.add("=profile=" + t.profile)
                    api.talk(set)
                    if (needDisable || needProfile) kick(api, c, t.user)
                } catch (e: RouterException) {
                    errors.add(t.user + ": " + (e.message ?: ""))
                }
            }
            RSyncResult(targets.distinctBy { it.user }.size, en, dis, prof, missing, errors)
        }
    }

    /** يقطع الجلسة الجارية للمستخدم ليُطبَّق الإيقاف أو البروفايل الجديد فوراً. */
    private fun kick(api: RouterApi, c: RouterCfg, user: String) {
        val (listCmd, removeCmd, q) = if (c.type == "hotspot")
            Triple("/ip/hotspot/active/print", "/ip/hotspot/active/remove", "?user=$user")
        else Triple("/ppp/active/print", "/ppp/active/remove", "?name=$user")
        val active = api.talk(listOf(listCmd, "=.proplist=.id", q)).rows
        active.forEach { r -> r[".id"]?.let { api.talk(listOf(removeCmd, "=.id=$it")) } }
    }

    // ---------- المزامنة التلقائية بعد أي تغيير ----------
    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    private fun say(ctx: Context, msg: String) { main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } }

    fun kick(ctx: Context, db: Db) {
        val app = ctx.applicationContext
        if (!enabled(app) || db.isGenerator()) return
        val c = cfg(app) ?: return
        if (!running.compareAndSet(false, true)) { pending.set(true); return }
        val targets = db.routerTargets(grace(app))
        Thread {
            try {
                val r = apply(c, targets, false)
                saveLast(app, r)
                if (r.enabled + r.disabled + r.profiled > 0) say(app, "الراوتر: " + summary(r, false).lineSequence().first())
                else if (r.errors.isNotEmpty()) say(app, "الراوتر: " + r.errors.first())
            } catch (e: Exception) {
                val s = p(app)
                val now = System.currentTimeMillis()
                if (now - s.getLong("err_at", 0) > 10 * 60 * 1000) {
                    s.edit().putLong("err_at", now).apply()
                    say(app, e.message ?: "تعذّرت مزامنة الراوتر")
                }
            } finally {
                running.set(false)
                if (pending.getAndSet(false)) main.post { kick(app, Db(app)) }
            }
        }.start()
    }
}

class RouterSyncWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!Router.enabled(c)) return Result.success()
        return try {
            val db = Db(c)
            if (db.isGenerator()) return Result.success()
            val r = Router.apply(Router.cfg(c)!!, db.routerTargets(Router.grace(c)), false)
            Router.saveLast(c, r)
            Result.success()
        } catch (e: Exception) { Result.success() }
    }
}

class MonitorWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val c = applicationContext
        try {
            // Scheduled history sample: WorkManager runs periodically (Android may defer it).
            val cfg = Router.cfg(c)
            if (cfg != null) {
                val stats = Router.dashboard(cfg, Router.wanIf(c))
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val sample = listOf(stamp, stats.cpu.toString(), stats.memPct.toString(), stats.rxBps?.toString() ?: "", stats.txBps?.toString() ?: "", stats.pppActive?.toString() ?: "").joinToString("|")
                val prefs = c.getSharedPreferences("network_history_v1", Context.MODE_PRIVATE)
                val all = (prefs.getString("samples", "") ?: "").split("\n").filter { it.isNotBlank() } + sample
                // 96 samples ~= 24 hours at 15-minute cadence; Android controls actual timing.
                prefs.edit().putString("samples", all.takeLast(96).joinToString("\n")).apply()
            }
            val list = Router.monList(c)
            if (list.isNotEmpty()) {
                val res = Router.monCheck(c, list)
                if (res.isNotEmpty()) {
                    val prev = Router.monState(c)
                    for (d in list) {
                        val now = res[d.ip] ?: continue
                        val was = prev[d.ip]
                        if (was != null && was != now) notifyChange(c, d, now)
                    }
                    Router.monSaveState(c, prev + res)
                }
            }
        } catch (e: Exception) {
            // Failed reads do not erase history or create fake successful samples.
        }
        return Result.success()
    }

    private fun notifyChange(c: Context, d: MonDevice, up: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            android.app.NotificationChannel("monitor", "مراقبة الأجهزة", android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = android.app.PendingIntent.getActivity(c, 0, android.content.Intent(c, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(c, "monitor")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (up) "عاد للعمل: ${d.name}" else "توقف: ${d.name}")
            .setContentText("${d.ip}  •  " + if (up) "الجهاز يعمل الآن" else "لا يستجيب لفحص الراوتر")
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(2000 + (d.ip.hashCode() and 0xFFFF), n)
    }
}
