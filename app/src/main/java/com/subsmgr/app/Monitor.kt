package com.subsmgr.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun bps(v: Long?): String = when {
    v == null -> "-"
    v >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", v / 1_000_000.0)
    v >= 1_000 -> String.format(java.util.Locale.US, "%.0f Kbps", v / 1_000.0)
    else -> "$v bps"
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(label, fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }
}

/** لوحة الراوتر الحية: المعالج والذاكرة والمتصلون والترافيك، تُحدَّث كل 5 ثوانٍ. */
@Composable
fun RouterDashboardDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<RouterStats?>(null) }
    var err by remember { mutableStateOf("") }
    var wan by remember { mutableStateOf(Router.wanIf(ctx)) }
    var ifs by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val c = Router.cfg(ctx)
        if (c == null) { err = "اربط الراوتر أولاً من الإعدادات"; return@LaunchedEffect }
        try { ifs = withContext(Dispatchers.IO) { Router.interfaces(c) } } catch (e: Exception) { }
    }
    LaunchedEffect(wan) {
        val c = Router.cfg(ctx) ?: return@LaunchedEffect
        while (true) {
            try { st = withContext(Dispatchers.IO) { Router.dashboard(c, wan) }; err = "" } catch (e: Exception) { err = e.message ?: "خطأ" }
            delay(5000)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("لوحة الراوتر", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Column(Modifier.weight(1f).padding(12.dp).then(Modifier), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (err.isNotBlank()) Text(err, color = Bad, fontSize = 12.sp)
                    val s = st
                    if (s == null && err.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (s != null) {
                        Text("${s.identity}  •  ${s.board}  •  RouterOS ${s.version}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("يعمل منذ: ${s.uptime}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stat("المعالج", "${s.cpu}%", Modifier.weight(1f))
                            Stat("الذاكرة", "${s.memPct}%", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stat("متصلون PPPoE", s.pppActive?.toString() ?: "-", Modifier.weight(1f))
                            Stat("متصلون هوتسبوت", s.hsActive?.toString() ?: "-", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stat("مستخدمو/كروت هوتسبوت", s.hsUsers?.toString() ?: "-", Modifier.weight(1f))
                        }
                        Text("الترافيك المباشر" + if (wan.isBlank()) " (اختر منفذ الإنترنت)" else " ($wan)", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stat("تنزيل", bps(s.rxBps), Modifier.weight(1f))
                            Stat("رفع", bps(s.txBps), Modifier.weight(1f))
                        }
                    }
                    if (ifs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ifs.forEach { n -> FilterChip(selected = wan == n, onClick = { wan = n; Router.setWanIf(ctx, n) }, label = { Text(n) }) }
                    }
                }
            }
        }
    }
}

/** مراقبة الأجهزة والسكترات: إشعار عند توقف أي جهاز أو عودته (فحص كل 15 دقيقة في الخلفية). */
@Composable
fun MonitorDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(Router.monList(ctx)) }
    var state by remember { mutableStateOf(Router.monState(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<MonDevice?>(null) }
    var at by remember { mutableStateOf(Router.monAt(ctx)) }

    fun check() {
        if (list.isEmpty()) return
        busy = true
        scope.launch {
            val r = try { withContext(Dispatchers.IO) { Router.monCheck(ctx, list) } } catch (e: Exception) { emptyMap() }
            busy = false
            if (r.isEmpty()) toast(ctx, "تعذّر الفحص. تأكد من الاتصال بالراوتر")
            else { Router.monSaveState(ctx, state + r); state = Router.monState(ctx); at = Router.monAt(ctx) }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("مراقبة السكترات والأجهزة", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { check() }, enabled = !busy) { Icon(Icons.Rounded.Refresh, "فحص الآن") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("أضف السكترات (نقاط الإرسال) وأجهزة الشبكة بعناوينها. يفحصها الراوتر بأمر ping ويصلك إشعار عند التوقف أو العودة. الفحص التلقائي كل 15 دقيقة (حد نظام أندرويد).",
                    fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                if (at.isNotBlank()) Text("آخر فحص: $at", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (list.isEmpty()) item { EmptyState(Icons.Rounded.SettingsInputAntenna, "لا توجد أجهزة مراقَبة بعد") }
                    items(list, key = { it.ip }) { d ->
                        val up = state[d.ip]
                        AppCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = when (up) { true -> Ok; false -> Bad; null -> cs.outline }, modifier = Modifier.size(12.dp)) {}
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, fontWeight = FontWeight.Bold)
                                    Text("\u200e" + d.ip + "  •  " + when (up) { true -> "يعمل"; false -> "متوقف"; null -> "لم يُفحص" }, fontSize = 12.sp, color = cs.onSurfaceVariant)
                                }
                                IconButton(onClick = { del = d }) { Icon(Icons.Rounded.Delete, "حذف", tint = Bad) }
                            }
                        }
                    }
                }
                Button(onClick = { adding = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("إضافة جهاز أو سكتر")
                }
            }
        }
    }
    if (adding) FormDialog("جهاز جديد", listOf(F("name", "الاسم (مثل: سكتر الحي الشمالي) *"), F("ip", "عنوان IP *")), emptyMap(), { adding = false }) { d ->
        val ip = (d["ip"] ?: "").trim()
        if ((d["name"] ?: "").isBlank() || ip.isEmpty()) throw IllegalArgumentException("أدخل الاسم والعنوان")
        if (list.any { it.ip == ip }) throw IllegalArgumentException("هذا العنوان مضاف مسبقاً")
        list = list + MonDevice((d["name"] ?: "").trim(), ip)
        Router.monSave(ctx, list)
        adding = false
    }
    del?.let { d -> ConfirmDialog("حذف ${d.name} من المراقبة؟", "حذف", { del = null }) { list = list.filter { it.ip != d.ip }; Router.monSave(ctx, list); del = null } }
}
