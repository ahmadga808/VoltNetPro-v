package com.subsmgr.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.foundation.shape.CircleShape
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

/** Read-only network health dashboard. Uses existing MikroTik API and manually supplied device IPs only. */
@Composable
fun NetworkMonitorSection(initialSection: String? = null) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<RouterStats?>(null) }
    var interfaces by remember { mutableStateOf<List<RouterInterface>>(emptyList()) }
    var signals by remember { mutableStateOf<List<SignalRow>>(emptyList()) }
    var states by remember { mutableStateOf(Router.monState(ctx)) }
    var devices by remember { mutableStateOf(Router.monList(ctx)) }
    var history by remember { mutableStateOf(((ctx.getSharedPreferences("network_history_v1", android.content.Context.MODE_PRIVATE).getString("samples", "") ?: "").split("\n").filter { it.isNotBlank() }.takeLast(96).reversed())) }
    val pageScroll = rememberScrollState()
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }

    fun refresh() {
        val cfg = Router.cfg(ctx)
        if (cfg == null) { error = "لا توجد بيانات MikroTik محفوظة. افتح الإعدادات ← الراوترات والأبراج أولاً."; return }
        busy = true; error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val s = Router.dashboard(cfg, Router.wanIf(ctx))
                    val i = Router.interfaceInventory(cfg)
                    val sig = try { Router.signals(cfg) } catch (_: Exception) { emptyList() }
                    Triple(s, i, sig)
                }
                stats = result.first; interfaces = result.second; signals = result.third
                lastUpdated = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val sample = listOf(stamp, result.first.cpu.toString(), result.first.memPct.toString(), result.first.rxBps?.toString() ?: "", result.first.txBps?.toString() ?: "", result.first.pppActive?.toString() ?: "").joinToString("|")
                val prefs = ctx.getSharedPreferences("network_history_v1", android.content.Context.MODE_PRIVATE)
                val allSamples = (prefs.getString("samples", "") ?: "").split("\n").filter { it.isNotBlank() } + sample
                prefs.edit().putString("samples", allSamples.takeLast(96).joinToString("\n")).apply()
                history = allSamples.takeLast(96).reversed()
                val currentDevices = Router.monList(ctx)
                val checked = withContext(Dispatchers.IO) { Router.monCheck(ctx, currentDevices) }
                if (checked.isNotEmpty()) {
                    val merged = currentDevices.associate { it.ip to (checked[it.ip] ?: false) }
                    Router.monSaveState(ctx, merged); states = merged
                } else if (currentDevices.isNotEmpty()) error = "تعذّر فحص أجهزة البث من الراوتر؛ تأكد من VPN وصلاحية ping."
            } catch (e: Exception) { error = e.message ?: "تعذّر قراءة حالة الشبكة" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (Router.cfg(ctx) != null && stats == null && !busy) refresh()
    }

    Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (initialSection == "telegram" || initialSection == "assistant") {
        TelegramAssistantSection(signals = signals, states = states, devices = devices, stats = stats, section = initialSection)
    } else {
    SectionTitle("مركز الشبكة")
    Text("لوحة موحّدة لحالة الراوتر والأبراج والروابط. تُعرض القراءات الفعلية فقط؛ البيانات غير المتاحة تظهر بوضوح ولا تُستنتج تخميناً.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(lastUpdated?.let { "آخر قراءة ناجحة: $it" } ?: "لم تُسجّل قراءة ناجحة في هذه الجلسة", fontSize = 10.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = { refresh() }, enabled = !busy) { Text(if (busy) "جارٍ التحديث…" else "تحديث الآن") }
    }
    if (error != null) {
        Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text(error!!, modifier = Modifier.padding(12.dp), color = cs.onErrorContainer, fontSize = 12.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val onlineCount = devices.count { states[it.ip] == true }
        val downCount = devices.count { states[it.ip] == false }
        MiniMetricCard("أجهزة مستجيبة", onlineCount.toString(), cs.primary, Modifier.weight(1f))
        MiniMetricCard("أجهزة منقطعة", downCount.toString(), if (downCount > 0) cs.error else cs.primary, Modifier.weight(1f))
        MiniMetricCard("عملاء بإشارة", signals.count { it.signal != null }.toString(), cs.tertiary, Modifier.weight(1f))
    }
    SectionTitle("خريطة الاتصال")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = MaterialTheme.shapes.large, color = cs.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.Router, null, tint = cs.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stats?.identity?.takeIf { it.isNotBlank() } ?: "راوتر MikroTik", fontWeight = FontWeight.Bold)
                        Text(if (stats == null) "لم تُقرأ الحالة بعد" else "CPU ${stats!!.cpu}% · RAM ${stats!!.memPct}%", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
            Text("↓ روابط الفحص المسجلة ↓", fontSize = 11.sp, color = cs.onSurfaceVariant)
            if (devices.isEmpty()) Text("أضف عناوين IP للأبراج أو الأجهزة أدناه لبناء خريطة حالة أولية.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            devices.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { d ->
                        val state = states[d.ip]
                        val color = when (state) { true -> cs.primary; false -> cs.error; null -> cs.onSurfaceVariant }
                        AppCard(Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Rounded.CellTower, null, tint = color)
                                Text(d.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                                Text(d.ip, fontSize = 10.sp, color = cs.onSurfaceVariant, maxLines = 1)
                                Text(when (state) { true -> "متصل"; false -> "منقطع"; null -> "غير مفحوص" }, color = color, fontSize = 11.sp)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("هذه خريطة حالة مبنية على عناوين الفحص المسجلة، وليست اكتشافاً تلقائياً للتوصيلات الفيزيائية.", fontSize = 10.sp, color = cs.onSurfaceVariant)
        }
    }

    SectionTitle("مراقبة الشبكة")
    Text("قراءة فعلية من MikroTik المحفوظ داخل التطبيق. تُجمع عينات دورية عبر Android عند توفر الاتصال، ويمكن التحديث يدوياً؛ قد يؤخر Android التنفيذ، والعمل من خارج الموقع يحتاج VPN أو وسيطاً متاحاً دائماً.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    Button(onClick = { refresh() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null)
        Spacer(Modifier.width(8.dp)); Text(if (busy) "جارٍ الفحص…" else "تحديث حالة الشبكة")
    }
    error?.let { Text(it, color = cs.error, fontSize = 12.sp) }

    stats?.let { s ->
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Router, null, tint = cs.primary); Spacer(Modifier.width(8.dp)); Text(s.identity.ifBlank { "MikroTik" }, fontWeight = FontWeight.Bold) }
                Text("اللوحة: ${s.board.ifBlank { "غير متاح" }} · RouterOS ${s.version.ifBlank { "؟" }}", fontSize = 12.sp)
                Text("مدة التشغيل: ${s.uptime.ifBlank { "غير متاحة" }}")
                Text("المعالج: ${s.cpu}% · استخدام الذاكرة: ${s.memPct}%")
                Text("جلسات PPPoE: ${s.pppActive?.toString() ?: "غير متاح"} · جلسات HotSpot: ${s.hsActive?.toString() ?: "غير متاح"}")
                if (s.rxBps != null || s.txBps != null) Text("WAN: تنزيل ${formatRate(s.rxBps)} · رفع ${formatRate(s.txBps)}")
            }
        }
    }

    SectionTitle("سجل الأداء المحلي")
    Text("يحفظ التطبيق آخر 96 قراءة ناجحة على هذا الهاتف فقط. قد تأتي العينات من المراقبة الدورية أو التحديث اليدوي؛ وقد يؤخر Android مواعيد الجمع.", fontSize = 11.sp, color = cs.onSurfaceVariant)
    if (history.isEmpty()) {
        Text("لا توجد عينات بعد. اضغط تحديث حالة الشبكة أو انتظر دورة المراقبة الدورية بعد حفظ إعدادات MikroTik.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    } else {
        history.take(8).forEach { line ->
            val parts = line.split("|")
            if (parts.size >= 6) AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(parts[0], fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text("CPU ${parts[1]}% · RAM ${parts[2]}% · PPPoE ${parts[5].ifBlank { "غير متاح" }}", fontSize = 12.sp)
                    if (parts[3].isNotBlank() || parts[4].isNotBlank()) Text("WAN تنزيل ${formatRate(parts[3].toLongOrNull())} · رفع ${formatRate(parts[4].toLongOrNull())}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val csv = buildString {
                    append("timestamp,cpu_percent,ram_percent,wan_rx_bps,wan_tx_bps,pppoe_active\n")
                    history.asReversed().forEach { line ->
                        val p = line.split("|")
                        if (p.size >= 6) append(p.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" }).append('\n')
                    }
                }
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "VoltNet Pro - Network History")
                    putExtra(android.content.Intent.EXTRA_TEXT, csv)
                }
                ctx.startActivity(android.content.Intent.createChooser(intent, "تصدير سجل الشبكة CSV"))
            }) { Text("تصدير CSV") }
            TextButton(onClick = { ctx.getSharedPreferences("network_history_v1", android.content.Context.MODE_PRIVATE).edit().remove("samples").apply(); history = emptyList() }) { Text("مسح السجل المحلي") }
        }
    }

    SectionTitle("تحليل العينات المتاحة")
    val parsedSamples = history.mapNotNull { line ->
        val p = line.split("|")
        if (p.size >= 6) {
            val cpu = p[1].toDoubleOrNull(); val ram = p[2].toDoubleOrNull()
            if (cpu != null && ram != null) Triple(cpu, ram, p[0]) else null
        } else null
    }
    if (parsedSamples.isEmpty()) {
        Text("تحتاج إلى جمع عينتين على الأقل لتكوين مقارنة أولية. يعتمد التحليل على العينات المحفوظة يدوياً أو دورياً.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    } else {
        val avgCpu = parsedSamples.map { it.first }.average()
        val avgRam = parsedSamples.map { it.second }.average()
        val maxCpu = parsedSamples.maxOf { it.first }
        val maxRam = parsedSamples.maxOf { it.second }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ملخص ${parsedSamples.size} عينة محفوظة", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetricCard("متوسط CPU", "${"%.1f".format(avgCpu)}%", cs.primary, Modifier.weight(1f))
                    MiniMetricCard("أعلى CPU", "${"%.1f".format(maxCpu)}%", if (maxCpu >= 85) cs.error else cs.primary, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetricCard("متوسط RAM", "${"%.1f".format(avgRam)}%", cs.tertiary, Modifier.weight(1f))
                    MiniMetricCard("أعلى RAM", "${"%.1f".format(maxRam)}%", if (maxRam >= 90) cs.error else cs.tertiary, Modifier.weight(1f))
                }
                val note = when {
                    maxCpu >= 85 -> "ظهرت عينة CPU مرتفعة؛ افحص وقت العينة وحركة الشبكة قبل استنتاج وجود ازدحام."
                    maxRam >= 90 -> "ظهرت عينة RAM مرتفعة؛ تحقق من الموارد وسجل الراوتر قبل أي إجراء."
                    parsedSamples.size < 3 -> "عدد العينات قليل؛ لا يكفي لتحديد نمط ساعات الذروة."
                    else -> "هذه إحصاءات وصفية للعينات المتوفرة، وليست توقعًا أو إثباتًا لازدحام الشبكة."
                }
                Text(note, fontSize = 12.sp, color = cs.onSurfaceVariant)
                Text("الفترة: ${parsedSamples.last().third} إلى ${parsedSamples.first().third}. قد توجد فجوات بسبب تأخير Android أو انقطاع الاتصال.", fontSize = 10.sp, color = cs.onSurfaceVariant)
            }
        }
    }

    SectionTitle("واجهات الراوتر / نقاط الإرسال")
    if (interfaces.isEmpty()) Text("اضغط تحديث لقراءة الواجهات. الحالة تعتمد على بيانات MikroTik، وليست قياساً مباشراً لكل صحن Ubiquiti.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    interfaces.forEach { itf ->
        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(itf.name, fontWeight = FontWeight.SemiBold)
                    Text("${itf.type} · ${itf.comment}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = CircleShape, color = when { itf.disabled -> cs.tertiary; itf.running -> cs.primary; else -> cs.error }, modifier = Modifier.size(9.dp)) {}
                    Text(when { itf.disabled -> "معطّلة"; itf.running -> "تعمل"; else -> "لا يوجد رابط" }, color = if (!itf.disabled && itf.running) cs.primary else cs.error, fontSize = 12.sp)
                }
            }
        }
    }

    NetworkAssistantSection(interfaces = interfaces, onRefresh = { refresh() })

    SectionTitle("إشارة العملاء اللاسلكيين")
    if (signals.isEmpty()) Text("لا توجد بيانات إشارة مقروءة أو أن هذا الراوتر لا يستضيف واجهة wireless/wifi. أجهزة airMAX المستقلة قد تحتاج مراقبتها بعنوانها أو نظامها الخاص.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    signals.take(100).forEach { row ->
        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.user ?: row.label.ifBlank { row.mac }, fontWeight = FontWeight.SemiBold)
                    Text("${row.iface} · ${row.mac} · ${row.ip}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.signal?.let { "RSSI $it dBm" } ?: "RSSI —", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("CCQ ${row.ccq.ifBlank { "—" }} · SNR غير متاح", fontSize = 10.sp, color = cs.onSurfaceVariant)
                }
            }
        }
    }

    SectionTitle("أجهزة البث المراد فحصها")
    Text("أدخل عنوان IP داخلياً لكل جهاز. يفحص التطبيق هذه العناوين عبر ping من MikroTik عند التحديث، ويسجل آخر حالة محلية. هذا لا يضمن تنبيهاً فورياً إذا انقطع الموقع بالكامل.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم البرج / الجهاز") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("عنوان IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = {
        val cleanName = name.trim(); val cleanIp = ip.trim()
        val valid = cleanIp.matches(Regex("[A-Za-z0-9.-]{1,253}")) && cleanIp.contains('.')
        if (cleanName.isNotBlank() && valid && devices.none { it.ip == cleanIp }) {
            devices = devices + MonDevice(cleanName, cleanIp); Router.monSave(ctx, devices); name = ""; ip = ""
        } else error = "أدخل اسماً وعنوان IP صحيحاً غير مكرر."
    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("إضافة جهاز") }
    devices.forEach { d ->
        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Surface(shape = CircleShape, color = when (states[d.ip]) { true -> cs.primary; false -> cs.error; null -> cs.onSurfaceVariant }, modifier = Modifier.size(9.dp)) {}
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                    }
                    Text(d.ip, fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Text(when (states[d.ip]) { true -> "متصل"; false -> "منقطع"; null -> "لم يُفحص" }, color = when (states[d.ip]) { true -> cs.primary; false -> cs.error; null -> cs.onSurfaceVariant }, fontSize = 12.sp)
                TextButton(onClick = { devices = devices.filterNot { it.ip == d.ip }; Router.monSave(ctx, devices); states = states - d.ip }) { Text("حذف") }
            }
        }
    }
    if (devices.isNotEmpty()) Text("ملاحظة: فحص الخلفية في Android قد يتأخر بسبب قيود النظام؛ لا تعتمد عليه كإنذار طوارئ مضمون.", fontSize = 11.sp, color = cs.onSurfaceVariant)

    }
    }
}

@Composable
private fun MiniMetricCard(label: String, value: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = tint)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

private fun formatRate(v: Long?): String = if (v == null) "—" else if (v >= 1_000_000) "${"%.2f".format(v / 1_000_000.0)} Mbps" else "${v / 1000} Kbps"
