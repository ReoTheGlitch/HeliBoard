// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import helium314.keyboard.latin.ClipboardHistoryEntry

/** which clips are shown in the clipboard panel */
sealed interface FolderFilter {
    data object All : FolderFilter
    data object Unfiled : FolderFilter
    data class Folder(val id: Long) : FolderFilter
}

object ClipboardListFilter {
    /**
     * Returns the clips to show, in the same order as [all].
     * A non-empty [query] only matches text clips, and it looks at all folders.
     */
    fun visible(all: List<ClipboardHistoryEntry>, folder: FolderFilter, query: String?): List<ClipboardHistoryEntry> {
        val searchText = query?.trim().orEmpty()
        if (searchText.isEmpty()) {
            return when (folder) {
                FolderFilter.All -> all.toList()
                FolderFilter.Unfiled -> all.filter { it.folderId == null }
                is FolderFilter.Folder -> all.filter { it.folderId == folder.id }
            }
        }
        return all.filter { it.text?.contains(searchText, ignoreCase = true) == true }
    }

    /** the text of the clips, one per line. Image clips have no text and are left out. */
    fun textToCopy(entries: List<ClipboardHistoryEntry>): String =
        entries.mapNotNull { it.text }.joinToString("\n")
}
