// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import helium314.keyboard.latin.ClipboardHistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardListFilterTest {
    private fun entry(id: Long, text: String?, folderId: Long? = null, filename: String? = null) =
        ClipboardHistoryEntry(id, id, false, text, filename, null, folderId)

    private val all = listOf(
        entry(1, "Hello world"),
        entry(2, "Work note", folderId = 10),
        entry(3, null, filename = "image.png"),
        entry(4, "hello again", folderId = 11),
    )

    private fun ids(folder: FolderFilter, query: String? = null) =
        ClipboardListFilter.visible(all, folder, query).map { it.id }

    @Test
    fun allShowsEveryClipInOrder() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(FolderFilter.All))
    }

    @Test
    fun unfiledShowsClipsWithoutFolder() {
        assertEquals(listOf(1L, 3L), ids(FolderFilter.Unfiled))
    }

    @Test
    fun folderShowsOnlyItsClips() {
        assertEquals(listOf(2L), ids(FolderFilter.Folder(10)))
    }

    @Test
    fun searchIgnoresCaseAndSkipsImages() {
        assertEquals(listOf(1L, 4L), ids(FolderFilter.All, "HELLO"))
    }

    @Test
    fun searchLooksAtAllFoldersNotOnlyTheSelectedOne() {
        assertEquals(listOf(1L, 4L), ids(FolderFilter.Folder(10), "hello"))
    }

    @Test
    fun blankSearchMeansNoSearch() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(FolderFilter.All, "   "))
    }
}
