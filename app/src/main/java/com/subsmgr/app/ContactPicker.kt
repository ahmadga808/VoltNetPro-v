package com.subsmgr.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** جهة اتصال من سجل أسماء الهاتف. wa = لها حساب واتساب (مزامنة واتساب مع الدفتر). */
data class PhoneContact(val name: String, val phone: String, val wa: Boolean)

private val WaColor = Color(0xFF25D366)
private const val WA_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.profile"

private fun digitsKey(phone: String): String =
    phone.filter { it.isDigit() }.map { Character.digit(it, 10).toString() }.joinToString("").takeLast(10)

/** يقرأ كل الأسماء والأرقام من الهاتف (بلا تكرار) ويعلّم من لديها واتساب. تحتاج READ_CONTACTS. */
fun loadPhoneContacts(ctx: Context): List<PhoneContact> {
    val waIds = HashSet<Long>()
    try {
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE}=?", arrayOf(WA_MIME), null,
        )?.use { c -> while (c.moveToNext()) waIds.add(c.getLong(0)) }
    } catch (e: Exception) { /* واتساب غير مثبّت أو لا يوجد وصول: نكمل بدون العلامة */ }

    val out = LinkedHashMap<String, PhoneContact>()
    try {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim() ?: ""
                val num = c.getString(1)?.trim() ?: ""
                val k = digitsKey(num)
                if (k.length < 7) continue
                val wa = waIds.contains(c.getLong(2))
                val old = out[k]
                if (old == null) out[k] = PhoneContact(name.ifBlank { num }, num, wa)
                else if (wa && !old.wa) out[k] = old.copy(wa = true)
            }
        }
    } catch (e: Exception) { }
    return out.values.toList()
}

/**
 * نافذة اختيار جهة اتصال (بحث + فلتر واتساب). عند الضغط على جهة يُرجع الاسم والرقم معاً.
 * تطلب صلاحية قراءة جهات الاتصال عند الحاجة.
 */
@Composable
fun ContactPickerDialog(onDismiss: () -> Unit, onPicked: (name: String, phone: String) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var contacts by remember { mutableStateOf<List<PhoneContact>?>(null) }
    var q by remember { mutableStateOf("") }
    var onlyWa by remember { mutableStateOf(false) }

    LaunchedEffect(granted) {
        if (granted && contacts == null) contacts = withContext(Dispatchers.IO) { loadPhoneContacts(ctx) }
    }
    val shown = remember(contacts, q, onlyWa) {
        (contacts ?: emptyList()).filter { c ->
            (!onlyWa || c.wa) && (q.isBlank() || c.name.contains(q, ignoreCase = true) || c.phone.contains(q))
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 12.dp), shape = MaterialTheme.shapes.large, color = cs.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Rounded.Contacts, cs.primary, 36)
                    Spacer(Modifier.width(10.dp))
                    Text("اختيار من سجل الأسماء", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "إغلاق") }
                }
                if (!granted) {
                    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("للاختيار من سجل الأسماء (ومنها أسماء وأرقام واتساب المحفوظة) اسمح للتطبيق بقراءة جهات الاتصال.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { perm.launch(Manifest.permission.READ_CONTACTS) }, shape = RoundedCornerShape(14.dp)) { Text("السماح بالوصول") }
                    }
                } else {
                    OutlinedTextField(
                        value = q, onValueChange = { q = it }, singleLine = true, label = { Text("بحث بالاسم أو الرقم") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !onlyWa, onClick = { onlyWa = false }, label = { Text("كل الأسماء") })
                        FilterChip(selected = onlyWa, onClick = { onlyWa = true }, label = { Text("واتساب فقط") },
                            leadingIcon = { Icon(Icons.Rounded.Chat, null, Modifier.size(16.dp), tint = WaColor) })
                    }
                    if (contacts == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (shown.isEmpty()) {
                        EmptyState(Icons.Rounded.Contacts, "لا توجد جهات اتصال مطابقة")
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(shown, key = { digitsKey(it.phone) + it.name }) { c ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onPicked(c.name, c.phone) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(c.name, if (c.wa) WaColor else cs.primary, 40)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                        Text(c.phone, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
                                    }
                                    if (c.wa) Pill("واتساب", WaColor)
                                }
                                HorizontalDivider(color = cs.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
