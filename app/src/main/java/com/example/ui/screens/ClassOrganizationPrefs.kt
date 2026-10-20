package com.example.ui.screens

import android.content.Context

/**
 * Persists each teacher's personal organization settings separately from shared lesson data.
 * These settings never change ownership, publication state, or the source teacher's records.
 */
object ClassOrganizationPrefs {
    private fun prefs(context: Context) =
        context.getSharedPreferences("darsyar_organization_${AuthPreferences.currentUid(context).orEmpty().ifBlank { "local" }}", Context.MODE_PRIVATE)

    fun folders(context: Context): List<String> =
        prefs(context).getString("folders", "").orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotBlank() }.distinct()

    fun createFolder(context: Context, name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank() || folders(context).any { it.equals(clean, true) }) return false
        prefs(context).edit().putString("folders", (folders(context) + clean).joinToString("\n")).apply()
        return true
    }

    fun folderForClass(context: Context, classId: Long): String =
        prefs(context).getString("class_folder_$classId", "").orEmpty()

    fun renameFolder(context: Context, oldName: String, newName: String): Boolean {
        val oldClean = oldName.trim()
        val newClean = newName.trim()
        if (oldClean.isBlank() || newClean.isBlank()) return false
        val current = folders(context)
        if (current.none { it == oldClean }) return false
        if (current.any { it.equals(newClean, true) && !it.equals(oldClean, true) }) return false
        val updated = current.map { if (it == oldClean) newClean else it }
        val editor = prefs(context).edit().putString("folders", updated.joinToString("\n"))
        val all = prefs(context).all
        all.forEach { (key, value) ->
            if (key.startsWith("class_folder_") && value == oldClean) editor.putString(key, newClean)
        }
        editor.apply()
        return true
    }

    fun deleteFolder(context: Context, folder: String) {
        val clean = folder.trim()
        val p = prefs(context)
        val editor = p.edit().putString("folders", folders(context).filterNot { it == clean }.joinToString("\n"))
        p.all.keys.filter { it.startsWith("class_folder_") && p.getString(it, "") == clean }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun moveClassToFolder(context: Context, classId: Long, folder: String) {
        prefs(context).edit().putString("class_folder_$classId", folder).apply()
    }

    fun orderedClasses(context: Context, ids: List<Long>): List<Long> =
        applyOrder(ids, prefs(context).getString("class_order", "").orEmpty())

    fun moveClass(context: Context, ids: List<Long>, fromId: Long, delta: Int) {
        val order = orderedClasses(context, ids).toMutableList()
        val from = order.indexOf(fromId)
        val to = from + delta
        if (from < 0 || to !in order.indices) return
        val item = order.removeAt(from)
        order.add(to, item)
        prefs(context).edit().putString("class_order", order.joinToString(",")).apply()
    }

    fun orderedSessions(context: Context, classId: Long, ids: List<Long>): List<Long> =
        applyOrder(ids, prefs(context).getString("session_order_$classId", "").orEmpty())

    fun moveSession(context: Context, classId: Long, ids: List<Long>, fromId: Long, delta: Int) {
        val order = orderedSessions(context, classId, ids).toMutableList()
        val from = order.indexOf(fromId)
        val to = from + delta
        if (from < 0 || to !in order.indices) return
        val item = order.removeAt(from)
        order.add(to, item)
        prefs(context).edit().putString("session_order_$classId", order.joinToString(",")).apply()
    }

    private fun applyOrder(ids: List<Long>, encoded: String): List<Long> {
        val saved = encoded.split(",").mapNotNull { it.toLongOrNull() }
        return saved.filter { it in ids }.distinct() + ids.filter { it !in saved }
    }
}
