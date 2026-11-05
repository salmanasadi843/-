package com.example.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendBrowseVisibilityTest {
    @Test
    fun `published public class remains visible when its teacher folder is hidden`() {
        assertTrue(
            isClassVisibleInTeacherBrowse(
                folderId = "hidden-folder",
                visibility = "public",
                isPublished = true,
                isHidden = false,
                canSeeHiddenFolders = false,
                visibleFolderIds = emptySet()
            )
        )
    }

    @Test
    fun `private or unpublished class does not bypass hidden folder for non-owner`() {
        assertFalse(
            isClassVisibleInTeacherBrowse(
                folderId = "hidden-folder",
                visibility = "private",
                isPublished = true,
                isHidden = false,
                canSeeHiddenFolders = false,
                visibleFolderIds = emptySet()
            )
        )
        assertFalse(
            isClassVisibleInTeacherBrowse(
                folderId = "hidden-folder",
                visibility = "public",
                isPublished = false,
                isHidden = false,
                canSeeHiddenFolders = false,
                visibleFolderIds = emptySet()
            )
        )
        assertFalse(
            isClassVisibleInTeacherBrowse(
                folderId = "hidden-folder",
                visibility = "public",
                isPublished = true,
                isHidden = true,
                canSeeHiddenFolders = false,
                visibleFolderIds = emptySet()
            )
        )
    }

    @Test
    fun `visible folders and unfiled classes remain visible`() {
        assertTrue(
            isClassVisibleInTeacherBrowse(
                folderId = "visible-folder",
                visibility = "private",
                isPublished = false,
                isHidden = false,
                canSeeHiddenFolders = false,
                visibleFolderIds = setOf("visible-folder")
            )
        )
        assertTrue(
            isClassVisibleInTeacherBrowse(
                folderId = null,
                visibility = "private",
                isPublished = false,
                isHidden = true,
                canSeeHiddenFolders = false,
                visibleFolderIds = emptySet()
            )
        )
    }
}
