package com.subsmgr.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class NetworkEntry(val title: String, val subtitle: String, val icon: ImageVector, val key: String)

@Composable
fun NetworkHub(onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val entries = listOf(
        NetworkEntry("لوحاتي", "روابط لوحات الإدارة", Icons.Rounded.Dashboard, "boards"),
        NetworkEntry("فحص الربط", "اختبار الاتصال الفعلي", Icons.Rounded.Cable, "integration"),
        NetworkEntry("مراقبة الشبكة", "الراوتر والأجهزة والإشارة", Icons.Rounded.Wifi, "monitor"),
        NetworkEntry("الراوترات والأبراج", "MikroTik وSAS وأجهزة البث", Icons.Rounded.Router, "routers"),
        NetworkEntry("تنبيهات Telegram", "إعداد التنبيهات واختبارها", Icons.Rounded.Send, "telegram"),
        NetworkEntry("المساعد الذكي", "تشخيص محلي لقراءات الشبكة", Icons.Rounded.Psychology, "assistant"),
    )
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("مركز الشبكة", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("كل أدوات الاتصال والإدارة والمراقبة في مكان واحد. افتح الأداة المطلوبة من الشبكة أدناه.", fontSize = 13.sp, color = cs.onSurfaceVariant)
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries) { entry ->
                Surface(modifier = Modifier.clickable { onOpen(entry.key) }, shape = MaterialTheme.shapes.large, color = cs.surface, tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(entry.icon, null, Modifier.size(34.dp), tint = cs.primary)
                        Spacer(Modifier.height(9.dp))
                        Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(entry.subtitle, fontSize = 11.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkDestination(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(5.dp)); Text("الشبكة") }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        content()
    }
}
