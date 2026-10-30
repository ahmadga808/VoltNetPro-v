package com.subsmgr.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** بطاقة لوحة التراخيص: تظهر فقط لحساب المطوّر (المسجّل في جدول admins). */
@Composable
fun LicenseAdminCard() {
    val ctx = LocalContext.current
    if (!Cloud.loggedIn(ctx) || !Cloud.isAdminCached(ctx)) return
    var open by remember { mutableStateOf(false) }
    SectionTitle("إدارة التراخيص (المطوّر)")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("جدّد أو جمّد أو امنح أياماً مجانية لأي حساب مباشرة من هنا.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { open = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.AdminPanelSettings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("فتح لوحة التراخيص")
            }
        }
    }
    if (open) LicenseAdminDialog { open = false }
}

@Composable
private fun LicenseAdminDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<LicRow>?>(null) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<LicRow?>(null) }
    var form by remember { mutableStateOf<String?>(null) }   // days | limit | paid

    fun reload() {
        busy = true
        scope.launch {
            try { rows = withContext(Dispatchers.IO) { Cloud.adminList(ctx) } } catch (e: Exception) { toast(ctx, e.message ?: "خطأ") }
            busy = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun applyLic(r: LicRow, plan: String = r.plan, expires: String = r.expires, limit: Int = r.limit, susp: Boolean = r.suspended) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Cloud.adminSet(ctx, r.userId, plan, expires, limit, susp); rows = Cloud.adminList(ctx) }
                toast(ctx, "تم التحديث")
            } catch (e: Exception) { toast(ctx, e.message ?: "خطأ") }
            busy = false
        }
    }
    fun plusDays(r: LicRow, n: Long): String {
        val cur = runCatching { LocalDate.parse(r.expires) }.getOrNull() ?: LocalDate.now()
        val base = if (cur.isBefore(LocalDate.now())) LocalDate.now() else cur
        return base.plusDays(n).toString()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("لوحة التراخيص", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { reload() }, enabled = !busy) { Icon(Icons.Rounded.Refresh, "تحديث") }
                }
                OutlinedTextField(q, { q = it }, label = { Text("بحث بالبريد") }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val list = (rows ?: emptyList()).filter { q.isBlank() || it.email.contains(q, ignoreCase = true) }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rows != null && list.isEmpty()) item { EmptyState(Icons.Rounded.People, "لا توجد حسابات") }
                    items(list, key = { it.userId }) { r ->
                        val expired = runCatching { LocalDate.parse(r.expires).isBefore(LocalDate.now()) }.getOrDefault(false)
                        AppCard(Modifier.fillMaxWidth().clickable { sel = r }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.email.ifBlank { r.userId.take(8) }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                                    if (r.suspended) Pill("مجمّد", Bad) else if (expired) Pill("منتهي", Warn) else Pill("فعّال", Ok)
                                }
                                Text("الباقة: ${r.plan}  •  ينتهي: ${r.expires}  •  الحد: ${r.limit} مشترك", fontSize = 12.sp, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    sel?.let { r ->
        AlertDialog(
            onDismissRequest = { sel = null },
            title = { Text(r.email.ifBlank { "حساب" }) },
            text = {
                Column {
                    TextButton(onClick = { applyLic(r, expires = plusDays(r, 30)); sel = null }, modifier = Modifier.fillMaxWidth()) { Text("تجديد 30 يوماً") }
                    TextButton(onClick = { applyLic(r, expires = plusDays(r, 180)); sel = null }, modifier = Modifier.fillMaxWidth()) { Text("تجديد 6 أشهر") }
                    TextButton(onClick = { applyLic(r, expires = plusDays(r, 365)); sel = null }, modifier = Modifier.fillMaxWidth()) { Text("تجديد سنة") }
                    TextButton(onClick = { form = "days" }, modifier = Modifier.fillMaxWidth()) { Text("منح أيام مجانية...") }
                    TextButton(onClick = { form = "limit" }, modifier = Modifier.fillMaxWidth()) { Text("تغيير حد المشتركين...") }
                    TextButton(onClick = { form = "paid" }, modifier = Modifier.fillMaxWidth()) { Text("تفعيل باقة مدفوعة...") }
                    TextButton(onClick = { applyLic(r, susp = !r.suspended); sel = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (r.suspended) "إلغاء التجميد" else "تجميد الحساب", color = if (r.suspended) Ok else Bad)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sel = null }) { Text("إغلاق") } },
        )
    }
    val target = sel
    when (form) {
        "days" -> if (target != null) FormDialog("منح أيام مجانية", listOf(F("days", "عدد الأيام *", number = true)), emptyMap(), { form = null }) { d ->
            val n = d["days"]?.toLongOrNull() ?: throw IllegalArgumentException("أدخل عدد الأيام")
            applyLic(target, expires = plusDays(target, n)); form = null; sel = null
        }
        "limit" -> if (target != null) FormDialog("حد المشتركين", listOf(F("limit", "الحد الجديد *", number = true)), mapOf("limit" to target.limit.toString()), { form = null }) { d ->
            val n = d["limit"]?.toIntOrNull() ?: throw IllegalArgumentException("أدخل رقماً")
            applyLic(target, limit = n); form = null; sel = null
        }
        "paid" -> if (target != null) FormDialog("تفعيل باقة مدفوعة",
            listOf(F("plan", "اسم الباقة"), F("days", "المدة بالأيام *", number = true), F("limit", "حد المشتركين *", number = true)),
            mapOf("plan" to "paid", "days" to "365", "limit" to "100"), { form = null }) { d ->
            val n = d["days"]?.toLongOrNull() ?: throw IllegalArgumentException("أدخل المدة")
            val lim = d["limit"]?.toIntOrNull() ?: throw IllegalArgumentException("أدخل الحد")
            applyLic(target, plan = (d["plan"] ?: "").ifBlank { "paid" }, expires = plusDays(target, n), limit = lim, susp = false); form = null; sel = null
        }
    }
}
