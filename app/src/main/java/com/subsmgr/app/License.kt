package com.subsmgr.app

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** نفس صيغة مفاتيح licensing.py في نسخة بايثون، فمفاتيح keygen.py الحالية تعمل هنا. */
object License {
    // يجب أن يطابق SECRET في licensing.py/keygen.py. غيّره إلى نص عشوائي طويل قبل البيع.
    private const val SECRET = "CHANGE-ME-TO-A-LONG-RANDOM-SECRET-ONLY-YOU-KNOW"

    fun machineId(ctx: Context): String =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN").uppercase()

    private fun sig(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(20)
    }

    data class Result(val ok: Boolean, val msg: String, val customer: String = "", val expiry: String = "", val limit: Int = 0)

    fun verify(key: String, machine: String): Result {
        val bad = Result(false, "مفتاح غير صالح")
        val raw = try {
            String(Base64.decode(key.trim(), Base64.URL_SAFE), Charsets.UTF_8)
        } catch (e: Exception) {
            return bad
        }
        val i = raw.lastIndexOf('|')
        if (i < 0) return bad
        val payload = raw.substring(0, i)
        val s = raw.substring(i + 1)
        val p = payload.split("|")
        if (p.size != 4) return bad
        if (!MessageDigest.isEqual(s.toByteArray(), sig(payload).toByteArray())) return bad
        if (p[2] != "*" && p[2] != machine) return Result(false, "هذا المفتاح لجهاز آخر")
        val exp = try { LocalDate.parse(p[1]) } catch (e: Exception) { return bad }
        if (exp.isBefore(LocalDate.now())) return Result(false, "انتهى الاشتراك بتاريخ ${p[1]}")
        val lim = p[3].toIntOrNull() ?: return bad
        return Result(true, "تم التفعيل", p[0], p[1], lim)
    }
}
