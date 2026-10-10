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
                val summary = lecture.aiSummary?.trim().orEmpty()
                if (summary.isNotBlank()) {
                    appendLine()
                    appendLine("نمای کلی بحث")
                    appendLine(shortOverview(summary))
                }
                if (lecture.transcript.isNotBlank()) {
                    appendLine()
                    appendLine("متن جزوه")
                    appendLine(lecture.transcript)
                }
                if (summary.isNotBlank()) {
                    appendLine()
                    appendLine("خلاصه")
                    appendLine(summary)
                }
                if (!lecture.aiKeyPoints.isNullOrBlank()) {
                    appendLine()
                    appendLine("کلیدواژه‌ها")
                    appendLine(lecture.aiKeyPoints)
                }
                if (lecture.tags.isNotBlank()) {
                    appendLine()
                    appendLine("برچسب‌ها: ${lecture.tags}")
                }
            }.trim()
        }

    private fun formatDate(millis: Long): String = PersianDateUtils.format(millis)

    private fun shortOverview(summary: String): String {
        val cleaned = summary.replace(Regex("\\s+"), " ").trim()
        val sentences = Regex("(?<=[.!؟?])\\s+").split(cleaned)
        return if (sentences.size > 2) sentences.take(2).joinToString(" ") else cleaned.take(360)
    }

    private fun headingLevel(line: String): Int {
        val s = line.trim()
        if (s.startsWith("###")) return 3
        if (s.startsWith("##")) return 2
        if (s.startsWith("#")) return 1
        if (s in setOf("نمای کلی بحث", "متن جزوه", "خلاصه", "خلاصه هوشمند", "کلیدواژه‌ها")) return 1
        if (Regex("^(بخش|فصل|گفتار|مبحث|موضوع اصلی|نتیجه‌گیری|نتیجه گیری|جمع‌بندی|جمع بندی)\\s*[:：0-9۰-۹-]*").containsMatchIn(s)) return 2
        if (Regex("^[0-9۰-۹]+[.)،-]\\s+.{2,90}$").matches(s) && !s.endsWith("؟")) return 2
        if (Regex("^[الف-ی][.)،-]\\s+.{2,80}$").matches(s)) return 3
        return 0
    }

    private fun createWord(file: File, lectures: List<LectureEntity>) {
        val paragraphs = plainText(lectures).split("\n").joinToString("") { raw ->
            val level = headingLevel(raw)
            val line = raw.replace(Regex("^#{1,3}\\s*"), "").trim()
            val safe = xmlEscape(line)
            if (safe.isBlank()) "<w:p/>" else {
                val style = when (level) {
                    1 -> "<w:pStyle w:val=\"Heading1\"/>"
                    2 -> "<w:pStyle w:val=\"Heading2\"/>"
                    3 -> "<w:pStyle w:val=\"Heading3\"/>"
                    else -> ""
                }
                "<w:p><w:pPr><w:bidi/>$style</w:pPr><w:r><w:rPr><w:rtl/></w:rPr><w:t xml:space=\"preserve\">$safe</w:t></w:r></w:p>"
            }
        }
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:sz w:val="24"/><w:rtl/></w:rPr><w:pPr><w:bidi/><w:spacing w:after="100" w:line="300" w:lineRule="auto"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:bidi/><w:keepNext/><w:spacing w:before="300" w:after="140"/></w:pPr><w:rPr><w:b/><w:sz w:val="34"/><w:color w:val="263A63"/><w:rtl/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:bidi/><w:keepNext/><w:spacing w:before="240" w:after="100"/></w:pPr><w:rPr><w:b/><w:sz w:val="29"/><w:color w:val="3F5B86"/><w:rtl/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:bidi/><w:keepNext/><w:spacing w:before="180" w:after="80"/></w:pPr><w:rPr><w:b/><w:sz w:val="25"/><w:color w:val="596579"/><w:rtl/></w:rPr></w:style></w:styles>""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""".toByteArray(Charsets.UTF_8))
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
        val width = pageWidth - margin * 2
        val bottom = pageHeight - margin
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var y = margin.toFloat()
        fun nextPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = margin.toFloat()
        }
        plainText(lectures).split("\n").forEach { raw ->
            val level = headingLevel(raw)
            val line = raw.replace(Regex("^#{1,3}\\s*"), "").trim()
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = when (level) { 1 -> 19f; 2 -> 16f; 3 -> 14f; else -> 12f }
                isFakeBoldText = level > 0
            }
            if (level > 0 && y > margin) y += if (level == 1) 10f else 6f
            val layout = StaticLayout.Builder.obtain(line, 0, line.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(true)
                .setLineSpacing(3f, 1.12f)
                .build()
            if (y + layout.height > bottom && y > margin) nextPage()
            page.canvas.save()
            page.canvas.translate(margin.toFloat(), y)
            layout.draw(page.canvas)
            page.canvas.restore()
            y += layout.height + if (line.isBlank()) 5f else 3f
        }
        document.finishPage(page)
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