package com.subsmgr.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** تفضيلات واجهة على مستوى الجهاز (الجولة التعريفية). */
object UiPrefs {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("voltnet_ui", Context.MODE_PRIVATE)
    fun tourDone(ctx: Context) = sp(ctx).getBoolean("tour_done", false)
    fun setTourDone(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("tour_done", v).apply()
    fun releaseShown(ctx: Context, version: String) = sp(ctx).getString("release_seen_version", "") == version
    fun setReleaseShown(ctx: Context, version: String) = sp(ctx).edit().putString("release_seen_version", version).apply()
}

// ======================= الرئيسية (ملخص اليوم) =======================
@Composable
fun HomeTab(db: Db, rev: Int, onGo: (Int) -> Unit, onGoFilter: (String?) -> Unit, onGoSettings: (Int) -> Unit, onAudit: () -> Unit, onTour: () -> Unit, onShowUpdates: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val gen = db.isGenerator()
    val data = remember(rev) {
        val subs = db.listSubs()
        val day = db.dayReport(today())
        Triple(subs, day, db.alerts())
    }
    val (subs, day, alerts) = data
    val ins = remember(rev) { db.insights() }
    val promises = remember(rev) { db.promises() }
    val recent = remember(rev) { if (Session.isAdmin) db.auditLog(limit = 5) else emptyList() }

    val statuses = subs.map { db.statusOf(it) }
    val first = if (gen) statuses.count { it == "مسدّد" } else statuses.count { it == "فعّال" }
    val second = if (gen) statuses.count { it == "مقطوع" } else statuses.count { it == "منتهي" }
    val debtors = subs.filter { it.bal > 0 }
    val totalDebt = debtors.sumOf { it.bal }
    val sasUsers = Sas.usersSnapshot.value
    val onlineSet = Router.online.value
    val onlineCount = if (sasUsers.isNotEmpty()) subs.count { sub ->
        sasUsers[sub.e1.orEmpty().trim().lowercase()]?.let { it.online && it.enabled && it.status() == "فعّال" && db.statusOf(sub) != "مجمّد" } == true
    } else subs.count { it.e1.orEmpty().trim() in onlineSet }
    val offlineCount = if (sasUsers.isNotEmpty()) subs.count { sub ->
        sasUsers[sub.e1.orEmpty().trim().lowercase()]?.let { !it.online && it.enabled && it.status() == "فعّال" && db.statusOf(sub) != "مجمّد" } == true
    } else 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // صندوق اليوم
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(listOf(cs.primary, cs.secondary))).padding(18.dp)) {
            Column {
                Text("صندوق اليوم", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                Text("${fmt(day.total)} دينار", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Spacer(Modifier.height(4.dp))
                Text("${day.payments.size} دفعة  •  المصاريف ${fmt(day.expenses)}  •  الصافي ${fmt(day.net)}",
                    color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
            }
        }

        SectionTitle("عدادات المشتركين")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeStat(Icons.Rounded.People, "إجمالي المشتركين", subs.size.toString(), cs.primary, Modifier.weight(1f)) { onGoFilter(null) }
            HomeStat(Icons.Rounded.CheckCircle, if (gen) "مسدّد" else "فعّال", first.toString(), Ok, Modifier.weight(1f)) { onGoFilter(if (gen) "paid" else "active") }
            HomeStat(if (gen) Icons.Rounded.PowerOff else Icons.Rounded.EventBusy, if (gen) "مقطوع" else "منتهٍ", second.toString(), Warn, Modifier.weight(1f)) { onGoFilter(if (gen) "cut" else "expired") }
        }
        if (!gen) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeStat(Icons.Rounded.Wifi, "متصل الآن", onlineCount.toString(), Ok, Modifier.weight(1f)) { onGoFilter("online") }
            HomeStat(Icons.Rounded.WifiOff, "غير متصل", offlineCount.toString(), Warn, Modifier.weight(1f)) { onGoFilter("offline") }
            HomeStat(Icons.Rounded.AccountBalanceWallet, "مدينون", debtors.size.toString(), Bad, Modifier.weight(1f)) { onGoFilter("debt") }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeStat(Icons.Rounded.AccountBalanceWallet, "مدينون", debtors.size.toString(), Bad, Modifier.weight(1f)) { onGoFilter("debt") }
        }

        SectionTitle("أقسام البرنامج")
        val destinations = listOf(
            Triple(Icons.Rounded.People, "المشتركون", 0),
            Triple(Icons.Rounded.Inventory2, "الباقات والتجديدات", 1),
            Triple(Icons.Rounded.AccountBalanceWallet, "الديون والحسابات", 2),
            Triple(Icons.Rounded.DeviceHub, "مركز الشبكة", 6),
            Triple(Icons.Rounded.BarChart, "التقارير", 3),
            Triple(Icons.Rounded.Notifications, "القوالب والرسائل", 1),
            Triple(Icons.Rounded.SupportAgent, "مركز الدعم والبلاغات", 4),
            Triple(Icons.Rounded.Settings, "الإعدادات العامة", 0),
        )
        destinations.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (icon, label, dest) ->
                    AppCard(Modifier.weight(1f), onClick = { if (label == "القوالب والرسائل" || label == "مركز الدعم والبلاغات" || label == "الإعدادات العامة") onGoSettings(dest) else onGo(dest) }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Savings, Bad)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("إجمالي الديون", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Text("${fmt(totalDebt)} دينار", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                ins.collectionRate?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$it%", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (it >= 80) Ok else Warn)
                        Text("نسبة التحصيل هذا الشهر", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }

        // وعود الدفع المستحقة اليوم أو المتأخرة
        val dueP = promises.filter { it.promiseDate == today() || db.promiseBroken(it) }
        if (dueP.isNotEmpty()) {
            SectionTitle("وعود دفع تحتاج متابعة (${dueP.size})")
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    dueP.take(5).forEach { s ->
                        val late = db.promiseBroken(s)
                        HomeRow(s.name, "${fmt(s.bal)} دينار", if (late) "تأخر وعده ${s.promiseDate}" else "وعد اليوم", if (late) Bad else Warn) { onGo(0) }
                    }
                }
            }
        }

        if (!gen && alerts.expiring.isNotEmpty()) {
            SectionTitle("تنتهي خلال 3 أيام (${alerts.expiring.size})")
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    alerts.expiring.take(5).forEach { s -> HomeRow(s.name, s.endDate ?: "", s.pkg ?: "", Warn) { onGo(0) } }
                }
            }
        }

        if (debtors.isNotEmpty()) {
            SectionTitle("أكبر الديون")
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    debtors.sortedByDescending { it.bal }.take(5).forEach { s ->
                        HomeRow(s.name, "${fmt(s.bal)} دينار", listOfNotNull(s.area?.takeIf { it.isNotEmpty() }, if (db.promiseActive(s)) "وعد حتى ${s.promiseDate}" else null).joinToString("  •  "), Bad) { onGo(0) }
                    }
                }
            }
        }

        AppCard(Modifier.fillMaxWidth(), onClick = onShowUpdates) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NewReleases, null, tint = cs.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("التعديلات والإضافات الجديدة", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("اضغط لعرض سجل تغييرات وإضافات VoltNet Pro 2.24", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Text("عرض", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        SectionTitle("اختصارات")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onGo(0) }, label = { Text("المشتركون") }, leadingIcon = { Icon(Icons.Rounded.People, null, Modifier.size(18.dp)) })
            if (Session.isAdmin) AssistChip(onClick = { onGo(3) }, label = { Text("التقارير") }, leadingIcon = { Icon(Icons.Rounded.BarChart, null, Modifier.size(18.dp)) })
            if (Session.isAdmin) AssistChip(onClick = onAudit, label = { Text("سجل العمليات") }, leadingIcon = { Icon(Icons.Rounded.History, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onTour, label = { Text("جولة تعريفية") }, leadingIcon = { Icon(Icons.Rounded.TipsAndUpdates, null, Modifier.size(18.dp)) })
        }

        if (recent.isNotEmpty()) {
            SectionTitle("آخر العمليات")
            AppCard(Modifier.fillMaxWidth(), onClick = onAudit) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    recent.forEach { r -> HomeRow(r.action, r.target, "${r.actor}  •  ${r.ts}", auditColor(r.action)) { onAudit() } }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HomeStat(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).clickable { onClick() }) { StatCard(icon, label, value, color, Modifier.fillMaxWidth()) }
}

@Composable
private fun HomeRow(title: String, trailing: String, sub: String, color: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1)
        }
        if (trailing.isNotBlank()) Text(trailing, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

private fun auditColor(action: String): Color = when {
    action.contains("إلغاء") || action.contains("حذف") || action.contains("قطع") || action.contains("استعادة") -> Bad
    action.contains("دفعة") || action.contains("تجديد") || action.contains("وصل") -> Ok
    action.contains("خصم") || action.contains("وعد") || action.contains("تجميد") -> Warn
    else -> Color(0xFF64748B)
}

// ======================= سجل العمليات =======================
@Composable
fun AuditDialog(db: Db, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    var onlyMoney by remember { mutableStateOf(false) }
    val rows = remember(q, onlyMoney) { db.auditLog(limit = 400, search = q, onlyMoney = onlyMoney) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 12.dp), shape = MaterialTheme.shapes.large, color = cs.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Rounded.History, cs.primary, 36)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("سجل العمليات", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("من فعل ماذا ومتى (للقراءة فقط)", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                }
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("بحث بالاسم أو العملية أو الموظف") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlyMoney, onClick = { onlyMoney = false }, label = { Text("الكل") })
                    FilterChip(selected = onlyMoney, onClick = { onlyMoney = true }, label = { Text("المالية فقط") })
                }
                if (rows.isEmpty()) EmptyState(Icons.Rounded.History, "لا توجد عمليات مسجّلة")
                else LazyColumn(Modifier.weight(1f)) {
                    items(rows, key = { it.id }) { r ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill(r.action, auditColor(r.action))
                                Spacer(Modifier.width(8.dp))
                                Text(r.target, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            }
                            if (r.detail.isNotBlank()) Text(r.detail, fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                            Text("${r.actor}  •  ${r.ts}", fontSize = 11.sp, color = cs.outline, modifier = Modifier.padding(top = 2.dp))
                        }
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        }
    }
}

// ======================= الجولة التعريفية =======================
private data class TourStep(val icon: ImageVector, val title: String, val text: String)

@Composable
fun TourDialog(gen: Boolean, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val steps = remember(gen) {
        buildList {
            add(TourStep(Icons.Rounded.EmojiEmotions, "أهلاً بك في VoltNet Pro", "جولة سريعة (دقيقة واحدة) تعرّفك على أهم ما في التطبيق. يمكنك تخطيها وإعادتها لاحقاً من الرئيسية أو الإعدادات."))
            add(TourStep(Icons.Rounded.PersonAdd, "أضف مشتركيك",
                "اضغط \"مشترك جديد\". وللاختيار السريع استخدم زر سجل الأسماء بجانب الاسم أو الهاتف لجلب الاسم والرقم معاً من هاتفك أو من واتساب. ويمكنك أيضاً الاستيراد من Excel."))
            add(TourStep(Icons.Rounded.Payments, if (gen) "سجّل القراءات والدفعات" else "جدّد وحصّل",
                if (gen) "اضغط على المشترك لتسجيل قراءة عداد أو دفعة أو خصم. وسعر الأمبير الشهري يُضبط من تبويب التسعير."
                else "اضغط على أي مشترك لتجديد اشتراكه أو تسجيل دفعة. يُسجَّل اسم المحصّل تلقائياً على كل دفعة."))
            add(TourStep(Icons.Rounded.EventAvailable, "وعد بالدفع",
                "إذا طلب المشترك مهلة، سجّل له \"وعد بالدفع\" بتاريخ محدد. لن يُقطع قبل تاريخه، وتظهر لك الوعود المتأخرة في الرئيسية."))
            add(TourStep(Icons.Rounded.Chat, "ذكّر مشتركيك",
                "اختر قناة الإرسال (SMS مباشر أو واتساب) من الإعدادات، وستُرسل بها كل إشعارات المشتركين. وفعّل التذكيرات التلقائية للانتهاء والدين بالساعات التي تناسبك."))
            if (!gen) add(TourStep(Icons.Rounded.Router, "اربط الراوتر والأبراج",
                "من الإعدادات ثم \"الراوترات والأبراج\": اربط MikroTik ليُفعَّل المشترك عند التجديد ويُوقف عند الانتهاء، وراقب سكترات Ubiquiti وإشارة الصحون."))
            add(TourStep(Icons.Rounded.Security, "الأمان والمتابعة",
                "فعّل قفل PIN والبصمة، واترك نسخة احتياطية يومية. ومن \"سجل العمليات\" تعرف من عدّل أو ألغى أو خصم ومتى."))
            add(TourStep(Icons.Rounded.CheckCircle, "أنت جاهز", "ابدأ بإضافة أول مشترك. بالتوفيق!"))
        }
    }
    val pager = rememberPagerState(pageCount = { steps.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == steps.size - 1
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(28.dp), color = cs.surface) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalPager(pager, Modifier.fillMaxWidth().height(250.dp)) { i ->
                    val st = steps[i]
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        IconBubble(st.icon, cs.primary, 72)
                        Spacer(Modifier.height(16.dp))
                        Text(st.title, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(st.text, fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 22.sp)
                    }
                }
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(steps.size) { i ->
                        Surface(shape = CircleShape, color = if (i == pager.currentPage) cs.primary else cs.outlineVariant, modifier = Modifier.size(if (i == pager.currentPage) 10.dp else 7.dp)) {}
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDone) { Text(if (last) "" else "تخطي") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        shape = RoundedCornerShape(14.dp)) { Text(if (last) "ابدأ الآن" else "التالي") }
                }
            }
        }
    }
}
