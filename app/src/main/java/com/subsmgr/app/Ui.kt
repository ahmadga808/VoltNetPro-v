@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.subsmgr.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.time.LocalDate
import java.util.Locale

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

/** ينفّذ العملية ويعرض رسالة الخطأ بدل أن ينهار التطبيق. */
fun <T> safe(ctx: Context, f: () -> T): T? = try { f() } catch (e: Exception) { toast(ctx, e.message ?: "خطأ"); null }

fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(i, "مشاركة"))
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toast(ctx, "لا يوجد تطبيق لفتح الرابط") }
}

// ======================= عناصر التصميم المشتركة =======================
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val m = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier
    Card(
        modifier = m, shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content,
    )
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun IconBubble(icon: ImageVector, color: Color, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
fun StatCard(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            IconBubble(icon, color, 34)
            Spacer(Modifier.height(8.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ======================= حقول الإدخال =======================
data class F(
    val key: String, val label: String, val options: List<String>? = null,
    val free: Boolean = false, val number: Boolean = false, val date: Boolean = false,
    val location: Boolean = false, val center: Pair<Double, Double>? = null,
    val phone: Boolean = false,
    val routerPick: Boolean = false,
    /** يعرض زر "سجل الأسماء" الذي يملأ الاسم والرقم معاً (للحقلين name و phone). */
    val contactBoth: Boolean = false,
)

fun pickDate(ctx: Context, cur: String, set: (String) -> Unit) {
    val d = runCatching { LocalDate.parse(cur) }.getOrDefault(LocalDate.now())
    DatePickerDialog(ctx, { _, y, m, day -> set(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

@Composable
fun FieldInput(f: F, value: String, onPickBoth: ((String, String) -> Unit)? = null, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    var pickRouter by remember { mutableStateOf(false) }
    val requestLoc = if (f.location) rememberLocationRequester { onChange(String.format(Locale.US, "%.6f,%.6f", it.first, it.second)) } else null
    var pickContact by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val opts = f.options
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = { if (opts == null || f.free) onChange(it) },
            label = { Text(f.label) },
            readOnly = f.date || (opts != null && !f.free),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = if (f.number) KeyboardType.Number else KeyboardType.Text),
            trailingIcon = {
                when {
                    opts != null -> IconButton(onClick = { open = true }) { Icon(Icons.Rounded.KeyboardArrowDown, "اختيار") }
                    f.date -> Row {
                        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "مسح") }
                        IconButton(onClick = { pickDate(ctx, value, onChange) }) { Icon(Icons.Rounded.CalendarMonth, "تاريخ") }
                    }
                    f.location -> Row {
                        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "مسح") }
                        IconButton(onClick = { requestLoc?.invoke() }) { Icon(Icons.Rounded.MyLocation, "موقعي الحالي", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = { picking = true }) { Icon(Icons.Rounded.Map, "خريطة", tint = MaterialTheme.colorScheme.primary) }
                    }
                    f.routerPick && Router.cfg(ctx) != null -> IconButton(onClick = { pickRouter = true }) {
                        Icon(Icons.Rounded.Router, "اختيار من الراوتر", tint = MaterialTheme.colorScheme.primary)
                    }
                    (f.phone || f.contactBoth) && onPickBoth != null -> IconButton(onClick = { pickContact = true }) {
                        Icon(Icons.Rounded.Contacts, "اختيار الاسم والرقم من سجل الأسماء / واتساب", tint = MaterialTheme.colorScheme.primary)
                    }
                    else -> {}
                }
            },
        )
        if (pickRouter) RouterUserPicker({ pickRouter = false }) { onChange(it); pickRouter = false }
        if (pickContact) ContactPickerDialog({ pickContact = false }) { nm, num ->
            pickContact = false
            onPickBoth?.invoke(nm, num)
        }
        if (opts != null) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            opts.forEach { o -> DropdownMenuItem(text = { Text(o.ifEmpty { "—" }) }, onClick = { onChange(o); open = false }) }
        }
    }
    if (picking) LocationPickerDialog(value, f.center, { picking = false }) { onChange(it); picking = false }
}

// ---------- اختيار الموقع من الخريطة ----------
private fun parseLatLng(s: String): Pair<Double, Double>? {
    val p = s.replace("،", ",").split(",").map { it.trim().toDoubleOrNull() }
    val a = p.getOrNull(0)
    val b = p.getOrNull(1)
    return if (p.size == 2 && a != null && b != null) Pair(a, b) else null
}

private class PickBridge(val onPick: (Double, Double) -> Unit) {
    @JavascriptInterface
    fun pick(lat: Double, lng: Double) { Handler(Looper.getMainLooper()).post { onPick(lat, lng) } }
}

/** WebView للخرائط مع تشخيص ظاهر (بدل الشاشة البيضاء) وزر إعادة تحميل يبدّل وضع العرض. */
@Composable
fun MapWebView(html: String, bridge: Any?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val wv = remember {
        try { android.webkit.WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "غير موجود" }
        catch (e: Throwable) { "غير معروف" }
    }
    LaunchedEffect(attempt) {
        loaded = false
        kotlinx.coroutines.delay(12000)
        if (!loaded) err = "لم تُحمَّل صفحة الخريطة (WebView: $wv). اضغط إعادة التحميل."
    }
    Box(modifier) {
        key(attempt) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(0xFFE5E7EB.toInt())
                    // المحاولة الثانية وما بعدها بالتناوب: عرض برمجي (يحل الشاشة البيضاء على بعض الأجهزة)
                    if (attempt % 2 == 1) setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                            if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                                info = "JS: ${m.message()} (سطر ${m.lineNumber()})"
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { loaded = true }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) err = "تعذّر تحميل الخريطة: ${error?.description ?: ""}"
                            else info = "فشل تحميل: ${request?.url?.host ?: ""} ${error?.description ?: ""}"
                        }
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            err = "توقفت عملية عرض الويب (WebView: $wv). اضغط إعادة التحميل."
                            return true
                        }
                    }
                    if (bridge != null) addJavascriptInterface(bridge, "Android")
                    loadDataWithBaseURL("https://appassets.local/", html, "text/html", "utf-8", null)
                }
            })
        }
        err?.let { Text(it, color = Bad, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
        if (info.isNotEmpty()) Text(info, color = Bad, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 8.dp, end = 8.dp))
        FilledTonalIconButton(onClick = { err = null; info = ""; attempt++ }, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Icon(Icons.Rounded.Refresh, "إعادة التحميل")
        }
    }
}

@Composable
fun LocationPickerDialog(current: String, center: Pair<Double, Double>?, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val cur = remember { parseLatLng(current) }
    val start = cur ?: center ?: Pair(33.3152, 44.3661)
    var mid by remember { mutableStateOf(start) }
    var target by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val requestLoc = rememberLocationRequester { target = it }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Column(Modifier.weight(1f)) {
                        Text("حرّك الخريطة حتى يستقر الدبوس على المكان ثم اضغط تأكيد", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(String.format(Locale.US, "%.5f , %.5f", mid.first, mid.second), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    OsmPicker(start.first, start.second, if (cur != null) 17.0 else 13.0, target, { a, b -> mid = Pair(a, b) }, Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = requestLoc, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(52.dp)) {
                            Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("موقعي")
                        }
                        Button(
                            onClick = { onPicked(String.format(Locale.US, "%.6f,%.6f", mid.first, mid.second)) },
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(52.dp),
                        ) { Icon(Icons.Rounded.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("تأكيد هذا الموقع") }
                    }
                }
            }
        }
    }
}

// ======================= نوافذ الحوار =======================
@Composable
fun FormDialog(
    title: String, fields: List<F>, initial: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit, onOk: (Map<String, String>) -> Unit,
) {
    val ctx = LocalContext.current
    val vals = remember { mutableStateMapOf<String, String>().apply { putAll(initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(2.dp))
                fields.forEach { f ->
                    FieldInput(f, vals[f.key] ?: "", onPickBoth = { nm, num -> vals["name"] = nm; vals["phone"] = num }) { vals[f.key] = it }
                }
            }
        },
        confirmButton = {
            Button(shape = RoundedCornerShape(14.dp), onClick = {
                try {
                    onOk(fields.associate { it.key to (vals[it.key] ?: "").trim() })
                    onDismiss()
                } catch (e: Exception) { toast(ctx, e.message ?: "خطأ") }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
fun AskDialog(title: String, label: String, initial: String = "", number: Boolean = false,
              onDismiss: () -> Unit, onOk: (String) -> Unit) =
    FormDialog(title, listOf(F("v", label, number = number)), mapOf("v" to initial), onDismiss) { onOk(it["v"] ?: "") }

@Composable
fun ConfirmDialog(text: String, yes: String = "نعم", onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary) },
        text = { Text(text) },
        confirmButton = { Button(shape = RoundedCornerShape(14.dp), onClick = { onYes(); onDismiss() }) { Text(yes) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
fun InfoDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CheckCircle, null, tint = Ok) },
        text = { Text(text) },
        confirmButton = { Button(shape = RoundedCornerShape(14.dp), onClick = onDismiss) { Text("حسناً") } })
}

data class SendMsg(val title: String, val text: String, val phone: String?, val name: String = "", val note: String = "")

/** معاينة رسالة/وصل مع إرسالها مباشرة إلى رقم المشترك على واتساب (يضغط المستخدم إرسال). */
@Composable
fun SendDialog(m: SendMsg, db: Db, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val notify = rememberNotifier(db)
    val chan = Notify.channel(db)
    val doPrint = { Printer.print(ctx, m.text) { err -> toast(ctx, err ?: "تمت الطباعة") } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doPrint() else toast(ctx, "صلاحية البلوتوث مطلوبة للطباعة")
    }
    val hasPrinter = db.setting("printer_mac").isNotEmpty()
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) safe(ctx) {
            ctx.contentResolver.openOutputStream(uri)?.use { ReceiptPdf.write(db, it, m.title, m.text) }
            toast(ctx, "تم حفظ الوصل PDF")
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(m.title, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(m.text, Modifier.padding(12.dp), fontSize = 13.sp)
                }
                if (m.note.isNotEmpty()) Text(m.note, color = Ok, fontSize = 12.sp)
                if (m.phone.isNullOrEmpty()) Text("لا يوجد رقم هاتف لهذا المشترك. يمكنك المشاركة فقط.", color = Bad, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(shape = RoundedCornerShape(14.dp), enabled = !m.phone.isNullOrEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = if (chan == Notify.WA || chan == Notify.WA_SMS) Color(0xFF25D366) else MaterialTheme.colorScheme.primary, contentColor = Color.White),
                onClick = { notify(m.name.ifBlank { m.title }, m.phone ?: "", m.text); onClose() }) {
                Icon(if (chan == Notify.SMS || chan == Notify.SMS_WA) Icons.Rounded.Sms else Icons.Rounded.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(Notify.buttonLabel(chan))
            }
        },
        dismissButton = {
            Row {
                if (hasPrinter) TextButton(onClick = { if (Printer.hasPermission(ctx)) doPrint() else permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("طباعة") }
                TextButton(onClick = { pdfLauncher.launch("receipt_${java.time.LocalDate.now()}.pdf") }) { Text("PDF") }
                TextButton(onClick = { shareText(ctx, m.text) }) { Text("مشاركة") }
                TextButton(onClick = onClose) { Text("إغلاق") }
            }
        },
    )
}

// ======================= الجذر: الإعداد → PIN → الترخيص → الواجهة =======================
private fun nextStage(db: Db, after: String): String {
    val order = listOf("login", "setup", "pin", "license", "main")
    for (s in order.drop(order.indexOf(after) + 1)) {
        when (s) {
            "login" -> if (!db.cloudLoggedIn() && !Session.skipLogin) return s
            "setup" -> if (db.setting("mode").isEmpty()) return s
            "pin" -> if (db.hasPin()) return s
            "license" -> if (db.licenseInfo().state == "expired") return s
            else -> return s
        }
    }
    return "main"
}

@Composable
fun AppRoot(db: Db, exit: () -> Unit) {
    val ctx = LocalContext.current
    // الحساب اختياري: تجاوز بوابة الدخول لهذه الجلسة، مع تذكير يظهر عند كل تشغيل بلا حساب.
    if (!db.cloudLoggedIn()) Session.skipLogin = true
    var stage by remember { mutableStateOf(nextStage(db, "")) }
    var showAccountReminder by remember { mutableStateOf(!db.cloudLoggedIn()) }
    var showAccountDialog by remember { mutableStateOf(false) }
    Session.lock = { stage = "pin" }
    when (stage) {
        "login" -> LoginGate(db) { stage = nextStage(db, "login") }
        "setup" -> SetupScreen(db) { stage = nextStage(db, "setup") }
        "pin" -> PinScreen(db, exit) { stage = nextStage(db, "pin") }
        "license" -> LicenseGate(db, exit) { stage = nextStage(db, "license") }
        else -> MainScreen(db)
    }
    if (showAccountReminder) {
        AlertDialog(
            onDismissRequest = { showAccountReminder = false },
            title = { Text("أنشئ حساباً لحماية بياناتك") },
            text = { Text("يمكنك استخدام VoltNet Pro دون حساب، لكن بياناتك لن تُربط بحساب سحابي ولن تتمكن من استعادتها عبر حسابك على هاتف آخر. تبقى البيانات على هذا الجهاز، لذا أنشئ حساباً للنسخ السحابي والاستعادة.") },
            confirmButton = {
                TextButton(onClick = { showAccountReminder = false; showAccountDialog = true }) { Text("إنشاء حساب") }
            },
            dismissButton = {
                TextButton(onClick = { Session.skipLogin = true; showAccountReminder = false }) { Text("المتابعة دون حساب") }
            }
        )
    }
    if (showAccountDialog) {
        CloudLoginDialog(
            onClose = { showAccountDialog = false },
            onLoggedIn = {
                showAccountDialog = false
                Session.skipLogin = false
                stage = nextStage(db, "login")
            }
        )
    }
}

@Composable
fun AuthFrame(icon: ImageVector, title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.primary, c.secondary)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Spacer(Modifier.height(22.dp))
            Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = c.surface)) {
                Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}

@Composable
fun ModeCard(selected: Boolean, title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(modifier = modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = if (selected) c.primaryContainer else c.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (selected) c.primary else c.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
        }
    }
}

@Composable
fun SetupScreen(db: Db, done: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("internet") }
    var sasUrl by remember { mutableStateOf(Sas.cfg(ctx)?.url ?: "") }
    var sasUser by remember { mutableStateOf(Sas.cfg(ctx)?.user ?: "") }
    var sasPass by remember { mutableStateOf(Sas.cfg(ctx)?.pass ?: "") }
    var busy by remember { mutableStateOf(false) }
    var sasOk by remember { mutableStateOf(false) }
    var sasMessage by remember { mutableStateOf("لم يتم اختبار الاتصال بعد") }
    var preview by remember { mutableStateOf<List<SasUser>>(emptyList()) }
    var importMessage by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf(false) }
    val packages = db.packages()
    fun finishSetup() {
        db.switchMode(mode)
        db.setSetting("network_name", name.ifBlank { "الشبكة" })
        db.setSetting("first_setup_completed", "1")
        done()
    }
    AuthFrame(Icons.Rounded.Wifi, "تهيئة VoltNet Pro", "الخطوة ${step + 1} من 4") {
        LinearProgressIndicator(progress = (step + 1) / 4f, modifier = Modifier.fillMaxWidth())
        when (step) {
            0 -> {
                Text("1. النشاط واسم النظام", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeCard(mode == "internet", "إدارة الإنترنت", Icons.Rounded.Wifi, Modifier.weight(1f)) { mode = "internet" }
                    ModeCard(mode == "generator", "نشاط آخر", Icons.Rounded.Bolt, Modifier.weight(1f)) { mode = "generator" }
                }
                FieldInput(F("n", "اسم النظام / الشبكة"), name) { name = it }
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("التالي") }
            }
            1 -> {
                Text("2. الاتصال بلوحة SAS", fontWeight = FontWeight.Bold)
                Text("أدخل بيانات لوحة SAS4. لن نعرض نجاح الربط إلا بعد اختبار تسجيل الدخول وقراءة قائمة المشتركين فعلياً. يمكنك التخطي والعودة لاحقاً.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(sasUrl, { sasUrl = it; sasOk = false }, label = { Text("رابط لوحة SAS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sasUser, { sasUser = it; sasOk = false }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sasPass, { sasPass = it; sasOk = false }, label = { Text("كلمة المرور") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && sasUrl.isNotBlank() && sasUser.isNotBlank() && sasPass.isNotBlank(), onClick = {
                    busy = true; sasOk = false; sasMessage = "جارٍ اختبار الاتصال…"
                    scope.launch {
                        val result = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val cfg = SasCfg(sasUrl.trim(), sasUser.trim(), sasPass)
                            Sas.test(cfg)
                            val users = Sas.users(cfg)
                            cfg to users
                        } }
                        result.onSuccess { (cfg, users) -> Sas.save(ctx, cfg); preview = users; sasOk = true; sasMessage = "نجح الاتصال. عُثر على ${users.size} حساباً في اللوحة." }
                            .onFailure { sasMessage = "فشل الاتصال: ${it.message ?: "خطأ غير معروف"}" }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "جارٍ الاختبار…" else "اختبار الاتصال وجلب معاينة") }
                Text(sasMessage, color = if (sasOk) Ok else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (sasOk) {
                    Text("المعاينة: ${preview.size} حساباً. لا يتم استيراد أي بيانات قبل ضغط زر الاستيراد.", fontSize = 12.sp)
                    if (preview.isNotEmpty()) preview.take(5).forEach { Text("• ${it.name} — ${it.username} — ${it.profile.ifBlank { "بلا باقة" }}", fontSize = 11.sp) }
                    Button(enabled = !busy && !imported, onClick = {
                        busy = true
                        scope.launch {
                            importMessage = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Sas.importUsers(db, preview) } }
                                .getOrElse { "تعذر الاستيراد: ${it.message ?: "خطأ"}" }
                            imported = true; busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (imported) "تم تنفيذ الاستيراد" else "استيراد المعاينة إلى التطبيق") }
                    if (importMessage.isNotBlank()) Text(importMessage, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { step = 0 }, modifier = Modifier.weight(1f)) { Text("السابق") }
                    Button(onClick = { step = 2 }, modifier = Modifier.weight(1f)) { Text("التالي") }
                }
            }
            2 -> {
                Text("3. الباقات ومراجعة البيانات", fontWeight = FontWeight.Bold)
                val knownUsers = db.listSubs().mapNotNull { it.e1?.trim()?.lowercase()?.takeIf { v -> v.isNotEmpty() } }.toSet()
                val duplicateCount = preview.count { it.username.trim().lowercase() in knownUsers }
                val missingNameCount = preview.count { it.name.isBlank() }
                val missingExpiryCount = preview.count { it.expiration.take(10).length != 10 }
                Text("عدد الباقات الحالية: ${packages.size}. بعد الاستيراد راجع أسماء الباقات وأسعارها ومددها من قسم الباقات. قد تُنشأ باقات SAS غير المعروفة بسعر صفر وتحتاج مراجعتك.", fontSize = 13.sp)
                if (sasOk) Text("مراجعة المعاينة: ${preview.size} حساباً • ${duplicateCount} مكرر سيُتخطّى • ${missingNameCount} بلا اسم • ${missingExpiryCount} بلا تاريخ انتهاء صالح.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("اختيار برج محدد غير متاح في موصل SAS الحالي؛ المعاينة والاستيراد يخصان قائمة الحسابات التي يعيدها الموصل. لم يتم اختيار برج أو مصدر غير مدعوم.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("لا يستبدل الاستيراد السجلات المالية الموجودة، ويتخطى أسماء المستخدمين الموجودة مسبقاً.", fontSize = 12.sp, color = Ok)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { step = 1 }, modifier = Modifier.weight(1f)) { Text("السابق") }
                    Button(onClick = { step = 3 }, modifier = Modifier.weight(1f)) { Text("التالي") }
                }
            }
            else -> {
                Text("4. مراجعة وإنهاء", fontWeight = FontWeight.Bold)
                Text("""النشاط: ${if (mode == "internet") "إدارة الإنترنت" else "النشاط الآخر"}
اسم النظام: ${name.ifBlank { "الشبكة" }}
اتصال SAS: ${if (sasOk) "تم التحقق" else "غير مربوط / تم التخطي"}
المشتركون المعاينون: ${preview.size}
الباقات الموجودة: ${db.packages().size}""", fontSize = 14.sp)
                Text("يمكنك تعديل هذه الإعدادات لاحقاً. لا يتم استيراد برج محدد لعدم توفر اختيار برج موثق في موصل SAS الحالي.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { finishSetup() }, modifier = Modifier.fillMaxWidth()) { Text("إنهاء والانتقال للرئيسية") }
                OutlinedButton(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) { Text("السابق") }
            }
        }
        TextButton(onClick = { finishSetup() }, modifier = Modifier.fillMaxWidth()) { Text("تأجيل الإعداد والمتابعة لاحقاً") }
    }
}

@Composable
fun PinScreen(db: Db, exit: () -> Unit, ok: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var tries by remember { mutableIntStateOf(0) }
    val bioOn = remember {
        db.setting("bio") == "1" && db.staffList().isEmpty() &&
            BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }
    val promptBio: () -> Unit = {
        val act = ctx as? FragmentActivity
        if (act != null) {
            val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { ok() }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("فتح التطبيق").setNegativeButtonText("استخدام PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build())
        }
    }
    LaunchedEffect(Unit) { if (bioOn) promptBio() }
    AuthFrame(Icons.Rounded.Lock, "القفل", "أدخل رمز الدخول للمتابعة") {
        OutlinedTextField(pin, { pin = it }, label = { Text("رمز الدخول (PIN)") }, singleLine = true,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Button(onClick = {
            val r = try { db.login(pin) } catch (e: IllegalStateException) { toast(ctx, e.message ?: ""); pin = ""; null }
            if (r == true) ok()
            else if (r == false) {
                pin = ""; tries++
                toast(ctx, "الرمز غير صحيح")
                if (tries >= 3) exit()
            }
        }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("دخول") }
        if (bioOn) OutlinedButton(onClick = promptBio, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Rounded.Fingerprint, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("البصمة")
        }
    }
}

@Composable
fun LicenseGate(db: Db, exit: () -> Unit, ok: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    var showLogin by remember { mutableStateOf(false) }
    AuthFrame(Icons.Rounded.Key, "تفعيل البرنامج", db.licenseInfo().message) {
        PurchaseSection(db)
        Text("رقم جهازك (أرسله للبائع للحصول على المفتاح):", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(db.machineId(), {}, readOnly = true, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            IconButton(onClick = { shareText(ctx, db.machineId()) }) { Icon(Icons.Rounded.Share, "مشاركة") }
        }
        OutlinedTextField(key, { key = it }, label = { Text("مفتاح التفعيل") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val (good, msg) = db.activate(key)
            toast(ctx, msg)
            if (good) ok()
        }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("تفعيل") }
        OutlinedButton(onClick = { showLogin = true }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("لدي حساب: تسجيل الدخول") }
        TextButton(onClick = exit, modifier = Modifier.fillMaxWidth()) { Text("خروج") }
        if (showLogin) CloudLoginDialog({ showLogin = false }) {
            showLogin = false
            val li = db.licenseInfo()
            if (li.state != "expired") ok() else toast(ctx, li.message)
        }
    }
}

// ======================= الشاشة الرئيسية =======================
@Composable
fun MainScreen(db: Db) {
    var tab by remember { mutableIntStateOf(if (Session.isAdmin) 5 else 0) }
    var subscriberFilter by remember { mutableStateOf<String?>(null) }
    var settingsSection by remember { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { rev++ }
    val appCtx = LocalContext.current
    var tour by remember { mutableStateOf(!UiPrefs.tourDone(appCtx)) }
    var auditOpen by remember { mutableStateOf(false) }
    var showReleaseNotes by remember { mutableStateOf(false) }
    var showUpdateCenter by remember { mutableStateOf(false) }
    var releaseVersion by remember { mutableStateOf("2.23") }
    var networkPage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(appCtx) {
        val v = try {
            val info = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "2.23"}-$code"
        } catch (_: Exception) { "2.23-27" }
        releaseVersion = v
        if (!UiPrefs.releaseShown(appCtx, v)) showReleaseNotes = true
    }
    LaunchedEffect(rev) { Router.kick(appCtx, db) }
    // النشاط الحالي حالة (State) كي تُعاد تركيبة الشاشة كلها عند التبديل. التبديل من الإعدادات فقط.
    var mode by remember { mutableStateOf(db.setting("mode", "internet")) }
    val gen = mode == "generator"
    val onSwitch: (String) -> Unit = { k -> db.switchMode(k); mode = k; tab = if (Session.isAdmin) 5 else 0; rev++ }
    val c = MaterialTheme.colorScheme
    // مركز التنقل الرئيسي: الأقسام مرتبة حسب سير العمل اليومي.
    val tabs = listOf(
        Triple(Icons.Rounded.Home, "الرئيسية", 5),
        Triple(Icons.Rounded.People, "المشتركون", 0),
        Triple(if (gen) Icons.Rounded.Bolt else Icons.Rounded.Inventory2, if (gen) "التسعير" else "الباقات", 1),
        Triple(Icons.Rounded.AccountBalanceWallet, "الحسابات", 2),
        Triple(Icons.Rounded.DeviceHub, "الشبكة", 6),
        Triple(Icons.Rounded.BarChart, "التقارير", 3),
        Triple(Icons.Rounded.Settings, "الإعدادات", 4),
    ).let { all -> if (Session.isAdmin) all else all.filter { it.third == 0 || it.third == 4 || it.third == 5 } }
    Scaffold(
        containerColor = c.background,
        topBar = {
          Column {
            val title = remember(rev, mode) { db.setting("network_name").ifEmpty { "VoltNet Pro" } }
            Box(Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(c.primary, c.secondary)), RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(if (gen) Icons.Rounded.Bolt else Icons.Rounded.Wifi, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                        Text(MODES[mode]?.first ?: "", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
            }
            LicenseBanner(db, rev)
          }
        },
        bottomBar = {
            NavigationBar(containerColor = c.surface) {
                tabs.forEach { (icon, label, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, label) }, label = { Text(label, fontSize = 10.sp, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                5 -> HomeTab(db, rev, onGo = { tab = it }, onGoFilter = { filter -> subscriberFilter = filter; tab = 0 }, onGoSettings = { sec -> settingsSection = sec; tab = 4 }, onAudit = { auditOpen = true }, onTour = { tour = true }, onShowUpdates = { showUpdateCenter = true })
                0 -> SubsTab(db, rev, bump, initialFilter = subscriberFilter)
                1 -> if (gen) AmpPriceTab(db, rev, bump) else PkgsTab(db, rev, bump)
                2 -> ExpensesTab(db, rev, bump)
                3 -> ReportsTab(db, rev)
                6 -> if (!Session.isAdmin) StaffSettings(db) else when (networkPage) {
                    null -> NetworkHub { networkPage = it }
                    "boards" -> NetworkDestination("لوحاتي", { networkPage = null }) { BoardsSection() }
                    "integration" -> NetworkDestination("فحص الربط", { networkPage = null }) { IntegrationSetupSection() }
                    "monitor", "telegram", "assistant" -> NetworkDestination(when (networkPage) { "telegram" -> "تنبيهات Telegram"; "assistant" -> "المساعد الذكي"; else -> "مراقبة الشبكة" }, { networkPage = null }) { NetworkMonitorSection(initialSection = networkPage) }
                    "routers" -> NetworkDestination("الراوترات والأبراج", { networkPage = null }) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) { SettingsExtras(db, rev, bump, "links") } }
                    else -> NetworkHub { networkPage = it }
                }
                else -> if (Session.isAdmin) SettingsTab(db, rev, bump, mode, onSwitch, onTour = { tour = true }, onAudit = { auditOpen = true }, initialSection = settingsSection) else StaffSettings(db)
            }
        }
    }
    if (tour) TourDialog(gen) { UiPrefs.setTourDone(appCtx, true); tour = false }
    if (showReleaseNotes) ReleaseNotesDialog(onDismiss = { showReleaseNotes = false; UiPrefs.setReleaseShown(appCtx, releaseVersion) })
    GitHubUpdateCenter(open = showUpdateCenter, localNotesVisible = showReleaseNotes, onDismiss = { showUpdateCenter = false })
    if (auditOpen) AuditDialog(db) { auditOpen = false }
}
