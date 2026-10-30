package com.subsmgr.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Approval-gated network assistant. Only uses the existing, verified RouterOS interface API. */
@Composable
fun NetworkAssistantSection(interfaces: List<RouterInterface>, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val wireless = interfaces.filter { it.wireless }
    var selected by remember { mutableStateOf<RouterInterface?>(null) }
    var desiredEnabled by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Station") }

    SectionTitle("مساعد ربط الأبراج والأجهزة")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row { Icon(Icons.Rounded.Psychology, null, tint = cs.primary); Spacer(Modifier.width(8.dp)); Text("مساعد الشبكة — بموافقتك", fontWeight = FontWeight.Bold) }
            Text("يساعدك في تخطيط الرابط وفحصه. اختر دور الجهاز لمعرفة خطوات الإعداد؛ لا يغيّر المساعد أي إعداد لاسلكي دون عرض العملية وطلب موافقتك.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Text("دور جهاز Ubiquiti في الرابط", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == "AP", onClick = { role = "AP" }, label = { Text("نقطة أساسية AP") })
                FilterChip(selected = role == "Station", onClick = { role = "Station" }, label = { Text("محطة بعيدة Station") })
            }
            val steps = if (role == "AP") listOf(
                "تأكد أن الجهاز في البرج الرئيسي وموجّه نحو محطات الربط المقصودة.",
                "اختر نمط AP المناسب للطراز والبرنامج الثابت، ثم عيّن SSID وأماناً قوياً.",
                "تحقق من البلد والتردد وعرض القناة وفق الأنظمة المحلية والتداخل وخطة الشبكة.",
                "احفظ الإعدادات ثم تحقق من اتصال محطة بعيدة وقراءات الإشارة وCCQ."
            ) else listOf(
                "تأكد من وجود خط نظر مناسب ومحاذاة الهوائي مع البرج الرئيسي.",
                "اضبط نمط Station وSSID والأمان بما يطابق نقطة AP الرئيسية.",
                "تحقق من عنوان الإدارة وVLAN/Bridge ومسار الوصول قبل حفظ التغييرات.",
                "بعد الاتصال، راجع RSSI وNoise Floor وSNR وCCQ ومعدلات الإرسال والاستقبال."
            )
            steps.forEachIndexed { index, step -> Text("${index + 1}. $step", fontSize = 13.sp) }
            Text("حدود الربط المباشر: التطبيق يقرأ حالياً MikroTik عبر RouterOS API. لا توجد واجهة موحّدة مثبتة لكل أجهزة airMAX؛ لذلك يقدّم إرشاداً لها ولا يرسل إعدادات Ubiquiti غير موثقة.", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }

    SectionTitle("إجراءات يمكن تطبيقها بعد الموافقة")
    if (wireless.isEmpty()) {
        Text("لم تُقرأ واجهات Wi‑Fi قابلة للإدارة من MikroTik. حدّث حالة الشبكة أو تحقق من نوع الجهاز. لا يُسمح بتغيير Ethernet أو Bridge أو VLAN من هذا المساعد.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    }
    wireless.forEach { iface ->
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(iface.name, fontWeight = FontWeight.SemiBold)
                Text("${iface.type} · ${if (iface.disabled) "معطّلة" else if (iface.running) "تعمل" else "مفعّلة لكن الرابط غير قائم"}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && iface.disabled, onClick = { selected = iface; desiredEnabled = true }) { Text("طلب تشغيل") }
                    OutlinedButton(enabled = !busy && !iface.disabled, onClick = { selected = iface; desiredEnabled = false }) { Text("طلب إيقاف") }
                }
            }
        }
    }
    if (status.isNotBlank()) Text(status, color = if (status.startsWith("فشل") || status.startsWith("تعذّر")) cs.error else cs.primary, fontSize = 12.sp)

    val target = selected
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text("تأكيد تغيير إعداد الشبكة") },
            text = { Text("الجهاز: ${target.name}\nالعملية: ${if (desiredEnabled) "تشغيل الواجهة اللاسلكية" else "إيقاف الواجهة اللاسلكية"}\n\nقد يؤدي إيقاف الواجهة إلى قطع الاتصال عن الأجهزة التي تعتمد عليها. لن يُرسل الأمر إلا بعد ضغطك على «موافقة وتنفيذ».") },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true
                    status = "جارٍ تنفيذ التغيير والتحقق منه…"
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val cfg = Router.cfg(ctx) ?: throw IllegalStateException("لا توجد بيانات MikroTik محفوظة.")
                                Router.setWirelessInterface(cfg, target, desiredEnabled)
                            }
                        }
                        status = result.getOrElse { "فشل التنفيذ أو التحقق: ${it.message ?: "خطأ غير معروف"}" }
                        busy = false
                        selected = null
                        if (result.isSuccess) onRefresh()
                    }
                }) { Text(if (busy) "جارٍ التنفيذ…" else "موافقة وتنفيذ") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text("إلغاء") } }
        )
    }
}
