package com.subsmgr.app

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SasCfg(val url: String, val user: String, val pass: String)

data class SasUser(
    val id: Long, val username: String, val name: String, val phone: String, val profile: String,
    val expiration: String, val enabled: Boolean, val online: Boolean, val balance: String,
) {
    /** فعّال / منتهي / موقوف حسب بيانات اللوحة. */
    fun status(): String {
        if (!enabled) return "موقوف"
        val e = expiration.take(10)
        return if (e.length == 10 && e < today()) "منتهي" else "فعّال"
    }
}

class SasException(msg: String) : Exception(msg)

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
private fun JSONObject.flag(k: String): Boolean = when (val v = opt(k)) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> v == "1" || v.equals("true", true)
    else -> false
}

/**
 * عميل للوحة SAS Radius 4 (لوحة المدير). آلية الواجهة (موثّقة في مشاريع مفتوحة المصدر):
 * - العنوان: {لوحتك}/admin/api/index.php/api/{route}
 * - الطلبات POST وجسمها JSON على الشكل {"payload": "<AES>"} حيث الـAES بصيغة OpenSSL (Salted__ + MD5 KDF + AES-256-CBC)
 *   بعبارة المرور الثابتة التي تستعملها واجهة اللوحة نفسها.
 * - تسجيل الدخول: route=login يرجع token، ثم الترويسة Authorization: Bearer <token>.
 * - قائمة المشتركين: POST index/user بـ {page,count,sortBy,direction,search}.
 * المدعوم حالياً قراءة فقط (قائمة المشتركين والمتصلين). لا تُنفَّذ أي عمليات تفعيل/خصم رصيد على اللوحة.
 */
object Sas {
    /** آخر لقطة حالة قرئت بنجاح من SAS؛ لا تُملأ من جلسات MikroTik. */
    val usersSnapshot = androidx.compose.runtime.mutableStateOf<Map<String, SasUser>>(emptyMap())
    val lastUsersSync = androidx.compose.runtime.mutableStateOf<Long?>(null)
    val usersSyncError = androidx.compose.runtime.mutableStateOf<String?>(null)
    fun publishUsers(list: List<SasUser>) {
        usersSnapshot.value = list.associateBy { it.username.trim().lowercase() }
        lastUsersSync.value = System.currentTimeMillis()
        usersSyncError.value = null
    }
    private const val PASSPHRASE = "abcdefghijuklmno0123456789012345"
    private fun p(ctx: Context) = ctx.getSharedPreferences("sas", Context.MODE_PRIVATE)

    fun cfg(ctx: Context): SasCfg? {
        val s = p(ctx)
        val url = s.getString("url", "") ?: ""
        if (url.isBlank()) return null
        return SasCfg(url, s.getString("user", "") ?: "", Secure.dec(s.getString("pass", "") ?: ""))
    }

    fun save(ctx: Context, c: SasCfg) {
        p(ctx).edit().putString("url", c.url.trim()).putString("user", c.user.trim()).putString("pass", Secure.enc(c.pass)).apply()
    }

    fun clear(ctx: Context) { p(ctx).edit().clear().apply() }
    fun ucpUrl(ctx: Context): String = p(ctx).getString("ucp_url", "") ?: ""
    fun saveUcpUrl(ctx: Context, value: String) { p(ctx).edit().putString("ucp_url", value.trim()).apply() }

    fun migrate(ctx: Context) {
        val s = p(ctx)
        val v = s.getString("pass", "") ?: ""
        if (v.isNotEmpty() && !Secure.isEnc(v)) s.edit().putString("pass", Secure.enc(v)).apply()
    }

    /** يطبّع عنوان لوحة SAS. HTTPS هو الافتراضي؛ HTTP مسموح فقط لعناوين الإدارة المحلية الخاصة. */
    fun base(url: String): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        val parsed = try { URL(u) } catch (_: Exception) { throw SasException("عنوان لوحة SAS غير صحيح") }
        if (parsed.protocol.equals("http", true) && !isPrivateManagementHost(parsed.host)) {
            throw SasException("لأمان بيانات الدخول، استخدم HTTPS للوحات خارج الشبكة المحلية. لا ترسل كلمة المرور عبر HTTP مكشوف.")
        }
        val schemeEnd = u.indexOf("://") + 3
        val slash = u.indexOf('/', schemeEnd)
        if (slash > 0) u = u.substring(0, slash)
        return "$u/admin/api/index.php/api/"
    }

    private fun isPrivateManagementHost(hostRaw: String): Boolean {
        val host = hostRaw.lowercase().removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".local")) return true
        val octets = host.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254)
    }

    // ---------- التشفير (متوافق مع CryptoJS / OpenSSL) ----------
    private fun evpKdf(pass: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        var d = ByteArray(0)
        val out = ByteArrayOutputStream()
        while (out.size() < 48) {
            md.reset(); md.update(d); md.update(pass); md.update(salt)
            d = md.digest()
            out.write(d)
        }
        val all = out.toByteArray()
        return Pair(all.copyOfRange(0, 32), all.copyOfRange(32, 48))
    }

    fun encrypt(plain: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val (key, iv) = evpKdf(PASSPHRASE.toByteArray(Charsets.UTF_8), salt)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString("Salted__".toByteArray(Charsets.UTF_8) + salt + ct, Base64.NO_WRAP)
    }

    // ---------- الشبكة ----------
    private fun http(method: String, url: String, token: String?, body: String?): String {
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { throw SasException("عنوان اللوحة غير صحيح") }
        try {
            c.requestMethod = method
            c.connectTimeout = 12000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 401 || code == 403) throw SasException("بيانات الدخول مرفوضة من اللوحة ($code). تحقق من المستخدم وكلمة المرور وصلاحياته")
            if (code !in 200..399) throw SasException("اللوحة أعادت خطأ ($code). تأكد أن العنوان لوحة SAS4 وأن النفق (VPN) متصل إن لزم")
            return text
        } catch (e: SasException) {
            throw e
        } catch (e: IOException) {
            throw SasException("تعذّر الاتصال باللوحة: " + (e.message ?: "") + "\nإن كانت اللوحة داخل شبكة الشركة فاتصل بنفق OpenVPN أولاً.")
        } finally {
            c.disconnect()
        }
    }

    private fun json(text: String): JSONObject = try { JSONObject(text) } catch (e: Exception) {
        throw SasException("رد غير متوقع من اللوحة. تأكد أن العنوان لوحة SAS Radius 4 وليس صفحة أخرى")
    }

    private fun post(c: SasCfg, route: String, token: String?, payload: JSONObject): JSONObject {
        val body = JSONObject().put("payload", encrypt(payload.toString())).toString()
        return json(http("POST", base(c.url) + route, token, body))
    }

    fun login(c: SasCfg): String {
        if (c.user.isBlank() || c.pass.isEmpty()) throw SasException("أدخل مستخدم اللوحة وكلمة المرور")
        val j = post(c, "login", null, JSONObject().put("username", c.user).put("password", c.pass))
        val t = j.str("token")
        if (t.isEmpty()) throw SasException(j.str("message").ifEmpty { "فشل تسجيل الدخول: تحقق من المستخدم وكلمة المرور" })
        return t
    }

    /** يجلب كل المشتركين (صفحات من 100). الحد الأقصى maxPages صفحة لحماية الهاتف. */
    fun users(c: SasCfg, search: String = "", maxPages: Int = 60): List<SasUser> {
        val token = login(c)
        val out = ArrayList<SasUser>()
        var page = 1
        while (page <= maxPages) {
            val j = post(c, "index/user", token,
                JSONObject().put("page", page).put("count", 100).put("sortBy", "username").put("direction", "asc").put("search", search))
            val arr = j.optJSONArray("data") ?: throw SasException(j.str("message").ifEmpty { "لم تُرجع اللوحة قائمة المشتركين. قد لا تملك صلاحية عرض المشتركين" })
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val first = o.str("firstname"); val last = o.str("lastname")
                val username = o.str("username")
                if (username.isEmpty()) continue
                out.add(SasUser(
                    id = o.optLong("id", 0), username = username,
                    name = (first + " " + last).trim().ifEmpty { username },
                    phone = o.str("phone"),
                    profile = o.optJSONObject("profile_details")?.str("name") ?: "",
                    expiration = o.str("expiration"),
                    enabled = o.flag("enabled"), online = o.flag("online_status"), balance = o.str("balance"),
                ))
            }
            val last = j.optInt("last_page", 1)
            if (page >= last || arr.length() == 0) break
            page++
        }
        return out
    }

    fun test(c: SasCfg): String {
        val token = login(c)
        val j = post(c, "index/user", token, JSONObject().put("page", 1).put("count", 1).put("sortBy", "username").put("direction", "asc").put("search", ""))
        val total = j.optInt("total", -1)
        return "تم الاتصال بلوحة SAS بنجاح" + if (total >= 0) "\nعدد المشتركين في اللوحة: $total" else ""
    }


    /**
     * طبقة عمليات الكتابة المتوافقة مع نمط API الشائع في SAS4.
     * ملاحظة: مسارات الكتابة وأسماء الحقول قد تختلف باختلاف إصدار SAS4 والصلاحيات؛
     * لا نعيد المحاولة على مسار بديل حتى لا نكرر أي عملية مالية أو اشتراك.
     */
    private fun writeAction(c: SasCfg, route: String, payload: JSONObject): String {
        val token = login(c)
        val j = post(c, route, token, payload)
        val success = when {
            j.has("success") -> j.optBoolean("success", false)
            j.has("status") && j.opt("status") is Boolean -> j.optBoolean("status", false)
            j.has("error") && !j.isNull("error") -> false
            else -> {
                val status = j.str("status").lowercase()
                val msg = j.str("message").lowercase()
                status in setOf("success", "ok", "true", "1") ||
                    (msg.contains("success") || msg.contains("successful") || msg.contains("تم بنجاح"))
            }
        }
        if (!success) throw SasException(j.str("message").ifBlank { "لم تؤكد SAS4 نجاح العملية؛ لم نعتبرها منفذة. راجع حالة الحساب قبل إعادة المحاولة." })
        return j.str("message").ifBlank { "أعادت SAS4 تأكيد العملية." }
    }

    /** تعطيل الحساب أو إعادة تفعيله؛ لا يمس السجل المالي المحلي. */
    fun setUserEnabled(c: SasCfg, u: SasUser, enabled: Boolean): String {
        if (u.username.isBlank()) throw SasException("اسم المستخدم غير متوفر")
        val route = if (enabled) "enable/user" else "disable/user"
        return writeAction(c, route, JSONObject().put("id", u.id).put("username", u.username).put("enabled", if (enabled) 1 else 0))
    }

    /** فصل جلسة المستخدم الحالية؛ لا يوقف الاشتراك ولا يحذف الحساب. */
    fun disconnectUser(c: SasCfg, u: SasUser): String {
        if (!u.online) throw SasException("المشترك غير متصل حسب آخر قراءة")
        return writeAction(c, "disconnect/user", JSONObject().put("id", u.id).put("username", u.username))
    }

    /** مزامنة سجل مشترك واحد إلى دفتر التطبيق. لا تُغيّر حساب SAS ولا الحركات المالية المحلية. */
    fun syncUserToLocal(db: Db, u: SasUser): String {
        val existing = db.listSubs().firstOrNull { it.e1?.trim()?.equals(u.username.trim(), true) == true }
        val pkgId = if (u.profile.isBlank()) null else {
            db.packages().firstOrNull { it.name.trim().equals(u.profile.trim(), true) }?.id ?: run {
                db.savePackage(u.profile.trim(), 0, 30, null, "")
                db.packages().firstOrNull { it.name.trim().equals(u.profile.trim(), true) }?.id
            }
        }
        val phone = u.phone.takeIf { it.isNotBlank() }?.let { runCatching { db.formatPhone(it) }.getOrNull() }
        val values = mutableMapOf<String, Any?>(
            "name" to u.name,
            "phone" to phone,
            "end_date" to u.expiration.take(10).takeIf { it.length == 10 },
            "extra1" to u.username,
        )
        if (pkgId != null) values["package_id"] = pkgId
        if (existing == null) {
            val (ok, msg) = db.canAddSubscriber()
            if (!ok) throw SasException(msg)
            values["notes"] = "مستورد من لوحة SAS"
            db.saveSub(values)
            db.audit("مزامنة مشترك SAS", u.name, "إضافة سجل محلي للحساب ${u.username}")
            return "أُضيف ${u.name} إلى قائمة التطبيق. لم يُعدّل حساب SAS."
        }
        db.saveSub(values, existing.id, log = false)
        db.audit("مزامنة مشترك SAS", u.name, "تحديث بيانات الحساب ${u.username} محلياً؛ السجل المالي محفوظ")
        return "تم تحديث بيانات ${u.name} في التطبيق مع الحفاظ على سجل الديون والدفعات. حساب SAS نفسه لم يتغير."
    }

    /** يضيف إلى التطبيق المشتركين غير الموجودين (المطابقة بحقل PPPoE = اسم المستخدم في اللوحة). */
    fun importUsers(db: Db, list: List<SasUser>): String {
        val existing = db.listSubs().mapNotNull { it.e1?.trim()?.lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        val pkgByName = HashMap<String, Long>()
        db.packages().forEach { pkgByName[it.name.trim().lowercase()] = it.id }
        var added = 0
        var skipped = 0
        var newPkgs = 0
        var limit: String? = null
        for (u in list) {
            if (u.username.lowercase() in existing) { skipped++; continue }
            val (ok, msg) = db.canAddSubscriber()
            if (!ok) { limit = msg; break }
            var pid: Long? = null
            if (u.profile.isNotBlank()) {
                val key = u.profile.trim().lowercase()
                pid = pkgByName[key]
                if (pid == null) {
                    db.savePackage(u.profile.trim(), 0, 30, null, "")
                    pid = db.packages().firstOrNull { it.name.trim().lowercase() == key }?.id
                    if (pid != null) { pkgByName[key] = pid; newPkgs++ }
                }
            }
            val end = u.expiration.take(10).takeIf { it.length == 10 }
            val phone = if (u.phone.isBlank()) null else runCatching { db.formatPhone(u.phone) }.getOrNull()
            db.saveSub(mapOf(
                "name" to u.name, "phone" to phone, "package_id" to pid, "end_date" to end,
                "extra1" to u.username, "notes" to "مستورد من لوحة SAS",
            ), log = false)
            existing.add(u.username.lowercase())
            added++
        }
        if (added > 0) db.audit("استيراد من لوحة SAS", "", "أُضيف $added مشترك")
        val sb = StringBuilder("تمت إضافة $added مشترك، وتخطّي $skipped موجود مسبقاً.")
        if (newPkgs > 0) sb.append("\nأُنشئت $newPkgs باقة جديدة بسعر 0 — عدّل أسعارها من تبويب الباقات.")
        if (limit != null) sb.append("\nتوقف الاستيراد: $limit")
        return sb.toString()
    }
}

/** بطاقة ربط لوحة SAS4 في الإعدادات. */
@Composable
fun SasCard(db: Db, bump: () -> Unit) {
    if (db.isGenerator()) return
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val cur = remember { Sas.cfg(ctx) }
    var url by remember { mutableStateOf(cur?.url ?: "") }
    var user by remember { mutableStateOf(cur?.user ?: "") }
    var pass by remember { mutableStateOf(cur?.pass ?: "") }
    var busy by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf("") }
    var ucp by remember { mutableStateOf(Sas.ucpUrl(ctx)) }
    var users by remember { mutableStateOf(false) }

    fun build(): SasCfg? {
        if (url.isBlank() || user.isBlank()) { toast(ctx, "أدخل رابط اللوحة والمستخدم"); return null }
        val c = SasCfg(url.trim(), user.trim(), pass)
        Sas.save(ctx, c)
        return c
    }

    SectionTitle("ربط لوحة SAS (SAS4)")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("اكتب رابط اللوحة والمستخدم وكلمة المرور فقط، فتظهر مشتركوك والمتصلون الآن ويمكنك استيرادهم إلى التطبيق. " +
                "الاتصال الحالي قراءة وعرض واستيراد فقط، ولا تُرسل أوامر تغيير حسابات إلى SAS4. واجهات API قد تختلف حسب إصدار اللوحة والإضافات المفعّلة؛ اختبر الاتصال قبل الاستيراد. HTTPS هو الافتراضي، ويسمح HTTP فقط لعناوين الإدارة المحلية الخاصة. للوحات داخل الشبكة استخدم VPN عند الوصول عن بُعد. التحكم بواجهات الهب اللاسلكية يتم عبر MikroTik بعد تأكيد منفصل.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            OutlinedTextField(url, { url = it }, label = { Text("رابط اللوحة (مثل admin.example.iq)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text("المستخدم") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(ucp, { ucp = it; Sas.saveUcpUrl(ctx, it) },
                label = { Text("رابط UCP الرسمي الكامل (اختياري)") },
                supportingText = { Text("أدخل رابط بوابة المشترك من مزوّد SAS نفسه؛ لن نخمن مسارها") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            // Use two rows instead of four buttons in one Row: on narrow phones the
            // original layout compressed every button and wrapped Arabic labels vertically.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val link = ucp.trim()
                        if (!link.startsWith("https://", true)) toast(ctx, "لأمان بيانات المشتركين، أدخل رابط UCP يبدأ بـ HTTPS")
                        else openUrl(ctx, link)
                    }, enabled = ucp.startsWith("https://", true), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Person, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("فتح بوابة UCP", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(onClick = {
                        val raw = url.trim()
                        if (raw.isBlank()) { toast(ctx, "أدخل رابط لوحة SAS4 أولاً") }
                        else {
                            val panelUrl = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
                            openUrl(ctx, panelUrl)
                        }
                    }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("فتح لوحة SAS4", maxLines = 1, softWrap = false)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val c = build() ?: return@OutlinedButton
                        busy = true
                        scope.launch {
                            out = try { withContext(Dispatchers.IO) { Sas.test(c) } } catch (e: Exception) { e.message ?: "خطأ" }
                            busy = false
                        }
                    }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Text("اختبار الاتصال", maxLines = 1, softWrap = false)
                    }
                    Button(onClick = { if (build() != null) users = true }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.People, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("المشتركون", maxLines = 1, softWrap = false)
                    }
                }
            }
            if (cur != null || url.isNotBlank()) TextButton(onClick = { Sas.clear(ctx); url = ""; user = ""; pass = ""; out = "" }) { Text("حذف ربط اللوحة", color = Bad) }
            if (out.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant) {
                Text(out, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
        }
    }
    if (users) SasUsersDialog(db, bump) { users = false }
}

/** عرض مشتركي لوحة SAS مع المتصلين الآن، واستيرادهم إلى دفتر التطبيق. */
@Composable
fun SasUsersDialog(db: Db, bump: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<SasUser>?>(null) }
    var err by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("الكل") }
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf("") }
    var pendingUser by remember { mutableStateOf<SasUser?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        val c = Sas.cfg(ctx)
        if (c == null) { err = "اربط اللوحة أولاً"; return@LaunchedEffect }
        list = null; err = ""
        try {
            val r = withContext(Dispatchers.IO) { Sas.users(c) }
            list = r
            // تُظهر النقطة الخضراء للمتصلين في قائمة المشتركين (تطابق PPPoE = اسم المستخدم)
            Router.online.value = Router.online.value + r.filter { it.online }.map { it.username }
        } catch (e: Exception) { err = e.message ?: "خطأ"; list = emptyList() }
    }

    val all = list ?: emptyList()
    val shown = all.filter { u ->
        (q.isBlank() || u.username.contains(q, true) || u.name.contains(q, true) || u.phone.contains(q)) &&
            when (filter) {
                "متصل" -> u.online
                "غير متصل" -> !u.online
                "فعّال" -> u.status() == "فعّال"
                "منتهي" -> u.status() == "منتهي"
                "موقوف" -> u.status() == "موقوف"
                else -> true
            }
    }
    val existing = remember(list) { db.listSubs().mapNotNull { it.e1?.trim()?.lowercase() }.toHashSet() }
    val newOnes = all.filter { it.username.lowercase() !in existing }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("مشتركو لوحة SAS", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { tick++ }, enabled = list != null) { Icon(Icons.Rounded.Refresh, "تحديث") }
                }
                if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (err.isNotBlank()) Text(err, color = Bad, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                if (list != null && err.isBlank()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(Icons.Rounded.People, "الكل", all.size.toString(), cs.primary, Modifier.weight(1f))
                    StatCard(Icons.Rounded.Wifi, "متصلون الآن", all.count { it.online }.toString(), Ok, Modifier.weight(1f))
                    StatCard(Icons.Rounded.EventBusy, "منتهون", all.count { it.status() == "منتهي" }.toString(), Warn, Modifier.weight(1f))
                }
                if (list != null && err.isBlank()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(Icons.Rounded.CheckCircle, "اشتراك فعّال", all.count { it.status() == "فعّال" }.toString(), Ok, Modifier.weight(1f))
                    StatCard(Icons.Rounded.WifiOff, "غير متصلين", all.count { !it.online }.toString(), Bad, Modifier.weight(1f))
                }
                OutlinedTextField(q, { q = it }, placeholder = { Text("ابحث بالاسم أو المستخدم أو الهاتف") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("الكل", "فعّال", "متصل", "غير متصل", "منتهي", "موقوف").forEach { k -> FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(k) }) }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (list != null && shown.isEmpty()) item { EmptyState(Icons.Rounded.PersonSearch, "لا توجد نتائج") }
                    items(shown, key = { it.username }) { u ->
                        val st = u.status()
                        AppCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = if (u.online) Ok else cs.outline, modifier = Modifier.size(11.dp)) {}
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(u.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text("\u200e" + u.username + (if (u.phone.isNotBlank()) "  •  \u200e" + u.phone else ""), fontSize = 12.sp, color = cs.onSurfaceVariant)
                                    Text(listOf(u.profile, u.expiration.take(10).takeIf { it.length == 10 }?.let { "حتى $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString("  •  "),
                                        fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                                Pill(st, when (st) { "فعّال" -> Ok; "منتهي" -> Warn; else -> Bad })
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    scope.launch {
                                        result = try { withContext(Dispatchers.IO) { Sas.syncUserToLocal(db, u) } }
                                        catch (e: Exception) { e.message ?: "تعذرت المزامنة" }
                                        bump(); tick++
                                    }
                                }) {
                                    Icon(Icons.Rounded.Sync, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("مزامنة بياناته إلى التطبيق")
                                }
                                TextButton(onClick = { result = "إجراء إدارة SAS4\n\nيمكنك محاولة ${if (u.enabled) "إيقاف" else "تفعيل"} الحساب من زر الحالة أدناه. هذه العملية تعتمد على مسار API الشائع وقد يرفضها إصدار لوحتك؛ تحقق من الحالة في SAS4 إذا لم يظهر تأكيد واضح." }) {
                                    Text("خيارات الإدارة")
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { result = "لتغيير حالة ${u.username} إلى ${if (u.enabled) "موقوف" else "فعّال"}؟"; pendingAction = if (u.enabled) "disable" else "enable"; pendingUser = u }) {
                                    Text(if (u.enabled) "إيقاف SAS4" else "تفعيل SAS4", color = if (u.enabled) Bad else Ok)
                                }
                                if (u.online) TextButton(onClick = { pendingAction = "disconnect"; pendingUser = u; result = "هل تريد فصل جلسة ${u.username} الحالية؟ لن يُلغى الاشتراك." }) { Text("فصل الجلسة") }
                            }
                        }
                    }
                }
                Button(onClick = { confirm = true }, enabled = newOnes.isNotEmpty(), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp)) {
                    Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (newOnes.isEmpty()) "كل المشتركين موجودون في التطبيق" else "استيراد ${newOnes.size} مشترك جديد إلى التطبيق")
                }
            }
        }
    }
    if (confirm) ConfirmDialog(
        "سيُضاف ${newOnes.size} مشترك إلى دفتر التطبيق (اسم المستخدم يُحفظ في حقل PPPoE، وتاريخ الانتهاء من اللوحة). " +
            "الباقات الجديدة تُنشأ بسعر 0 لتعدّلها لاحقاً. إن كانت المزامنة التلقائية مع الراوتر مفعّلة فستُطبَّق حالة اشتراكهم على الراوتر. متابعة؟",
        "استيراد", { confirm = false }) {
        scope.launch {
            result = try { withContext(Dispatchers.IO) { Sas.importUsers(db, newOnes) } } catch (e: Exception) { e.message ?: "خطأ" }
            bump(); tick++
        }
    }
    if (pendingAction.isNotBlank() && pendingUser != null) {
        ConfirmDialog(
            result ?: "تأكيد العملية؟", "تنفيذ", { pendingAction = ""; pendingUser = null; result = null }
        ) {
            val action = pendingAction
            val target = pendingUser!!
            pendingAction = ""; pendingUser = null; result = "جارٍ تنفيذ العملية…"
            scope.launch {
                val c = Sas.cfg(ctx)
                result = try {
                    if (c == null) throw SasException("اربط لوحة SAS4 أولاً")
                    withContext(Dispatchers.IO) {
                        when (action) {
                            "enable" -> Sas.setUserEnabled(c, target, true)
                            "disable" -> Sas.setUserEnabled(c, target, false)
                            "disconnect" -> Sas.disconnectUser(c, target)
                            else -> throw SasException("عملية غير معروفة")
                        }
                    }.let { "أعادت اللوحة: $it\n\nحدّث القائمة للتحقق من الحالة الفعلية." }
                } catch (e: Exception) {
                    "لم يتم تأكيد العملية: ${e.message ?: "خطأ غير معروف"}\n\nلم تُعد المحاولة تلقائياً لتجنب تكرار الأوامر. تحقق من حالة المشترك قبل المحاولة مرة أخرى."
                }
                tick++
            }
        }
    }
    result?.let { InfoDialog(it) { result = null; if (pendingAction.isNotBlank()) { pendingAction = ""; pendingUser = null } } }
}
