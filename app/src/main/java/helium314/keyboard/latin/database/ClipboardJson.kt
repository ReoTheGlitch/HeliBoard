// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import helium314.keyboard.latin.ClipboardHistoryEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Clipboard history and folders as a JSON file. Only text clips are in the file, images are not. */
@Serializable
class ClipboardExport(
    val version: Int = 1,
    val folders: List<String> = emptyList(),
    val clips: List<ExportedClip> = emptyList(),
)

@Serializable
class ExportedClip(
    val text: String,
    val timestamp: Long = 0L,
    val pinned: Boolean = false,
    val folder: String? = null, // folder name, null if the clip is in no folder
)

object ClipboardJson {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun toExport(entries: List<ClipboardHistoryEntry>, folders: List<ClipboardFolder>): ClipboardExport {
        val folderNames = folders.associate { it.id to it.name }
        return ClipboardExport(
            folders = folders.map { it.name },
            clips = entries.filter { it.filename == null && it.text != null }
                .map { ExportedClip(it.text!!, it.timeStamp, it.isPinned, it.folderId?.let { id -> folderNames[id] }) }
        )
    }

    fun encode(export: ClipboardExport): String = json.encodeToString(ClipboardExport.serializer(), export)

    /** throws if the text is not a clipboard export */
    fun decode(text: String): ClipboardExport = json.decodeFromString(ClipboardExport.serializer(), text)
}
