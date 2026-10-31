package com.subsmgr.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

val MODES = linkedMapOf(
    "internet" to Pair("مزود إنترنت", listOf("PPPoE", "IP", "MAC")),
    "generator" to Pair("مولدة أهلية", listOf("رقم العداد", "الأمبير", "ملاحظة فنية")),
)
val TYPE_AR = mapOf("charge" to "مستحق", "payment" to "دفعة", "discount" to "خصم")

const val TRIAL_DAYS = 14
const val TRIAL_LIMIT = 15
private const val DB_NAME = "subscription_manager.db"

private val SCHEMA = """
CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS packages(
    id INTEGER PRIMARY KEY, name TEXT NOT NULL, price INTEGER NOT NULL, days INTEGER NOT NULL DEFAULT 30);
CREATE TABLE IF NOT EXISTS subscribers(
    id INTEGER PRIMARY KEY, name TEXT NOT NULL, phone TEXT, area TEXT, address TEXT,
    package_id INTEGER, end_date TEXT, extra1 TEXT, extra2 TEXT, extra3 TEXT, notes TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP, lat REAL, lng REAL, cut INTEGER DEFAULT 0, frozen_at TEXT);
CREATE TABLE IF NOT EXISTS transactions(
    id INTEGER PRIMARY KEY, subscriber_id INTEGER NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('charge','payment','discount')),
    amount INTEGER NOT NULL, date TEXT NOT NULL, note TEXT, renew_days INTEGER DEFAULT 0,
    cancelled INTEGER DEFAULT 0, cancel_reason TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP, collector TEXT);
CREATE TABLE IF NOT EXISTS meter_readings(
    id INTEGER PRIMARY KEY, subscriber_id INTEGER NOT NULL, date TEXT NOT NULL, reading REAL NOT NULL,
    prev REAL NOT NULL, rate INTEGER NOT NULL, amount INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS expenses(
    id INTEGER PRIMARY KEY, date TEXT NOT NULL, category TEXT, amount INTEGER NOT NULL, note TEXT);
CREATE TABLE IF NOT EXISTS fuel_log(
    id INTEGER PRIMARY KEY, date TEXT NOT NULL, liters REAL, cost INTEGER, hours REAL, note TEXT, expense_id INTEGER);
CREATE TABLE IF NOT EXISTS msg_log(
    id INTEGER PRIMARY KEY, ts TEXT NOT NULL, via TEXT NOT NULL, name TEXT, phone TEXT, body TEXT);
CREATE TABLE IF NOT EXISTS vouchers(
    id INTEGER PRIMARY KEY, code TEXT NOT NULL, pass TEXT NOT NULL, profile TEXT, batch TEXT,
    price INTEGER NOT NULL DEFAULT 0, created TEXT NOT NULL, sold INTEGER NOT NULL DEFAULT 0, sold_at TEXT);
CREATE TABLE IF NOT EXISTS audit_log(
    id INTEGER PRIMARY KEY, ts TEXT NOT NULL, actor TEXT, action TEXT NOT NULL, target TEXT, detail TEXT)
"""

private val SUB_COLS = listOf("name", "phone", "area", "address", "package_id", "end_date",
    "extra1", "extra2", "extra3", "notes", "lat", "lng")

data class Pkg(val id: Long, val name: String, val price: Long, val days: Int, val profile: String? = null)
data class Sub(
    val id: Long, val name: String, val phone: String?, val area: String?, val address: String?,
    val packageId: Long?, val endDate: String?, val e1: String?, val e2: String?, val e3: String?,
    val notes: String?, val lat: Double?, val lng: Double?, val pkg: String?, val bal: Long,
    val cut: Boolean = false, val frozenAt: String? = null,
    val promiseDate: String? = null, val promiseNote: String? = null,
    val noMsg: Boolean = false,
)
data class Tx(val id: Long, val subId: Long, val type: String, val amount: Long, val date: String,
              val note: String?, val renewDays: Int, val cancelled: Boolean, val reason: String?, val collector: String? = null)
data class Expense(val id: Long, val date: String, val category: String?, val amount: Long, val note: String?)
data class LicInfo(val state: String, val limit: Int, val message: String, val daysLeft: Long? = null)
data class Staff(val id: String, val name: String)
data class FullReport(
    val from: String, val to: String,
    val revenue: Long, val charges: Long, val discounts: Long, val expenses: Long, val profit: Long,
    val newSubs: Int, val totalSubs: Int, val activeSubs: Int, val expiredSubs: Int, val debtSubs: Int, val totalDebt: Long,
    val byCollector: List<Triple<String, Long, Int>>, val byDay: List<Pair<String, Long>>,
    val expenseByCat: List<Pair<String, Long>>, val debtors: List<Sub>, val payments: List<List<Any?>>,
)
data class Voucher(val id: Long, val code: String, val pass: String, val profile: String, val batch: String, val price: Long, val created: String, val sold: Boolean, val soldAt: String?) {
    companion object {
        private const val CH = "0123456789"
        fun gen(len: Int): String { val r = java.security.SecureRandom(); return (1..len).map { CH[r.nextInt(CH.length)] }.joinToString("") }
    }
}
data class Insights(val weekCount: Int, val weekRevenue: Long, val monthCount: Int, val monthRevenue: Long, val atRisk: List<Sub>, val collectionRate: Int?)
data class AuditRow(val id: Long, val ts: String, val actor: String, val action: String, val target: String, val detail: String)
data class MsgLog(val id: Long, val ts: String, val via: String, val name: String, val phone: String, val body: String,
    val status: String = "", val parts: Int = 1, val kind: String = "", val retries: Int = 0)
data class Alerts(val expiring: List<Sub>, val expired: List<Sub>, val debtors: List<Sub>)
data class MonthRow(val sub: Sub, val amps: Double, val price: Long, val charged: Long?, val paid: Long) {
    val due: Long get() = charged ?: price
    val left: Long get() = (due - paid).coerceAtLeast(0)
}
data class MonthResult(val billed: Int, val paid: Int, val total: Long)
data class Profit(val revenue: Long, val expenses: Long, val net: Long, val liters: Double, val hours: Double)

fun today(): String = LocalDate.now().toString()
/** يحوّل الأرقام العربية/الهندية إلى لاتينية. */
fun normDigits(s: String): String = s.map {
    if (Character.isDigit(it)) Character.digit(it, 10).toString() else if (it == '٫') "." else it.toString()
}.joinToString("")
fun numOf(s: String): Double? = normDigits(s).replace(",", "").trim().toDoubleOrNull()
fun fmt(n: Long): String = String.format(Locale.US, "%,d", n)
fun g(x: Double): String = if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString() else x.toString()

private fun Cursor.s(n: String): String? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getString(it) }
private fun Cursor.l(n: String): Long = getLong(getColumnIndexOrThrow(n))
private fun Cursor.lOrNull(n: String): Long? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.dOrNull(n: String): Double? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getDouble(it) }

fun dbFileName(mode: String) = if (mode == "generator") "generator.db" else DB_NAME

private class Helper(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, 1) {
    init { setWriteAheadLoggingEnabled(false) }
    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    /** يُكمل بنية أي قاعدة بيانات مستعادة (مثل نسخة سطح المكتب) بالجداول والأعمدة الناقصة. */
    override fun onOpen(db: SQLiteDatabase) {
        SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
        addCol(db, "packages", "profile", "TEXT")
        addCol(db, "subscribers", "lat", "REAL"); addCol(db, "subscribers", "lng", "REAL")
        addCol(db, "subscribers", "cut", "INTEGER DEFAULT 0"); addCol(db, "subscribers", "frozen_at", "TEXT")
        addCol(db, "subscribers", "promise_date", "TEXT"); addCol(db, "subscribers", "promise_note", "TEXT")
        addCol(db, "subscribers", "no_msg", "INTEGER DEFAULT 0")
        addCol(db, "msg_log", "status", "TEXT"); addCol(db, "msg_log", "parts", "INTEGER DEFAULT 1")
        addCol(db, "msg_log", "kind", "TEXT"); addCol(db, "msg_log", "retries", "INTEGER DEFAULT 0")
        addCol(db, "transactions", "renew_days", "INTEGER DEFAULT 0"); addCol(db, "transactions", "cancelled", "INTEGER DEFAULT 0")
        addCol(db, "transactions", "cancel_reason", "TEXT"); addCol(db, "transactions", "collector", "TEXT")
    }
    private fun addCol(db: SQLiteDatabase, table: String, col: String, type: String) {
        var has = false
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> while (c.moveToNext()) if (c.getString(1) == col) has = true }
        if (!has) db.execSQL("ALTER TABLE $table ADD COLUMN $col $type")
    }
}

/** يُحدّث حالة رسالة SMS في سجل الرسائل (يُستدعى من مستقبِل نتائج الإرسال/التسليم، حتى لو كان التطبيق مغلقاً). */
fun applySmsResult(ctx: Context, mode: String, id: Long, delivered: Boolean, ok: Boolean) {
    val h = Helper(ctx.applicationContext, dbFileName(mode))
    try {
        val dbx = h.writableDatabase
        val cur = dbx.rawQuery("SELECT status FROM msg_log WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) (it.getString(0) ?: "") else null } ?: return
        val now = when {
            !ok || cur == "failed" -> "failed"
            delivered || cur == "delivered" -> "delivered"
            else -> "sent"
        }
        dbx.execSQL("UPDATE msg_log SET status=? WHERE id=?", arrayOf(now, id))
    } catch (e: Exception) { } finally { h.close() }
}

class Db(private val ctx: Context) {
    // لكل نشاط (إنترنت / مولدة) قاعدة بيانات مستقلة: مشتركوه وباقاته ومصاريفه وإعداداته.
    // إعدادات الجهاز المشتركة (النشاط الحالي، الترخيص، PIN) تُحفظ في SharedPreferences.
    private val prefs = ctx.getSharedPreferences("app_global", Context.MODE_PRIVATE)
    private val globalKeys = setOf("mode", "license_key", "trial_start", "pin_hash", "pin_salt", "collector", "printer_mac", "printer_dots", "bio", "staff_json")
    private fun mode(): String = prefs.getString("mode", "internet") ?: "internet"
    fun currentMode(): String = mode()
    private var helper = Helper(ctx, dbFileName(mode()))

    fun switchMode(newMode: String) {
        if (!MODES.containsKey(newMode)) return
        prefs.edit().putString("mode", newMode).apply()
        helper.close()
        helper = Helper(ctx, dbFileName(newMode))
    }
    private val d: SQLiteDatabase get() = helper.writableDatabase
    private val machine = License.machineId(ctx)
    fun machineId() = machine
    fun cloudLoggedIn() = Cloud.loggedIn(ctx)

    private fun <T> q(sql: String, args: List<Any?> = emptyList(), f: (Cursor) -> T): List<T> {
        d.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val out = ArrayList<T>()
            while (c.moveToNext()) out.add(f(c))
            return out
        }
    }
    private fun exec(sql: String, vararg args: Any?) = d.execSQL(sql, args)
    private fun scalar(sql: String, args: List<Any?> = emptyList()): Long = q(sql, args) { it.getLong(0) }.firstOrNull() ?: 0L
    private fun <T> tx(f: () -> T): T {
        d.beginTransaction()
        try { val r = f(); d.setTransactionSuccessful(); return r } finally { d.endTransaction() }
    }

    // ---------- الإعدادات ----------
    fun setting(key: String, default: String = ""): String =
        if (key == "collector" && Session.role == "staff") Session.name
        else if (key in globalKeys) (prefs.getString(key, default) ?: default)
        else q("SELECT value FROM settings WHERE key=?", listOf(key)) { it.getString(0) }.firstOrNull() ?: default
    fun setSetting(key: String, value: String) {
        if (key in globalKeys) prefs.edit().putString(key, value).apply()
        else exec("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", key, value)
    }
    fun extraLabels(): List<String> = (MODES[setting("mode", "internet")] ?: MODES["internet"]!!).second
    fun isGenerator() = setting("mode") == "generator"

    // ---------- الباقات ----------
    fun savePackage(name: String, price: Long, days: Int, pid: Long? = null, profile: String = "") {
        if (name.isBlank()) throw IllegalArgumentException("اسم الباقة مطلوب")
        audit(if (pid != null) "تعديل باقة" else "إضافة باقة", name, "${fmt(price)} دينار / $days يوم")
        if (pid != null) exec("UPDATE packages SET name=?, price=?, days=?, profile=? WHERE id=?", name, price, days, profile.trim().ifEmpty { null }, pid)
        else exec("INSERT INTO packages(name,price,days,profile) VALUES(?,?,?,?)", name, price, days, profile.trim().ifEmpty { null })
    }
    private fun pkgOf(c: Cursor) = Pkg(c.l("id"), c.s("name")!!, c.l("price"), c.l("days").toInt(), c.s("profile"))

    /** الحالة المطلوبة لكل مشترك مربوط براوتر (اسم الحساب = حقل PPPoE). المنتهي والمجمّد يُوقفان، والفعّال يعمل. */
    fun routerTargets(graceDays: Int = 0): List<RTarget> {
        val prof = packages().associate { it.id to (it.profile ?: "").trim() }
        val out = ArrayList<RTarget>()
        for (s in listSubs()) {
            val u = (s.e1 ?: "").trim()
            if (u.isEmpty()) continue
            val st = statusOf(s)
            val on = when (st) {
                "فعّال" -> true
                "منتهي" -> promiseActive(s) || (graceDays > 0 && runCatching { LocalDate.parse(s.endDate).plusDays(graceDays.toLong()) >= LocalDate.now() }.getOrDefault(false))
                "مجمّد" -> false
                else -> continue
            }
            out.add(RTarget(u, on, prof[s.packageId]?.ifEmpty { null }))
        }
        return out
    }
    fun packages(): List<Pkg> = q("SELECT * FROM packages ORDER BY price") { pkgOf(it) }
    fun getPkg(id: Long): Pkg? = q("SELECT * FROM packages WHERE id=?", listOf(id)) { pkgOf(it) }.firstOrNull()

    // ---------- المشتركون ----------
    fun saveSub(v: Map<String, Any?>, sid: Long? = null, log: Boolean = true): Long {
        val cv = ContentValues()
        for (c in SUB_COLS) when (val x = v[c]) {
            null, "" -> cv.putNull(c)
            is String -> cv.put(c, x)
            is Long -> cv.put(c, x)
            is Double -> cv.put(c, x)
            else -> cv.put(c, x.toString())
        }
        val id = if (sid != null) { d.update("subscribers", cv, "id=?", arrayOf(sid.toString())); sid }
        else d.insert("subscribers", null, cv)
        if (log) audit(if (sid != null) "تعديل مشترك" else "إضافة مشترك", (v["name"] ?: "").toString())
        return id
    }

    private val listSql = """
SELECT s.*, p.name AS pkg, COALESCE(b.bal,0) AS bal FROM subscribers s
LEFT JOIN packages p ON p.id = s.package_id
LEFT JOIN (SELECT subscriber_id, SUM(CASE type WHEN 'charge' THEN amount ELSE -amount END) AS bal
           FROM transactions WHERE cancelled=0 GROUP BY subscriber_id) b ON b.subscriber_id = s.id
"""
    private fun subOf(c: Cursor) = Sub(c.l("id"), c.s("name")!!, c.s("phone"), c.s("area"), c.s("address"),
        c.lOrNull("package_id"), c.s("end_date"), c.s("extra1"), c.s("extra2"), c.s("extra3"), c.s("notes"),
        c.dOrNull("lat"), c.dOrNull("lng"), c.s("pkg"), c.l("bal"), c.l("cut") == 1L, c.s("frozen_at"),
        c.s("promise_date"), c.s("promise_note"), c.l("no_msg") == 1L)

    fun getSub(id: Long): Sub? = q("$listSql WHERE s.id=?", listOf(id)) { subOf(it) }.firstOrNull()

    fun listSubs(search: String = "", area: String = "", status: String = ""): List<Sub> {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        if (search.isNotEmpty()) {
            where.add("(s.name LIKE ? OR s.phone LIKE ? OR s.extra1 LIKE ? OR s.extra2 LIKE ? OR s.extra3 LIKE ?)")
            repeat(5) { args.add("%$search%") }
        }
        if (area.isNotEmpty()) { where.add("s.area = ?"); args.add(area) }
        when (status) {
            "active" -> { where.add("(s.end_date IS NOT NULL AND TRIM(s.end_date) <> '' AND s.end_date >= ? AND s.frozen_at IS NULL)"); args.add(today()) }
            "expired" -> { where.add("(s.end_date IS NULL OR TRIM(s.end_date) = '' OR s.end_date < ?) AND s.frozen_at IS NULL"); args.add(today()) }
            "cut" -> where.add("s.cut = 1")
            "frozen" -> where.add("s.frozen_at IS NOT NULL")
            "debt" -> where.add("COALESCE(b.bal,0) > 0")
            "paid" -> where.add("COALESCE(b.bal,0) <= 0")
        }
        val sql = listSql + (if (where.isNotEmpty()) " WHERE " + where.joinToString(" AND ") else "") + " ORDER BY s.name"
        return q(sql, args) { subOf(it) }
    }

    fun areas(): List<String> = q("SELECT DISTINCT area FROM subscribers WHERE area IS NOT NULL AND area<>'' ORDER BY area") { it.getString(0) }

    /** أقدم تاريخ حركة اشتراك/تجديد مسجلة لكل مشترك؛ المستوردون بلا حركة يبقون بلا تاريخ. */
    fun subscriptionStartDates(): Map<Long, String> = q(
        "SELECT subscriber_id, MIN(date) AS first_date FROM transactions WHERE type='charge' AND cancelled=0 GROUP BY subscriber_id"
    ) { it.getLong(0) to (it.getString(1) ?: "") }.toMap()

    fun statusOf(s: Sub): String {
        if (isGenerator()) return if (s.cut) "مقطوع" else if (s.bal > 0) "عليه دين" else "مسدّد"
        if (s.frozenAt != null) return "مجمّد"
        val e = s.endDate ?: ""
        return if (e.isEmpty()) "بدون اشتراك" else if (e >= today()) "فعّال" else "منتهي"
    }

    fun balance(sid: Long): Long = scalar(
        "SELECT COALESCE(SUM(CASE type WHEN 'charge' THEN amount ELSE -amount END),0) FROM transactions WHERE subscriber_id=? AND cancelled=0",
        listOf(sid))

    // ---------- الحركات المالية ----------
    fun addTransaction(sid: Long, type: String, amount: Long, note: String = "", renewDays: Int = 0, log: Boolean = true): Long {
        if (amount <= 0) throw IllegalArgumentException("المبلغ يجب أن يكون أكبر من صفر")
        if (type == "payment" || type == "discount") {
            val owed = balance(sid)
            if (owed <= 0) throw IllegalArgumentException("لا يوجد دين على هذا المشترك")
            if (amount > owed) throw IllegalArgumentException("المبلغ (${fmt(amount)}) أكبر من الدين الحالي (${fmt(owed)}). أقصى مبلغ ${fmt(owed)}")
        }
        exec("INSERT INTO transactions(subscriber_id,type,amount,date,note,renew_days,collector) VALUES(?,?,?,?,?,?,?)",
            sid, type, amount, today(), note, renewDays, setting("collector").ifEmpty { null })
        val id = scalar("SELECT last_insert_rowid()")
        // سُدّد الدين كاملاً: يسقط وعد الدفع تلقائياً
        if (type != "charge" && balance(sid) <= 0) {
            exec("UPDATE subscribers SET promise_date=NULL, promise_note=NULL WHERE id=?", sid)
            exec("DELETE FROM settings WHERE key IN (?,?)", "auto_debt_cnt_$sid", "auto_debt_last_$sid")
        }
        if (log) audit(when (type) { "payment" -> "تسجيل دفعة"; "discount" -> "خصم"; else -> "إضافة دين" },
            nameOf(sid), "${fmt(amount)} دينار" + (if (note.isNotBlank()) " — $note" else ""))
        return id
    }

    /** تجديد الاشتراك: قيد بسعر الباقة ومدّ التاريخ، مع دفع كامل أو جزئي أو بدون دفع (دين). */
    fun renew(sid: Long, packageId: Long, payNow: Long, note: String = ""): Pair<String, Long?> = tx {
        val sub = getSub(sid) ?: throw IllegalArgumentException("المشترك غير موجود")
        val pkg = getPkg(packageId) ?: throw IllegalArgumentException("الباقة المحددة غير موجودة")
        if (payNow < 0) throw IllegalArgumentException("المبلغ المدفوع لا يمكن أن يكون سالباً")
        if (payNow > pkg.price) throw IllegalArgumentException("المبلغ المدفوع أكبر من سعر الباقة")
        var start = LocalDate.now()
        val curEnd = sub.endDate ?: ""
        if (curEnd.isNotEmpty() && curEnd >= today()) start = LocalDate.parse(curEnd)
        val newEnd = start.plusDays(pkg.days.toLong()).toString()
        addTransaction(sid, "charge", pkg.price, note.ifEmpty { "تجديد: ${pkg.name}" }, pkg.days, log = false)
        exec("UPDATE subscribers SET package_id=?, end_date=? WHERE id=?", packageId, newEnd, sid)
        val payId = if (payNow > 0) addTransaction(sid, "payment", payNow, "دفعة عند التجديد: ${pkg.name}", log = false) else null
        val remaining = pkg.price - payNow
        audit("تجديد اشتراك", sub.name, "${pkg.name} حتى $newEnd، السعر ${fmt(pkg.price)}، المدفوع ${fmt(payNow)}، الدين ${fmt(remaining)}")
        Pair(newEnd, payId)
    }

    private fun txOf(c: Cursor) = Tx(c.l("id"), c.l("subscriber_id"), c.s("type")!!, c.l("amount"), c.s("date")!!,
        c.s("note"), c.l("renew_days").toInt(), c.l("cancelled") == 1L, c.s("cancel_reason"), c.s("collector"))
    private fun getTx(id: Long) = q("SELECT * FROM transactions WHERE id=?", listOf(id)) { txOf(it) }.firstOrNull()

    fun cancelTransaction(txId: Long, reason: String) = tx {
        if (reason.isBlank()) throw IllegalArgumentException("يجب كتابة سبب الإلغاء")
        val t = getTx(txId)
        if (t == null || t.cancelled) throw IllegalArgumentException("الحركة غير موجودة أو ملغاة سابقاً")
        exec("UPDATE transactions SET cancelled=1, cancel_reason=? WHERE id=?", reason.trim(), txId)
        audit("إلغاء حركة مالية", nameOf(t.subId), "${TYPE_AR[t.type] ?: t.type} ${fmt(t.amount)} — السبب: ${reason.trim()}")
        if (t.type == "charge" && t.renewDays > 0) {
            val sub = getSub(t.subId)
            val e = sub?.endDate ?: ""
            if (sub != null && e.isNotEmpty()) {
                exec("UPDATE subscribers SET end_date=? WHERE id=?",
                    LocalDate.parse(e).minusDays(t.renewDays.toLong()).toString(), sub.id)
            }
        }
        Unit
    }

    fun statement(sid: Long): List<Tx> = q("SELECT * FROM transactions WHERE subscriber_id=? ORDER BY id DESC", listOf(sid)) { txOf(it) }

    // ---------- التقارير والتصدير ----------
    fun revenueByDay(start: String, end: String): List<Pair<String, Long>> = q(
        "SELECT date, SUM(amount) AS total FROM transactions WHERE type='payment' AND cancelled=0 AND date BETWEEN ? AND ? GROUP BY date ORDER BY date",
        listOf(start, end)) { Pair(it.getString(0), it.getLong(1)) }

    fun debtsByArea(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (r in listSubs(status = "debt")) {
            val k = (r.area ?: "").ifEmpty { "بدون منطقة" }
            out[k] = (out[k] ?: 0L) + r.bal
        }
        return out
    }

    fun profit(start: String, end: String): Profit {
        val rev = scalar("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type='payment' AND cancelled=0 AND date BETWEEN ? AND ?", listOf(start, end))
        val exp = scalar("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE date BETWEEN ? AND ?", listOf(start, end))
        val f = q("SELECT COALESCE(SUM(liters),0), COALESCE(SUM(hours),0) FROM fuel_log WHERE date BETWEEN ? AND ?", listOf(start, end)) {
            Pair(it.getDouble(0), it.getDouble(1))
        }.first()
        return Profit(rev, exp, rev - exp, f.first, f.second)
    }

    fun exportCsv(out: OutputStream) {
        fun esc(x: Any?): String {
            val t = x?.toString() ?: ""
            return if (t.any { it == ',' || it == '"' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        }
        val head = listOf("الرقم", "الاسم", "الهاتف", "المنطقة", "العنوان", "الباقة", "تاريخ الانتهاء", "الحالة", "الدين") + extraLabels()
        val sb = StringBuilder("\uFEFF")
        sb.append(head.joinToString(",") { esc(it) }).append("\r\n")
        for (r in listSubs()) {
            sb.append(listOf(r.id, r.name, r.phone?.let { "\u200e$it" }, r.area, r.address, r.pkg, r.endDate,
                statusOf(r), r.bal, r.e1, r.e2, r.e3).joinToString(",") { esc(it) }).append("\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    // ---------- الهاتف وواتساب والإيصال ----------
    /** يحوّل أي صيغة لرقم عراقي بصيغة واتساب الدولية: +9647XXXXXXXXX */
    fun formatPhone(text: String?): String {
        var s = (text ?: "").filter { Character.isDigit(it) }.map { Character.digit(it, 10).toString() }.joinToString("")
        if (s.startsWith("00964")) s = s.substring(2)
        if (s.startsWith("964")) s = s.substring(3) else if (s.startsWith("0")) s = s.substring(1)
        if (s.length != 10 || s[0] != '7' || s[1] !in "3456789")
            throw IllegalArgumentException("رقم الهاتف غير صحيح. مثال: 07814521213 أو +9647814521213")
        return "+964$s"
    }

    private fun normalizePhone(p: String?): String =
        try { formatPhone(p).substring(1) } catch (e: Exception) { (p ?: "").filter { it.isDigit() } }

    fun whatsappUrl(sid: Long): String {
        val sub = getSub(sid)!!
        val net = setting("network_name", "الشبكة")
        val msg = "مرحباً ${sub.name}، نذكّرك بأن عليك مبلغ ${fmt(sub.bal)} دينار لدى $net. يرجى التسديد. شكراً لك."
        return "https://wa.me/${normalizePhone(sub.phone)}?text=${Uri.encode(msg)}"
    }

    fun waUrl(phone: String?, text: String): String = "https://wa.me/${normalizePhone(phone)}?text=${Uri.encode(text)}"

    // قوالب الرسائل: {name} {net} {end} {left} {days} {pkg} {debt} {debt_line}
    private val tplDefault = mapOf(
        "soon" to "مرحباً {name}، نذكّرك بأن اشتراكك في {net} ينتهي بتاريخ {end} ({left}). للتجديد يرجى التواصل معنا. شكراً لك.",
        "expired" to "مرحباً {name}، انتهى اشتراكك ({pkg}) في {net} بتاريخ {end}. {debt_line} يرجى التجديد لإعادة تفعيل الخدمة. شكراً لك.",
        "debt" to "مرحباً {name}، نذكّرك بأن عليك مبلغ {debt} دينار لدى {net}. يرجى التسديد. شكراً لك.",
        "debt2" to "مرحباً {name}، هذا تذكير ثانٍ بأن عليك مبلغ {debt} دينار لدى {net}. نرجو التسديد في أقرب وقت لتجنب إيقاف الخدمة. شكراً لك.",
        "debt3" to "مرحباً {name}، تذكير أخير: المبلغ المستحق عليك {debt} دينار لدى {net}. سيتم إيقاف اشتراكك عند عدم التسديد، يرجى المبادرة بالتسديد.",
    )
    fun template(kind: String): String = setting("tpl_$kind", tplDefault[kind] ?: "").ifBlank { tplDefault[kind] ?: "" }
    fun setTemplate(kind: String, text: String) = setSetting("tpl_$kind", text.ifBlank { tplDefault[kind] ?: "" })
    fun defaultTemplate(kind: String): String = tplDefault[kind] ?: ""

    fun reminderText(s: Sub, kind: String): String {
        val net = setting("network_name", "الشبكة")
        val left: Long? = s.endDate?.let { e -> runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(e)) }.getOrNull() }
        val leftTxt = when {
            left == null -> ""
            left < 0 -> "منذ ${-left} يوم"
            left == 0L -> "اليوم"
            left == 1L -> "غداً"
            else -> "بعد $left أيام"
        }
        return template(kind)
            .replace("{name}", s.name).replace("{net}", net).replace("{end}", s.endDate ?: "-")
            .replace("{left}", leftTxt).replace("{days}", (left ?: 0L).toString()).replace("{pkg}", s.pkg ?: "")
            .replace("{debt}", fmt(s.bal)).replace("{debt_line}", if (s.bal > 0) "المتبقي عليك ${fmt(s.bal)} دينار." else "")
    }

    // ---------- كروت الهوتسبوت ----------
    fun addVoucher(code: String, pass: String, profile: String, batch: String, price: Long) =
        exec("INSERT INTO vouchers(code,pass,profile,batch,price,created) VALUES(?,?,?,?,?,?)", code, pass, profile, batch, price, today())
    fun vouchers(sold: Boolean?): List<Voucher> = q(
        "SELECT * FROM vouchers" + (if (sold == null) "" else " WHERE sold=${if (sold) 1 else 0}") + " ORDER BY id DESC LIMIT 3000") {
        Voucher(it.l("id"), it.s("code") ?: "", it.s("pass") ?: "", it.s("profile") ?: "", it.s("batch") ?: "", it.l("price"), it.s("created") ?: "", it.l("sold") == 1L, it.s("sold_at"))
    }
    fun markVoucherSold(id: Long) = exec("UPDATE vouchers SET sold=1, sold_at=? WHERE id=?", today(), id)
    fun deleteVoucher(id: Long) = exec("DELETE FROM vouchers WHERE id=?", id)

    // ---------- رؤى ذكية ----------
    fun insights(): Insights {
        val subs = listSubs()
        val today = LocalDate.now()
        val price = packages().associate { it.id to it.price }
        fun due(s: Sub): LocalDate? = runCatching { LocalDate.parse(s.endDate) }.getOrNull()
        fun within(s: Sub, days: Long) = s.frozenAt == null && (due(s)?.let { !it.isBefore(today) && !it.isAfter(today.plusDays(days)) } == true)
        val week = subs.filter { within(it, 7) }
        val month = subs.filter { within(it, 30) }
        val risk = subs.filter { s -> s.frozenAt == null && (due(s)?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) in 4..30 } == true) }
        val m = today.toString().take(7)
        val charges = scalar("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type='charge' AND cancelled=0 AND substr(date,1,7)=?", listOf(m))
        val pays = scalar("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type='payment' AND cancelled=0 AND substr(date,1,7)=?", listOf(m))
        return Insights(week.size, week.sumOf { price[it.packageId ?: -1L] ?: 0L }, month.size, month.sumOf { price[it.packageId ?: -1L] ?: 0L },
            risk, if (charges > 0) (pays * 100 / charges).toInt().coerceAtMost(999) else null)
    }

    // ---------- سجل الرسائل ----------
    fun logMsg(via: String, name: String, phone: String, body: String, status: String = "", parts: Int = 1, kind: String = "", retries: Int = 0): Long {
        exec("INSERT INTO msg_log(ts,via,name,phone,body,status,parts,kind,retries) VALUES(?,?,?,?,?,?,?,?,?)",
            java.time.LocalDateTime.now().toString().take(16).replace('T', ' '), via, name, phone, body, status, parts, kind, retries)
        return scalar("SELECT last_insert_rowid()")
    }
    fun setMsgStatus(id: Long, status: String) = exec("UPDATE msg_log SET status=? WHERE id=?", status, id)
    private fun msgOf(c: Cursor) = MsgLog(c.l("id"), c.s("ts") ?: "", c.s("via") ?: "", c.s("name") ?: "", c.s("phone") ?: "", c.s("body") ?: "",
        c.s("status") ?: "", c.l("parts").toInt().coerceAtLeast(1), c.s("kind") ?: "", c.l("retries").toInt())
    fun msgLog(limit: Int = 300): List<MsgLog> = q("SELECT * FROM msg_log ORDER BY id DESC LIMIT $limit") { msgOf(it) }
    /** رسائل SMS الفاشلة (للمحاولة مرة أخرى). */
    fun failedMsgs(limit: Int = 200): List<MsgLog> =
        q("SELECT * FROM msg_log WHERE status='failed' AND via IN ('sms','auto') ORDER BY id DESC LIMIT $limit") { msgOf(it) }
    /** إحصاء رسائل SMS في الشهر: (عدد الرسائل، عدد الأجزاء المحتسبة، الفاشلة). */
    fun smsStats(ym: String = LocalDate.now().toString().take(7)): Triple<Int, Int, Int> {
        val rows = q("SELECT COUNT(*), COALESCE(SUM(parts),0), COALESCE(SUM(CASE WHEN status='failed' THEN 1 ELSE 0 END),0) FROM msg_log WHERE via IN ('sms','auto') AND ts LIKE ?",
            listOf("$ym%")) { Triple(it.getInt(0), it.getInt(1), it.getInt(2)) }
        return rows.firstOrNull() ?: Triple(0, 0, 0)
    }
    /** إيقاف/السماح بالرسائل الآلية لمشترك. */
    fun setNoMsg(sid: Long, v: Boolean) {
        exec("UPDATE subscribers SET no_msg=? WHERE id=?", if (v) 1 else 0, sid)
        audit(if (v) "إيقاف الرسائل للمشترك" else "السماح بالرسائل للمشترك", nameOf(sid))
    }

    /** يضيف جهات اتصال كمشتركين. يرجع (المضاف، المكرر، الرقم غير الصالح). */
    fun importContacts(list: List<Pair<String, String>>): Triple<Int, Int, Int> {
        var added = 0; var dup = 0; var bad = 0
        val seen = listSubs().map { (it.phone ?: "").filter { ch -> ch.isDigit() }.takeLast(10) }.toMutableSet()
        for ((name, num) in list) {
            val ph = try { formatPhone(num) } catch (e: Exception) { bad++; continue }
            val k = ph.filter { it.isDigit() }.takeLast(10)
            if (k in seen) { dup++; continue }
            val (ok, msg) = canAddSubscriber()
            if (!ok) throw IllegalArgumentException(msg)
            saveSub(mapOf("name" to name.ifBlank { ph }, "phone" to ph), log = false)
            seen.add(k); added++
        }
        if (added > 0) audit("استيراد من جهات الاتصال", "", "أُضيف $added مشترك")
        return Triple(added, dup, bad)
    }

    fun isSent(kind: String, sid: Long): Boolean = setting("wa_${kind}_$sid") == today()
    fun markSent(kind: String, sid: Long) = setSetting("wa_${kind}_$sid", today())

    /** وصل آخر تجديد (اشتراك جديد) بتفاصيله، جاهز للإرسال للمشترك. */
    fun renewSlip(sid: Long): String {
        val sub = getSub(sid) ?: throw IllegalArgumentException("المشترك غير موجود")
        val ch = q("SELECT * FROM transactions WHERE subscriber_id=? AND type='charge' AND cancelled=0 AND renew_days>0 ORDER BY id DESC LIMIT 1",
            listOf(sid)) { txOf(it) }.firstOrNull() ?: throw IllegalArgumentException("لا يوجد تجديد مسجّل")
        val paid = q("SELECT amount FROM transactions WHERE subscriber_id=? AND type='payment' AND cancelled=0 AND id>? AND date=? AND note='دفعة عند التجديد' ORDER BY id LIMIT 1",
            listOf(sid, ch.id, ch.date)) { it.getLong(0) }.firstOrNull() ?: 0L
        val end = sub.endDate ?: "-"
        val start = sub.endDate?.let { LocalDate.parse(it).minusDays(ch.renewDays.toLong()).toString() } ?: "-"
        return listOf(
            setting("network_name", "الشبكة"), "――――――――――――",
            "وصل اشتراك رقم: ${ch.id}", "التاريخ: ${ch.date}", "المشترك: ${sub.name}",
            "الباقة: ${sub.pkg ?: "-"} (${ch.renewDays} يوم)", "السعر: ${fmt(ch.amount)} دينار",
            "المدفوع: ${fmt(paid)} دينار", "المتبقي (الدين): ${fmt(sub.bal)} دينار",
            "فترة الاشتراك: من $start إلى $end", "", "شكراً لتعاملكم معنا",
        ).joinToString("\n")
    }

    fun receiptText(txId: Long): String {
        val t = getTx(txId)!!
        val sub = getSub(t.subId)!!
        return listOf(
            setting("network_name", "الشبكة"), "――――――――――――",
            "رقم الإيصال: ${t.id}", "التاريخ: ${t.date}", "المشترك: ${sub.name}",
            "نوع الحركة: ${TYPE_AR[t.type]}", "المبلغ: ${fmt(t.amount)} دينار",
            "الدين المتبقي: ${fmt(sub.bal)} دينار",
            if (isGenerator()) "عدد الأمبيرات: ${g(ampsOf(sub))}" else "تاريخ انتهاء الاشتراك: ${sub.endDate ?: "-"}",
            "ملاحظة: ${t.note ?: "-"}", "المستلم: ${t.collector ?: "-"}", "", "شكراً لتعاملكم معنا",
        ).joinToString("\n")
    }

    // ---------- المولدة: سعر الأمبير والفوترة الشهرية ----------
    fun ampPrice(): Long = numOf(setting("amp_price", "0"))?.toLong() ?: 0L
    fun setAmpPrice(v: Long) = setSetting("amp_price", v.toString())
    fun ampsFromText(t: String): Double =
        Regex("[0-9]+(\\.[0-9]+)?").find(normDigits(t))?.value?.toDoubleOrNull() ?: 0.0
    fun ampsOf(s: Sub): Double = ampsFromText(s.e2 ?: "")
    fun priceOf(s: Sub, ampPrice: Long = ampPrice()): Long = Math.round(ampsOf(s) * ampPrice)

    /** حالة كل مشترك لشهر معيّن (tag مثل 2026-10): هل فُوتر وكم سدّد. */
    fun monthRows(tag: String, ampPrice: Long): List<MonthRow> {
        YearMonth.parse(tag)
        val sums = HashMap<Pair<Long, String>, Long>()
        q("SELECT subscriber_id, type, SUM(amount) FROM transactions WHERE cancelled=0 AND note IN (?,?) GROUP BY subscriber_id, type",
            listOf("اشتراك شهر $tag", "تسديد شهر $tag")) { Triple(it.getLong(0), it.getString(1), it.getLong(2)) }
            .forEach { sums[Pair(it.first, it.second)] = it.third }
        return listSubs().filter { !it.cut }.map { s ->
            val amps = ampsOf(s)
            MonthRow(s, amps, Math.round(amps * ampPrice), sums[Pair(s.id, "charge")], sums[Pair(s.id, "payment")] ?: 0L)
        }
    }

    /** يقيّد اشتراك الشهر على كل المشتركين (فمن لم يُسدَّد يبقى ديناً)، ويسدّد المحدّدين فقط. */
    fun billMonth(tag: String, ampPrice: Long, payIds: Set<Long>): MonthResult {
        if (ampPrice <= 0) throw IllegalArgumentException("أدخل سعر الأمبير أولاً")
        val rows = monthRows(tag, ampPrice)
        return tx {
            var billed = 0
            var paidN = 0
            var total = 0L
            for (r in rows) {
                if (r.amps <= 0) continue
                var charged = r.charged
                if (charged == null) {
                    if (r.price <= 0) continue
                    addTransaction(r.sub.id, "charge", r.price, "اشتراك شهر $tag", log = false)
                    charged = r.price
                    billed++
                }
                if (r.sub.id in payIds) {
                    val left = charged - r.paid
                    if (left > 0) { addTransaction(r.sub.id, "payment", left, "تسديد شهر $tag", log = false); paidN++; total += left }
                }
            }
            audit("فوترة شهر $tag", "", "فُوتر $billed وسُدّد $paidN بمبلغ ${fmt(total)}")
            MonthResult(billed, paidN, total)
        }
    }

    // ---------- العدادات (المولدات) ----------
    fun lastReading(sid: Long): Double =
        q("SELECT reading FROM meter_readings WHERE subscriber_id=? ORDER BY id DESC LIMIT 1", listOf(sid)) { it.getDouble(0) }.firstOrNull() ?: 0.0

    fun addReading(sid: Long, reading: Double, rate: Long): Triple<Double, Long, Long?> = tx {
        val prev = lastReading(sid)
        if (reading < prev) throw IllegalArgumentException("القراءة الجديدة أقل من السابقة")
        val units = reading - prev
        val amount = Math.round(units * rate)
        val t = if (amount > 0) addTransaction(sid, "charge", amount, "فاتورة عداد: ${g(units)} وحدة × ${fmt(rate)}") else null
        exec("INSERT INTO meter_readings(subscriber_id,date,reading,prev,rate,amount) VALUES(?,?,?,?,?,?)",
            sid, today(), reading, prev, rate, amount)
        Triple(units, amount, t)
    }

    // ---------- المصاريف والوقود ----------
    fun addExpense(category: String, amount: Long, note: String = ""): Long {
        if (amount <= 0) throw IllegalArgumentException("المبلغ يجب أن يكون أكبر من صفر")
        exec("INSERT INTO expenses(date,category,amount,note) VALUES(?,?,?,?)", today(), category.ifEmpty { "عام" }, amount, note)
        audit("تسجيل مصروف", category.ifEmpty { "عام" }, "${fmt(amount)} دينار" + (if (note.isNotBlank()) " — $note" else ""))
        return scalar("SELECT last_insert_rowid()")
    }

    fun addFuel(liters: Double, cost: Long, hours: Double, note: String) = tx {
        val eid = addExpense("وقود", cost, "${g(liters)} لتر" + (if (hours > 0) " / ${g(hours)} ساعة تشغيل" else "") +
            (if (note.isNotEmpty()) " - $note" else ""))
        exec("INSERT INTO fuel_log(date,liters,cost,hours,note,expense_id) VALUES(?,?,?,?,?,?)", today(), liters, cost, hours, note, eid)
    }

    fun listExpenses(): List<Expense> = q("SELECT * FROM expenses ORDER BY date DESC, id DESC LIMIT 300") {
        Expense(it.l("id"), it.s("date")!!, it.s("category"), it.l("amount"), it.s("note"))
    }

    fun deleteExpense(id: Long) = tx {
        q("SELECT category, amount, note FROM expenses WHERE id=?", listOf(id)) { Triple(it.s("category") ?: "", it.l("amount"), it.s("note") ?: "") }
            .firstOrNull()?.let { audit("حذف مصروف", it.first, "${fmt(it.second)} دينار" + (if (it.third.isNotBlank()) " — ${it.third}" else "")) }
        exec("DELETE FROM fuel_log WHERE expense_id=?", id)
        exec("DELETE FROM expenses WHERE id=?", id)
    }

    // ---------- التنبيهات ----------
    fun alerts(days: Long = 3): Alerts {
        val t = today()
        val soon = LocalDate.now().plusDays(days).toString()
        val subs = listSubs()
        return Alerts(
            subs.filter { val e = it.endDate ?: ""; e.isNotEmpty() && it.frozenAt == null && e >= t && e <= soon },
            subs.filter { val e = it.endDate ?: ""; e.isNotEmpty() && it.frozenAt == null && e < t },
            subs.filter { it.bal > 0 })
    }

    fun alertsText(): String {
        val a = alerts()
        val p = ArrayList<String>()
        if (a.expiring.isNotEmpty()) p.add("اشتراكات تنتهي خلال 3 أيام: ${a.expiring.size}")
        if (a.expired.isNotEmpty()) p.add("اشتراكات منتهية: ${a.expired.size}")
        if (a.debtors.isNotEmpty()) p.add("مدينون: ${a.debtors.size} (المجموع ${fmt(a.debtors.sumOf { it.bal })} دينار)")
        val dueP = promises().count { it.promiseDate == today() || promiseBroken(it) }
        if (dueP > 0) p.add("وعود دفع مستحقة أو متأخرة: $dueP")
        return p.joinToString("   |   ")
    }

    // ---------- الموقع والخريطة ----------
    /** يقبل "lat,lng" أو أي نص/رابط خرائط يحتوي إحداثيين (مثل رابط جوجل ماب). */
    fun parseLocation(text: String?): Pair<Double?, Double?> {
        val t = (text ?: "").trim()
        if (t.isEmpty()) return Pair(null, null)
        val m = Regex("(-?\\d{1,3}(?:\\.\\d+)?)\\s*[,،]\\s*(-?\\d{1,3}(?:\\.\\d+)?)").find(normDigits(t))
            ?: throw IllegalArgumentException("صيغة الموقع: خط العرض,خط الطول  مثال: 33.3152,44.3661")
        val lat = m.groupValues[1].toDouble()
        val lng = m.groupValues[2].toDouble()
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) throw IllegalArgumentException("إحداثيات غير صحيحة")
        return Pair(lat, lng)
    }

    fun mapPoints(): List<MapPt> {
        val out = ArrayList<MapPt>()
        for (r in listSubs()) {
            val la = r.lat; val ln = r.lng
            if (la == null || ln == null) continue
            val st = statusOf(r)
            val color = if (r.bal > 0) 0xFFD32F2F.toInt() else if (st != "فعّال" && st != "مسدّد") 0xFFF9A825.toInt() else 0xFF2E7D32.toInt()
            val ph = (r.phone ?: "").trim()
            out.add(MapPt(la, ln, color, r.name, "$st\nالدين: ${fmt(r.bal)}" + (if (ph.isEmpty()) "" else "\n$ph")))
        }
        if (out.isEmpty()) throw IllegalArgumentException("لا توجد مواقع مسجلة للمشتركين")
        return out
    }

    fun mapHtml(): String {
        val arr = JSONArray()
        for (r in listSubs()) {
            if (r.lat == null || r.lng == null) continue
            val st = statusOf(r)
            val color = if (r.bal > 0) "#d32f2f" else if (st != "فعّال" && st != "مسدّد") "#f9a825" else "#2e7d32"
            arr.put(JSONObject().put("lat", r.lat).put("lng", r.lng).put("color", color).put("popup",
                "<b>${TextUtils.htmlEncode(r.name)}</b><br>$st<br>الدين: ${fmt(r.bal)}<br>${TextUtils.htmlEncode(r.phone ?: "")}"))
        }
        if (arr.length() == 0) throw IllegalArgumentException("لا توجد مواقع مسجلة للمشتركين")
        val data = arr.toString().replace("<", "\\u003c")
        return subsPage(data)
    }

    // ---------- قطع/وصل (مولدة) وتجميد/أيام مجانية (إنترنت) ----------
    /** حذف مشترك مع سجلاته التابعة، بعد تأكيد المستخدم من الواجهة. */
    fun deleteSubscriber(sid: Long) {
        val name = nameOf(sid)
        tx {
            exec("DELETE FROM transactions WHERE subscriber_id=?", sid)
            exec("DELETE FROM meter_readings WHERE subscriber_id=?", sid)
            exec("DELETE FROM subscribers WHERE id=?", sid)
            audit("حذف مشترك", name, "حُذفت سجلاته المالية وقراءات العداد التابعة")
        }
    }

    fun setCut(sid: Long, cut: Boolean) {
        exec("UPDATE subscribers SET cut=? WHERE id=?", if (cut) 1 else 0, sid)
        audit(if (cut) "قطع الخدمة" else "إعادة الوصل", nameOf(sid))
    }
    fun freeze(sid: Long) {
        exec("UPDATE subscribers SET frozen_at=? WHERE id=?", today(), sid)
        audit("تجميد اشتراك", nameOf(sid))
    }
    /** يلغي التجميد ويمدّ تاريخ الانتهاء بعدد أيام التجميد. يعيد عدد الأيام المضافة. */
    fun unfreeze(sid: Long): Long {
        val sub = getSub(sid) ?: throw IllegalArgumentException("المشترك غير موجود")
        val fz = sub.frozenAt ?: return 0L
        val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(fz), LocalDate.now()).coerceAtLeast(0)
        val e = sub.endDate ?: ""
        if (e.isNotEmpty() && days > 0) exec("UPDATE subscribers SET end_date=? WHERE id=?", LocalDate.parse(e).plusDays(days).toString(), sid)
        exec("UPDATE subscribers SET frozen_at=NULL WHERE id=?", sid)
        audit("إلغاء تجميد", sub.name, "أُضيف $days يوم")
        return days
    }
    fun giveDays(sid: Long, days: Int) {
        if (days <= 0) throw IllegalArgumentException("أدخل عدد أيام صحيحاً")
        val sub = getSub(sid) ?: throw IllegalArgumentException("المشترك غير موجود")
        val e = sub.endDate ?: ""
        val base = if (e.isNotEmpty() && e >= today()) LocalDate.parse(e) else LocalDate.now()
        val note = ((sub.notes ?: "") + "\n[${today()}] منح $days يوم مجاناً").trim()
        exec("UPDATE subscribers SET end_date=?, notes=? WHERE id=?", base.plusDays(days.toLong()).toString(), note, sid)
        audit("منح أيام مجانية", sub.name, "$days يوم")
    }

    /** مشتركو المولدة الذين تجاوز دينهم (عدد أشهر) من اشتراكهم الشهري ولم يُقطعوا بعد. */
    fun cutCandidates(months: Double, ampPrice: Long = ampPrice()): List<Pair<Sub, Long>> =
        listSubs(status = "debt").filter { !it.cut && !promiseActive(it) }.mapNotNull { s ->
            val p = priceOf(s, ampPrice)
            if (p > 0 && s.bal >= months * p) Pair(s, p) else null
        }

    // ---------- أعمار الديون ----------
    data class AgingRow(val sub: Sub, val oldestDays: Long, val buckets: LongArray)
    private data class TRow(val sid: Long, val type: String, val amount: Long, val date: String)

    /** يوزّع الدفعات والخصومات على أقدم القيود أولاً، والباقي هو الدين حسب عمره. */
    fun debtAging(): List<AgingRow> {
        val subs = listSubs(status = "debt").associateBy { it.id }
        if (subs.isEmpty()) return emptyList()
        val rows = q("SELECT subscriber_id, type, amount, date FROM transactions WHERE cancelled=0 ORDER BY subscriber_id, date, id") {
            TRow(it.getLong(0), it.getString(1), it.getLong(2), it.getString(3))
        }.groupBy { it.sid }
        val today = LocalDate.now()
        val out = ArrayList<AgingRow>()
        for ((sid, sub) in subs) {
            val list = rows[sid] ?: continue
            var credit = list.filter { it.type != "charge" }.sumOf { it.amount }
            val b = LongArray(4)
            var oldest = 0L
            for (t in list) if (t.type == "charge") {
                val left = if (credit >= t.amount) { credit -= t.amount; 0L } else { val l = t.amount - credit; credit = 0; l }
                if (left > 0) {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(t.date), today).coerceAtLeast(0)
                    b[if (days <= 30) 0 else if (days <= 60) 1 else if (days <= 90) 2 else 3] += left
                    if (days > oldest) oldest = days
                }
            }
            if (b.sum() > 0) out.add(AgingRow(sub, oldest, b))
        }
        return out.sortedByDescending { it.sub.bal }
    }

    // ---------- صندوق اليوم ----------
    data class DayRow(val name: String, val amount: Long, val collector: String?, val note: String?, val type: String)
    data class DayReport(val date: String, val payments: List<DayRow>, val byCollector: Map<String, Long>, val discounts: Long, val charges: Long, val expenses: Long) {
        val total: Long get() = payments.sumOf { it.amount }
        val net: Long get() = total - expenses
    }

    fun dayReport(date: String): DayReport {
        LocalDate.parse(date)
        val rows = q("SELECT s.name, t.type, t.amount, t.collector, t.note FROM transactions t JOIN subscribers s ON s.id=t.subscriber_id WHERE t.cancelled=0 AND t.date=? ORDER BY t.id",
            listOf(date)) { DayRow(it.getString(0), it.getLong(2), it.s("collector"), it.s("note"), it.getString(1)) }
        val pays = rows.filter { it.type == "payment" }
        val exp = scalar("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE date=?", listOf(date))
        return DayReport(date, pays, pays.groupBy { it.collector ?: "بدون اسم" }.mapValues { e -> e.value.sumOf { it.amount } },
            rows.filter { it.type == "discount" }.sumOf { it.amount }, rows.filter { it.type == "charge" }.sumOf { it.amount }, exp)
    }

    // ---------- رسم شهري ----------
    fun monthlyTotals(n: Int = 6): List<Triple<String, Long, Long>> {
        val months = (n - 1 downTo 0).map { YearMonth.now().minusMonths(it.toLong()).toString() }
        val rev = q("SELECT substr(date,1,7), SUM(amount) FROM transactions WHERE type='payment' AND cancelled=0 GROUP BY 1") { Pair(it.getString(0), it.getLong(1)) }.toMap()
        val exp = q("SELECT substr(date,1,7), SUM(amount) FROM expenses GROUP BY 1") { Pair(it.getString(0), it.getLong(1)) }.toMap()
        return months.map { Triple(it, rev[it] ?: 0L, exp[it] ?: 0L) }
    }

    // ---------- استيراد CSV (Excel) ----------
    data class ImportResult(val added: Int, val skipped: Int, val notes: List<String>)

    fun importCsv(input: java.io.InputStream): ImportResult {
        val rows = parseCsv(input.readBytes().toString(Charsets.UTF_8))
        if (rows.size < 2) throw IllegalArgumentException("الملف فارغ أو لا يحتوي صفوفاً بعد السطر الأول")
        val head = rows[0].map { it.trim().lowercase() }
        fun col(vararg names: String): Int = head.indexOfFirst { h -> names.any { it.lowercase() == h } }
        val iName = col("الاسم", "name", "اسم المشترك")
        if (iName < 0) throw IllegalArgumentException("لم أجد عمود \"الاسم\" في السطر الأول")
        val iPhone = col("الهاتف", "phone", "رقم الهاتف", "الموبايل", "mobile")
        val iArea = col("المنطقة", "area"); val iAddr = col("العنوان", "address"); val iPkg = col("الباقة", "package")
        val iEnd = col("تاريخ الانتهاء", "تاريخ انتهاء الاشتراك", "end_date", "end")
        val iDebt = col("الدين", "debt", "المتبقي"); val iNotes = col("ملاحظات", "notes")
        val labels = extraLabels().map { it.lowercase() }
        val iE = (0..2).map { i -> col(labels[i], "extra${i + 1}") }
        val iAmps = col("الأمبير", "الامبير", "amps", "عدد الأمبيرات", "عدد الامبيرات")
        fun cell(r: List<String>, i: Int) = if (i in r.indices) r[i].trim() else ""
        val gen = isGenerator()
        val pk = packages().associateBy { it.name.trim().lowercase() }
        val seen = listSubs().map { "${it.name.trim()}|${(it.phone ?: "").filter { ch -> ch.isDigit() }}" }.toMutableSet()
        val notes = ArrayList<String>()
        return tx {
            var added = 0
            var skipped = 0
            for ((n, r) in rows.drop(1).withIndex()) {
                val name = cell(r, iName)
                if (name.isEmpty()) { skipped++; continue }
                val raw = cell(r, iPhone)
                val phone: String? = if (raw.isEmpty()) null else try { formatPhone(raw) } catch (e: Exception) { raw.filter { it.isDigit() || it == '+' }.ifEmpty { null } }
                val key = "$name|${(phone ?: "").filter { it.isDigit() }}"
                if (!seen.add(key)) { skipped++; continue }
                val (ok, msg) = canAddSubscriber()
                if (!ok) { notes.add(msg); skipped += rows.size - 1 - n; break }
                var e2 = cell(r, iE[1]).ifEmpty { cell(r, iAmps) }
                if (gen) e2 = ampsFromText(e2).takeIf { it > 0 }?.let { g(it) } ?: e2
                val id = saveSub(mapOf(
                    "name" to name, "phone" to phone, "area" to cell(r, iArea), "address" to cell(r, iAddr),
                    "package_id" to (if (gen) null else pk[cell(r, iPkg).lowercase()]?.id),
                    "end_date" to (if (gen) null else parseDateLoose(cell(r, iEnd))),
                    "extra1" to cell(r, iE[0]), "extra2" to e2, "extra3" to cell(r, iE[2]), "notes" to cell(r, iNotes)), log = false)
                val debt = numOf(cell(r, iDebt))?.toLong() ?: 0L
                if (debt > 0) addTransaction(id, "charge", debt, "دين سابق (استيراد)", log = false)
                added++
            }
            if (added > 0) audit("استيراد مشتركين من ملف", "", "أُضيف $added وتخطّي $skipped")
            ImportResult(added, skipped, notes)
        }
    }

    // ---------- سجل العمليات ----------
    private fun actor(): String = if (Session.role == "staff") Session.name.ifEmpty { "موظف" } else "المدير"
    private fun nameOf(sid: Long): String = q("SELECT name FROM subscribers WHERE id=?", listOf(sid)) { it.getString(0) }.firstOrNull() ?: ""

    /** يسجّل عملية حساسة (من فعل ماذا ومتى). لا يُفشل العملية الأصلية إن تعذّر التسجيل. */
    fun audit(action: String, target: String = "", detail: String = "") {
        try {
            exec("INSERT INTO audit_log(ts,actor,action,target,detail) VALUES(?,?,?,?,?)",
                java.time.LocalDateTime.now().toString().take(16).replace('T', ' '), actor(), action, target, detail)
            if (scalar("SELECT COUNT(*) FROM audit_log") > 6000) exec("DELETE FROM audit_log WHERE id <= (SELECT MAX(id) FROM audit_log) - 5000")
        } catch (e: Exception) { }
    }

    fun auditLog(limit: Int = 500, search: String = "", onlyMoney: Boolean = false): List<AuditRow> {
        val args = ArrayList<Any?>()
        val where = ArrayList<String>()
        if (search.isNotBlank()) { where.add("(target LIKE ? OR actor LIKE ? OR action LIKE ? OR detail LIKE ?)"); repeat(4) { args.add("%${search.trim()}%") } }
        if (onlyMoney) where.add("(action LIKE '%دفعة%' OR action LIKE '%خصم%' OR action LIKE '%دين%' OR action LIKE '%تجديد%' OR action LIKE '%مصروف%' OR action LIKE '%إلغاء حركة%')")
        val sql = "SELECT * FROM audit_log" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY id DESC LIMIT $limit"
        return q(sql, args) { AuditRow(it.l("id"), it.s("ts") ?: "", it.s("actor") ?: "", it.s("action") ?: "", it.s("target") ?: "", it.s("detail") ?: "") }
    }

    // ---------- وعد بالدفع ----------
    /** وعد ساري: على المشترك دين وتاريخ الوعد لم يحن بعد (اليوم يُحتسب). */
    fun promiseActive(s: Sub): Boolean = s.bal > 0 && !s.promiseDate.isNullOrEmpty() && s.promiseDate >= today()
    /** وعد متأخر: حلّ تاريخه ولم يُسدَّد الدين. */
    fun promiseBroken(s: Sub): Boolean = s.bal > 0 && !s.promiseDate.isNullOrEmpty() && s.promiseDate < today()

    fun setPromise(sid: Long, date: String, note: String = "") {
        val d0 = try { LocalDate.parse(normDigits(date.trim())) } catch (e: Exception) { throw IllegalArgumentException("اختر تاريخاً صحيحاً للوعد") }
        if (d0.isBefore(LocalDate.now())) throw IllegalArgumentException("تاريخ الوعد يجب أن يكون اليوم أو بعده")
        val sub = getSub(sid) ?: throw IllegalArgumentException("المشترك غير موجود")
        if (sub.bal <= 0) throw IllegalArgumentException("لا يوجد دين على هذا المشترك")
        exec("UPDATE subscribers SET promise_date=?, promise_note=? WHERE id=?", d0.toString(), note.trim().ifEmpty { null }, sid)
        audit("وعد بالدفع", sub.name, "حتى $d0 — الدين ${fmt(sub.bal)}" + (if (note.isNotBlank()) " — ${note.trim()}" else ""))
    }
    fun clearPromise(sid: Long) {
        val sub = getSub(sid) ?: return
        exec("UPDATE subscribers SET promise_date=NULL, promise_note=NULL WHERE id=?", sid)
        audit("إلغاء وعد الدفع", sub.name)
    }
    /** كل من لديه وعد وعليه دين، الأقرب تاريخاً أولاً. */
    fun promises(): List<Sub> = listSubs().filter { !it.promiseDate.isNullOrEmpty() && it.bal > 0 }.sortedBy { it.promiseDate }

    // ---------- النسخ الاحتياطي ----------
    fun backup(out: OutputStream) {
        File(d.path).inputStream().use { it.copyTo(out) }
    }

    fun restore(input: java.io.InputStream) {
        val tmp = File(ctx.cacheDir, "restore.db")
        tmp.outputStream().use { input.copyTo(it) }
        val ok = try {
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { t ->
                t.rawQuery("SELECT 1 FROM sqlite_master WHERE name='subscribers'", null).use { it.moveToFirst() }
            }
        } catch (e: Exception) { false }
        if (!ok) { tmp.delete(); throw IllegalArgumentException("هذا الملف ليس نسخة احتياطية صالحة") }
        val target = File(d.path)
        helper.close()
        tmp.copyTo(target, overwrite = true)
        File(target.path + "-wal").delete(); File(target.path + "-shm").delete(); File(target.path + "-journal").delete()
        tmp.delete()
        helper = Helper(ctx, dbFileName(mode()))
        audit("استعادة نسخة احتياطية")
    }

    // ---------- رمز القفل PIN ----------
    private fun hashPin(salt: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    fun hasPin() = setting("pin_hash").isNotEmpty()
    fun setPin(pin: String) {
        if (pin.isEmpty()) { prefs.edit().remove("pin_hash").remove("pin_salt").remove("staff_json").apply(); return }
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        setSetting("pin_salt", salt)
        setSetting("pin_hash", hashPin(salt, pin))
    }
    fun checkPin(pin: String) = hashPin(setting("pin_salt"), pin) == setting("pin_hash")

    // ---------- الموظفون ----------
    private fun staffArr(): JSONArray = try { JSONArray(setting("staff_json", "[]")) } catch (e: Exception) { JSONArray() }
    fun staffList(): List<Staff> {
        val a = staffArr()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Staff(it.getString("id"), it.getString("name")) }
    }
    fun addStaff(name: String, pin: String) {
        if (name.isBlank()) throw IllegalArgumentException("اسم الموظف مطلوب")
        if (!hasPin()) throw IllegalArgumentException("عيّن رمز PIN للمدير أولاً")
        if (pin.length < 4 || !pin.all { it.isDigit() }) throw IllegalArgumentException("رمز الموظف 4 أرقام على الأقل")
        if (checkPin(pin)) throw IllegalArgumentException("هذا الرمز مستخدم للمدير")
        val a = staffArr()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (hashPin(o.getString("salt"), pin) == o.getString("hash")) throw IllegalArgumentException("هذا الرمز مستخدم لموظف آخر")
            if (o.getString("name") == name.trim()) throw IllegalArgumentException("يوجد موظف بنفس الاسم")
        }
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        a.put(JSONObject().put("id", salt).put("name", name.trim()).put("salt", salt).put("hash", hashPin(salt, pin)))
        setSetting("staff_json", a.toString())
        audit("إضافة موظف", name.trim())
    }
    fun removeStaff(id: String) {
        val a = staffArr()
        staffList().firstOrNull { it.id == id }?.let { audit("حذف موظف", it.name) }
        val n = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.getString("id") != id) n.put(o) }
        setSetting("staff_json", n.toString())
    }
    /** يتحقق من الرمز ويحدد المستخدم الحالي (مدير أو موظف). */
    fun pinLockSeconds(): Long = ((prefs.getLong("pin_lock_until", 0) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    /** يتحقق من الرمز. بعد 5 محاولات خاطئة يُقفل الدخول مدة تتضاعف (30 ثانية فما فوق). */
    fun login(pin: String): Boolean {
        val wait = pinLockSeconds()
        if (wait > 0) throw IllegalStateException("محاولات خاطئة كثيرة. انتظر $wait ثانية")
        var ok = false
        if (checkPin(pin)) { Session.role = "admin"; Session.name = ""; ok = true }
        else {
            val a = staffArr()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (hashPin(o.getString("salt"), pin) == o.getString("hash")) { Session.role = "staff"; Session.name = o.getString("name"); ok = true; break }
            }
        }
        if (ok) { prefs.edit().putInt("pin_fails", 0).putLong("pin_lock_until", 0).apply(); return true }
        val f = prefs.getInt("pin_fails", 0) + 1
        val e = prefs.edit().putInt("pin_fails", f)
        if (f >= 5) e.putLong("pin_lock_until", System.currentTimeMillis() + minOf(30L * (1L shl minOf(f - 5, 6)), 3600L) * 1000)
        e.apply()
        return false
    }

    // ---------- التقرير الشامل ----------
    fun fullReport(from: String, to: String): FullReport {
        val pf = profit(from, to)
        fun sum(type: String) = scalar("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type=? AND cancelled=0 AND date BETWEEN ? AND ?", listOf(type, from, to))
        val subs = listSubs()
        val debtors = subs.filter { it.bal > 0 }.sortedByDescending { it.bal }
        val sts = subs.map { statusOf(it) }
        return FullReport(
            from, to, pf.revenue, sum("charge"), sum("discount"), pf.expenses, pf.net,
            scalar("SELECT COUNT(*) FROM subscribers WHERE substr(created_at,1,10) BETWEEN ? AND ?", listOf(from, to)).toInt(),
            subs.size, sts.count { it == "فعّال" || it == "مسدّد" }, sts.count { it == "منتهي" }, debtors.size, debtors.sumOf { it.bal },
            q("SELECT COALESCE(NULLIF(collector,''),'غير محدد'), SUM(amount), COUNT(*) FROM transactions WHERE type='payment' AND cancelled=0 AND date BETWEEN ? AND ? GROUP BY 1 ORDER BY 2 DESC", listOf(from, to)) { Triple(it.getString(0), it.getLong(1), it.getInt(2)) },
            revenueByDay(from, to),
            q("SELECT COALESCE(NULLIF(category,''),'أخرى'), SUM(amount) FROM expenses WHERE date BETWEEN ? AND ? GROUP BY 1 ORDER BY 2 DESC", listOf(from, to)) { Pair(it.getString(0), it.getLong(1)) },
            debtors,
            q("SELECT t.date, s.name, t.amount, t.collector, t.note FROM transactions t JOIN subscribers s ON s.id=t.subscriber_id WHERE t.type='payment' AND t.cancelled=0 AND t.date BETWEEN ? AND ? ORDER BY t.date, t.id", listOf(from, to)) {
                listOf<Any?>(it.getString(0), it.getString(1), it.getLong(2), it.getString(3) ?: "", it.getString(4) ?: "")
            },
        )
    }

    // ---------- الترخيص ----------
    fun licenseInfo(): LicInfo {
        if (Cloud.loggedIn(ctx) && Cloud.isSuspended(ctx)) return LicInfo("expired", 0, "تم إيقاف ترخيص حسابك. تواصل مع المطوّر لإعادة التفعيل.")
        val key = setting("license_key")
        if (key.isNotEmpty()) {
            val r = License.verify(key, machine)
            if (r.ok) return LicInfo("licensed", r.limit, "مرخّص لـ ${r.customer} حتى ${r.expiry} (حتى ${r.limit} مشترك)",
                runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(r.expiry.toString())) }.getOrNull())
        }
        Cloud.cachedLicense(ctx)?.let { (exp, lim, plan) ->
            val e = runCatching { LocalDate.parse(exp) }.getOrNull()
            if (e != null && !e.isBefore(LocalDate.now())) return LicInfo("licensed", lim, "ترخيص الحساب ($plan) حتى $exp (حتى $lim مشترك)", java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), e))
        }
        var start = setting("trial_start")
        if (start.isEmpty()) { start = today(); setSetting("trial_start", start) }
        val left = TRIAL_DAYS - java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.now())
        if (left > 0) return LicInfo("trial", TRIAL_LIMIT, "نسخة تجريبية: باقي $left يوم (حتى $TRIAL_LIMIT مشترك)", left)
        return LicInfo("expired", 0, "انتهت الفترة التجريبية أو الترخيص. أدخل مفتاح التفعيل.")
    }

    fun activate(key: String): Pair<Boolean, String> {
        val r = License.verify(key.trim(), machine)
        if (r.ok) setSetting("license_key", key.trim())
        return Pair(r.ok, r.msg)
    }

    /** حد الترخيص يشمل مشتركي النشاطين معاً. */
    private fun otherActivityCount(): Long {
        val other = if (mode() == "generator") "internet" else "generator"
        val f = ctx.getDatabasePath(dbFileName(other))
        if (!f.exists()) return 0
        return try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { t ->
                t.rawQuery("SELECT COUNT(*) FROM subscribers", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            }
        } catch (e: Exception) { 0L }
    }

    /** العدد الفعلي المشترك بين قاعدتي النشاطين، ويُستخدم في رسائل طلب زيادة الحد. */
    fun subscriberCountForLicense(): Int =
        (scalar("SELECT COUNT(*) FROM subscribers") + otherActivityCount()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun licenseLimitForRequests(): Int = licenseInfo().limit

    fun canAddSubscriber(): Pair<Boolean, String> {
        val info = licenseInfo()
        val used = subscriberCountForLicense()
        if (info.state == "expired") return Pair(false, "${info.message}\nالمستخدم: $used مشترك. يمكنك طلب تجديد الترخيص عبر واتساب.")
        if (used >= info.limit) return Pair(false,
            "بلغت الحد الأقصى للمشتركين.\nالحد المسموح: ${info.limit}\nالمستخدم حالياً: $used\nيمكنك التواصل مع المطوّر لطلب زيادة العدد أو تمديد الترخيص أو تجديده.")
        return Pair(true, "")
    }
}


private fun parseCsv(raw: String): List<List<String>> {
    val t = raw.removePrefix("\uFEFF")
    val first = t.lineSequence().firstOrNull() ?: ""
    val delim = listOf(',', ';', '\t').maxByOrNull { d -> first.count { it == d } } ?: ','
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val sb = StringBuilder()
    var inQ = false
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (inQ) {
            if (c == '"') { if (i + 1 < t.length && t[i + 1] == '"') { sb.append('"'); i++ } else inQ = false } else sb.append(c)
        } else when (c) {
            '"' -> inQ = true
            delim -> { row.add(sb.toString()); sb.clear() }
            '\r' -> {}
            '\n' -> { row.add(sb.toString()); sb.clear(); rows.add(row); row = ArrayList() }
            else -> sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
    return rows.filter { r -> r.any { it.isNotBlank() } }
}

private fun parseDateLoose(s: String): String? {
    val t = normDigits(s.trim())
    if (t.isEmpty()) return null
    try { return LocalDate.parse(t.take(10)).toString() } catch (e: Exception) { }
    val p = t.split('/', '-', '.')
    if (p.size == 3) {
        val a = p[0].toIntOrNull(); val b = p[1].toIntOrNull(); val c = p[2].take(4).toIntOrNull()
        if (a != null && b != null && c != null) {
            return try { (if (c > 31) LocalDate.of(c, b, a) else LocalDate.of(a, b, c)).toString() } catch (e: Exception) { null }
        }
    }
    return null
}
