package com.subsmgr.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** تشفير الأسرار (رموز الدخول، كلمة مرور الراوتر) بمفتاح AES داخل Android Keystore لا يمكن استخراجه. */
object Secure {
    private const val ALIAS = "subsmgr_secrets"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    fun isEnc(s: String) = s.startsWith(PREFIX)

    fun enc(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: Exception) { plain }
    }

    /** يفك التشفير. القيم القديمة غير المشفّرة تُرجع كما هي. فشل الفك (مفتاح مفقود) يرجع نصاً فارغاً. */
    fun dec(stored: String): String {
        if (!isEnc(stored)) return stored
        return try {
            val all = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }
}
