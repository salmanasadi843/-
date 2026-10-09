package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.data.local.LectureEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class LectureExportFormat { WORD, PDF, TEXT }

object LectureExport {
    fun share(context: Context, lectures: List<LectureEntity>, format: LectureExportFormat, title: String = "اشتراک‌گذاری مطالب درس‌یار") {
        require(lectures.isNotEmpty()) { "هیچ جلسه‌ای برای خروجی انتخاب نشده است." }
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = when (format) {
            LectureExportFormat.WORD -> File(directory, "darsyar-${System.currentTimeMillis()}.docx").also { createWord(it, lectures) }
            LectureExportFormat.PDF -> File(directory, "darsyar-${System.currentTimeMillis()}.pdf").also { createPdf(it, lectures) }
            LectureExportFormat.TEXT -> File(directory, "darsyar-${System.currentTimeMillis()}.txt").also { it.writeText(plainText(lectures), Charsets.UTF_8) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = when (format) {
            LectureExportFormat.WORD -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            LectureExportFormat.PDF -> "application/pdf"
            LectureExportFormat.TEXT -> "text/plain"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun plainText(lectures: List<LectureEntity>): String = lectures
        .sortedBy { it.dateMillis }
        .joinToString("\n\n════════════════════════════\n\n") { lecture ->
            buildString {
                appendLine(lecture.title)
                if (lecture.courseName.isNotBlank()) appendLine("درس: ${lecture.courseName}")
                if (lecture.professorName.isNotBlank()) appendLine("استاد: ${lecture.professorName}")
                appendLine("تاریخ: ${formatDate(lecture.dateMillis)}")
                if (!lecture.aiSummary.isNullOrBlank()) {
                    appendLine()
                    appendLine("خلاصه هوشمند")
                    appendLine(lecture.aiSummary)
                }
                if (!lecture.aiKeyPoints.isNullOrBlank()) {
                    appendLine()
                    appendLine("کلیدواژه‌ها")
                    appendLine(lecture.aiKeyPoints)
                }
                if (lecture.transcript.isNotBlank()) {
                    appendLine()
                    appendLine("متن جزوه")
                    appendLine(lecture.transcript)
                }
                if (lecture.tags.isNotBlank()) {
                    appendLine()
                    appendLine("برچسب‌ها: ${lecture.tags}")
                }
            }.trim()
        }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd", Locale("fa", "IR")).format(Date(millis))

    private fun createWord(file: File, lectures: List<LectureEntity>) {
        val paragraphs = plainText(lectures).split("\n").joinToString("") { line ->
            val safe = xmlEscape(line)
            if (safe.isBlank()) "<w:p/>" else
                "<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:rPr><w:rtl/></w:rPr><w:t xml:space=\"preserve\">$safe</w:t></w:r></w:p>"
        }
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="900" w:right="900" w:bottom="900" w:left="900"/></w:sectPr></w:body></w:document>""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun createPdf(file: File, lectures: List<LectureEntity>) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 42
        val text = plainText(lectures)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, pageWidth - margin * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(true)
            .setLineSpacing(4f, 1.15f)
            .build()
        val contentHeight = pageHeight - margin * 2
        val pageCount = (layout.height + contentHeight - 1) / contentHeight
        for (index in 0 until pageCount.coerceAtLeast(1)) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create())
            page.canvas.save()
            page.canvas.translate(margin.toFloat(), margin.toFloat() - index * contentHeight)
            layout.draw(page.canvas)
            page.canvas.restore()
            document.finishPage(page)
        }
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}