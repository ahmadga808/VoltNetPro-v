package com.subsmgr.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** إدارة الموظفين (للمدير فقط). */
@Composable
fun StaffCard(db: Db, bump: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var local by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Staff?>(null) }
    val staff = remember(local) { db.staffList() }
    SectionTitle("الموظفون والصلاحيات")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("لكل موظف رمز دخول خاص. الموظف يرى المشتركين ويسجّل الدفعات والتجديد ويرسل الرسائل ويفتح كشف الحساب، ويُسجَّل اسمه على كل دفعة. لا يرى الباقات والمصاريف والتقارير والإعدادات، ولا يعدّل البيانات ولا يخصم ولا يلغي حركة.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            if (staff.isEmpty()) Text("لا يوجد موظفون بعد.", fontSize = 12.sp)
            staff.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Person, null, tint = cs.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(s.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { del = s }) { Icon(Icons.Rounded.Delete, "حذف", tint = Bad) }
                }
            }
            if (!db.hasPin()) Text("عيّن رمز PIN للمدير أولاً (من قسم الأمان) لتتمكن من إضافة موظفين.", fontSize = 12.sp, color = Bad)
            Button(onClick = { adding = true }, enabled = db.hasPin(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("إضافة موظف")
            }
            if (db.hasPin()) OutlinedButton(onClick = { Session.lock?.invoke() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("قفل / تبديل المستخدم")
            }
        }
    }
    if (adding) FormDialog("موظف جديد", listOf(F("name", "اسم الموظف *"), F("pin", "رمز الدخول (4 أرقام فأكثر) *", number = true)), emptyMap(), { adding = false }) { d ->
        db.addStaff(d["name"] ?: "", d["pin"] ?: "")
        local++; bump()
    }
    del?.let { s -> ConfirmDialog("حذف الموظف ${s.name}؟ لن يستطيع الدخول بعد الآن (حركاته السابقة تبقى مسجّلة).", "حذف", { del = null }) { db.removeStaff(s.id); local++; bump() } }
}

/** شاشة الإعدادات المبسّطة للموظف. */
@Composable
fun StaffSettings(db: Db) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeBar()
        SectionTitle("المستخدم")
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("الموظف: ${Session.name}", fontWeight = FontWeight.Bold)
                Text("يُسجَّل اسمك على كل دفعة تستلمها. للخروج أو تبديل المستخدم اضغط الزر.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Button(onClick = { Session.lock?.invoke() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("قفل / تبديل المستخدم")
                }
            }
        }
    }
}
