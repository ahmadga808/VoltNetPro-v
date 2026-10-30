package com.subsmgr.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** تحديد الموقع الحالي عبر LocationManager (بدون خدمات جوجل). */
object Loc {
    fun has(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** يرجع آخر موقع حديث (أقل من دقيقتين) وإلا ينتظر تحديثاً جديداً حتى 25 ثانية. */
    fun current(ctx: Context, onDone: (Pair<Double, Double>?) -> Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            val providers = lm.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
            if (providers.isEmpty()) { onDone(null); return }
            val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time < 120_000) {
                onDone(Pair(last.latitude, last.longitude)); return
            }
            var done = false
            val listeners = ArrayList<LocationListener>()
            val handler = Handler(Looper.getMainLooper())
            fun finish(r: Pair<Double, Double>?) {
                if (done) return
                done = true
                listeners.forEach { try { lm.removeUpdates(it) } catch (e: Exception) { } }
                onDone(r)
            }
            val timeout = Runnable { finish(last?.let { Pair(it.latitude, it.longitude) }) }
            for (p in providers) {
                val l = object : LocationListener {
                    override fun onLocationChanged(loc: Location) { handler.removeCallbacks(timeout); finish(Pair(loc.latitude, loc.longitude)) }
                    @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                listeners.add(l)
                lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper())
            }
            handler.postDelayed(timeout, 25_000)
        } catch (e: SecurityException) {
            onDone(null)
        }
    }
}

/** يرجع دالة تطلب الصلاحية عند الحاجة ثم تحدد الموقع الحالي وتمرّره إلى onResult. */
@Composable
fun rememberLocationRequester(onResult: (Pair<Double, Double>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val cb = rememberUpdatedState(onResult)
    fun go() {
        toast(ctx, "جارٍ تحديد الموقع...")
        Loc.current(ctx) { r -> if (r != null) cb.value(r) else toast(ctx, "تعذّر تحديد الموقع. فعّل GPS وجرّب في مكان مكشوف.") }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) go() else toast(ctx, "صلاحية الموقع مطلوبة")
    }
    return {
        if (Loc.has(ctx)) go()
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
