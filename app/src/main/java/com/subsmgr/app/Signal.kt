package com.subsmgr.app

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun sigColor(s: Int?): Color = when {
    s == null -> Muted
    s >= -65 -> Ok
    s >= -75 -> Warn
    else -> Bad
}

private fun sigLabel(s: Int?): String = when {
    s == null -> "—"
    s >= -65 -> "جيدة"
    s >= -75 -> "متوسطة"
    else -> "ضعيفة"
}

/**
 * إشارة الصحون والسكترات مباشرة: يقرأ الراوتر/نقطة الوصول جدول العملاء اللاسلكيين ويُحدَّث كل 10 ثوانٍ.
 * الأضعف إشارة يظهر أولاً. يطابق الحساب باسم PPPoE ثم بدفتر المشتركين. initialQuery لفتح الشاشة على مشترك محدد.
 */
@Composable
fun SignalDialog(db: Db, initialQuery: String = "", onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var rows by remember { mutableStateOf<List<SignalRow>?>(null) }
    var err by remember { mutableStateOf("") }
    var q by remember { mutableStateOf(initialQuery) }
    var at by remember { mutableStateOf("") }
    val nameByUser = remember { db.listSubs().filter { !it.e1.isNullOrBlank() }.associate { it.e1!!.trim().lowercase() to it.name } }

    LaunchedEffect(Unit) {
        val c = Router.cfg(ctx)
        if (c == null) { err = "اربط الراوتر أولاً من الإعدادات"; rows = emptyList(); return@LaunchedEffect }
        while (true) {
            try {
                rows = withContext(Dispatchers.IO) { Router.signals(c) }
                err = ""
                at = java.time.LocalTime.now().toString().take(8)
            } catch (e: Exception) { err = e.message ?: "خطأ"; if (rows == null) rows = emptyList() }
            delay(10000)
        }
    }

    val all = rows ?: emptyList()
    val shown = all.filter { r ->
        q.isBlank() || r.mac.contains(q, true) || r.ip.contains(q) || r.label.contains(q, true) || (r.user ?: "").contains(q, true) ||
            (r.user?.let { nameByUser[it.lowercase()] } ?: "").contains(q, true)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("إشارة الصحون والسكترات", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                }
                if (rows == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (err.isNotBlank()) Text(err, color = Bad, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                if (rows != null && all.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard(Icons.Rounded.SettingsInputAntenna, "المتصلون", all.size.toString(), cs.primary, Modifier.weight(1f))
                        StatCard(Icons.Rounded.SignalCellularAlt, "ضعيفة", all.count { (it.signal ?: 0) < -75 && it.signal != null }.toString(), Bad, Modifier.weight(1f))
                    }
                    if (at.isNotBlank()) Text("آخر تحديث: $at (تلقائي كل 10 ثوانٍ)", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
                }
                OutlinedTextField(q, { q = it }, placeholder = { Text("ابحث بالاسم أو الحساب أو MAC أو IP") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rows != null && shown.isEmpty()) item { EmptyState(Icons.Rounded.SettingsInputAntenna, if (all.isEmpty()) "لا يوجد عملاء لاسلكيون متصلون على هذا الراوتر" else "لا توجد نتائج") }
                    items(shown, key = { it.iface + it.mac }) { r ->
                        val col = sigColor(r.signal)
                        AppCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val who = r.user?.let { u -> nameByUser[u.lowercase()]?.let { "$it ($u)" } ?: u } ?: r.label.ifBlank { "غير معروف" }
                                    Text(who, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text("\u200e" + r.mac + "  •  " + r.iface, fontSize = 11.sp, color = cs.onSurfaceVariant)
                                    Text(listOf(r.ip, r.uptime.takeIf { it.isNotBlank() }?.let { "متصل منذ $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString("  •  "),
                                        fontSize = 11.sp, color = cs.onSurfaceVariant)
                                    val rates = listOf(r.rxRate.takeIf { it.isNotBlank() }?.let { "استقبال $it" }, r.txRate.takeIf { it.isNotBlank() }?.let { "إرسال $it" }, r.ccq.takeIf { it.isNotBlank() }?.let { "CCQ $it%" })
                                        .filterNotNull().joinToString("  •  ")
                                    if (rates.isNotBlank()) Text("\u200e" + rates, fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(if (r.signal != null) "\u200e${r.signal} dBm" else "—", color = col, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Pill(sigLabel(r.signal), col)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
