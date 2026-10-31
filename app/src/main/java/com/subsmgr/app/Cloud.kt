package com.subsmgr.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

data class LicRow(val userId: String, val email: String, val plan: String, val expires: String, val limit: Int, val suspended: Boolean)

data class CloudFile(val name: String, val updatedAt: String, val size: Long)

class CloudBackupWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!Cloud.loggedIn(c) || !Cloud.autoOn(c)) return Result.success()
        return try {
            Cloud.uploadAll(c)
            try { Cloud.fetchLicense(c) } catch (e: Exception) { }
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }
}

/** حساب المستخدم والنسخ الاحتياطي والترخيص عبر Supabase (REST مباشر بلا مكتبات). */
object Cloud {
    // المفتاح العام (anon) مخصّص لوضعه داخل التطبيق. الحماية بقواعد RLS في supabase_setup.sql
    private const val URL_BASE = "https://aaxysrhhiemnshmhbuxp.supabase.co"
    private const val KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFheHlzcmhoaWVtbnNobWhidXhwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTIzNDU4MDcsImV4cCI6MjEwNzkyMTgwN30.3sAfHRivbX5nFPx63aatm5LNBGlS8bTzYQ9xQXqd36M"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    fun loggedIn(ctx: Context) = Secure.dec(prefs(ctx).getString("refresh", "") ?: "").isNotEmpty()

    /** يشفّر الرموز المخزّنة بصيغة قديمة (مرة واحدة عند فتح التطبيق). */
    fun migrate(ctx: Context) {
        val p = prefs(ctx)
        for (k in listOf("access", "refresh")) {
            val v = p.getString(k, "") ?: ""
            if (v.isNotEmpty() && !Secure.isEnc(v)) p.edit().putString(k, Secure.enc(v)).apply()
        }
    }
    fun email(ctx: Context): String = prefs(ctx).getString("email", "") ?: ""
    fun displayName(ctx: Context): String = prefs(ctx).getString("display_name", "") ?: ""
    fun lastBackup(ctx: Context): String = prefs(ctx).getString("last_backup", "") ?: ""
    fun autoOn(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("auto", on).apply()
        if (on) scheduleAuto(ctx) else WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork("cloud_backup")
    }
    fun scheduleAuto(ctx: Context) {
        if (!loggedIn(ctx) || !autoOn(ctx)) return
        val req = PeriodicWorkRequestBuilder<CloudBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("cloud_backup", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    // ---------- HTTP ----------
    private class Resp(val code: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
        val ok: Boolean get() = code in 200..299
    }

    private fun http(method: String, path: String, token: String?, body: ByteArray? = null,
                     ctype: String = "application/json", extra: Map<String, String> = emptyMap()): Resp {
        try {
            val c = URL(URL_BASE + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Authorization", "Bearer " + (token ?: KEY))
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", ctype)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val st = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = st?.use { it.readBytes() } ?: ByteArray(0)
            c.disconnect()
            return Resp(code, bytes)
        } catch (e: IOException) {
            throw IllegalStateException("تعذّر الاتصال بالإنترنت")
        }
    }

    private fun err(r: Resp): String {
        val j = try { JSONObject(r.text) } catch (e: Exception) { null }
        val code = j?.optString("error_code") ?: ""
        val msg = (j?.optString("msg") ?: "").ifEmpty { j?.optString("message") ?: "" }.ifEmpty { j?.optString("error_description") ?: "" }
        return when {
            code == "invalid_credentials" || msg.contains("Invalid login", true) -> "البريد أو كلمة المرور غير صحيحة"
            code == "user_already_exists" || msg.contains("already registered", true) -> "هذا البريد مسجّل مسبقاً، سجّل الدخول"
            code == "weak_password" || msg.contains("at least", true) -> "كلمة المرور ضعيفة (6 أحرف على الأقل)"
            code == "email_not_confirmed" -> "لم يتم تأكيد البريد بعد. افتح الرسالة المرسلة إليك"
            code.contains("rate_limit") -> "محاولات كثيرة، حاول بعد قليل"
            code == "validation_failed" || msg.contains("valid email", true) -> "صيغة البريد غير صحيحة"
            msg.isNotEmpty() -> msg
            else -> "خطأ من الخادم (${r.code})"
        }
    }

    // ---------- الحساب ----------
    private fun saveSession(ctx: Context, j: JSONObject) {
        val exp = System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600)
        val u = j.optJSONObject("user")
        val e = prefs(ctx).edit()
            .putString("access", Secure.enc(j.optString("access_token")))
            .putString("refresh", Secure.enc(j.optString("refresh_token")))
            .putLong("exp", exp)
        if (u != null) {
            e.putString("uid", u.optString("id"))
            e.putString("email", u.optString("email"))
            val meta = u.optJSONObject("user_metadata")
            val display = meta?.optString("full_name")?.takeIf { !it.isNullOrBlank() && it != "null" }
                ?: meta?.optString("name")?.takeIf { !it.isNullOrBlank() && it != "null" }
                ?: ""
            if (display.isNotBlank()) e.putString("display_name", display)
        }
        e.apply()
    }

    fun signUp(ctx: Context, email: String, pass: String): String {
        val r = http("POST", "/auth/v1/signup", null, JSONObject().put("email", email).put("password", pass).toString().toByteArray())
        if (!r.ok) throw IllegalArgumentException(err(r))
        val j = JSONObject(r.text)
        return if (j.has("access_token")) { saveSession(ctx, j); "تم إنشاء الحساب" }
        else "تم إنشاء الحساب. افتح رسالة التأكيد في بريدك ثم سجّل الدخول."
    }

    fun signIn(ctx: Context, email: String, pass: String) {
        val r = http("POST", "/auth/v1/token?grant_type=password", null, JSONObject().put("email", email).put("password", pass).toString().toByteArray())
        if (!r.ok) throw IllegalArgumentException(err(r))
        saveSession(ctx, JSONObject(r.text))
    }

    fun recover(email: String) {
        val r = http("POST", "/auth/v1/recover", null, JSONObject().put("email", email).toString().toByteArray())
        if (!r.ok) throw IllegalArgumentException(err(r))
    }

    fun signOut(ctx: Context) {
        WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork("cloud_backup")
        prefs(ctx).edit().clear().apply()
    }

    @Synchronized
    private fun token(ctx: Context): String {
        val p = prefs(ctx)
        val tok = Secure.dec(p.getString("access", "") ?: "")
        if (tok.isEmpty()) throw IllegalStateException("سجّل الدخول أولاً")
        if (System.currentTimeMillis() / 1000 < p.getLong("exp", 0) - 60) return tok
        val r = http("POST", "/auth/v1/token?grant_type=refresh_token", null,
            JSONObject().put("refresh_token", Secure.dec(p.getString("refresh", "") ?: "")).toString().toByteArray())
        if (!r.ok) { signOut(ctx); throw IllegalStateException("انتهت جلسة الدخول، سجّل الدخول من جديد") }
        saveSession(ctx, JSONObject(r.text))
        return Secure.dec(p.getString("access", "") ?: "")
    }
    private fun uid(ctx: Context): String = prefs(ctx).getString("uid", "") ?: ""

    // ---------- النسخ الاحتياطي ----------
    private fun put(ctx: Context, name: String, bytes: ByteArray) {
        val r = http("POST", "/storage/v1/object/backups/${uid(ctx)}/$name", token(ctx), bytes, "application/octet-stream", mapOf("x-upsert" to "true"))
        if (!r.ok) throw IllegalStateException("فشل رفع النسخة: " + err(r))
    }

    fun list(ctx: Context): List<CloudFile> {
        val body = JSONObject().put("prefix", uid(ctx)).put("limit", 200)
            .put("sortBy", JSONObject().put("column", "name").put("order", "desc")).toString().toByteArray()
        val r = http("POST", "/storage/v1/object/list/backups", token(ctx), body)
        if (!r.ok) throw IllegalStateException(err(r))
        val a = JSONArray(r.text)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            CloudFile(it.optString("name"), it.optString("updated_at"), it.optJSONObject("metadata")?.optLong("size") ?: 0L)
        }.filter { it.name.endsWith(".db") }
    }

    fun download(ctx: Context, name: String): ByteArray {
        val r = http("GET", "/storage/v1/object/authenticated/backups/${uid(ctx)}/$name", token(ctx))
        if (!r.ok) throw IllegalStateException("فشل التنزيل: " + err(r))
        return r.body
    }

    private fun prune(ctx: Context, mode: String) {
        val old = list(ctx).filter { it.name.startsWith(mode + "_") && !it.name.contains("latest") }
            .sortedByDescending { it.name }.drop(7)
        old.forEach { http("DELETE", "/storage/v1/object/backups/${uid(ctx)}/${it.name}", token(ctx)) }
    }

    /** يرفع قاعدتَي النشاطين (آخر نسخة + نسخة اليوم) ويحذف الأقدم من 7 نسخ. يرجع عدد القواعد المرفوعة. */
    fun uploadAll(ctx: Context): Int {
        var n = 0
        for (mode in MODES.keys) {
            val f = ctx.getDatabasePath(dbFileName(mode))
            if (!f.exists()) continue
            val bytes = f.readBytes()
            put(ctx, "${mode}_latest.db", bytes)
            put(ctx, "${mode}_${LocalDate.now()}.db", bytes)
            try { prune(ctx, mode) } catch (e: Exception) { }
            n++
        }
        prefs(ctx).edit().putString("last_backup", LocalDateTime.now().toString().take(16).replace('T', ' ')).apply()
        return n
    }

    // ---------- الترخيص من الخادم ----------
    /** يجلب الترخيص ويقارن آخر حالة مؤكدة بالحالة الجديدة؛ أول مزامنة تحفظ خط أساس بلا تنبيه. */
    fun fetchLicense(ctx: Context) {
        val r = http("GET", "/rest/v1/licenses?select=plan,expires_at,sub_limit,suspended&user_id=eq.${uid(ctx)}", token(ctx), null, "application/json", mapOf("Accept" to "application/json"))
        if (!r.ok) throw IllegalStateException(err(r))
        val a = JSONArray(r.text)
        val j = if (a.length() > 0) a.getJSONObject(0) else null
        val next = JSONObject()
            .put("exists", j != null)
            .put("plan", j?.optString("plan", "") ?: "")
            .put("expires", j?.optString("expires_at", "") ?: "")
            .put("limit", j?.optInt("sub_limit", 0) ?: 0)
            .put("suspended", j?.optBoolean("suspended", false) ?: false)
        val p = prefs(ctx)
        val oldText = p.getString("lic_snapshot", null)
        if (oldText != null) {
            val old = runCatching { JSONObject(oldText) }.getOrNull()
            if (old != null && old.toString() != next.toString()) {
                val changes = mutableListOf<String>()
                if (old.optBoolean("exists") != next.optBoolean("exists")) {
                    changes += if (next.optBoolean("exists")) "تم إنشاء ترخيص لحسابك" else "لم يعد الترخيص موجوداً على الخادم"
                }
                if (old.optString("plan") != next.optString("plan")) changes += "الخطة: ${old.optString("plan").ifBlank { "غير محددة" }} ← ${next.optString("plan").ifBlank { "غير محددة" }}"
                if (old.optString("expires") != next.optString("expires")) changes += "تاريخ الانتهاء: ${old.optString("expires").ifBlank { "غير محدد" }} ← ${next.optString("expires").ifBlank { "غير محدد" }}"
                if (old.optInt("limit") != next.optInt("limit")) changes += "حد المشتركين: ${old.optInt("limit")} ← ${next.optInt("limit")}"
                if (old.optBoolean("suspended") != next.optBoolean("suspended")) changes += if (next.optBoolean("suspended")) "تم إيقاف الترخيص" else "تم رفع إيقاف الترخيص"
                if (changes.isNotEmpty()) p.edit().putString("lic_notice", changes.joinToString("\n")).apply()
            }
        }
        val e = p.edit().putString("lic_snapshot", next.toString()).putString("lic_checked", LocalDate.now().toString())
        if (j == null) e.remove("lic_exp").remove("lic_limit").remove("lic_plan").remove("lic_susp")
        else e.putString("lic_exp", j.optString("expires_at")).putInt("lic_limit", j.optInt("sub_limit", 0)).putString("lic_plan", j.optString("plan")).putBoolean("lic_susp", j.optBoolean("suspended", false))
        e.apply()
        try { refreshAdmin(ctx) } catch (ex: Exception) { }
    }

    fun pendingLicenseNotice(ctx: Context): String = prefs(ctx).getString("lic_notice", "") ?: ""
    fun clearLicenseNotice(ctx: Context) { prefs(ctx).edit().remove("lic_notice").apply() }

    // ---------- إعدادات التطبيق من الخادم (أسعار، رقم التواصل...) تُعدَّل بدون تحديث التطبيق ----------
    fun fetchConfig(ctx: Context) {
        val r = http("GET", "/rest/v1/app_config?select=key,value", null, null, "application/json", mapOf("Accept" to "application/json"))
        if (!r.ok) return
        val a = JSONArray(r.text)
        val e = ctx.getSharedPreferences("cloud_config", Context.MODE_PRIVATE).edit()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); e.putString(o.optString("key"), o.optString("value")) }
        e.apply()
    }

    fun config(ctx: Context, key: String, default: String = ""): String =
        (ctx.getSharedPreferences("cloud_config", Context.MODE_PRIVATE).getString(key, "") ?: "").ifBlank { default }

    /** يحذف نسخك السحابية ثم الحساب نفسه (والترخيص) من الخادم، ثم يخرجك محلياً. */
    fun deleteAccount(ctx: Context) {
        val tok = token(ctx)
        for (f in list(ctx)) http("DELETE", "/storage/v1/object/backups/${uid(ctx)}/${f.name}", tok)
        val r = http("POST", "/rest/v1/rpc/delete_my_account", tok, "{}".toByteArray())
        if (!r.ok) throw IllegalStateException(err(r))
        signOut(ctx)
    }

    fun isSuspended(ctx: Context): Boolean = prefs(ctx).getBoolean("lic_susp", false)

    // ---------- لوحة المطوّر (تتطلب أن يكون حسابك في جدول admins) ----------
    fun isAdminCached(ctx: Context): Boolean = prefs(ctx).getBoolean("is_admin", false)

    fun refreshAdmin(ctx: Context) {
        val r = http("POST", "/rest/v1/rpc/is_admin", token(ctx), "{}".toByteArray())
        prefs(ctx).edit().putBoolean("is_admin", r.ok && r.text.trim() == "true").apply()
    }

    fun adminList(ctx: Context): List<LicRow> {
        val r = http("POST", "/rest/v1/rpc/admin_list_licenses", token(ctx), "{}".toByteArray())
        if (!r.ok) throw IllegalStateException(err(r))
        val a = JSONArray(r.text)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            LicRow(it.optString("user_id"), it.optString("email").takeIf { e -> e != "null" } ?: "", it.optString("plan"),
                it.optString("expires_at"), it.optInt("sub_limit", 0), it.optBoolean("suspended", false))
        }
    }

    fun adminSet(ctx: Context, userId: String, plan: String, expires: String, limit: Int, suspended: Boolean) {
        val body = JSONObject().put("p_user", userId).put("p_plan", plan).put("p_expires", expires)
            .put("p_limit", limit).put("p_suspended", suspended).toString().toByteArray()
        val r = http("POST", "/rest/v1/rpc/admin_set_license", token(ctx), body)
        if (!r.ok) throw IllegalStateException(err(r))
    }

    /** (تاريخ الانتهاء، الحد، الباقة) من آخر مزامنة، أو null. */
    fun cachedLicense(ctx: Context): Triple<String, Int, String>? {
        val p = prefs(ctx)
        val exp = p.getString("lic_exp", "") ?: ""
        if (exp.isEmpty()) return null
        return Triple(exp, p.getInt("lic_limit", 0), p.getString("lic_plan", "") ?: "")
    }
}
