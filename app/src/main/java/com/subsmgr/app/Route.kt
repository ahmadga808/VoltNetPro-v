package com.subsmgr.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.core.content.ContextCompat

private fun meters(a: Pair<Double, Double>, b: Pair<Double, Double>): Float {
    val r = FloatArray(1)
    Location.distanceBetween(a.first, a.second, b.first, b.second, r)
    return r[0]
}

/** آخر موقع معروف للجهاز (بلا تشغيل GPS جديد). */
private fun lastKnown(ctx: Context): Pair<Double, Double>? {
    val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { Pair(it.latitude, it.longitude) }
    } catch (e: SecurityException) { null }
}

/** ترتيب الجولة: الأقرب فالأقرب بدءاً من موقعك (أو من أكبر دين إن لم يتوفر الموقع). */
private fun orderRoute(start: Pair<Double, Double>?, list: List<Sub>): List<Sub> {
    val left = list.toMutableList()
    val out = ArrayList<Sub>()
    var cur = start ?: left.maxByOrNull { it.bal }?.let { Pair(it.lat!!, it.lng!!) } ?: return list
    while (left.isNotEmpty()) {
        val n = left.minByOrNull { meters(cur, Pair(it.lat!!, it.lng!!)) }!!
        out.add(n); left.remove(n); cur = Pair(n.lat!!, n.lng!!)
    }
    return out
}

private fun navigate(ctx: Context, s: Sub) {
    val la = s.lat ?: return
    val ln = s.lng ?: return
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$la,$ln")))
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$la,$ln?q=$la,$ln(" + Uri.encode(s.name) + ")")))
        } catch (e2: Exception) { toast(ctx, "لا يوجد تطبيق خرائط للملاحة") }
    }
}

/** جولة التحصيل: المدينون الذين لهم موقع، مرتبون بالأقرب، مع زر ملاحة لكل واحد. */
@Composable
fun CollectionRouteDialog(subs: List<Sub>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val debtors = remember(subs) { subs.filter { it.bal > 0 && it.lat != null && it.lng != null } }
    val noLoc = remember(subs) { subs.count { it.bal > 0 && (it.lat == null || it.lng == null) } }
    var me by remember { mutableStateOf(lastKnown(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { me = lastKnown(ctx) }
    val route = remember(debtors, me) { orderRoute(me, debtors) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                    Text("جولة التحصيل", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${route.size} مشترك  •  الإجمالي: ${fmt(route.sumOf { it.bal })} دينار", fontWeight = FontWeight.SemiBold)
                    if (noLoc > 0) Text("$noLoc مدين بلا موقع مسجّل (لا يظهرون هنا).", fontSize = 12.sp, color = Bad)
                    if (me == null) {
                        OutlinedButton(
                            onClick = { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                        ) { Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("رتّب حسب موقعي الحالي") }
                    } else Text("الترتيب من موقعك الحالي: الأقرب فالأقرب.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (route.isEmpty()) item { EmptyState(Icons.Rounded.CheckCircle, "لا يوجد مدينون لهم موقع مسجّل") }
                    itemsIndexed(route, key = { _, s -> s.id }) { i, s ->
                        val km = me?.let { meters(it, Pair(s.lat!!, s.lng!!)) / 1000f }
                        AppCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.width(28.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontWeight = FontWeight.Bold)
                                    Text("الدين: ${fmt(s.bal)} دينار" + (s.area?.takeIf { it.isNotEmpty() }?.let { "  •  $it" } ?: ""), fontSize = 12.sp, color = cs.onSurfaceVariant)
                                    if (km != null) Text(String.format(java.util.Locale.US, "%.1f كم عنك", km), fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                                IconButton(onClick = { navigate(ctx, s) }) { Icon(Icons.Rounded.Directions, "ملاحة", tint = cs.primary) }
                            }
                        }
                    }
                }
            }
        }
    }
}
