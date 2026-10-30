package com.subsmgr.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/** نقطة مشترك على الخريطة. */
data class MapPt(val lat: Double, val lng: Double, val color: Int, val title: String, val snippet: String)

/** خريطة أصلية (osmdroid) بدل WebView: لا تعتمد على CDN ولا على صفحة HTML. */
private fun initOsm(ctx: Context) {
    val c = Configuration.getInstance()
    c.userAgentValue = "VoltNetPro/2.0 (" + ctx.packageName + ")"   // OSM تحجب الطلبات بدون User-Agent خاص بالتطبيق
    val base = java.io.File(ctx.cacheDir, "osmdroid").apply { mkdirs() }
    c.osmdroidBasePath = base
    c.osmdroidTileCache = java.io.File(base, "tiles").apply { mkdirs() }
}

private fun dot(ctx: Context, color: Int): GradientDrawable {
    val d = ctx.resources.displayMetrics.density
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((2 * d).toInt(), 0xFFFFFFFF.toInt())
        setSize((22 * d).toInt(), (22 * d).toInt())
    }
}

@Composable
private fun rememberMapView(ctx: Context): MapView {
    val mv = remember {
        initOsm(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            overlays.add(CopyrightOverlay(ctx))
        }
    }
    DisposableEffect(mv) {
        mv.onResume()
        onDispose { mv.onPause(); mv.onDetach() }
    }
    return mv
}

/** اختيار موقع: الدبوس ثابت في منتصف الشاشة؛ حرّك الخريطة حتى يستقر على المكان ثم أكّد. المس نقطة لتنتقل إليها. */
@Composable
fun OsmPicker(lat: Double, lng: Double, zoom: Double, target: Pair<Double, Double>?,
              onCenter: (Double, Double) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mv = rememberMapView(ctx)
    val cb = rememberUpdatedState(onCenter)
    LaunchedEffect(target) {
        target?.let { mv.controller.setZoom(18.0); mv.controller.setCenter(GeoPoint(it.first, it.second)) }
    }
    Box(modifier) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            mv.controller.setZoom(zoom)
            mv.controller.setCenter(GeoPoint(lat, lng))
            mv.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { val c = mv.mapCenter; cb.value(c.latitude, c.longitude); return false }
                override fun onZoom(event: ZoomEvent?): Boolean { val c = mv.mapCenter; cb.value(c.latitude, c.longitude); return false }
            })
            mv.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean { if (p != null) mv.controller.animateTo(p); return true }
                override fun longPressHelper(p: GeoPoint?): Boolean = false
            }))
            mv
        })
        Icon(Icons.Rounded.LocationOn, null, tint = Color(0xFFD32F2F),
            modifier = Modifier.align(Alignment.Center).offset(y = (-22).dp).size(44.dp))
    }
}

/** خريطة كل المشتركين: أخضر فعّال/مسدّد، أصفر منتهي، أحمر عليه دين. المس نقطة لرؤية التفاصيل. */
@Composable
fun OsmSubsMap(pts: List<MapPt>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mv = rememberMapView(ctx)
    Box(modifier) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            for (p in pts) {
                val m = Marker(mv)
                m.position = GeoPoint(p.lat, p.lng)
                m.icon = dot(ctx, p.color)
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                m.title = p.title
                m.snippet = p.snippet
                mv.overlays.add(m)
            }
            mv.controller.setZoom(15.0)
            mv.controller.setCenter(GeoPoint(pts[0].lat, pts[0].lng))
            val box = BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lng) })
            if (pts.size > 1 && (box.latitudeSpan > 1e-5 || box.longitudeSpan > 1e-5))
                mv.post { mv.zoomToBoundingBox(box, false, 80) }
            mv
        })
        Surface(shape = RoundedCornerShape(8.dp), shadowElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text("🟢 فعّال / مسدّد   🟡 منتهي   🔴 عليه دين", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}
