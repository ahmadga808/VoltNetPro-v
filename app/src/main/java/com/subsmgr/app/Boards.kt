@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.subsmgr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.json.JSONArray
import org.json.JSONObject

/** Saved panel bookmarks. Credentials are intentionally not stored here. */
data class BoardLink(val id: String, val name: String, val type: String, val url: String, val note: String = "")

object Boards {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("voltnet_boards", Context.MODE_PRIVATE)
    fun all(ctx: Context): List<BoardLink> {
        val raw = prefs(ctx).getString("items", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                BoardLink(o.optString("id"), o.optString("name"), o.optString("type"), o.optString("url"), o.optString("note"))
            }
        } catch (_: Exception) { emptyList() }
    }
    fun save(ctx: Context, rows: List<BoardLink>) {
        val a = JSONArray()
        rows.forEach { b -> a.put(JSONObject().put("id", b.id).put("name", b.name).put("type", b.type).put("url", b.url).put("note", b.note)) }
        prefs(ctx).edit().putString("items", a.toString()).apply()
    }
    fun open(ctx: Context, board: BoardLink) {
        val u = board.url.trim()
        val parsed = Uri.parse(u)
        require(parsed.scheme == "https" || parsed.scheme == "http") { "الرابط يجب أن يبدأ بـ https:// أو http://" }
        require(!parsed.host.isNullOrBlank()) { "عنوان اللوحة غير صحيح" }
        require(parsed.userInfo.isNullOrBlank()) { "لا تضع اسم المستخدم أو كلمة المرور داخل الرابط؛ احذف بيانات الدخول منه." }
        ctx.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun BoardsSection() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var rows by remember { mutableStateOf(Boards.all(ctx)) }
    var editing by remember { mutableStateOf<BoardLink?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BoardLink?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    SectionTitle("لوحات الشبكة")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("احفظ روابط لوحات SAS وSAS3 وSAS4 وإيرثلينك وأي واجهة إدارة أخرى، ثم افتحها بضغطة. بيانات الدخول لا تُحفظ هنا.", fontSize = 13.sp, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("إضافة لوحة")
                }
                OutlinedButton(onClick = { rows = Boards.all(ctx) }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("تحديث القائمة")
                }
            }
        }
    }
    if (rows.isEmpty()) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.DashboardCustomize, null, tint = cs.primary, modifier = Modifier.size(36.dp))
                Text("لا توجد لوحات محفوظة", fontWeight = FontWeight.SemiBold)
                Text("أضف رابط لوحة الإدارة للبدء.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.id }) { b ->
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(when (b.type) { "MikroTik" -> Icons.Rounded.Router; "Earthlink" -> Icons.Rounded.Language; else -> Icons.Rounded.Dashboard }, cs.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, fontWeight = FontWeight.Bold)
                            Text(b.type, fontSize = 11.sp, color = cs.primary)
                            Text(b.url, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 2)
                            if (b.note.isNotBlank()) Text(b.note, fontSize = 11.sp, color = cs.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { try { Boards.open(ctx, b) } catch (e: Exception) { info = e.message ?: "تعذّر فتح الرابط" } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("فتح اللوحة")
                        }
                        OutlinedButton(onClick = { editing = b }, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Rounded.Edit, "تعديل") }
                        OutlinedButton(onClick = { deleting = b }, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Rounded.Delete, "حذف", tint = cs.error) }
                    }
                }
            }
        }
    }
    Text("تنبيه: حفظ الرابط لا يعني أن واجهة API متاحة. التفعيل والتجديد الآلي وعرض الكروت لكل لوحة يحتاج توثيق API وصلاحياتها. لا تدخل كلمات المرور في خانة الرابط.", fontSize = 12.sp, color = cs.onSurfaceVariant)

    if (adding) BoardEditor(null, onDismiss = { adding = false }) { b ->
        rows = rows + b; Boards.save(ctx, rows); adding = false
    }
    editing?.let { old -> BoardEditor(old, onDismiss = { editing = null }) { b ->
        rows = rows.map { if (it.id == old.id) b.copy(id = old.id) else it }; Boards.save(ctx, rows); editing = null
    } }
    deleting?.let { b -> ConfirmDialog("حذف اللوحة «${b.name}» من القائمة؟ لن تُحذف اللوحة الأصلية.", "حذف", { deleting = null }) {
        rows = rows.filterNot { it.id == b.id }; Boards.save(ctx, rows); deleting = null
    } }
    info?.let { message -> AlertDialog(onDismissRequest = { info = null }, title = { Text("معلومات") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { info = null }) { Text("حسناً") } }) }
}

@Composable
private fun BoardEditor(existing: BoardLink?, onDismiss: () -> Unit, onSave: (BoardLink) -> Unit) {
    val ctx = LocalContext.current
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var type by remember(existing?.id) { mutableStateOf(existing?.type ?: "SAS4") }
    var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "https://") }
    var note by remember(existing?.id) { mutableStateOf(existing?.note ?: "") }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val types = listOf("SAS", "SAS3", "SAS4", "Earthlink", "MikroTik", "أخرى")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "إضافة لوحة" else "تعديل اللوحة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسم اللوحة") }, singleLine = true)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(type, {}, readOnly = true, label = { Text("نوع اللوحة") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { types.forEach { t -> DropdownMenuItem(text = { Text(t) }, onClick = { type = t; expanded = false }) } }
                }
                OutlinedTextField(url, { url = it }, label = { Text("رابط لوحة الإدارة") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("ملاحظة اختيارية") }, minLines = 2)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Text("استخدم HTTPS عند توفره. لا تحفظ كلمة المرور أو رمز API داخل الرابط.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = {
            val clean = url.trim()
            val uri = Uri.parse(clean)
            if (name.isBlank()) error = "اكتب اسم اللوحة"
            else if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) error = "أدخل رابطاً صحيحاً يبدأ بـ https:// أو http://"
            else if (!uri.userInfo.isNullOrBlank()) error = "لا تضع بيانات الدخول داخل الرابط؛ احذفها أولاً."
            else onSave(BoardLink(existing?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), type, clean, note.trim()))
        }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
