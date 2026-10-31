package com.subsmgr.app

import androidx.compose.foundation.layout.*
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


/** إشعار ما بعد إنشاء الحساب وطلب تفعيل الترخيص عبر واتساب. */
@Composable
private fun NewAccountWhatsAppNotice(email: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefix = email.substringBefore('@').trim()
    val phone = "9647863745531"
    val message = "مرحباً، تم إنشاء حساب جديد في VoltNet Pro.\nالجزء الأول من البريد الإلكتروني: $prefix\nأرجو تفعيل الحساب ومراجعة زيادة مدة الترخيص وحدّ المشتركين.\nالبريد الإلكتروني: $email"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تفعيل الحساب الجديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تم إنشاء الحساب بنجاح.")
                Text("لإكمال التفعيل وزيادة مدة الترخيص وحدّ المشتركين، أرسل الجزء الأول من بريدك الإلكتروني إلى الرقم التالي عبر واتساب:")
                Text(prefix, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("+964 786 374 5531", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("ستفتح رسالة جاهزة؛ راجعها ثم اضغط إرسال بنفسك.", fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = { openUrl(ctx, "https://wa.me/$phone?text=" + android.net.Uri.encode(message)) }) { Text("فتح واتساب") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لاحقاً") } }
    )
}

/** تسجيل الدخول أو إنشاء حساب (بريد + كلمة مرور). */
@Composable
fun CloudLoginDialog(onClose: () -> Unit, onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var signupNoticeEmail by remember { mutableStateOf("") }

    fun go(signup: Boolean) {
        if (email.isBlank() || pass.isBlank()) { toast(ctx, "أدخل البريد وكلمة المرور"); return }
        busy = true
        scope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val m = if (signup) Cloud.signUp(ctx, email.trim(), pass) else { Cloud.signIn(ctx, email.trim(), pass); "تم تسجيل الدخول" }
                    if (Cloud.loggedIn(ctx)) { try { Cloud.fetchLicense(ctx) } catch (e: Exception) { } }
                    m
                }
            } catch (e: Exception) {
                busy = false
                toast(ctx, e.message ?: "خطأ")
                return@launch
            }
            busy = false
            toast(ctx, msg)
            if (signup) signupNoticeEmail = email.trim()
            if (Cloud.loggedIn(ctx)) { Cloud.scheduleAuto(ctx); if (!signup) onLoggedIn() }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("حسابي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سجّل لتُحفظ بياناتك على السحابة وتستعيدها إن ضاع الهاتف.", fontSize = 12.sp)
                OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور (6 أحرف فأكثر)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = { go(false) }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("تسجيل الدخول") }
                OutlinedButton(onClick = { go(true) }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("إنشاء حساب جديد") }
                TextButton(enabled = !busy, onClick = {
                    if (email.isBlank()) { toast(ctx, "اكتب بريدك أولاً"); return@TextButton }
                    busy = true
                    scope.launch {
                        val m = try { withContext(Dispatchers.IO) { Cloud.recover(email.trim()) }; "أُرسل رابط تغيير كلمة المرور إلى بريدك" }
                        catch (e: Exception) { e.message ?: "خطأ" }
                        busy = false; toast(ctx, m)
                    }
                }) { Text("نسيت كلمة المرور؟") }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text("إغلاق") } },
    )
    if (signupNoticeEmail.isNotBlank()) NewAccountWhatsAppNotice(signupNoticeEmail) {
        signupNoticeEmail = ""
        if (Cloud.loggedIn(ctx)) onLoggedIn()
    }
}

/** بطاقة الحساب والنسخ السحابي في الإعدادات. */
@Composable
fun CloudAccountCard(db: Db, bump: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var local by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<CloudFile>?>(null) }
    var pick by remember { mutableStateOf<CloudFile?>(null) }
    var delAcc by remember { mutableStateOf(false) }
    val logged = remember(local) { Cloud.loggedIn(ctx) }

    fun run(block: () -> String) {
        busy = true
        scope.launch {
            val m = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { e.message ?: "خطأ" }
            busy = false; toast(ctx, m); local++; bump()
        }
    }

    SectionTitle("الحساب والنسخ السحابي")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!logged) {
                Text("أنشئ حساباً ليُنسخ بياناتك تلقائياً كل يوم إلى السحابة، فتستعيدها على أي هاتف جديد. وترخيصك يُدار من حسابك.",
                    fontSize = 12.sp, color = cs.onSurfaceVariant)
                Button(onClick = { login = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("تسجيل الدخول / إنشاء حساب")
                }
            } else {
                Text("الحساب: ${Cloud.email(ctx)}", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("الترخيص: ${db.licenseInfo().message}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Text("آخر نسخة سحابية: ${Cloud.lastBackup(ctx).ifEmpty { "لم تُنفَّذ بعد" }}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { run { val n = Cloud.uploadAll(ctx); "تم رفع $n قاعدة إلى السحابة" } }, enabled = !busy,
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("نسخ الآن") }
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            val mode = db.setting("mode", "internet")
                            try { files = withContext(Dispatchers.IO) { Cloud.list(ctx).filter { it.name.startsWith(mode + "_") } } }
                            catch (e: Exception) { toast(ctx, e.message ?: "خطأ") }
                            busy = false
                        }
                    }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("استعادة") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("نسخ يومي تلقائي")
                    var auto by remember(local) { mutableStateOf(Cloud.autoOn(ctx)) }
                    Switch(checked = auto, onCheckedChange = { auto = it; Cloud.setAuto(ctx, it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { run { Cloud.fetchLicense(ctx); "تم تحديث الترخيص" } }, enabled = !busy,
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("تحديث الترخيص") }
                    TextButton(onClick = { Cloud.signOut(ctx); local++; bump() }, modifier = Modifier.weight(1f)) { Text("تسجيل الخروج", color = Bad) }
                }
                TextButton(onClick = { delAcc = true }, modifier = Modifier.fillMaxWidth()) { Text("حذف حسابي وبياناتي السحابية", color = Bad) }
            }
        }
    }

    if (login) CloudLoginDialog({ login = false }) { login = false; local++; bump() }
    if (delAcc) ConfirmDialog("سيُحذف حسابك وكل نسخك السحابية وترخيصك نهائياً ولا يمكن التراجع. بيانات هذا الهاتف تبقى. متابعة؟", "حذف نهائي", { delAcc = false }) {
        delAcc = false
        run { Cloud.deleteAccount(ctx); "تم حذف حسابك وبياناتك السحابية" }
    }

    files?.let { list ->
        AlertDialog(
            onDismissRequest = { files = null },
            title = { Text("اختر نسخة للاستعادة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (list.isEmpty()) Text("لا توجد نسخ سحابية لهذا النشاط.")
                    list.forEach { f ->
                        val label = if (f.name.contains("latest")) "آخر نسخة" else f.name.substringAfter('_').removeSuffix(".db")
                        TextButton(onClick = { pick = f; files = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(label + "   " + f.updatedAt.take(16).replace('T', ' '))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { files = null }) { Text("إغلاق") } },
        )
    }
    pick?.let { f ->
        ConfirmDialog("ستُستبدل كل بيانات النشاط الحالي بهذه النسخة. متابعة؟", "استعادة", { pick = null }) {
            busy = true
            scope.launch {
                val bytes = try { withContext(Dispatchers.IO) { Cloud.download(ctx, f.name) } }
                catch (e: Exception) { toast(ctx, e.message ?: "خطأ"); busy = false; return@launch }
                busy = false
                safe(ctx) { db.restore(java.io.ByteArrayInputStream(bytes)); toast(ctx, "تمت الاستعادة"); bump() }
            }
        }
    }
}

/** شاشة الدخول الأولى: تسجيل الدخول أو حساب جديد، مع خيار المتابعة بدون حساب. */
@Composable
fun LoginGate(db: Db, ok: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<CloudFile>?>(null) }
    var signupNoticeEmail by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { try { withContext(Dispatchers.IO) { Cloud.fetchConfig(ctx) } } catch (e: Exception) { } }

    fun go(signup: Boolean) {
        if (email.isBlank() || pass.isBlank()) { toast(ctx, "أدخل البريد وكلمة المرور"); return }
        busy = true
        scope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val m = if (signup) Cloud.signUp(ctx, email.trim(), pass) else { Cloud.signIn(ctx, email.trim(), pass); "تم تسجيل الدخول" }
                    if (Cloud.loggedIn(ctx)) { try { Cloud.fetchLicense(ctx) } catch (e: Exception) { } }
                    m
                }
            } catch (e: Exception) {
                busy = false
                toast(ctx, e.message ?: "خطأ")
                return@launch
            }
            toast(ctx, msg)
            if (signup) signupNoticeEmail = email.trim()
            if (!Cloud.loggedIn(ctx)) { busy = false; return@launch }
            Cloud.scheduleAuto(ctx)
            if (signup) { busy = false; return@launch }
            // هاتف جديد بلا بيانات وعند الحساب نسخة سحابية: اعرض الاستعادة
            val latest = try { withContext(Dispatchers.IO) { Cloud.list(ctx).filter { it.name.endsWith("_latest.db") } } } catch (e: Exception) { emptyList() }
            busy = false
            if (latest.isNotEmpty() && db.listSubs().isEmpty()) found = latest else ok()
        }
    }

    AuthFrame(Icons.Rounded.Person, "مرحباً بك", "سجّل الدخول أو أنشئ حساباً لتُحفظ بياناتك على السحابة") {
        OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور (6 أحرف فأكثر)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = { go(false) }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("تسجيل الدخول") }
        OutlinedButton(onClick = { go(true) }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("إنشاء حساب جديد") }
        TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            if (email.isBlank()) { toast(ctx, "اكتب بريدك أولاً"); return@TextButton }
            busy = true
            scope.launch {
                val m = try { withContext(Dispatchers.IO) { Cloud.recover(email.trim()) }; "أُرسل رابط تغيير كلمة المرور إلى بريدك" }
                catch (e: Exception) { e.message ?: "خطأ" }
                busy = false; toast(ctx, m)
            }
        }) { Text("نسيت كلمة المرور؟") }
        TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { Session.skipLogin = true; ok() }) {
            Text("المتابعة بدون حساب (لن تُحفظ نسخة سحابية)")
        }
        var policy by remember { mutableStateOf(false) }
        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { policy = true }) { Text("سياسة الخصوصية", fontSize = 12.sp) }
        if (policy) PolicyDialog { policy = false }
    }

    if (signupNoticeEmail.isNotBlank()) NewAccountWhatsAppNotice(signupNoticeEmail) {
        signupNoticeEmail = ""
        if (Cloud.loggedIn(ctx)) {
            busy = true
            scope.launch {
                val latest = try { withContext(Dispatchers.IO) { Cloud.list(ctx).filter { it.name.endsWith("_latest.db") } } } catch (e: Exception) { emptyList() }
                busy = false
                if (latest.isNotEmpty() && db.listSubs().isEmpty()) found = latest else ok()
            }
        }
    }

    found?.let { list ->
        AlertDialog(
            onDismissRequest = { found = null; ok() },
            title = { Text("وجدنا نسخة من بياناتك") },
            text = { Text("لحسابك نسخة محفوظة على السحابة. هل تريد استعادتها على هذا الهاتف؟") },
            confirmButton = {
                Button(onClick = {
                    found = null
                    busy = true
                    scope.launch {
                        val files = try { withContext(Dispatchers.IO) { list.map { it.name to Cloud.download(ctx, it.name) } } }
                        catch (e: Exception) { toast(ctx, e.message ?: "خطأ"); busy = false; ok(); return@launch }
                        busy = false
                        safe(ctx) {
                            var first: String? = null
                            files.forEach { (name, bytes) ->
                                val mode = name.substringBefore("_latest")
                                if (MODES.containsKey(mode)) {
                                    db.switchMode(mode)
                                    db.restore(java.io.ByteArrayInputStream(bytes))
                                    if (first == null) first = mode
                                }
                            }
                            first?.let { db.switchMode(it) }
                            toast(ctx, "تمت استعادة بياناتك")
                        }
                        ok()
                    }
                }) { Text("استعادة") }
            },
            dismissButton = { TextButton(onClick = { found = null; ok() }) { Text("تجاهل") } },
        )
    }
}
