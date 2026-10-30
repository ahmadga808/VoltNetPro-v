package com.subsmgr.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val DEVELOPER_SUPPORT_EMAIL = "ahmadga808@gmail.com"

@Composable
fun SupportCenterSection() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var kind by remember { mutableStateOf("مشكلة أو خطأ") }
    var subject by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var adminVerified by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val version = remember {
        try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "غير معروف"
        } catch (_: Exception) { "غير معروف" }
    }

    SectionTitle("مركز الدعم والبلاغات")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("أرسل بلاغاً أو اقتراحاً إلى المطوّر عبر بريدك الإلكتروني. لن تُرسل أي بيانات للمشتركين أو كلمات مرور تلقائياً.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Text("نوع الرسالة", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            listOf("مشكلة أو خطأ", "اقتراح ميزة", "استفسار", "ملاحظة أخرى").forEach { option ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = kind == option, onClick = { kind = option })
                    Text(option)
                }
            }
            OutlinedTextField(subject, { subject = it }, label = { Text("عنوان الرسالة") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(details, { details = it }, label = { Text("اشرح المشكلة أو طلبك") }, modifier = Modifier.fillMaxWidth(), minLines = 4, shape = RoundedCornerShape(14.dp))
            Text("سيُرفق تلقائياً: إصدار التطبيق (${version})، ونوع الجهاز (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}).", fontSize = 11.sp, color = cs.onSurfaceVariant)
            Button(onClick = {
                if (details.isBlank()) { toast(ctx, "اكتب تفاصيل الرسالة أولاً"); return@Button }
                val mailSubject = Uri.encode("VoltNet Pro - $kind${if (subject.isBlank()) "" else ": $subject"}")
                val body = buildString {
                    append("نوع الرسالة: ").append(kind).append("\n")
                    append("العنوان: ").append(subject.ifBlank { "بدون عنوان" }).append("\n\n")
                    append(details).append("\n\n--- معلومات التطبيق ---\n")
                    append("الإصدار: ").append(version).append("\n")
                    append("الجهاز: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n")
                    if (Cloud.loggedIn(ctx)) append("حساب التطبيق: ").append(Cloud.email(ctx)).append("\n")
                }
                val uri = Uri.parse("mailto:$DEVELOPER_SUPPORT_EMAIL?subject=$mailSubject&body=${Uri.encode(body)}")
                try { ctx.startActivity(Intent(Intent.ACTION_SENDTO, uri)) }
                catch (_: Exception) { toast(ctx, "لا يوجد تطبيق بريد مثبت على الجهاز") }
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.Email, null); Spacer(Modifier.width(8.dp)); Text("مراجعة الرسالة وفتح البريد")
            }
            Text("لن تُعتبر الرسالة مرسلة إلا بعد الضغط على إرسال داخل تطبيق البريد.", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }

    SectionTitle("حساب المطوّر")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تظهر صلاحيات المطوّر فقط للحساب الذي يؤكده خادم الترخيص ضمن قائمة المطوّرين، وليس بمجرد كتابة البريد.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            if (Cloud.loggedIn(ctx)) Text("الحساب الحالي: ${Cloud.email(ctx)}", fontSize = 12.sp)
            Button(onClick = {
                busy = true
                // تحقق مباشر من جدول admins على الخادم؛ لا نعتمد على قيمة مخزنة قديمة.
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try { Cloud.refreshAdmin(ctx); Cloud.isAdminCached(ctx) } catch (_: Exception) { false }
                    }
                    busy = false; adminVerified = ok
                    toast(ctx, if (ok) "تم التحقق من صلاحية المطوّر" else "هذا الحساب غير معتمد كمطوّر أو تعذّر التحقق")
                }
            }, enabled = !busy && Cloud.loggedIn(ctx), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.SupportAgent, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "جارٍ التحقق..." else "التحقق من حساب المطوّر")
            }
            adminVerified?.let { ok -> Text(if (ok) "تم التحقق من حساب المطوّر. واجهة صندوق الرسائل الداخلي تحتاج إعداد خدمة الرسائل على الخادم قبل تفعيل استقبالها." else "لا توجد صلاحية مطوّر مؤكدة حالياً.", color = if (ok) cs.primary else cs.error, fontSize = 12.sp) }
        }
    }
}
