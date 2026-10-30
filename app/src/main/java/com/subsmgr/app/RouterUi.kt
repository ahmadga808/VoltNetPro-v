package com.subsmgr.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ربط الراوتر (MikroTik): التجديد والتجميد وانتهاء الاشتراك تنعكس فعلياً على حساب المشترك. */
@Composable
fun RouterCard(db: Db) {
    if (db.isGenerator()) return
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val cur = remember { Router.cfg(ctx) }
    var host by remember { mutableStateOf(cur?.host ?: "") }
    var port by remember { mutableStateOf((cur?.port ?: 8728).toString()) }
    var user by remember { mutableStateOf(cur?.user ?: "") }
    var pass by remember { mutableStateOf(cur?.pass ?: "") }
    var type by remember { mutableStateOf(cur?.type ?: "pppoe") }
    var ssl by remember { mutableStateOf(cur?.ssl ?: false) }
    var grace by remember { mutableStateOf(Router.grace(ctx).toString()) }
    var busy by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf(Router.lastResult(ctx)) }
    var auto by remember { mutableStateOf(Router.autoOn(ctx)) }
    var confirm by remember { mutableStateOf(false) }
    var vouchers by remember { mutableStateOf(false) }
    var dash by remember { mutableStateOf(false) }
    var monitor by remember { mutableStateOf(false) }
    var signal by remember { mutableStateOf(false) }
    var interfaces by remember { mutableStateOf(false) }

    fun build(): RouterCfg? {
        if (host.isBlank() || user.isBlank()) { toast(ctx, "أدخل عنوان الراوتر واسم المستخدم"); return null }
        val c = RouterCfg(host.trim(), port.toIntOrNull() ?: (if (ssl) 8729 else 8728), user.trim(), pass, type, ssl)
        Router.save(ctx, c)
        Router.setGrace(ctx, grace.toIntOrNull() ?: 0)
        return Router.cfg(ctx)
    }
    fun doRun(block: (RouterCfg, List<RTarget>) -> String) {
        val c = build() ?: return
        val targets = db.routerTargets(Router.grace(ctx))
        busy = true
        scope.launch {
            val m = try { withContext(Dispatchers.IO) { block(c, targets) } } catch (e: Exception) { e.message ?: "خطأ" }
            busy = false
            out = m
        }
    }

    SectionTitle("ربط الراوتر (MikroTik)")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("عند التجديد أو منح أيام مجانية يُفعَّل حساب المشترك على الراوتر، وعند الانتهاء أو التجميد يُوقَف وتُقطع جلسته. اسم الحساب هو حقل PPPoE في بيانات المشترك.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("عنوان الراوتر (IP)") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(2f))
                OutlinedTextField(port, { port = it }, label = { Text("المنفذ") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text("مستخدم الراوتر") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = type == "pppoe", onClick = { type = "pppoe" }, label = { Text("PPPoE") })
                FilterChip(selected = type == "hotspot", onClick = { type = "hotspot" }, label = { Text("Hotspot") })
                FilterChip(selected = ssl, onClick = { ssl = !ssl; if (ssl && port == "8728") port = "8729" else if (!ssl && port == "8729") port = "8728" }, label = { Text("SSL") })
            }
            if (ssl) TextButton(onClick = { Router.resetPin(ctx); toast(ctx, "تمت إعادة تعيين بصمة الشهادة، ستُحفظ عند الاتصال التالي") }) { Text("إعادة تعيين بصمة شهادة الراوتر") }
            OutlinedTextField(grace, { grace = it }, label = { Text("أيام سماح بعد الانتهاء قبل القطع (0 = فوراً)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { doRun { c, _ -> Router.test(c) } }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("اختبار الاتصال") }
                OutlinedButton(onClick = { doRun { c, t -> Router.summary(Router.apply(c, t, true), true) } }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("معاينة التغييرات") }
            }
            Button(onClick = { if (build() != null) confirm = true }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("مزامنة الآن") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("مزامنة تلقائية")
                    Text("بعد كل تغيير وكل ساعة. جرّب المعاينة أولاً.", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Switch(checked = auto, onCheckedChange = {
                    if (it && build() == null) return@Switch
                    auto = it; Router.setAuto(ctx, it)
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (build() != null) dash = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("لوحة الراوتر") }
                OutlinedButton(onClick = { monitor = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("مراقبة السكترات") }
            }
            OutlinedButton(onClick = { if (build() != null) signal = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("إشارة الصحون والسكترات (مباشر)") }
            OutlinedButton(onClick = { vouchers = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("كروت الهوتسبوت (توليد وبيع وطباعة)") }
            if (out.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant) {
                Text(out, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
        }
    }
    if (vouchers) VouchersDialog(db) { vouchers = false }
    if (dash) RouterDashboardDialog { dash = false }
    if (monitor) MonitorDialog { monitor = false }
    if (signal) SignalDialog(db) { signal = false }
    if (interfaces) RouterInterfacesDialog { interfaces = false }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("مزامنة الراوتر") },
        text = { Text("ستُفعَّل وتُوقَف حسابات على الراوتر حسب حالة اشتراك كل مشترك (المنتهي والمجمّد يُقطعان). يُنصح بالمعاينة أولاً. متابعة؟") },
        confirmButton = { Button(onClick = { confirm = false; doRun { c, t -> val r = Router.apply(c, t, false); Router.saveLast(ctx, r); Router.summary(r, false) } }) { Text("نفّذ") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } },
    )
}

/** اختيار اسم الحساب من قائمة حسابات الراوتر. */
@Composable
fun RouterUserPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var names by remember { mutableStateOf<List<String>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val c = Router.cfg(ctx)
        names = if (c == null) emptyList() else try { withContext(Dispatchers.IO) { Router.listUsers(c) } } catch (e: Exception) { toast(ctx, e.message ?: "خطأ"); emptyList() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حسابات الراوتر") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text("بحث") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                if (names == null) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items((names ?: emptyList()).filter { q.isBlank() || it.contains(q, ignoreCase = true) }, key = { it }) { n ->
                        Text(n, modifier = Modifier.fillMaxWidth().clickable { onPick(n) }.padding(vertical = 10.dp, horizontal = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}


/** Safe interface manager: read all interfaces, but allow state changes only for wireless interfaces. */
@Composable
fun RouterInterfacesDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var rows by remember { mutableStateOf<List<RouterInterface>?>(null) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<RouterInterface, Boolean>?>(null) }
    var result by remember { mutableStateOf("") }

    fun reload() {
        val cfg = Router.cfg(ctx)
        if (cfg == null) { err = "اربط راوتر MikroTik أولاً"; rows = emptyList(); return }
        busy = true; err = ""
        scope.launch {
            try { rows = withContext(Dispatchers.IO) { Router.interfaceInventory(cfg) } }
            catch (e: Exception) { err = e.message ?: "تعذّر قراءة واجهات الراوتر"; rows = emptyList() }
            busy = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("واجهات MikroTik — الهب والسكترات") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("قراءة الحالة من الراوتر مباشرة. لأمان الشبكة، تغيير الحالة متاح لواجهات Wi‑Fi فقط؛ لن يسمح التطبيق بإيقاف Ethernet أو Bridge أو VLAN.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (err.isNotBlank()) Text(err, color = cs.error, fontSize = 12.sp)
                if (result.isNotBlank()) Text(result, color = cs.primary, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rows ?: emptyList(), key = { it.id }) { r ->
                        Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.bodyMedium)
                                    Text("${r.type.ifBlank { "نوع غير معروف" }} • ${if (r.disabled) "معطّلة" else if (r.running) "تعمل" else "مفعّلة لكن لا تعمل حالياً"}", fontSize = 11.sp, color = if (r.disabled) cs.error else cs.onSurfaceVariant)
                                    if (r.comment.isNotBlank()) Text(r.comment, fontSize = 10.sp, color = cs.onSurfaceVariant)
                                }
                                if (r.wireless) {
                                    Switch(checked = !r.disabled, enabled = !busy, onCheckedChange = { enabled -> pending = r to enabled })
                                } else {
                                    Text("قراءة فقط", fontSize = 10.sp, color = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { reload() }, enabled = !busy) { Text("تحديث") } },
        dismissButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
    )
    pending?.let { (iface, enable) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (enable) "تشغيل واجهة Wi‑Fi؟" else "إيقاف واجهة Wi‑Fi؟") },
            text = { Text("الواجهة: ${iface.name}. ${if (enable) "سيحاول الراوتر تشغيل الواجهة." else "قد ينقطع الاتصال عن جميع المشتركين المتصلين بهذه الواجهة."} تأكد أنها ليست واجهة الإدارة التي تتصل عبرها بالراوتر.") },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    val cfg = Router.cfg(ctx)
                    pending = null
                    if (cfg == null) { err = "اربط الراوتر أولاً"; return@Button }
                    busy = true; result = ""
                    scope.launch {
                        try { result = withContext(Dispatchers.IO) { Router.setWirelessInterface(cfg, iface, enable) }; rows = withContext(Dispatchers.IO) { Router.interfaceInventory(cfg) }; err = "" }
                        catch (e: Exception) { err = e.message ?: "فشل تغيير الواجهة" }
                        busy = false
                    }
                }) { Text("تأكيد التنفيذ") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } },
        )
    }
}
