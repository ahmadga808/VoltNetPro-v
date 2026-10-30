package com.subsmgr.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * طباعة الوصل على طابعة حرارية ESC/POS عبر بلوتوث.
 * يُرسم النص كصورة (Bitmap) ثم يُرسل كنقاط، فيعمل العربي على أي طابعة دون الحاجة لدعم الخط العربي فيها.
 */
object Printer {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun bonded(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        val ad = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
        return ad.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun nameOf(d: BluetoothDevice): String = try { d.name ?: d.address } catch (e: Exception) { d.address }

    private fun toBitmap(text: String, width: Int): Bitmap {
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = if (width >= 500) 28f else 24f }
        val pad = 8
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - pad * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setLineSpacing(4f, 1f)
            .build()
        val bmp = Bitmap.createBitmap(width, layout.height + pad * 2 + 16, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.translate(pad.toFloat(), pad.toFloat())
        layout.draw(c)
        return bmp
    }

    private fun raster(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val bytesPerRow = (w + 7) / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40))
        val pixels = IntArray(w)
        var y = 0
        while (y < h) {
            val rows = minOf(128, h - y)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte(),
                (rows and 0xFF).toByte(), (rows shr 8).toByte()))
            for (r in 0 until rows) {
                bmp.getPixels(pixels, 0, w, 0, y + r, w, 1)
                val line = ByteArray(bytesPerRow)
                for (x in 0 until w) {
                    val p = pixels[x]
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    if (lum < 140) line[x / 8] = (line[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                }
                out.write(line)
            }
            y += rows
        }
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A))
        return out.toByteArray()
    }

    /** done(null) عند النجاح، وإلا رسالة الخطأ. تُستدعى على الخيط الرئيسي. */
    @SuppressLint("MissingPermission")
    fun print(ctx: Context, text: String, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("app_global", Context.MODE_PRIVATE)
        val mac = prefs.getString("printer_mac", "") ?: ""
        val dots = prefs.getString("printer_dots", "384")?.toIntOrNull() ?: 384
        if (mac.isEmpty()) { done("لم تُحدَّد طابعة. اخترها من الإعدادات."); return }
        if (!hasPermission(ctx)) { done("صلاحية البلوتوث غير ممنوحة"); return }
        val main = Handler(Looper.getMainLooper())
        Thread {
            var err: String? = null
            try {
                val ad = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ad.cancelDiscovery()
                ad.getRemoteDevice(mac).createRfcommSocketToServiceRecord(SPP).use { s ->
                    s.connect()
                    val data = raster(toBitmap(text, dots))
                    s.outputStream.write(data)
                    s.outputStream.flush()
                    Thread.sleep(800)
                }
            } catch (e: Exception) {
                err = "تعذّرت الطباعة: ${e.message ?: ""}"
            }
            main.post { done(err) }
        }.start()
    }
}
