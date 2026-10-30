package com.subsmgr.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val LICENSE_REQUEST_WHATSAPP = "9647863745531"

private fun appVersion(ctx: android.content.Context): String = try {
    @Suppress("DEPRECATION")
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "غير معروف"
} catch (_: Exception) { "غير معروف" }

/** طلب ترخيص عبر واتساب: يفتح رسالة جاهزة ولا يرسلها تلقائياً. */
@Composable
fun PurchaseSection(db: Db) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val plans = Cloud.config(ctx, "plans_text", "للاشتراك أو التجديد تواصل مع المطوّر.")
    var requestType by remember { mutableStateOf("تجديد الاشتراك") }
    var notes by remember { mutableStateOf("") }
    var chooseRequest by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant) {
            Text(plans, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
        OutlinedButton(onClick = { chooseRequest = true }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Rounded.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("طلب زيادة الحد أو تمديد / تجديد الترخيص")
        }
        if (chooseRequest) {
            AlertDialog(
                onDismissRequest = { chooseRequest = false },
                title = { Text("طلب الترخيص") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("زيادة عدد المشتركين", "تمديد مدة الترخيص", "تجديد الاشتراك", "استفسار عن الاشتراك أو التفعيل").forEach { option ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                RadioButton(selected = requestType == option, onClick = { requestType = option })
                                Text(option, fontSize = 13.sp)
                            }
                        }
                        OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات اختيارية") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                        val used = db.subscriberCountForLicense()
                        val limit = db.licenseLimitForRequests()
                        Text("الحد الحالي: $limit مشترك • المستخدم: $used", fontSize = 12.sp,
                            color = cs.onSurfaceVariant)
                        Text("ستُفتح محادثة واتساب برسالة جاهزة. لن تُرسل إلا بعد ضغطك على إرسال.",
                            fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val used = db.subscriberCountForLicense()
                        val limit = db.licenseLimitForRequests()
                        val email = Cloud.email(ctx)
                        val accountName = Cloud.displayName(ctx).ifBlank { "غير متاح" }
                        val body = buildString {
                            appendLine("السلام عليكم، أرسل طلباً بخصوص ترخيص VoltNet Pro.")
                            appendLine("نوع الطلب: $requestType")
                            appendLine("اسم التطبيق: VoltNet Pro")
                            appendLine("إصدار التطبيق: ${appVersion(ctx)}")
                            appendLine("اسم صاحب الحساب: $accountName")
                            if (Cloud.loggedIn(ctx) && email.isNotBlank()) appendLine("البريد الإلكتروني: $email")
                            appendLine("معرّف الجهاز: ${db.machineId()}")
                            appendLine("الحد الحالي للمشتركين: $limit")
                            appendLine("عدد المشتركين المستخدم: $used")
                            appendLine("حالة الترخيص الحالية: ${db.licenseInfo().message}")
                            if (notes.isNotBlank()) appendLine("ملاحظات: ${notes.trim()}")
                            appendLine()
                            append("لم يتم تضمين كلمات مرور أو بيانات المشتركين أو أسرار الاتصال.")
                        }
                        chooseRequest = false
                        openUrl(ctx, "https://wa.me/$LICENSE_REQUEST_WHATSAPP?text=" + android.net.Uri.encode(body))
                    }) { Text("فتح واتساب") }
                },
                dismissButton = { TextButton(onClick = { chooseRequest = false }) { Text("إلغاء") } }
            )
        }
    }
}

@Composable
fun SubscriptionDialog(db: Db, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("اشتراكي") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(db.licenseInfo().message, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                PurchaseSection(db)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
    )
}

/** بطاقة "اشتراكي" في الإعدادات. */
@Composable
fun SubscriptionCard(db: Db) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf(false) }
    val li = remember(open) { db.licenseInfo() }
    SectionTitle("اشتراكي")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(li.message, fontWeight = FontWeight.SemiBold)
            if (Cloud.loggedIn(ctx)) Text("الحساب: ${Cloud.email(ctx)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Button(onClick = { open = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("الاشتراك والتجديد") }
            TextButton(onClick = { policy = true }, modifier = Modifier.fillMaxWidth()) { Text("سياسة الخصوصية") }
        }
    }
    if (open) SubscriptionDialog(db) { open = false }
    if (policy) PolicyDialog { policy = false }
}

/** شريط تنبيه في أعلى التطبيق عندما يقترب انتهاء الترخيص (7 أيام أو أقل). */
@Composable
fun LicenseBanner(db: Db, rev: Int) {
    if (!Session.isAdmin) return
    val li = remember(rev) { db.licenseInfo() }
    val d = li.daysLeft ?: return
    if (d > 7) return
    var open by remember { mutableStateOf(false) }
    Surface(color = Warn.copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth().clickable { open = true }) {
        Text(
            if (d <= 0) "ينتهي ترخيصك اليوم. اضغط للتجديد" else "ينتهي ترخيصك خلال $d ${if (d == 1L) "يوم" else "أيام"}. اضغط للتجديد",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    if (open) SubscriptionDialog(db) { open = false }
}

@Composable
fun PolicyDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dev = Cloud.config(ctx, "developer_name", "المطوّر")
    val contact = Cloud.config(ctx, "support_whatsapp", "").ifBlank { "عبر التطبيق" }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("سياسة الخصوصية") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PRIVACY_SECTIONS.forEach { (t, b) ->
                    Text(t, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(b.replace("{dev}", dev).replace("{contact}", contact), fontSize = 12.sp)
                }
                Text("آخر تحديث: $PRIVACY_UPDATED", fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
    )
}
