package com.subsmgr.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ======================= Excel (xlsx) بلا مكتبات خارجية =======================
object Xlsx {
    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 32 && ch != '\n' && ch != '\t' && ch != '\r' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + (n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    private fun sheetName(s: String): String = s.map { if (it in "[]:*?/\\") '-' else it }.joinToString("").take(31)

    fun write(out: OutputStream, sheets: List<Pair<String, List<List<Any?>>>>) {
        val z = ZipOutputStream(out)
        fun entry(name: String, body: String) {
            z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        val ct = StringBuilder(head)
        ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        sheets.indices.forEach { i ->
            ct.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        ct.append("</Types>")
        entry("[Content_Types].xml", ct.toString())
        entry("_rels/.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        val wb = StringBuilder(head)
        wb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
        val rl = StringBuilder(head)
        rl.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sheets.forEachIndexed { i, (name, _) ->
            wb.append("<sheet name=\"${esc(sheetName(name))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
            rl.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
        }
        wb.append("</sheets></workbook>")
        rl.append("</Relationships>")
        entry("xl/workbook.xml", wb.toString())
        entry("xl/_rels/workbook.xml.rels", rl.toString())
        sheets.forEachIndexed { i, (_, rows) ->
            val w = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
            val x = StringBuilder(head)
            x.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            x.append("<sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\"/></sheetViews>")
            x.append("<cols><col min=\"1\" max=\"$w\" width=\"24\" customWidth=\"1\"/></cols><sheetData>")
            rows.forEachIndexed { ri, r ->
                x.append("<row r=\"${ri + 1}\">")
                r.forEachIndexed { ci, v ->
                    val ref = colName(ci) + (ri + 1)
                    when (v) {
                        null -> {}
                        is Int, is Long -> x.append("<c r=\"$ref\"><v>$v</v></c>")
                        is Double -> x.append("<c r=\"$ref\"><v>${java.math.BigDecimal.valueOf(v).toPlainString()}</v></c>")
                        else -> x.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                    }
                }
                x.append("</row>")
            }
            x.append("</sheetData></worksheet>")
            entry("xl/worksheets/sheet${i + 1}.xml", x.toString())
        }
        z.finish()
    }
}

/** أوراق Excel للتقرير الشامل. */
fun reportSheets(db: Db, r: FullReport): List<Pair<String, List<List<Any?>>>> {
    val summary = listOf<List<Any?>>(
        listOf("البند", "القيمة"),
        listOf("الشبكة", db.setting("network_name", "الشبكة")),
        listOf("الفترة", "${r.from}  إلى  ${r.to}"),
        listOf("الإيرادات (المقبوض)", r.revenue),
        listOf("المستحقات المقيَّدة", r.charges),
        listOf("الخصومات", r.discounts),
        listOf("المصاريف", r.expenses),
        listOf("الصافي", r.profit),
        listOf("إجمالي المشتركين", r.totalSubs),
        listOf("فعّال / مسدّد", r.activeSubs),
        listOf("منتهي", r.expiredSubs),
        listOf("عليهم دين", r.debtSubs),
        listOf("مشتركون جدد في الفترة", r.newSubs),
        listOf("إجمالي الديون الحالية", r.totalDebt),
    )
    return listOf(
        "الملخص" to summary,
        "التحصيل حسب الموظف" to (listOf<List<Any?>>(listOf("الموظف", "المبلغ", "عدد الدفعات")) + r.byCollector.map { listOf<Any?>(it.first, it.second, it.third) }),
        "الإيرادات اليومية" to (listOf<List<Any?>>(listOf("التاريخ", "المبلغ")) + r.byDay.map { listOf<Any?>(it.first, it.second) }),
        "المصاريف" to (listOf<List<Any?>>(listOf("البند", "المبلغ")) + r.expenseByCat.map { listOf<Any?>(it.first, it.second) }),
        "المدينون" to (listOf<List<Any?>>(listOf("الاسم", "الهاتف", "المنطقة", "الدين")) + r.debtors.map { listOf<Any?>(it.name, it.phone ?: "", it.area ?: "", it.bal) }),
        "الدفعات" to (listOf<List<Any?>>(listOf("التاريخ", "المشترك", "المبلغ", "الموظف", "ملاحظة")) + r.payments),
    )
}

// ======================= PDF =======================
object PdfReport {
    private const val W = 595
    private const val H = 842
    private const val M = 36f

    private class Pager(val doc: PdfDocument) {
        var no = 0
        lateinit var page: PdfDocument.Page
        lateinit var cv: Canvas
        var y = M
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; color = Color.BLACK }
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.6f }
        val fill = Paint()
        init { newPage() }

        fun newPage() {
            if (no > 0) doc.finishPage(page)
            no++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, no).create())
            cv = page.canvas
            y = M
        }
        fun ensure(h: Float) { if (y + h > H - M) newPage() }

        fun text(t: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK, gap: Float = 6f) {
            p.textSize = size; p.isFakeBoldText = bold; p.color = color
            ensure(size + gap)
            y += size
            cv.drawText(t, W - M, y, p)
            y += gap
        }

        fun row(cells: List<String>, widths: List<Float>, bold: Boolean = false, bg: Int? = null) {
            val h = 19f
            ensure(h)
            if (bg != null) { fill.color = bg; cv.drawRect(M, y, W - M, y + h, fill) }
            p.textSize = 10f; p.isFakeBoldText = bold; p.color = Color.BLACK
            var x = W - M
            cells.forEachIndexed { i, c ->
                val w = widths[i]
                val n = p.breakText(c, true, w - 6f, null)
                cv.drawText(c.substring(0, n), x - 3f, y + 13f, p)
                x -= w
            }
            cv.drawLine(M, y + h, W - M, y + h, line)
            y += h
        }

        fun gap(h: Float) { y += h }
        fun finish(out: OutputStream) { doc.finishPage(page); doc.writeTo(out); doc.close() }
    }

    fun write(db: Db, out: OutputStream, r: FullReport) {
        val pg = Pager(PdfDocument())
        val full = (W - 2 * M)
        fun table(title: String, head: List<String>, widths: List<Float>, rows: List<List<String>>) {
            pg.gap(8f)
            pg.text(title, 13f, bold = true, color = Color.rgb(30, 64, 175))
            pg.row(head, widths, bold = true, bg = Color.rgb(226, 232, 240))
            if (rows.isEmpty()) pg.row(listOf("لا توجد بيانات"), listOf(full))
            rows.forEach { pg.row(it, widths) }
        }
        pg.text("تقرير الاشتراكات", 20f, bold = true, gap = 4f)
        pg.text(db.setting("network_name", "الشبكة"), 13f, gap = 2f)
        pg.text("الفترة: ${r.from}  إلى  ${r.to}", 11f, color = Color.DKGRAY, gap = 8f)

        val two = listOf(full * 0.6f, full * 0.4f)
        pg.text("الملخص المالي", 13f, bold = true, color = Color.rgb(30, 64, 175))
        listOf(
            "الإيرادات (المقبوض)" to fmt(r.revenue), "المستحقات المقيَّدة" to fmt(r.charges), "الخصومات" to fmt(r.discounts),
            "المصاريف" to fmt(r.expenses), "الصافي" to fmt(r.profit),
        ).forEach { (a, b) -> pg.row(listOf(a, b), two) }
        pg.gap(6f)
        pg.text("المشتركون", 13f, bold = true, color = Color.rgb(30, 64, 175))
        listOf(
            "إجمالي المشتركين" to r.totalSubs.toString(), "فعّال / مسدّد" to r.activeSubs.toString(), "منتهي" to r.expiredSubs.toString(),
            "عليهم دين" to r.debtSubs.toString(), "جدد في الفترة" to r.newSubs.toString(), "إجمالي الديون الحالية" to fmt(r.totalDebt),
        ).forEach { (a, b) -> pg.row(listOf(a, b), two) }

        table("التحصيل حسب الموظف", listOf("الموظف", "المبلغ", "عدد الدفعات"), listOf(full * 0.5f, full * 0.3f, full * 0.2f),
            r.byCollector.map { listOf(it.first, fmt(it.second), it.third.toString()) })
        table("المصاريف حسب البند", listOf("البند", "المبلغ"), two, r.expenseByCat.map { listOf(it.first, fmt(it.second)) })
        table("أكبر المدينين", listOf("الاسم", "الهاتف", "المنطقة", "الدين"), listOf(full * 0.34f, full * 0.24f, full * 0.22f, full * 0.2f),
            r.debtors.take(25).map { listOf(it.name, it.phone ?: "", it.area ?: "", fmt(it.bal)) })
        table("الإيرادات اليومية", listOf("التاريخ", "المبلغ"), two, r.byDay.map { listOf(it.first, fmt(it.second)) })
        pg.finish(out)
    }

    /** كشف حساب فردي قابل للحفظ والمشاركة بصيغة PDF. */
    fun writeStatement(db: Db, sub: Sub, out: OutputStream) {
        val pg = Pager(PdfDocument())
        val full = W - 2 * M
        pg.text("كشف حساب مشترك", 20f, bold = true, gap = 4f)
        pg.text(db.setting("network_name", "الشبكة"), 13f, gap = 3f)
        pg.text("اسم المشترك: ${sub.name}", 12f, bold = true)
        pg.text("الهاتف: ${sub.phone.orEmpty()}    المستخدم: ${sub.e1.orEmpty()}", 10f)
        pg.text("الباقة: ${sub.pkg.orEmpty()}    الانتهاء: ${sub.endDate.orEmpty()}", 10f)
        pg.text("تاريخ إصدار الكشف: ${today()}", 10f, color = Color.DKGRAY)
        pg.gap(8f)
        pg.row(listOf("الرصيد الحالي (دينار)", fmt(db.balance(sub.id))), listOf(full * .65f, full * .35f), bold = true, bg = Color.rgb(226, 232, 240))
        pg.gap(8f)
        pg.text("الحركات المالية", 13f, bold = true, color = Color.rgb(30, 64, 175))
        val widths = listOf(full * .17f, full * .22f, full * .20f, full * .41f)
        pg.row(listOf("رقم", "التاريخ", "النوع", "المبلغ / الملاحظة"), widths, bold = true, bg = Color.rgb(226, 232, 240))
        val rows = db.statement(sub.id)
        if (rows.isEmpty()) pg.row(listOf("—", "—", "لا توجد حركات", "—"), widths)
        rows.forEach { t ->
            val amount = (if (t.cancelled) "ملغاة — " else "") + fmt(t.amount) + (if (t.note.isNullOrBlank()) "" else " / ${t.note}")
            pg.row(listOf(t.id.toString(), t.date, TYPE_AR[t.type] ?: t.type, amount), widths)
        }
        pg.gap(10f)
        pg.text("هذا الكشف مستخرج من سجل التطبيق المحلي.", 9f, color = Color.DKGRAY)
        pg.finish(out)
    }

}
