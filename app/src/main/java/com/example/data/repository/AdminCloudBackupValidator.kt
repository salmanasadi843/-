package com.example.data.repository

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validates an admin aggregate backup before any restore write is attempted.
 * Only schema-3 teacher generations are eligible for automatic restoration;
 * legacy formats remain exportable but require a dedicated migration path.
 */
object AdminCloudBackupValidator {
    private const val FORMAT = "darsyar-admin-cloud-backup"
    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 25 * 1024 * 1024
    private const val MAX_TEACHERS = 500
    private const val MAX_RECORDS = 100_000

    data class TeacherBackup(
        val ownerUid: String,
        val generationId: String,
        val root: JSONObject,
        val records: List<Record>
    )

    data class Record(val documentId: String, val data: JSONObject)

    data class ValidatedBackup(val teachers: List<TeacherBackup>, val recordCount: Int)

    fun validate(jsonText: String): ValidatedBackup {
        require(jsonText.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "حجم فایل پشتیبان بیش از ۲۵ مگابایت است."
        }
        val input = JSONObject(jsonText)
        require(input.optString("format") == FORMAT) { "قالب فایل پشتیبان درس‌یار معتبر نیست." }
        require(input.optInt("version", -1) == VERSION) { "نسخهٔ فایل پشتیبان پشتیبانی نمی‌شود." }

        val teacherArray = input.optJSONArray("teachers")
            ?: throw IllegalArgumentException("فهرست استادان در فایل پشتیبان وجود ندارد.")
        require(teacherArray.length() in 1..MAX_TEACHERS) { "تعداد استادان فایل پشتیبان نامعتبر است." }
        require(input.optInt("teacherCount", -1) == teacherArray.length()) {
            "تعداد استادان با محتوای فایل تطبیق ندارد."
        }

        val seenOwners = mutableSetOf<String>()
        val teachers = ArrayList<TeacherBackup>(teacherArray.length())
        var totalRecords = 0

        for (i in 0 until teacherArray.length()) {
            val entry = teacherArray.optJSONObject(i)
                ?: throw IllegalArgumentException("ساختار استاد شمارهٔ ${i + 1} نامعتبر است.")
            val ownerUid = entry.optString("ownerUid").trim()
            require(ownerUid.isNotEmpty() && ownerUid !in seenOwners) {
                "شناسهٔ استاد خالی یا تکراری است."
            }
            seenOwners += ownerUid
            val schema = entry.optInt("schemaVersion", -1)
            require(schema == 3) {
                "پشتیبان استاد $ownerUid نسخهٔ $schema است؛ بازیابی خودکار فقط نسخهٔ ۳ را می‌پذیرد."
            }

            val root = entry.optJSONObject("root")
                ?: throw IllegalArgumentException("سند اصلی استاد $ownerUid موجود نیست.")
            val rootOwner = root.optString("ownerUid")
            require(rootOwner == ownerUid) { "مالک سند اصلی استاد $ownerUid با شناسهٔ فایل تطبیق ندارد." }
            val generation = root.optString("activeGeneration").trim()
            require(generation.isNotEmpty()) { "نسخهٔ فعال استاد $ownerUid مشخص نیست." }

            val recordsArray = entry.optJSONArray("records")
                ?: throw IllegalArgumentException("رکوردهای استاد $ownerUid وجود ندارد.")
            val expected = root.optInt("recordCount", -1)
            require(expected >= 0 && expected == recordsArray.length()) {
                "تعداد رکوردهای استاد $ownerUid ناقص یا ناسازگار است."
            }
            totalRecords += recordsArray.length()
            require(totalRecords <= MAX_RECORDS) { "تعداد رکوردهای فایل بیش از حد مجاز است." }

            val seenIds = mutableSetOf<String>()
            val records = ArrayList<Record>(recordsArray.length())
            for (j in 0 until recordsArray.length()) {
                val record = recordsArray.optJSONObject(j)
                    ?: throw IllegalArgumentException("رکورد ${j + 1} استاد $ownerUid نامعتبر است.")
                val id = record.optString("documentId").trim()
                require(id.isNotEmpty() && id !in seenIds) {
                    "شناسهٔ رکورد خالی یا تکراری در پشتیبان استاد $ownerUid."
                }
                seenIds += id
                val data = record.optJSONObject("data")
                    ?: throw IllegalArgumentException("دادهٔ رکورد $id استاد $ownerUid موجود نیست.")
                require(data.optString("ownerUid") == ownerUid) {
                    "مالک رکورد $id با استاد $ownerUid یکسان نیست؛ بازیابی متوقف شد."
                }
                records += Record(id, JSONObject(data.toString()))
            }
            teachers += TeacherBackup(ownerUid, generation, JSONObject(root.toString()), records)
        }

        require(input.optInt("recordCount", -1) == totalRecords) {
            "شمار کل رکوردها با محتوای فایل پشتیبان تطبیق ندارد."
        }
        return ValidatedBackup(teachers, totalRecords)
    }
}
