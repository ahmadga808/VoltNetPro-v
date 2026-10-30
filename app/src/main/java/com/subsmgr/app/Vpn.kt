package com.subsmgr.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback

/**
 * نفق OpenVPN للوصول إلى لوحات الشركات (مثل Earthlink) أو الراوتر من أي مكان.
 *
 * الطريقة: التطبيق لا يحمل محرك OpenVPN بنفسه؛ يتحكم بتطبيق «OpenVPN for Android» (de.blinkt.openvpn) المجاني
 * عبر واجهته الرسمية (AIDL). نمرّر له ملف الإعداد (.ovpn) فيُنشئ النفق، وبعدها تمرّ اتصالات الهاتف (ومنها
 * لوحة SAS والراوتر) عبر النفق. بهذا لا يُدمَج كود GPL داخل تطبيقك.
 */
object Vpn {
    const val PKG = "de.blinkt.openvpn"
    private fun p(ctx: Context) = ctx.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    fun name(ctx: Context): String = p(ctx).getString("name", "") ?: ""
    fun config(ctx: Context): String = Secure.dec(p(ctx).getString("cfg", "") ?: "")
    fun user(ctx: Context): String = Secure.dec(p(ctx).getString("user", "") ?: "")
    fun pass(ctx: Context): String = Secure.dec(p(ctx).getString("pass", "") ?: "")
    fun autoOn(ctx: Context): Boolean = p(ctx).getBoolean("auto", false)
    fun setAuto(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("auto", on).apply() }
    fun hasProfile(ctx: Context): Boolean = config(ctx).isNotBlank()

    fun save(ctx: Context, name: String, cfg: String, user: String, pass: String) {
        p(ctx).edit().putString("name", name.trim()).putString("cfg", Secure.enc(cfg.trim()))
            .putString("user", Secure.enc(user.trim())).putString("pass", Secure.enc(pass)).apply()
    }

    fun clear(ctx: Context) { p(ctx).edit().clear().apply() }

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (e: Exception) { false }

    /** يتحقق أن النص يشبه ملف OpenVPN. يرجع رسالة الخطأ أو null إن كان سليماً. */
    fun validate(cfg: String): String? {
        val t = cfg.trim()
        if (t.isEmpty()) return "الصق محتوى ملف .ovpn أو استورده أولاً"
        if (t.length > 262_144) return "الملف كبير جداً"
        val hasRemote = t.lineSequence().any { it.trim().startsWith("remote ") }
        if (!hasRemote) return "الملف لا يحتوي سطر remote (عنوان الخادم). تأكد أنه ملف OpenVPN صحيح"
        return null
    }

    /** يبني الإعداد النهائي: إن وُجد اسم مستخدم وكلمة مرور يُدرجان داخل الملف (auth-user-pass مضمّن). */
    fun buildConfig(ctx: Context): String? {
        var c = config(ctx).trim()
        if (c.isEmpty()) return null
        val u = user(ctx)
        val pw = pass(ctx)
        if (u.isNotEmpty() && !c.contains("<auth-user-pass>")) {
            val block = "<auth-user-pass>\n$u\n$pw\n</auth-user-pass>"
            val lines = c.lines().toMutableList()
            val idx = lines.indexOfFirst { it.trim() == "auth-user-pass" }
            if (idx >= 0) lines[idx] = block else lines.add(block)
            c = lines.joinToString("\n")
        }
        return c + "\n"
    }

    fun readUri(ctx: Context, uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("تعذّرت قراءة الملف")
        if (bytes.size > 262_144) throw IllegalStateException("الملف كبير جداً")
        return String(bytes, Charsets.UTF_8)
    }

    // ---------- الاتصال بتطبيق OpenVPN for Android ----------
    val state = mutableStateOf("NOPROCESS")
    val message = mutableStateOf("")
    val connected: Boolean get() = state.value == "CONNECTED"

    fun stateText(s: String): String = when (s) {
        "CONNECTED" -> "متصل"
        "NOPROCESS", "" -> "غير متصل"
        "AUTH_FAILED" -> "فشل التحقق: اسم المستخدم أو كلمة المرور"
        "NONETWORK" -> "لا يوجد اتصال بالإنترنت"
        "EXITING" -> "جارٍ القطع..."
        "RECONNECTING" -> "إعادة الاتصال..."
        else -> "جارٍ الاتصال... ($s)"
    }

    private val main = Handler(Looper.getMainLooper())
    private var svc: IOpenVPNAPIService? = null
    private var bound = false
    private var registered = false
    private var whenReady: (() -> Unit)? = null

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            main.post { Vpn.state.value = if (level == "LEVEL_CONNECTED") "CONNECTED" else (state ?: ""); Vpn.message.value = message ?: "" }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = IOpenVPNAPIService.Stub.asInterface(binder)
            if (!registered) { try { svc?.registerStatusCallback(callback); registered = true } catch (e: Exception) { } }
            val w = whenReady; whenReady = null
            w?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null; registered = false; bound = false }
    }

    private fun withService(ctx: Context, onFail: (String) -> Unit, block: (IOpenVPNAPIService) -> Unit) {
        val s = svc
        if (s != null) { block(s); return }
        if (!installed(ctx)) { onFail("ثبّت تطبيق OpenVPN for Android أولاً"); return }
        whenReady = { svc?.let(block) }
        if (!bound) {
            val i = Intent("de.blinkt.openvpn.api.IOpenVPNAPIService").setPackage(PKG)
            val ok = try { ctx.applicationContext.bindService(i, conn, Context.BIND_AUTO_CREATE) } catch (e: Exception) { false }
            if (!ok) { whenReady = null; onFail("تعذّر الاتصال بتطبيق OpenVPN for Android. حدّثه إلى آخر إصدار"); return }
            bound = true
        }
    }

    /**
     * يبدأ النفق. ask = دالة تُطلق شاشة صلاحية (أول مرة فقط)؛ إن كانت null يتخطى الاتصال بصمت عند الحاجة لصلاحية
     * (للاتصال التلقائي عند فتح التطبيق). say تُظهر رسالة للمستخدم.
     */
    fun connect(ctx: Context, ask: ((Intent) -> Unit)?, say: (String) -> Unit) {
        val cfgText = buildConfig(ctx)
        if (cfgText == null) { say("أضف ملف إعداد OpenVPN أولاً"); return }
        val err = validate(cfgText)
        if (err != null) { say(err); return }
        withService(ctx, say) { s ->
            try {
                val need: Intent? = s.prepare(ctx.packageName)
                if (need != null) { if (ask != null) ask(need) else say("يلزم السماح للتطبيق بالتحكم في OpenVPN (افتح الإعدادات واضغط اتصال)"); return@withService }
                val vp: Intent? = s.prepareVPNService()
                if (vp != null) { if (ask != null) ask(vp) else say("يلزم السماح بإذن VPN (افتح الإعدادات واضغط اتصال)"); return@withService }
                state.value = "CONNECTING"
                s.startVPN(cfgText)
            } catch (e: Exception) { say("تعذّر بدء النفق: " + (e.message ?: "")) }
        }
    }

    fun disconnect(ctx: Context, say: (String) -> Unit) {
        withService(ctx, say) { s -> try { s.disconnect() } catch (e: Exception) { say("تعذّر قطع النفق: " + (e.message ?: "")) } }
    }

    /** اتصال تلقائي عند فتح التطبيق إن فُعّل الخيار وكانت الصلاحيات ممنوحة مسبقاً. */
    fun autoStart(ctx: Context) {
        if (!autoOn(ctx) || !hasProfile(ctx) || !installed(ctx)) return
        connect(ctx, null) { }
    }
}

/** بطاقة إعداد OpenVPN في الإعدادات. */
@Composable
fun OpenVpnCard() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(Vpn.name(ctx).ifEmpty { "Earthlink" }) }
    var cfg by remember { mutableStateOf(Vpn.config(ctx)) }
    var user by remember { mutableStateOf(Vpn.user(ctx)) }
    var pass by remember { mutableStateOf(Vpn.pass(ctx)) }
    var auto by remember { mutableStateOf(Vpn.autoOn(ctx)) }
    var retry by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val st by Vpn.state
    val msg by Vpn.message
    val installed = remember(st) { Vpn.installed(ctx) }
    val say: (String) -> Unit = { toast(ctx, it) }

    fun persist(): Boolean {
        val err = Vpn.validate(cfg)
        if (err != null) { say(err); return false }
        Vpn.save(ctx, name, cfg, user, pass)
        return true
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) retry++ else say("لم تُمنح الصلاحية")
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) safe(ctx) { cfg = Vpn.readUri(ctx, uri); toast(ctx, "تم استيراد الملف") }
    }
    LaunchedEffect(retry) { if (retry > 0) Vpn.connect(ctx, { permLauncher.launch(it) }, say) }

    SectionTitle("نفق OpenVPN (لوحات الشركات مثل Earthlink)")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("بعض الشركات (مثل Earthlink) لا تفتح لوحتها إلا من داخل شبكتها عبر OpenVPN. استورد ملف .ovpn الذي تعطيك إياه الشركة، " +
                "واضغط اتصال، وبعدها تعمل لوحة SAS والراوتر داخل التطبيق عبر النفق. يعمل هذا بتطبيق OpenVPN for Android المجاني.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            if (!installed) Surface(shape = RoundedCornerShape(12.dp), color = cs.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("تطبيق OpenVPN for Android غير مثبّت على هذا الهاتف.", fontSize = 12.sp, color = cs.onErrorContainer)
                    Button(onClick = { openUrl(ctx, "https://play.google.com/store/apps/details?id=${Vpn.PKG}") }, shape = RoundedCornerShape(14.dp)) { Text("تثبيت من Google Play") }
                    TextButton(onClick = { openUrl(ctx, "https://f-droid.org/packages/${Vpn.PKG}/") }) { Text("أو من F-Droid") }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("اسم الاتصال") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cfg, { cfg = it }, label = { Text("محتوى ملف .ovpn") }, minLines = 4, maxLines = 8,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("استيراد ملف .ovpn")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text("مستخدم VPN (إن طُلب)") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = if (st == "CONNECTED") Ok else if (st == "AUTH_FAILED" || st == "NONETWORK") Bad else if (st == "NOPROCESS" || st.isEmpty()) cs.outline else Warn,
                    modifier = Modifier.size(12.dp)) {}
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(Vpn.stateText(st), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    if (msg.isNotBlank() && st != "NOPROCESS") Text(msg, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 2)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (persist()) Vpn.connect(ctx, { permLauncher.launch(it) }, say) },
                    enabled = st != "CONNECTED", shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.VpnKey, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("اتصال")
                }
                OutlinedButton(onClick = { Vpn.disconnect(ctx, say) }, enabled = st != "NOPROCESS" && st.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("قطع الاتصال") }
            }
            OutlinedButton(onClick = { if (persist()) toast(ctx, "تم حفظ إعداد VPN") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("حفظ الإعداد") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("اتصال تلقائي عند فتح التطبيق")
                    Text("يعمل بعد منح الصلاحيات مرة واحدة عبر زر اتصال.", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; Vpn.setAuto(ctx, it) })
            }
            if (Vpn.hasProfile(ctx)) TextButton(onClick = { confirmClear = true }) { Text("حذف إعداد VPN المحفوظ", color = Bad) }
            Text("ملاحظة: النفق يشمل اتصالات الهاتف حسب مسارات الملف. من إعدادات تطبيق OpenVPN for Android يمكنك حصره بتطبيقات محددة.",
                fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }
    if (confirmClear) ConfirmDialog("حذف ملف VPN وبيانات الدخول المحفوظة من هذا التطبيق؟", "حذف", { confirmClear = false }) {
        Vpn.clear(ctx); name = "Earthlink"; cfg = ""; user = ""; pass = ""; auto = false
    }
}
