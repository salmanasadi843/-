package com.example.data.repository

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validates an admin aggregate backup before any restore write is attempted.
 * Supports validated migration of schema-1 array snapshots and schema-2
 * record collections into a newly staged schema-3 generation.
 */
object AdminCloudBackupValidator {
    private const val FORMAT = "darsyar-admin-cloud-backup"
    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 25 * 1024 * 1024
    private const val MAX_TEACHERS = 500
    private const val MAX_RECORDS = 100_000
    private const val MAX_RECORD_BYTES = 700 * 1024

    data class TeacherBackup(
        val ownerUid: String,
        val schemaVersion: Int,
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
            require(schema in 1..3) {
                "پشتیبان استاد $ownerUid نسخهٔ $schema است؛ نسخه‌های ۱ تا ۳ پشتیبانی می‌شوند."
            }

            val root = entry.optJSONObject("root")
                ?: throw IllegalArgumentException("سند اصلی استاد $ownerUid موجود نیست.")
            val rootOwner = if (root.isNull("ownerUid")) "" else root.optString("ownerUid")
            require(
                if (schema == 3) rootOwner == ownerUid
                else rootOwner.isBlank() || rootOwner == ownerUid
            ) {
                "مالک سند اصلی استاد $ownerUid با شناسهٔ فایل تطبیق ندارد."
            }
            val generation = if (schema == 3) root.optString("activeGeneration").trim() else "legacy-records"
            if (schema == 3) require(generation.isNotEmpty()) { "نسخهٔ فعال استاد $ownerUid مشخص نیست." }

            val folderIds = mutableSetOf<Long>()
            val folderParents = mutableListOf<Long?>()
            val rawFolders = root.opt("folders")
            if (schema == 3) require(rawFolders is org.json.JSONArray) {
                "فهرست پوشه‌های استاد $ownerUid در پشتیبان نسخهٔ ۳ موجود نیست."
            }
            if (rawFolders != null && rawFolders != JSONObject.NULL) {
                val folders = rawFolders as? org.json.JSONArray
                    ?: throw IllegalArgumentException("ساختار پوشه‌های استاد $ownerUid معتبر نیست.")
                for (folderIndex in 0 until folders.length()) {
                    val folder = folders.optJSONObject(folderIndex)
                        ?: throw IllegalArgumentException("پوشهٔ شمارهٔ ${folderIndex + 1} استاد $ownerUid معتبر نیست.")
                    val folderId = folder.optLong("id", Long.MIN_VALUE)
                    require(folderId > 0 && folderIds.add(folderId) &&
                        folder.optString("ownerUid") == ownerUid &&
                        folder.optString("name").isNotBlank()
                    ) { "شناسه، مالک یا نام یکی از پوشه‌های استاد $ownerUid معتبر نیست." }
                    val rawParent = folder.opt("parentFolderId")
                    val parentId = if (rawParent == null || rawParent == JSONObject.NULL) null
                        else (rawParent as? Number)?.toLong()?.takeIf { it > 0 }
                            ?: throw IllegalArgumentException("شناسهٔ پوشهٔ والد برای استاد $ownerUid معتبر نیست.")
                    folderParents += parentId
                }
                require(folderParents.filterNotNull().all { it in folderIds }) {
                    "یکی از پوشه‌های استاد $ownerUid به پوشهٔ والد ناموجود اشاره می‌کند."
                }
            }

            val recordsArray = entry.optJSONArray("records")
                ?: throw IllegalArgumentException("رکوردهای استاد $ownerUid وجود ندارد.")
            val expected = if (schema == 1) recordsArray.length() else root.optInt("recordCount", -1)
            require(expected >= 0 && expected == recordsArray.length()) {
                "تعداد رکوردهای استاد $ownerUid ناقص یا ناسازگار است."
            }
            totalRecords += recordsArray.length()
            require(totalRecords <= MAX_RECORDS) { "تعداد رکوردهای فایل بیش از حد مجاز است." }

            val seenIds = mutableSetOf<String>()
            val classIds = mutableSetOf<Long>()
            val courseClassIds = mutableListOf<Long>()
            val lectureClassIds = mutableListOf<Long?>()
            val lectureDataById = mutableMapOf<Long, JSONObject>()
            val chunksByLecture = mutableMapOf<Long, MutableMap<String, MutableMap<Int, String>>>()
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
                require(data.toString().toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) {
                    "رکورد $id استاد $ownerUid بیش از اندازهٔ مجاز است؛ بازیابی برای جلوگیری از خطای ناقص متوقف شد."
                }
                val recordType = data.optString("recordType")
                require(recordType in setOf("class", "course", "lecture", "lecture_text_chunk")) {
                    "نوع رکورد $id استاد $ownerUid پشتیبانی نمی‌شود."
                }
                val recordOwner = if (data.isNull("ownerUid")) "" else data.optString("ownerUid")
                val ownerRequired = schema == 3 || recordType == "class" || recordType == "lecture_text_chunk"
                require(
                    if (ownerRequired) recordOwner == ownerUid
                    else recordOwner.isBlank() || recordOwner == ownerUid
                ) {
                    "مالک رکورد $id با استاد $ownerUid یکسان نیست؛ بازیابی متوقف شد."
                }
                // Legacy schema-two course/lecture records did not always carry ownerUid.
                // Bind missing ownership to the teacher UID from the enclosing backup.
                if (recordOwner.isBlank()) data.put("ownerUid", ownerUid)
                when (recordType) {
                    "class" -> {
                        val entityId = data.optLong("id", Long.MIN_VALUE)
                        require(entityId > 0 && id == "class_$entityId" && classIds.add(entityId)) {
                            "شناسهٔ کلاس $id با محتوای رکورد سازگار یا یکتا نیست."
                        }
                        val rawFolderId = data.opt("folderId")
                        if (rawFolderId != null && rawFolderId != JSONObject.NULL) {
                            val folderId = (rawFolderId as? Number)?.toLong()
                            require(folderId != null && folderId in folderIds) {
                                "پوشهٔ ارجاع‌شده در کلاس $id برای استاد $ownerUid وجود ندارد."
                            }
                        }
                    }
                    "course" -> {
                        val entityId = data.optLong("id", Long.MIN_VALUE)
                        val classId = data.optLong("classId", Long.MIN_VALUE)
                        require(entityId > 0 && id == "course_$entityId" && classId > 0) {
                            "شناسه یا ارتباط کلاسِ درس $id معتبر نیست."
                        }
                        courseClassIds += classId
                    }
                    "lecture" -> {
                        val entityId = data.optLong("id", Long.MIN_VALUE)
                        require(entityId > 0 && id == "lecture_$entityId" && entityId !in lectureDataById) {
                            "شناسهٔ جلسه $id با محتوای رکورد سازگار یا یکتا نیست."
                        }
                        lectureDataById[entityId] = data
                        val rawClassId = data.opt("classId")
                        val lectureClassId = if (rawClassId == null || rawClassId == JSONObject.NULL) {
                            null
                        } else {
                            (rawClassId as? Number)?.toLong()?.takeIf { it > 0 }
                                ?: throw IllegalArgumentException("شناسهٔ کلاس جلسه $id معتبر نیست.")
                        }
                        lectureClassIds += lectureClassId
                    }
                    "lecture_text_chunk" -> {
                        val lectureId = data.optLong("lectureId", Long.MIN_VALUE)
                        val field = data.optString("field")
                        val index = data.optInt("index", -1)
                        val allowedFields = setOf("transcript", "originalTranscript", "aiSummary", "aiKeyPoints", "aiQuizJson", "tags")
                        require(lectureId > 0 && field in allowedFields && index >= 0 &&
                            data.opt("value") is String &&
                            id == "lecture_text_${lectureId}_${field}_${index}"
                        ) { "بخش متن $id معتبر نیست." }
                        val parts = chunksByLecture.getOrPut(lectureId) { mutableMapOf() }
                            .getOrPut(field) { mutableMapOf() }
                        require(parts.put(index, data.getString("value")) == null) {
                            "بخش تکراری متن جلسه در رکورد $id."
                        }
                    }
                }
                records += Record(id, JSONObject(data.toString()))
            }
            require(courseClassIds.all { it in classIds } &&
                lectureClassIds.filterNotNull().all { it in classIds }
            ) { "ارتباط درس‌ها یا جلسه‌ها با کلاس‌های پشتیبان استاد $ownerUid ناقص است." }
            require(chunksByLecture.keys.all { it in lectureDataById.keys }) {
                "بخش متنی به جلسه‌ای ناموجود در پشتیبان استاد $ownerUid متصل شده است."
            }
            lectureDataById.forEach { (lectureId, lecture) ->
                val manifestValue = lecture.opt("textChunkCounts")
                val rawManifest = when {
                    manifestValue == null || manifestValue == JSONObject.NULL -> null
                    manifestValue is JSONObject -> manifestValue
                    else -> throw IllegalArgumentException("فهرست بخش‌های متن جلسه $lectureId معتبر نیست.")
                }
                val manifest = mutableMapOf<String, Int>()
                if (rawManifest != null) {
                    val keys = rawManifest.keys()
                    while (keys.hasNext()) {
                        val field = keys.next()
                        val countValue = rawManifest.opt(field)
                        require(field in setOf("transcript", "originalTranscript", "aiSummary", "aiKeyPoints", "aiQuizJson", "tags") &&
                            countValue is Number && countValue.toInt() > 0
                        ) { "فهرست بخش‌های متن جلسه $lectureId معتبر نیست." }
                        manifest[field] = (countValue as Number).toInt()
                    }
                }
                val actual = chunksByLecture[lectureId].orEmpty()
                require(actual.keys == manifest.keys) {
                    "فهرست بخش‌های متن جلسه $lectureId با رکوردهای موجود سازگار نیست."
                }
                manifest.forEach { (field, count) ->
                    val parts = actual[field].orEmpty()
                    require(parts.size == count && (0 until count).all { it in parts }) {
                        "بخش‌های متن فیلد $field از جلسه $lectureId ناقص است."
                    }
                }
            }
            teachers += TeacherBackup(ownerUid, schema, generation, JSONObject(root.toString()), records)
        }

        require(input.optInt("recordCount", -1) == totalRecords) {
            "شمار کل رکوردها با محتوای فایل پشتیبان تطبیق ندارد."
        }
        return ValidatedBackup(teachers, totalRecords)
    }
}
