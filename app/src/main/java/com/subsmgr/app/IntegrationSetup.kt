package com.subsmgr.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Honest integration-readiness page. Only runs connector tests already implemented in the app.
 * It deliberately does not invent SAS3/Earthlink endpoints or pretend that a saved bookmark is an API connection.
 */
@Composable
fun IntegrationSetupSection() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var routerBusy by remember { mutableStateOf(false) }
    var sasBusy by remember { mutableStateOf(false) }
    var routerResult by remember { mutableStateOf<String?>(null) }
    var sasResult by remember { mutableStateOf<String?>(null) }

    SectionTitle("فحص الربط الفعلي")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Router, null, tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text("MikroTik RouterOS", fontWeight = FontWeight.Bold)
            }
            Text("يفحص بيانات الراوتر المحفوظة حالياً ويجرب تسجيل الدخول وقراءة هوية الراوتر وعدد حسابات HotSpot أو PPPoE. لا ينشئ حسابات ولا يغيّر الإعدادات أثناء الاختبار.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Button(enabled = !routerBusy, modifier = Modifier.fillMaxWidth(), onClick = {
                val cfg = Router.cfg(ctx)
                if (cfg == null) { routerResult = "لم يُحفظ راوتر بعد. افتح الإعدادات ← الراوترات والأبراج وأدخل بياناته."; return@Button }
                routerBusy = true; routerResult = null
                scope.launch {
                    routerResult = try { withContext(Dispatchers.IO) { Router.test(cfg) } }
                    catch (e: Exception) { "فشل الاختبار: ${e.message ?: "خطأ غير معروف"}" }
                    routerBusy = false
                }
            }) { if (routerBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.CheckCircle, null); Spacer(Modifier.width(7.dp)); Text("اختبار اتصال MikroTik") }
            routerResult?.let { Text(it, color = if (it.startsWith("فشل")) cs.error else cs.onSurface, fontSize = 12.sp) }
        }
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WifiTethering, null, tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text("SAS4", fontWeight = FontWeight.Bold)
            }
            Text("اختبار قراءة فقط باستخدام موصل SAS4 الموجود في المشروع. قد يختلف مسار الواجهة حسب نسخة اللوحة؛ نجاح الاختبار لا يعني أن التفعيل والتجديد متاحان.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Button(enabled = !sasBusy, modifier = Modifier.fillMaxWidth(), onClick = {
                val cfg = Sas.cfg(ctx)
                if (cfg == null) { sasResult = "لم تُحفظ بيانات SAS4. افتح إعدادات الربط وأدخل بيانات اللوحة."; return@Button }
                sasBusy = true; sasResult = null
                scope.launch {
                    sasResult = try { withContext(Dispatchers.IO) { Sas.test(cfg) } }
                    catch (e: Exception) { "فشل الاختبار: ${e.message ?: "خطأ غير معروف"}" }
                    sasBusy = false
                }
            }) { if (sasBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.CheckCircle, null); Spacer(Modifier.width(7.dp)); Text("اختبار قراءة SAS4") }
            sasResult?.let { Text(it, color = if (it.startsWith("فشل")) cs.error else cs.onSurface, fontSize = 12.sp) }
        }
    }

    SectionTitle("حالة الموصلات الأخرى")
    IntegrationStatusRow("SAS3", "غير مربوط API بعد", "يلزم توثيق API أو تفاصيل رسمية من مزوّد اللوحة.", false)
    IntegrationStatusRow("إيرثلينك", "رابط لوحة فقط حالياً", "لا تنفذ هذه النسخة أوامر مشتركين على إيرثلينك دون واجهة رسمية موثقة وصلاحيات مناسبة.", false)
    IntegrationStatusRow("Ubiquiti airMAX", "مراقبة تعتمد على طريقة الوصول", "يمكن عرض الإشارة إذا توفرت بيانات الجهاز عبر MikroTik أو واجهة إدارة موثقة؛ لا يُفترض أن كل صحن يوفّر API موحداً.", false)

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Security, null, tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text("الوصول من داخل الشبكة وخارجها", fontWeight = FontWeight.Bold)
            }
            Text("داخل الشبكة: استخدم عنوان الإدارة الداخلي. خارج الشبكة: الأفضل الاتصال عبر VPN مثل WireGuard أو OpenVPN ثم استخدام عنوان الشبكة الداخلي. لا تفتح منافذ API أو WebFig مباشرة على الإنترنت، ولا تستخدم HTTP مكشوفاً لبيانات الدخول.", fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text("قبل تفعيل التجديد الآلي", fontWeight = FontWeight.Bold)
            }
            Text("اختبر الاتصال أولاً، ثم جرّب على حساب تجريبي واحد. تأكد أن اسم مستخدم المشترك يطابق حساب الراوتر، وأن سياسة البروفايل صحيحة. موصل SAS4 الحالي لا يُعتبر موثوقاً لعمليات التفعيل/التجديد حتى يُثبت مسار الكتابة الرسمي للوحة لديك.", fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun IntegrationStatusRow(title: String, status: String, detail: String, ready: Boolean) {
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.CloudOff, null, tint = if (ready) cs.primary else cs.tertiary)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(status, color = cs.primary, fontSize = 12.sp)
                Text(detail, color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}
