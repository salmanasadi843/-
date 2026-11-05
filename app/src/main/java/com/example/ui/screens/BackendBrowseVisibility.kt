package com.example.ui.screens

/**
 * A hidden teacher folder hides the folder navigation entry, not an independently
 * published public class stored inside it. Private memberships do not override
 * the hidden-folder boundary for non-owners.
 */
internal fun isClassVisibleInTeacherBrowse(
    folderId: String?,
    visibility: String,
    isPublished: Boolean,
    isHidden: Boolean,
    canSeeHiddenFolders: Boolean,
    visibleFolderIds: Set<String>
): Boolean {
    if (folderId == null || folderId in visibleFolderIds) return true
    return !canSeeHiddenFolders &&
        visibility == "public" &&
        isPublished &&
        !isHidden
}
