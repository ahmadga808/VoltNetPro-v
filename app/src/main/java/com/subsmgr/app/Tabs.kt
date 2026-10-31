@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.subsmgr.app

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate

private fun statusMap(gen: Boolean) =
    if (gen) mapOf("الكل" to "", "مسدّد" to "paid", "عليه دين" to "debt", "مقطوع" to "cut")
    else mapOf("الكل" to "", "فعّال" to "active", "متصل" to "online", "غير متصل" to "offline", "ينتهي قريباً" to "soon", "منتهي / غير فعّال" to "expired", "عليه دين" to "debt", "مجمّد" to "frozen")
private val WhatsApp = Color(0xFF25D366)
private val CutColor = Color(0xFF6D28D9)
private val FrozenColor = Color(0xFF0284C7)

private data class Act(val label: String, val key: String, val icon: ImageVector, val color: Color)

@Composable
private fun ActionTile(label: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant.copy(alpha = 0.55f))
            .clickable { onClick() }.padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBubble(icon, color, 38)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, minLines = 2, lineHeight = 14.sp)
    }
}

@Composable
fun Avatar(name: String, color: Color, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1), color = color, fontWeight = FontWeight.Bold, fontSize = (size * 0.4).sp)
    }
}

private fun statusColor(st: String) = when (st) { "فعّال" -> Color(0xFF2196F3); "مسدّد" -> Ok; "منتهي" -> Color(0xFFFFC107); "عليه دين" -> Bad; "مقطوع" -> CutColor; "مجمّد" -> Color(0xFF8B5CF6); else -> Muted }

// ======================= المشتركون =======================
@Composable
fun SubsTab(db: Db, rev: Int, bump: () -> Unit, initialFilter: String? = null) {
    val ctx = LocalContext.current
    val gen = db.isGenerator()
    LaunchedEffect(gen) {
        while (!gen) {
            if (Router.cfg(ctx) != null) {
                try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Router.refreshOnline(ctx) } } catch (_: Exception) { }
            }
            val sasCfg = Sas.cfg(ctx)
            if (sasCfg != null) {
                try {
                    val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Sas.users(sasCfg) }
                    Sas.publishUsers(snapshot)
                } catch (e: Exception) {
                    Sas.usersSyncError.value = e.message ?: "تعذرت قراءة بيانات SAS"
                }
            } else {
                Sas.usersSyncError.value = "لم يتم ضبط ربط SAS"
            }
            kotlinx.coroutines.delay(60000)
        }
    }
    var q by remember(gen) { mutableStateOf("") }
    var area by remember(gen) { mutableStateOf("") }
    var status by remember(gen) { mutableStateOf("الكل") }
    var local by remember { mutableIntStateOf(0) }
    var sortKey by remember(gen) { mutableStateOf("name") }
    var sortMenu by remember { mutableStateOf(false) }
    val startDates = remember(rev, local) { db.subscriptionStartDates() }
    val packagePrices = remember(rev, local) { db.packages().associate { it.id to it.price } }
    val sortLabels = listOf(
        "name" to "الاسم (أ-ي)",
        "start_old" to "تاريخ الاشتراك (الأقدم)",
        "start_new" to "تاريخ الاشتراك (الأحدث)",
        "end_near" to "تاريخ الانتهاء (الأقرب)",
        "end_far" to "تاريخ الانتهاء (الأبعد)",
        "price_low" to "السعر (الأقل)",
        "price_high" to "السعر (الأعلى)"
    )
    LaunchedEffect(initialFilter, gen) {
        status = if (initialFilter == null) "الكل"
        else statusMap(gen).entries.firstOrNull { it.value == initialFilter }?.key ?: "الكل"
    }
    var areaMenu by remember { mutableStateOf(false) }
    val all = remember(rev, local) { db.listSubs() }
    val sasUsers = Sas.usersSnapshot.value
    val filteredSubs = remember(rev, local, q, area, status, sasUsers, Sas.lastUsersSync.value, Sas.usersSyncError.value) {
        val filter = statusMap(gen)[status] ?: ""
        val candidates = db.listSubs(q.trim(), area)
        fun sasUser(s: Sub): SasUser? = s.e1?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { sasUsers[it] }
        when {
            !gen && filter == "online" -> candidates.filter { s ->
                val u = sasUser(s)
                u != null && u.online && u.enabled && u.status() == "فعّال" && db.statusOf(s) != "مجمّد"
            }
            !gen && filter == "offline" -> candidates.filter { s ->
                val u = sasUser(s)
                u != null && !u.online && u.enabled && u.status() == "فعّال" && db.statusOf(s) != "مجمّد"
            }
            !gen && filter == "expired" -> candidates.filter { s ->
                db.statusOf(s) == "منتهي" || sasUser(s)?.enabled == false
            }
            !gen && filter == "soon" -> candidates.filter { s ->
                val end = runCatching { LocalDate.parse(s.endDate) }.getOrNull()
                end != null && end >= LocalDate.now() && end <= LocalDate.now().plusDays(3) && db.statusOf(s) == "فعّال"
            }.sortedBy { it.endDate }
            else -> db.listSubs(q.trim(), area, filter)
        }
    }
    val subs = remember(filteredSubs, sortKey, startDates, packagePrices) {
        when (sortKey) {
            "start_old" -> filteredSubs.sortedWith(compareBy<Sub> { startDates[it.id].isNullOrBlank() }.thenBy { startDates[it.id] ?: "" })
            "start_new" -> filteredSubs.sortedWith(compareByDescending<Sub> { !startDates[it.id].isNullOrBlank() }.thenByDescending { startDates[it.id] ?: "" })
            "end_near" -> filteredSubs.sortedWith(compareBy<Sub> { it.endDate.isNullOrBlank() }.thenBy { it.endDate ?: "" })
            "end_far" -> filteredSubs.sortedWith(compareByDescending<Sub> { !it.endDate.isNullOrBlank() }.thenByDescending { it.endDate ?: "" })
            "price_low" -> filteredSubs.sortedBy { packagePrices[it.packageId] ?: Long.MAX_VALUE }
            "price_high" -> filteredSubs.sortedByDescending { packagePrices[it.packageId] ?: Long.MIN_VALUE }
            else -> filteredSubs.sortedBy { it.name.lowercase() }
        }
    }
    val areas = remember(rev, local) { db.areas() }
    val alertText = remember(rev, local) { db.alertsText() }

    var sel by remember { mutableStateOf<Sub?>(null) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }          // new/edit/renew/pay/disc/read/stmt
    var info by remember { mutableStateOf<String?>(null) }
    var receiptTx by remember { mutableStateOf<Long?>(null) }
    var mapPts by remember { mutableStateOf<List<MapPt>?>(null) }
    var sendMsg by remember { mutableStateOf<SendMsg?>(null) }
    var reminders by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf(false) }
    val refresh: () -> Unit = { local++; bump() }
    val ampPrice = remember(rev, local) { db.ampPrice() }
    val cs = MaterialTheme.colorScheme

    val pkgs = remember(rev, local) { db.packages() }
    val labelToId = LinkedHashMap<String, Long>()
    val idToLabel = HashMap<Long, String>()
    pkgs.forEach { p ->
        var lab = "${p.name} - ${fmt(p.price)}"
        if (lab in labelToId) lab += " (${p.id})"
        labelToId[lab] = p.id; idToLabel[p.id] = lab
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (gen) item {
                AppCard(Modifier.fillMaxWidth(), onClick = { dialog = "month" }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.CalendarMonth, cs.primary, 46)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("فوترة وتحصيل الشهر", fontWeight = FontWeight.Bold)
                            Text(if (ampPrice > 0) "سعر الأمبير: ${fmt(ampPrice)} دينار" else "حدد سعر الأمبير أولاً من تبويب التسعير",
                                fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronLeft, null, tint = cs.onSurfaceVariant)
                    }
                }
            }
            if (alertText.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { reminders = true },
                    shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = cs.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = Bad)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(alertText, fontSize = 12.sp, color = cs.onErrorContainer)
                            Text("اضغط لإرسال التذكيرات عبر واتساب", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.onErrorContainer)
                        }
                        Icon(Icons.Rounded.ChevronLeft, null, tint = cs.onErrorContainer)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(q, { q = it }, placeholder = { Text("ابحث بالاسم أو الهاتف") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "مسح") } },
                        shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = cs.surface, unfocusedContainerColor = cs.surface))
                    FilledTonalIconButton(onClick = { safe(ctx) { mapPts = db.mapPoints() } }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Map, "الخريطة")
                    }
                    FilledTonalIconButton(onClick = { bulk = true }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Sms, "رسالة جماعية")
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statusMap(gen).keys.forEach { k ->
                        FilterChip(selected = status == k, onClick = { status = k }, label = { Text(k) })
                    }
                    Box {
                        FilterChip(selected = area.isNotEmpty(), onClick = { areaMenu = true },
                            label = { Text(if (area.isEmpty()) "كل المناطق" else area) },
                            leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp)) })
                        DropdownMenu(expanded = areaMenu, onDismissRequest = { areaMenu = false }) {
                            (listOf("") + areas).forEach { a ->
                                DropdownMenuItem(text = { Text(a.ifEmpty { "كل المناطق" }) }, onClick = { area = a; areaMenu = false })
                            }
                        }
                    }
                }
            }
            item {
                val filterDescription = when (status) {
                    "الكل" -> if (gen) "يعرض جميع سجلات النشاط، بما فيها المسدّدة والتي عليها دين أو المقطوعة." else "يعرض جميع المشتركين دون استبعاد حسب حالة الاشتراك أو الاتصال."
                    "فعّال" -> "يعرض الاشتراكات الفعّالة حالياً، مع استبعاد المنتهي والمجمّد."
                    "متصل" -> "يعرض المشتركين الفعّالين الذين لديهم جلسة اتصال نشطة ومؤكدة من SAS."
                    "غير متصل" -> "يعرض المشتركين الفعّالين الذين لا تظهر لهم جلسة اتصال نشطة في آخر قراءة من SAS."
                    "ينتهي قريباً" -> "يعرض الاشتراكات الفعّالة التي ينتهي تاريخها خلال اليوم وحتى 3 أيام، مرتبة بالأقرب انتهاءً."
                    "منتهي / غير فعّال" -> "يعرض الاشتراكات المنتهية أو الحسابات المعطّلة في SAS."
                    "عليه دين" -> "يعرض المشتركين الذين لديهم رصيد مستحق غير مسدّد."
                    "مجمّد" -> "يعرض الاشتراكات التي تم تجميدها ولم يُلغَ تجميدها بعد."
                    "مسدّد" -> "يعرض سجلات النشاط التي لا يوجد عليها مبلغ متبقٍ." 
                    "مقطوع" -> "يعرض سجلات النشاط التي تم قطعها." 
                    else -> "يعرض السجلات المطابقة للفلتر المحدد."
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer.copy(alpha = 0.48f))) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ماذا يعرض هذا الفلتر؟", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer)
                        Text(filterDescription, fontSize = 12.sp, color = cs.onSecondaryContainer)
                        Text("النتائج: ${subs.size}", fontSize = 11.sp, color = cs.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ترتيب حسب", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    Box {
                        FilterChip(
                            selected = sortKey != "name",
                            onClick = { sortMenu = true },
                            label = { Text(sortLabels.firstOrNull { it.first == sortKey }?.second ?: "الاسم (أ-ي)") },
                            leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp)) }
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            sortLabels.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { sortKey = key; sortMenu = false }
                                )
                            }
                        }
                    }
                    if (sortKey != "name" || status != "الكل" || area.isNotEmpty()) {
                        TextButton(onClick = { sortKey = "name"; status = "الكل"; area = ""; q = "" }) { Text("مسح الفلاتر") }
                    }
                }
            }
            if (subs.isEmpty()) item { EmptyState(Icons.Rounded.PersonSearch, if (all.isEmpty()) "لا يوجد مشتركون بعد.\nاضغط \"مشترك جديد\" للبدء" else "لا توجد نتائج") }
            items(subs, key = { it.id }) { s ->
                val st = db.statusOf(s)
                val col = statusColor(st)
                AppCard(Modifier.fillMaxWidth(), onClick = { sel = s; menu = true }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(s.name, if (s.bal > 0) Bad else col)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Always show subscription-state light beside the subscriber name.
                                Surface(shape = CircleShape, color = col, modifier = Modifier.size(9.dp)) {}
                                Spacer(Modifier.width(6.dp))
                                Text(s.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                                if (!gen && db.statusOf(s) == "فعّال" && Router.cfg(ctx) != null && !s.e1.isNullOrBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    val onlineNow = s.e1.trim() in Router.online.value
                                    Surface(shape = CircleShape, color = if (onlineNow) Ok else Bad, modifier = Modifier.size(10.dp)) {}
                                    Text(if (onlineNow) "متصل" else "غير متصل", fontSize = 10.sp,
                                        color = if (onlineNow) Ok else Bad, maxLines = 1)
                                }
                            }
                            Text(listOfNotNull(s.phone?.let { "\u200e$it" }, s.area?.takeIf { it.isNotEmpty() }).joinToString("  •  "),
                                fontSize = 12.sp, color = cs.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Pill(st, col)
                                if (db.promiseActive(s)) Pill("وعد ${s.promiseDate?.drop(5)}", Warn)
                                else if (db.promiseBroken(s)) Pill("وعد متأخر", Bad)
                                if (s.noMsg) Pill("بلا رسائل", Muted)
                                Text(if (gen) "${g(db.ampsOf(s))} أمبير  •  ${fmt(db.priceOf(s, ampPrice))} / شهر"
                                    else listOfNotNull(s.pkg, s.endDate?.let { "حتى $it" }).joinToString("  •  "),
                                    fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                        }
                        if (s.bal > 0) Column(horizontalAlignment = Alignment.End) {
                            Text(fmt(s.bal), color = Bad, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("دين", color = Bad, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                if (!gen && pkgs.isEmpty()) toast(ctx, "أضف باقة واحدة على الأقل من تبويب \"الباقات\"")
                sel = null; dialog = "new"
            },
            icon = { Icon(Icons.Rounded.PersonAdd, null) }, text = { Text("مشترك جديد") },
            containerColor = cs.primary, contentColor = cs.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    val cur = sel

    // قائمة إجراءات المشترك
    if (menu && cur != null) {
        val allActs = buildList {
            add(Act("تعديل البيانات", "edit", Icons.Rounded.Edit, cs.secondary))
            if (!gen) add(Act("تجديد الاشتراك", "renew", Icons.Rounded.Refresh, cs.primary))
            if (cur.bal > 0) add(Act("تسجيل دفعة", "pay", Icons.Rounded.Payments, Ok))
            add(Act("إضافة دين", "debtadd", Icons.Rounded.AddCard, Bad))
            if (cur.bal > 0) add(Act(if (cur.promiseDate.isNullOrEmpty()) "وعد بالدفع" else "تعديل وعد الدفع", "promise", Icons.Rounded.EventAvailable, Warn))
            if (!cur.promiseDate.isNullOrEmpty()) add(Act("إلغاء وعد الدفع", "promise_clear", Icons.Rounded.EventBusy, Bad))
            if (gen) add(Act(if (cur.cut) "إعادة الوصل" else "قطع الخدمة", "cut", if (cur.cut) Icons.Rounded.Power else Icons.Rounded.PowerOff, CutColor))
            if (!gen) {
                if (cur.frozenAt != null) add(Act("إلغاء التجميد", "unfreeze", Icons.Rounded.PlayCircle, FrozenColor))
                else add(Act("تجميد الاشتراك", "freeze", Icons.Rounded.PauseCircle, FrozenColor))
                add(Act("منح أيام مجانية", "gift", Icons.Rounded.CardGiftcard, Ok))
            }
            if (cur.bal > 0) add(Act("خصم", "disc", Icons.Rounded.LocalOffer, Warn))
            add(Act("كشف الحساب", "stmt", Icons.Rounded.ReceiptLong, cs.secondary))
            if (Session.isAdmin) add(Act("حذف المشترك", "delete_subscriber", Icons.Rounded.DeleteForever, Bad))
            if (gen) add(Act("قراءة عداد", "read", Icons.Rounded.Speed, Warn))
            if (!gen) add(Act("إشعار الاشتراك عبر واتساب", "wa_exp", Icons.Rounded.NotificationsActive, Warn))
            add(Act("تذكير بالدين عبر واتساب", "wa", Icons.Rounded.Chat, WhatsApp))
            if (cur.lat != null) add(Act("فتح الموقع", "loc", Icons.Rounded.LocationOn, Bad))
            if (Session.isAdmin) add(Act(if (cur.noMsg) "السماح بالرسائل" else "إيقاف الرسائل", "nomsg", if (cur.noMsg) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff, Muted))
            if (!gen && !cur.e1.isNullOrBlank() && Router.cfg(ctx) != null) add(Act("إنشاء الحساب على الراوتر", "rcreate", Icons.Rounded.Router, Ok))
            if (!gen) add(Act("فتح راوتر / نانو المشترك", "open_cpe", Icons.Rounded.OpenInBrowser, cs.secondary))
            if (!gen && !cur.e1.isNullOrBlank() && Router.cfg(ctx) != null) {
                add(Act("إشارة الصحن (مباشر)", "signal", Icons.Rounded.SettingsInputAntenna, cs.secondary))
                add(Act("إيقاف الحساب على الراوتر الآن", "rstop", Icons.Rounded.Block, Bad))
                add(Act("تشغيل الحساب على الراوتر الآن", "rstart", Icons.Rounded.PlayArrow, Ok))
                add(Act("فصل الجلسة (إعادة اتصال)", "rkick", Icons.Rounded.LinkOff, Warn))
            }
        }
        val acts = if (Session.isAdmin) allActs else allActs.filter { it.key in STAFF_ACTIONS }
        ModalBottomSheet(onDismissRequest = { menu = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(cur.name, statusColor(db.statusOf(cur)), 48)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(cur.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${db.statusOf(cur)}  •  الدين: ${fmt(cur.bal)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val run: (Act) -> Unit = { a ->
                    menu = false
                    when (a.key) {
                        "delete_subscriber" -> dialog = "delete_subscriber"
                        "wa" -> sendMsg = SendMsg("تذكير بالدين", db.reminderText(cur, "debt"), cur.phone, cur.name)
                        "wa_exp" -> if (cur.endDate.isNullOrEmpty()) toast(ctx, "لا يوجد تاريخ انتهاء لهذا المشترك")
                            else sendMsg = if (db.statusOf(cur) == "منتهي") SendMsg("وصل انتهاء الاشتراك", db.reminderText(cur, "expired"), cur.phone, cur.name)
                            else SendMsg("تنبيه قرب انتهاء الاشتراك", db.reminderText(cur, "soon"), cur.phone, cur.name)
                        "loc" -> openUrl(ctx, "geo:${cur.lat},${cur.lng}?q=${cur.lat},${cur.lng}(${android.net.Uri.encode(cur.name)})")
                        "open_cpe" -> {
                            val candidates = listOf(cur.e2, cur.e3, cur.address).map { it.orEmpty().trim() }
                            val ip = candidates.firstOrNull { it.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) }
                            if (ip == null) toast(ctx, "أدخل عنوان IP لجهاز المشترك في أحد الحقول الإضافية أو العنوان")
                            else openUrl(ctx, "http://$ip")
                        }
                        "cut" -> safe(ctx) { db.setCut(cur.id, !cur.cut); refresh() }
                        "freeze" -> safe(ctx) { db.freeze(cur.id); refresh() }
                        "promise_clear" -> safe(ctx) { db.clearPromise(cur.id); refresh(); toast(ctx, "أُلغي وعد الدفع") }
                        "nomsg" -> safe(ctx) { db.setNoMsg(cur.id, !cur.noMsg); refresh(); toast(ctx, if (cur.noMsg) "أُعيد السماح بالرسائل" else "أُوقفت الرسائل الآلية لهذا المشترك") }
                        "rstart", "rkick" -> {
                            val rc = Router.cfg(ctx)
                            val ru = (cur.e1 ?: "").trim()
                            if (rc != null && ru.isNotEmpty()) Thread {
                                val m = try { if (a.key == "rstart") Router.setUserEnabled(rc, ru, true) else Router.disconnectUser(rc, ru) } catch (e: Exception) { e.message ?: "خطأ" }
                                android.os.Handler(android.os.Looper.getMainLooper()).post { toast(ctx, m) }
                            }.start()
                        }
                        "unfreeze" -> safe(ctx) { val n = db.unfreeze(cur.id); refresh(); toast(ctx, "أُلغي التجميد وأُضيف $n يوم إلى الاشتراك") }
                        else -> dialog = a.key
                    }
                }
                val groups = listOf(
                    "الأساسية" to setOf("renew", "pay", "stmt", "edit", "delete_subscriber"),
                    "الحساب والديون" to setOf("debtadd", "disc", "promise", "promise_clear", "cut", "freeze", "unfreeze", "gift", "read"),
                    "تواصل وموقع" to setOf("wa", "wa_exp", "loc", "nomsg"),
                    "الراوتر والشبكة" to setOf("rcreate", "signal", "rstop", "rstart", "rkick", "open_cpe"),
                )
                val shortLabels = mapOf(
                    "edit" to "تعديل", "renew" to "تجديد", "pay" to "تسجيل دفعة", "stmt" to "كشف الحساب", "delete_subscriber" to "حذف مشترك",
                    "debtadd" to "إضافة دين", "disc" to "خصم", "promise_clear" to "إلغاء الوعد",
                    "freeze" to "تجميد", "unfreeze" to "إلغاء التجميد", "gift" to "أيام مجانية", "read" to "قراءة عداد",
                    "wa" to "تذكير بالدين", "wa_exp" to "إشعار الاشتراك", "loc" to "فتح الموقع",
                    "rcreate" to "إنشاء حساب", "signal" to "إشارة الصحن", "rstop" to "إيقاف", "rstart" to "تشغيل", "rkick" to "فصل الجلسة", "open_cpe" to "فتح جهازه",
                )
                groups.forEach { (title, keys) ->
                    val items = acts.filter { it.key in keys }
                    if (items.isNotEmpty()) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = cs.primary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp))
                        items.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { a ->
                                    ActionTile(shortLabels[a.key] ?: a.label, a.icon, a.color, Modifier.weight(1f)) { run(a) }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    // نموذج مشترك جديد / تعديل
    if ((dialog == "new" || dialog == "edit") && (dialog == "new" || cur != null)) {
        val editing = if (dialog == "edit") cur else null
        val e = db.extraLabels()
        val opt = " (اختياري)"
        val centerPoint = subs.firstNotNullOfOrNull { x -> x.lat?.let { la -> x.lng?.let { ln -> Pair(la, ln) } } }
        val baseFields = if (gen) listOf(
            F("name", "الاسم *", contactBoth = true), F("phone", "الهاتف * (مثال: 07814521213)", phone = true),
            F("area", "المنطقة (اختر أو اكتب)", options = areas, free = true), F("address", "العنوان$opt"),
            F("extra2", "عدد الأمبيرات *", number = true),
            F("extra1", e[0] + opt), F("extra3", e[2] + opt),
            F("location", "الموقع (اختر من الخريطة أو اكتب lat,lng)$opt", location = true, center = centerPoint),
            F("notes", "ملاحظات$opt"),
        ) else listOf(
            F("name", "الاسم *", contactBoth = true), F("phone", "الهاتف * (مثال: 07814521213)", phone = true),
            F("area", "المنطقة (اختر أو اكتب)", options = areas, free = true), F("address", "العنوان$opt"),
            F("package", "الباقة (اختر)", options = labelToId.keys.toList()),
            F("end_date", "تاريخ انتهاء الاشتراك$opt", date = true),
            F("extra1", e[0] + opt, routerPick = true), F("extra2", e[1] + opt), F("extra3", e[2] + opt),
            F("location", "الموقع (اختر من الخريطة أو اكتب lat,lng)$opt", location = true, center = centerPoint),
            F("notes", "ملاحظات$opt"),
        )
        val fields = baseFields + (if (editing == null) listOf(F("old_debt", "دين سابق (اختياري)", number = true)) else emptyList())
        val initial = if (editing == null) emptyMap() else mapOf(
            "name" to editing.name, "phone" to (editing.phone ?: ""), "area" to (editing.area ?: ""),
            "address" to (editing.address ?: ""), "package" to (editing.packageId?.let { idToLabel[it] } ?: ""),
            "end_date" to (editing.endDate ?: ""), "extra1" to (editing.e1 ?: ""), "extra2" to (editing.e2 ?: ""),
            "extra3" to (editing.e3 ?: ""), "notes" to (editing.notes ?: ""),
            "location" to (if (editing.lat != null) "${editing.lat},${editing.lng}" else ""))
        FormDialog(if (editing == null) "مشترك جديد" else "تعديل مشترك", fields, initial, { dialog = "" }) { d ->
            if ((d["name"] ?: "").isEmpty()) throw IllegalArgumentException("الاسم مطلوب")
            val phone = db.formatPhone(d["phone"])
            val amps = if (gen) (db.ampsFromText(d["extra2"] ?: "").takeIf { it > 0 } ?: throw IllegalArgumentException("أدخل عدد الأمبيرات")) else 0.0
            if (editing == null) { val (ok, msg) = db.canAddSubscriber(); if (!ok) throw IllegalArgumentException(msg) }
            val (lat, lng) = db.parseLocation(d["location"])
            val newId = db.saveSub(mapOf(
                "name" to d["name"], "phone" to phone, "area" to d["area"], "address" to d["address"],
                "package_id" to (if (gen) null else d["package"]?.let { labelToId[it] }), "end_date" to (if (gen) null else d["end_date"]),
                "extra1" to d["extra1"], "extra2" to (if (gen) g(amps) else d["extra2"]), "extra3" to d["extra3"], "notes" to d["notes"],
                "lat" to lat, "lng" to lng), editing?.id)
            val oldDebt = numOf(d["old_debt"] ?: "")?.toLong() ?: 0L
            if (editing == null && oldDebt > 0) db.addTransaction(newId, "charge", oldDebt, "دين سابق")
            refresh()
        }
    }

    if (dialog == "delete_subscriber" && cur != null) {
        ConfirmDialog("حذف المشترك ${cur.name} نهائياً؟ سيُحذف سجل الدفعات والديون وقراءات العداد المرتبطة به، ولا يمكن التراجع عن ذلك.", "حذف نهائي", { dialog = "" }) {
            safe(ctx) { db.deleteSubscriber(cur.id); sel = null; refresh(); dialog = ""; toast(ctx, "تم حذف المشترك وسجلاته التابعة") }
        }
    }

    if (dialog == "signal" && cur != null) SignalDialog(db, cur.e1 ?: "") { dialog = "" }

    if (dialog == "rstop" && cur != null) {
        ConfirmDialog("سيُوقَف حساب ${cur.e1} على الراوتر فوراً وتُقطع جلسته.\nإن كان اشتراكه ما زال فعّالاً فقد تعيد المزامنة التلقائية تشغيله؛ للإيقاف المستمر استخدم \"تجميد الاشتراك\". متابعة؟", "إيقاف", { dialog = "" }) {
            val rc = Router.cfg(ctx)
            val ru = (cur.e1 ?: "").trim()
            if (rc != null && ru.isNotEmpty()) Thread {
                val m = try { Router.setUserEnabled(rc, ru, false) } catch (e: Exception) { e.message ?: "خطأ" }
                android.os.Handler(android.os.Looper.getMainLooper()).post { toast(ctx, m) }
            }.start()
        }
    }

    if (dialog == "rcreate" && cur != null) {
        AskDialog("إنشاء حساب على الراوتر", "كلمة مرور الحساب ${cur.e1}", (100000..999999).random().toString(), false, { dialog = "" }) { pw ->
            dialog = ""
            val c = Router.cfg(ctx)
            if (c != null) {
                val prof = cur.packageId?.let { db.getPkg(it) }?.profile
                val user = (cur.e1 ?: "").trim()
                Thread {
                    val m = try { Router.createUser(c, user, pw, prof) } catch (e: Exception) { e.message ?: "خطأ" }
                    android.os.Handler(android.os.Looper.getMainLooper()).post { toast(ctx, m) }
                }.start()
            }
        }
    }

    // تجديد مع اختيار الباقة وتعبئة السعر والمدة تلقائياً
    if (dialog == "renew" && cur != null) {
        val availablePackages = remember(rev, cur.id) { db.packages() }
        var selectedPackageId by remember(cur.id) { mutableLongStateOf(cur.packageId ?: availablePackages.firstOrNull()?.id ?: 0L) }
        val selectedPackage = availablePackages.firstOrNull { it.id == selectedPackageId }
        var paidText by remember(cur.id) { mutableStateOf(selectedPackage?.price?.toString() ?: "0") }
        var packageMenu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("تجديد الاشتراك") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("اختر الباقة المراد تجديدها")
                    Box {
                        OutlinedButton(onClick = { packageMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = availablePackages.isNotEmpty()) {
                            Text(selectedPackage?.let { "${it.name} — ${fmt(it.price)} دينار / ${it.days} يوم" } ?: "لا توجد باقات")
                        }
                        DropdownMenu(expanded = packageMenu, onDismissRequest = { packageMenu = false }) {
                            availablePackages.forEach { item ->
                                DropdownMenuItem(text = { Text("${item.name} — ${fmt(item.price)} دينار / ${item.days} يوم") }, onClick = {
                                    selectedPackageId = item.id; paidText = item.price.toString(); packageMenu = false
                                })
                            }
                        }
                    }
                    if (selectedPackage != null) {
                        Text("سعر الباقة: ${fmt(selectedPackage.price)} دينار | المدة: ${selectedPackage.days} يوم", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = paidText, onValueChange = { paidText = normDigits(it).filter { ch -> ch.isDigit() } }, label = { Text("المبلغ المدفوع الآن (0 = دين)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        val paid = paidText.toLongOrNull() ?: 0L
                        val debt = (selectedPackage.price - paid).coerceAtLeast(0)
                        Text("المتبقي كدين: ${fmt(debt)} دينار", color = if (debt > 0) Bad else Ok, fontWeight = FontWeight.SemiBold)
                        if (paid > selectedPackage.price) Text("المبلغ المدفوع أكبر من سعر الباقة", color = Bad)
                    }
                }
            },
            confirmButton = { TextButton(enabled = selectedPackage != null && paidText.toLongOrNull() != null && paidText.toLong() <= selectedPackage.price, onClick = {
                val (newEnd, _) = db.renew(cur.id, selectedPackageId, paidText.toLong())
                dialog = ""; refresh()
                val autoSent = AutoSms.active(db) && db.setting("auto_receipt", "1") == "1" && AutoSms.hasPerm(ctx) && !cur.phone.isNullOrBlank() && !cur.noMsg
                AutoSms.receipt(ctx, db, cur.id)
                sendMsg = SendMsg("تم التجديد حتى $newEnd — إرسال الوصل للمشترك", db.renewSlip(cur.id), cur.phone, cur.name,
                    if (autoSent) "أُرسل الوصل للمشترك تلقائياً عبر SMS. لا تضغط إرسال إلا لإعادة الإرسال، ويمكنك الطباعة أو المشاركة." else "")
            }) { Text("تأكيد التجديد") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("إلغاء") } }
        )
    }

    // منح أيام مجانية
    if (dialog == "gift" && cur != null) {
        AskDialog("منح أيام مجانية", "عدد الأيام", "", true, { dialog = "" }) { v ->
            db.giveDays(cur.id, numOf(v)?.toInt() ?: 0); refresh()
        }
    }

    // إضافة دين يدوي
    if (dialog == "debtadd" && cur != null) {
        FormDialog("إضافة دين على ${cur.name}", listOf(F("amount", "المبلغ (دينار)", number = true), F("note", "السبب (اختياري)")),
            onDismiss = { dialog = "" }) { d ->
            db.addTransaction(cur.id, "charge", numOf(d["amount"] ?: "")?.toLong() ?: throw IllegalArgumentException("أدخل مبلغاً صحيحاً"),
                (d["note"] ?: "").ifEmpty { "دين مضاف" })
            refresh()
        }
    }

    // دفعة / خصم
    if ((dialog == "pay" || dialog == "disc") && cur != null) {
        val isPay = dialog == "pay"
        FormDialog(if (isPay) "تسجيل دفعة - الدين ${fmt(cur.bal)}" else "خصم - الدين ${fmt(cur.bal)}",
            listOf(F("amount", "المبلغ (حتى ${fmt(cur.bal)} دينار)", number = true), F("note", "ملاحظة (اختياري)")),
            mapOf("amount" to cur.bal.coerceAtLeast(0).toString()), { dialog = "" }) { d ->
            val tx = db.addTransaction(cur.id, if (isPay) "payment" else "discount",
                d["amount"]?.toLongOrNull() ?: throw IllegalArgumentException("أدخل مبلغاً صحيحاً"), d["note"] ?: "")
            refresh()
            if (isPay) sendMsg = SendMsg("إيصال الدفعة", db.receiptText(tx), cur.phone, cur.name)
        }
    }

    // وعد بالدفع
    if (dialog == "promise" && cur != null) {
        FormDialog("وعد بالدفع - ${cur.name}", listOf(F("date", "تاريخ الوعد", date = true), F("note", "ملاحظة (اختياري)")),
            mapOf("date" to (cur.promiseDate?.takeIf { it >= today() } ?: java.time.LocalDate.now().plusDays(3).toString()), "note" to (cur.promiseNote ?: "")),
            { dialog = "" }) { d ->
            db.setPromise(cur.id, d["date"] ?: "", d["note"] ?: "")
            refresh(); toast(ctx, "سُجّل الوعد، ولن يُقطع المشترك قبل هذا التاريخ")
        }
    }

    // قراءة عداد
    if (dialog == "read" && cur != null) {
        val prev = db.lastReading(cur.id)
        FormDialog("قراءة عداد", listOf(F("reading", "القراءة الجديدة (السابقة: ${g(prev)})", number = true), F("rate", "سعر الوحدة (دينار)", number = true)),
            mapOf("rate" to db.setting("unit_rate", "")), { dialog = "" }) { d ->
            val reading = d["reading"]?.toDoubleOrNull() ?: throw IllegalArgumentException("أدخل القراءة")
            val rate = d["rate"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("أدخل سعر الوحدة")
            db.setSetting("unit_rate", rate.toString())
            val (units, amount, _) = db.addReading(cur.id, reading, rate)
            refresh()
            info = "الاستهلاك: ${g(units)} وحدة\nالمبلغ المضاف: ${fmt(amount)} دينار"
        }
    }

    if (dialog == "stmt" && cur != null) StatementDialog(db, cur, refresh) { dialog = "" }
    if (dialog == "month" && gen) MonthDialog(db, refresh) { dialog = "" }
    sendMsg?.let { m -> SendDialog(m, db) { sendMsg = null } }
    if (reminders) RemindersDialog(db, gen) { reminders = false; refresh() }
    if (bulk) BulkSendDialog(db, all, refresh) { bulk = false }
    if (route) CollectionRouteDialog(all) { route = false }

    info?.let { msg ->
        val tx = receiptTx
        if (tx != null) ConfirmDialog(msg, "عرض", { info = null; receiptTx = null }) { shareText(ctx, db.receiptText(tx)) }
        else InfoDialog(msg) { info = null }
    }

    mapPts?.let { pts ->
        Dialog(onDismissRequest = { mapPts = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { mapPts = null }) { Icon(Icons.Rounded.Close, "إغلاق") }
                        Text("خريطة المشتركين", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { route = true }) { Text("جولة التحصيل") }
                    }
                    OsmSubsMap(pts, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun StatementDialog(db: Db, sub: Sub, refresh: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var r by remember { mutableIntStateOf(0) }
    var cancelId by remember { mutableStateOf<Long?>(null) }
    var slip by remember { mutableStateOf<SendMsg?>(null) }
    val statementPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { PdfReport.writeStatement(db, sub, it) }
            toast(ctx, "تم حفظ كشف حساب المشترك PDF")
        }.onFailure { toast(ctx, "تعذر حفظ PDF: ${it.message ?: "خطأ"}") }
    }
    val txs = remember(r) { db.statement(sub.id) }
    val debt = remember(r) { db.balance(sub.id) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("كشف حساب: ${sub.name}", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { statementPdf.launch("كشف_حساب_${sub.id}_${today()}.pdf") }) {
                        Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("PDF")
                    }
                }
                Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(Brush.horizontalGradient(listOf(if (debt > 0) Bad else Ok, if (debt > 0) Warn else cs.primary))).padding(18.dp)) {
                    Column {
                        Text(if (debt > 0) "الدين الحالي" else "لا يوجد دين", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                        Text("${fmt(debt)} دينار", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (txs.isEmpty()) item { EmptyState(Icons.Rounded.ReceiptLong, "لا توجد حركات") }
                    items(txs, key = { it.id }) { t ->
                        val (ic, col) = when (t.type) {
                            "payment" -> Pair(Icons.Rounded.ArrowDownward, Ok)
                            "charge" -> Pair(Icons.Rounded.ArrowUpward, Bad)
                            else -> Pair(Icons.Rounded.LocalOffer, Warn)
                        }
                        AppCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBubble(ic, if (t.cancelled) Muted else col)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(TYPE_AR[t.type] ?: "", fontWeight = FontWeight.SemiBold,
                                            textDecoration = if (t.cancelled) TextDecoration.LineThrough else null)
                                        Text("#${t.id}  •  ${t.date}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                                    }
                                    Text(fmt(t.amount), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (t.cancelled) Muted else col,
                                        textDecoration = if (t.cancelled) TextDecoration.LineThrough else null)
                                }
                                if (!t.note.isNullOrEmpty()) Text(t.note ?: "", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                                if (!t.collector.isNullOrEmpty()) Text("استلمها: ${t.collector}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                                if (t.cancelled) Text("ملغاة: ${t.reason ?: ""}", color = Bad, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                else Row(Modifier.padding(top = 4.dp)) {
                                    TextButton(onClick = { slip = SendMsg("إيصال #${t.id}", db.receiptText(t.id), sub.phone, sub.name) }) {
                                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("إيصال")
                                    }
                                    if (Session.isAdmin) TextButton(onClick = { cancelId = t.id }) {
                                        Icon(Icons.Rounded.Cancel, null, Modifier.size(18.dp), tint = Bad); Spacer(Modifier.width(4.dp)); Text("إلغاء", color = Bad)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    slip?.let { m -> SendDialog(m, db) { slip = null } }
    cancelId?.let { id ->
        AskDialog("إلغاء حركة", "سبب الإلغاء (مطلوب)", onDismiss = { cancelId = null }) { reason ->
            db.cancelTransaction(id, reason); r++; refresh()
        }
    }
}

// ======================= الباقات =======================
@Composable
fun PkgsTab(db: Db, rev: Int, bump: () -> Unit) {
    var local by remember { mutableIntStateOf(0) }
    val pkgs = remember(rev, local) { db.packages() }
    var editing by remember { mutableStateOf<Pkg?>(null) }
    var adding by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pkgs.isEmpty()) item { EmptyState(Icons.Rounded.Inventory2, "لا توجد باقات بعد.\nأضف أول باقة للبدء") }
            items(pkgs, key = { it.id }) { p ->
                AppCard(Modifier.fillMaxWidth(), onClick = { editing = p }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.Inventory2, cs.primary, 46)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Pill("${p.days} يوم", cs.secondary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmt(p.price), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = cs.primary)
                            Text("دينار", fontSize = 11.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("باقة جديدة") },
            containerColor = cs.primary, contentColor = cs.onPrimary, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (adding || editing != null) {
        val p = editing
        FormDialog(if (p == null) "باقة جديدة" else "تعديل باقة", listOf(F("name", "اسم الباقة"), F("price", "السعر (دينار)", number = true),
            F("days", "المدة بالأيام (اختر أو اكتب)", options = listOf("7", "15", "30", "60", "90", "180", "365"), free = true, number = true),
            F("profile", "بروفايل الراوتر لهذه الباقة (اختياري، مثل 10M)")),
            if (p == null) mapOf("days" to "30") else mapOf("name" to p.name, "price" to p.price.toString(), "days" to p.days.toString(), "profile" to (p.profile ?: "")),
            { adding = false; editing = null }) { d ->
            db.savePackage(d["name"] ?: "", d["price"]?.toLongOrNull() ?: throw IllegalArgumentException("أدخل السعر"),
                d["days"]?.toIntOrNull() ?: 30, p?.id, d["profile"] ?: "")
            local++; bump()
        }
    }
}

// ======================= المصاريف =======================
@Composable
fun ExpensesTab(db: Db, rev: Int, bump: () -> Unit) {
    var local by remember { mutableIntStateOf(0) }
    val list = remember(rev, local) { db.listExpenses() }
    val gen = db.isGenerator()
    var dialog by remember { mutableStateOf("") }
    var del by remember { mutableStateOf<Expense?>(null) }
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) item { EmptyState(Icons.Rounded.AccountBalanceWallet, "لا توجد مصاريف مسجلة") }
            items(list, key = { it.id }) { e ->
                val fuel = e.category == "وقود"
                AppCard(Modifier.fillMaxWidth(), onClick = { del = e }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(if (fuel) Icons.Rounded.LocalGasStation else Icons.Rounded.Receipt, if (fuel) Warn else cs.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.category ?: "", fontWeight = FontWeight.Bold)
                            Text(listOf(e.date, e.note ?: "").filter { it.isNotEmpty() }.joinToString("  •  "), fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        Text("-${fmt(e.amount)}", color = Bad, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (gen) SmallFloatingActionButton(onClick = { dialog = "fuel" }, containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                Icon(Icons.Rounded.LocalGasStation, "وقود")
            }
            ExtendedFloatingActionButton(onClick = { dialog = "exp" }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("مصروف جديد") },
                containerColor = cs.primary, contentColor = cs.onPrimary)
        }
    }
    if (dialog == "exp") {
        val cats = listOf("رواتب", "صيانة", "إيجار", "كهرباء", "شراء معدات", "أخرى") +
            (if (gen) listOf("زيت وفلاتر", "قطع غيار") else listOf("شراء سعة إنترنت", "أجور فنيين"))
        FormDialog("مصروف جديد", listOf(F("category", "النوع (اختر أو اكتب)", options = cats, free = true),
            F("amount", "المبلغ (دينار)", number = true), F("note", "ملاحظة")), onDismiss = { dialog = "" }) { d ->
            db.addExpense(d["category"] ?: "", d["amount"]?.toLongOrNull() ?: throw IllegalArgumentException("أدخل المبلغ"), d["note"] ?: "")
            local++; bump()
        }
    }
    if (dialog == "fuel") {
        FormDialog("تعبئة وقود", listOf(F("liters", "عدد اللترات", number = true), F("cost", "الكلفة (دينار)", number = true),
            F("hours", "ساعات التشغيل (اختياري)", number = true), F("note", "ملاحظة")), onDismiss = { dialog = "" }) { d ->
            db.addFuel(d["liters"]?.toDoubleOrNull() ?: 0.0, d["cost"]?.toLongOrNull() ?: throw IllegalArgumentException("أدخل الكلفة"),
                d["hours"]?.toDoubleOrNull() ?: 0.0, d["note"] ?: "")
            local++; bump()
        }
    }
    del?.let { e -> ConfirmDialog("حذف هذا المصروف؟", "حذف", { del = null }) { db.deleteExpense(e.id); local++; bump() } }
}

// ======================= التقارير =======================
private data class RepData(val daily: List<Pair<String, Long>>, val debts: Map<String, Long>, val profit: Profit)

@Composable
fun ReportsTab(db: Db, rev: Int) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var from by remember { mutableStateOf(LocalDate.now().minusDays(30).toString()) }
    var to by remember { mutableStateOf(today()) }
    val calc: () -> RepData? = { safe(ctx) { RepData(db.revenueByDay(from, to), db.debtsByArea(), db.profit(from, to)) } }
    var rep by remember(rev) { mutableStateOf(calc()) }
    var cash by remember { mutableStateOf(false) }
    var cutTick by remember { mutableIntStateOf(0) }
    val gen = db.isGenerator()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) safe(ctx) {
            ctx.contentResolver.openOutputStream(uri)?.use { db.exportCsv(it) }
            toast(ctx, "تم التصدير. افتح الملف بـ Excel.")
        }
    }
    val pdfOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) safe(ctx) {
            val r = db.fullReport(from, to)
            ctx.contentResolver.openOutputStream(uri)?.use { PdfReport.write(db, it, r) }
            toast(ctx, "تم حفظ تقرير PDF")
        }
    }
    val xlsxOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) safe(ctx) {
            val r = db.fullReport(from, to)
            ctx.contentResolver.openOutputStream(uri)?.use { Xlsx.write(it, reportSheets(db, r)) }
            toast(ctx, "تم حفظ ملف Excel")
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { InsightsCard(db) }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { FieldInput(F("f", "من", date = true), from) { from = it } }
                        Box(Modifier.weight(1f)) { FieldInput(F("t", "إلى", date = true), to) { to = it } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { rep = calc() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Assessment, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("عرض التقرير")
                        }
                        OutlinedButton(onClick = { exporter.launch("subscribers_${today()}.csv") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("تصدير Excel")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { pdfOut.launch("report_${from}_${to}.pdf") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("تقرير PDF")
                        }
                        FilledTonalButton(onClick = { xlsxOut.launch("report_${from}_${to}.xlsx") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.TableChart, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("تقرير Excel")
                        }
                    }
                    FilledTonalButton(onClick = { cash = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Savings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("صندوق اليوم")
                    }
                }
            }
        }
        rep?.let { r ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(Icons.Rounded.Payments, "الإيرادات", fmt(r.profit.revenue), Ok, Modifier.weight(1f))
                    StatCard(Icons.Rounded.AccountBalanceWallet, "المصاريف", fmt(r.profit.expenses), Bad, Modifier.weight(1f))
                    StatCard(Icons.Rounded.Savings, "الصافي", fmt(r.profit.net), if (r.profit.net >= 0) cs.primary else Bad, Modifier.weight(1f))
                }
            }
            if (gen) item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.LocalGasStation, Warn)
                        Spacer(Modifier.width(12.dp))
                        Text("الوقود: ${g(r.profit.liters)} لتر   •   التشغيل: ${g(r.profit.hours)} ساعة")
                    }
                }
            }
            item { SectionTitle("الإيرادات اليومية") }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.daily.isEmpty()) Text("لا توجد إيرادات في هذه الفترة", color = cs.onSurfaceVariant)
                        r.daily.forEach { (d, v) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(d, color = cs.onSurfaceVariant); Text(fmt(v), fontWeight = FontWeight.SemiBold, color = Ok)
                            }
                        }
                        if (r.daily.isNotEmpty()) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("المجموع", fontWeight = FontWeight.Bold); Text("${fmt(r.daily.sumOf { it.second })} دينار", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            item { SectionTitle("الديون حسب المنطقة") }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.debts.isEmpty()) Text("لا توجد ديون", color = cs.onSurfaceVariant)
                        r.debts.forEach { (a, v) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(a, color = cs.onSurfaceVariant); Text(fmt(v), fontWeight = FontWeight.SemiBold, color = Bad)
                            }
                        }
                        if (r.debts.isNotEmpty()) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("الإجمالي", fontWeight = FontWeight.Bold); Text("${fmt(r.debts.values.sum())} دينار", fontWeight = FontWeight.Bold, color = Bad)
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("الإيرادات والمصاريف — آخر 6 أشهر") }
        item { MonthlyChart(db, rev) }
        item { SectionTitle("أعمار الديون") }
        item { AgingCard(db, rev + cutTick) }
        if (gen) {
            item { SectionTitle("مرشحون لقطع الخدمة") }
            item { CutCandidatesCard(db, rev) { cutTick++ } }
        }
    }
    if (cash) CashDialog(db) { cash = false }
}

// ======================= الإعدادات =======================
@Composable
fun SettingsTab(db: Db, rev: Int, bump: () -> Unit, mode: String, onSwitch: (String) -> Unit, onTour: () -> Unit = {}, onAudit: () -> Unit = {}, initialSection: Int = 0, openInitialSection: Boolean = false, onSettingsRoot: () -> Unit = {}) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var local by remember { mutableIntStateOf(0) }
    var sec by rememberSaveable { mutableIntStateOf(initialSection.coerceIn(0, SETTINGS_SECTIONS.lastIndex)) }
    var sectionPageOpen by rememberSaveable { mutableStateOf(openInitialSection) }
    LaunchedEffect(initialSection) { sec = initialSection.coerceIn(0, SETTINGS_SECTIONS.lastIndex) }
    LaunchedEffect(openInitialSection) { sectionPageOpen = openInitialSection }
    val lic = remember(rev, local) { db.licenseInfo() }
    var key by remember { mutableStateOf("") }
    val modeKey = db.setting("mode", "internet")
    var net by remember(modeKey) { mutableStateOf(db.setting("network_name")) }
    var pendingMode by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) safe(ctx) { ctx.contentResolver.openOutputStream(uri)?.use { db.backup(it) }; toast(ctx, "تم حفظ النسخة الاحتياطية") }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreUri = uri }

    Column(Modifier.fillMaxSize()) {
        if (!sectionPageOpen) {
            // صفحة الإعدادات الرئيسية: المظهر وأيقونات الأقسام فقط.
            ThemeBar(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp))
            Text("الإعدادات", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            SettingsTabsRow(sec) { sec = it; sectionPageOpen = true }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { sectionPageOpen = false; onSettingsRoot() }) {
                    Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("الإعدادات")
                }
                Text(SETTINGS_SECTIONS[sec].second, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsIntro(sec)
            when (sec) {
                0 -> {
                    SectionTitle("تبديل النشاط وتحميل المشتركين")
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("لكل نشاط مشتركوه وبياناته المستقلة. اختر النشاط لتحميل مشتركيه.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModeCard(mode == "internet", MODES["internet"]!!.first, Icons.Rounded.Wifi, Modifier.weight(1f)) { if (mode != "internet") pendingMode = "internet" }
                                ModeCard(mode == "generator", MODES["generator"]!!.first, Icons.Rounded.Bolt, Modifier.weight(1f)) { if (mode != "generator") pendingMode = "generator" }
                            }
                        }
                    }

                    SectionTitle("النشاط الحالي: ${MODES[modeKey]?.first ?: ""}")
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(net, { net = it }, label = { Text("اسم هذا النشاط (يظهر في الإيصال)") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                            Button(onClick = { db.setSetting("network_name", net); toast(ctx, "تم الحفظ"); bump() },
                                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("حفظ الاسم") }
                        }
                    }
                    SettingsExtras(db, rev, bump, "general")
                    CurrencySettingsSection(db, rev, bump)
                    SectionTitle("مساعدة")
                    AppCard(Modifier.fillMaxWidth(), onClick = onTour) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(Icons.Rounded.TipsAndUpdates, cs.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("الجولة التعريفية", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("تعرّف على أهم ميزات التطبيق في دقيقة", fontSize = 12.sp, color = cs.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronLeft, null, tint = cs.outline)
                        }
                    }
                }
                1 -> {
                    TemplatesSection(db, modeKey)
                    Spacer(Modifier.height(8.dp))
                    SectionTitle("قنوات الإرسال والتذكيرات")
                    SettingsExtras(db, rev, bump, "auto")
                }
                2 -> SettingsExtras(db, rev, bump, "links")
                3 -> {
                    SectionTitle("الترخيص")
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBubble(Icons.Rounded.Key, if (lic.state == "licensed") Ok else Warn)
                                Spacer(Modifier.width(12.dp))
                                Text(lic.message, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                            Text("رقم الجهاز: ${db.machineId()}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                            OutlinedTextField(key, { key = it }, label = { Text("مفتاح التفعيل") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                            Button(onClick = { val (ok, msg) = db.activate(key); toast(ctx, msg); if (ok) key = ""; local++; bump() },
                                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("تفعيل") }
                        }
                    }

                    SettingsExtras(db, rev, bump, "account")

                    SectionTitle("المتابعة والرقابة")
                    AppCard(Modifier.fillMaxWidth(), onClick = onAudit) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(Icons.Rounded.History, Warn)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("سجل العمليات", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("من أضاف أو عدّل أو ألغى أو خصم، ومتى", fontSize = 12.sp, color = cs.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronLeft, null, tint = cs.outline)
                        }
                    }

                    SectionTitle("النسخ الاحتياطي (للنشاط الحالي)")
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { backupLauncher.launch("backup_${today()}.db") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("نسخ احتياطي")
                            }
                            OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("استعادة / استيراد")
                            }
                        }
                    }
                    SettingsExtras(db, rev, bump, "data")
                }
                4 -> SupportCenterSection()
                5 -> AppInfoSection()
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    pendingMode?.let { k ->
        ConfirmDialog("تحميل مشتركي \"${MODES[k]?.first ?: ""}\"؟", "تحميل", { pendingMode = null }) {
            onSwitch(k); toast(ctx, "تم تحميل مشتركي ${MODES[k]?.first ?: ""}")
        }
    }
    restoreUri?.let { uri ->
        ConfirmDialog("ستُستبدل كل بيانات النشاط الحالي بالنسخة المختارة. متابعة؟", "استعادة", { restoreUri = null }) {
            safe(ctx) {
                ctx.contentResolver.openInputStream(uri)?.use { db.restore(it) }
                toast(ctx, "تمت الاستعادة"); local++; bump()
            }
        }
    }
}

/** قوالب رسائل واتساب (تبويب مستقل). */
@Composable
fun TemplatesSection(db: Db, modeKey: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("المتغيرات المتاحة", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("{name} الاسم، {net} اسم الشبكة، {end} تاريخ الانتهاء، {left} المدة المتبقية، {pkg} الباقة، {debt} الدين، {debt_line} جملة الدين.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
    val debtKinds = listOf("debt" to "تذكير الدين الأول", "debt2" to "تذكير الدين الثاني (أشد)", "debt3" to "تذكير الدين الأخير")
    val kinds = if (db.isGenerator()) debtKinds
        else listOf("soon" to "قرب انتهاء الاشتراك", "expired" to "انتهاء الاشتراك") + debtKinds
    kinds.forEach { (k, label) ->
        var txt by remember(modeKey, k) { mutableStateOf(db.template(k)) }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Rounded.Chat, Color(0xFF25D366), 34)
                    Spacer(Modifier.width(10.dp))
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                OutlinedTextField(txt, { txt = it }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(), minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { db.setTemplate(k, txt); toast(ctx, "تم الحفظ") }, shape = RoundedCornerShape(14.dp)) { Text("حفظ") }
                    TextButton(onClick = { txt = db.defaultTemplate(k); db.setTemplate(k, txt) }) { Text("استعادة الافتراضي") }
                }
            }
        }
    }
}


// ======================= المولدة: سعر الأمبير =======================
@Composable
fun AmpPriceTab(db: Db, rev: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var price by remember(rev) { mutableStateOf(db.ampPrice().takeIf { it > 0 }?.toString() ?: "") }
    val p = numOf(price)?.toLong() ?: 0L
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Rounded.Bolt, Warn, 46)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("سعر الأمبير الواحد", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("اشتراك الشهر لكل مشترك", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                FieldInput(F("p", "السعر بالدينار (يمكن تعديله في أي وقت)", number = true), price) { price = it }
                Button(onClick = { db.setAmpPrice(p); toast(ctx, "تم حفظ سعر الأمبير"); bump() },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("حفظ السعر") }
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("طريقة الحساب", fontWeight = FontWeight.Bold)
                Text("سعر الاشتراك = سعر الأمبير × عدد أمبيرات المشترك", fontSize = 13.sp, color = cs.onSurfaceVariant)
                if (p > 0) listOf(3, 5, 10).forEach { a ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("$a أمبير"); Text("${fmt(p * a)} دينار", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    }
                }
            }
        }
    }
}

// ======================= المولدة: فوترة وتحصيل الشهر =======================
@Composable
fun MonthDialog(db: Db, refresh: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tag by remember { mutableStateOf(java.time.YearMonth.now().toString()) }
    var priceTxt by remember { mutableStateOf(db.ampPrice().takeIf { it > 0 }?.toString() ?: "") }
    var q by remember { mutableStateOf("") }
    var r by remember { mutableIntStateOf(0) }
    val sel = remember { mutableStateMapOf<Long, Boolean>() }
    val ampPrice = numOf(priceTxt)?.toLong() ?: 0L
    val tagOk = runCatching { java.time.YearMonth.parse(tag) }.isSuccess
    val rows = remember(tag, ampPrice, r) { if (tagOk) runCatching { db.monthRows(tag, ampPrice) }.getOrDefault(emptyList()) else emptyList() }
    val shown = rows.filter { it.amps > 0 && (q.isBlank() || it.sub.name.contains(q.trim())) }
    val selectable = shown.filter { it.price > 0 && it.left > 0 }
    val chosen = rows.filter { sel[it.sub.id] == true && it.left > 0 }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("فوترة وتحصيل الشهر", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { FieldInput(F("t", "الشهر (مثال 2026-10)"), tag) { tag = it } }
                        Box(Modifier.weight(1f)) { FieldInput(F("p", "سعر الأمبير", number = true), priceTxt) { priceTxt = it } }
                    }
                    if (!tagOk) Text("صيغة الشهر: السنة-الشهر مثل 2026-10", color = Bad, fontSize = 12.sp)
                    OutlinedTextField(q, { q = it }, placeholder = { Text("بحث بالاسم") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selectable.forEach { sel[it.sub.id] = true } }, shape = RoundedCornerShape(14.dp)) { Text("تحديد الكل") }
                        OutlinedButton(onClick = { sel.clear() }, shape = RoundedCornerShape(14.dp)) { Text("إلغاء التحديد") }
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.isEmpty()) item { EmptyState(Icons.Rounded.PersonSearch, "لا يوجد مشتركون بأمبيرات محددة") }
                    items(shown, key = { it.sub.id }) { m ->
                        val canPick = m.price > 0 && m.left > 0
                        val checked = sel[m.sub.id] == true && canPick
                        AppCard(Modifier.fillMaxWidth(), onClick = if (canPick) ({ sel[m.sub.id] = !checked }) else null) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, enabled = canPick, onCheckedChange = { sel[m.sub.id] = it })
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(m.sub.name, fontWeight = FontWeight.Bold)
                                    Text("${g(m.amps)} أمبير  •  ${fmt(m.due)} دينار", fontSize = 12.sp, color = cs.onSurfaceVariant)
                                }
                                if (m.charged != null && m.left == 0L) Pill("مسدّد", Ok)
                                else if (m.charged != null) Pill("متبقي ${fmt(m.left)}", Bad)
                                else Pill("غير مفوتر", Muted)
                            }
                        }
                    }
                }
                Surface(shadowElevation = 8.dp, color = cs.surface) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("المحدّدون: ${chosen.size}   •   المبلغ المستلم: ${fmt(chosen.sumOf { it.left })} دينار", fontWeight = FontWeight.SemiBold)
                        Text("يُقيَّد اشتراك الشهر على الجميع، ومن لم تحدّده يبقى دَيناً عليه.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        Button(onClick = {
                            safe(ctx) {
                                db.setAmpPrice(ampPrice)
                                val res = db.billMonth(tag, ampPrice, chosen.map { it.sub.id }.toSet())
                                toast(ctx, "فُوتر ${res.billed} مشترك، وسُدّد ${res.paid} (${fmt(res.total)} دينار)")
                                sel.clear(); r++; refresh()
                            }
                        }, enabled = tagOk, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("تأكيد الفوترة والتسديد")
                        }
                    }
                }
            }
        }
    }
}


// ======================= تذكيرات واتساب =======================
@Composable
fun RemindersDialog(db: Db, gen: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val kinds = if (gen) listOf("debt") else listOf("soon", "expired", "debt")
    var kind by remember { mutableStateOf(kinds.first()) }
    var days by remember { mutableStateOf(db.setting("remind_days", "3")) }
    var r by remember { mutableIntStateOf(0) }
    val notify = rememberNotifier(db)
    val chan = Notify.channel(db)
    val d = days.toIntOrNull()?.coerceIn(1, 60) ?: 3
    val al = remember(d, r) { db.alerts(d.toLong()) }
    val list = when (kind) { "soon" -> al.expiring; "expired" -> al.expired; else -> al.debtors }
    val pending = list.filter { !it.phone.isNullOrEmpty() && !it.noMsg && !db.isSent(kind, it.id) }
    fun send(s: Sub) {
        val msg = db.reminderText(s, kind)
        db.markSent(kind, s.id); r++
        notify(s.name, s.phone ?: "", msg)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("تذكيرات المشتركين", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!gen) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == "soon", onClick = { kind = "soon" }, label = { Text("ينتهي قريباً (${al.expiring.size})") })
                        FilterChip(selected = kind == "expired", onClick = { kind = "expired" }, label = { Text("منتهي (${al.expired.size})") })
                        FilterChip(selected = kind == "debt", onClick = { kind = "debt" }, label = { Text("عليه دين (${al.debtors.size})") })
                    }
                    if (kind == "soon") FieldInput(F("d", "يُعدّ قريباً إذا بقي (أيام)", options = listOf("1", "2", "3", "5", "7", "10"), free = true, number = true), days) {
                        days = it; it.toIntOrNull()?.let { n -> db.setSetting("remind_days", n.toString()) }
                    }
                    Button(onClick = { pending.firstOrNull()?.let { send(it) } }, enabled = pending.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsApp, contentColor = Color.White)) {
                        Icon(if (chan == Notify.SMS) Icons.Rounded.Sms else Icons.Rounded.Chat, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("إرسال للتالي (${pending.size} متبقٍ)")
                    }
                    Text(when (chan) {
                        Notify.SMS -> "تُرسل SMS مباشرة بدون فتح أي تطبيق، اضغط لكل مشترك بالتتابع. (القناة: SMS)"
                        Notify.WA -> "تُفتح الرسالة جاهزة في واتساب فتضغط إرسال ثم ترجع للتطبيق وتكمل التالي."
                        Notify.SMS_WA -> "تُرسل SMS مباشرة، وإن تعذّر يُفتح واتساب. (القناة: SMS ثم واتساب)"
                        else -> "تُفتح الرسالة في واتساب، وإن لم يكن مثبتاً تُرسل SMS مباشرة. (القناة: واتساب ثم SMS)"
                    }, fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (list.isEmpty()) item { EmptyState(Icons.Rounded.CheckCircle, "لا يوجد مشتركون في هذه القائمة") }
                    items(list, key = { it.id }) { s ->
                        val sent = db.isSent(kind, s.id)
                        AppCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(s.name, statusColor(db.statusOf(s)), 40)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontWeight = FontWeight.Bold)
                                    Text(if (kind == "debt") "الدين: ${fmt(s.bal)} دينار" else "ينتهي: ${s.endDate ?: "-"}",
                                        fontSize = 12.sp, color = cs.onSurfaceVariant)
                                    if (s.phone.isNullOrEmpty()) Text("بدون رقم هاتف", fontSize = 11.sp, color = Bad)
                                }
                                if (sent) Pill("أُرسل اليوم", Ok)
                                IconButton(onClick = { send(s) }, enabled = !s.phone.isNullOrEmpty()) {
                                    Icon(if (chan == Notify.SMS) Icons.Rounded.Sms else Icons.Rounded.Chat, "إرسال", tint = if (s.phone.isNullOrEmpty()) Muted else if (chan == Notify.SMS) cs.primary else WhatsApp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
