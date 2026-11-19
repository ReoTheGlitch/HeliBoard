// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import helium314.keyboard.latin.ClipboardHistoryEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** what happened on import */
class ClipboardImportResult(val added: Int, val moved: Int, val unchanged: Int)

/** Clipboard history and folders as a JSON file. Only text clips are in the file, images are not. */
@Serializable
class ClipboardExport(
    // tells a person or an AI assistant how the file can be edited to organize the clips, ignored on import
    val instructions: String = "",
    val version: Int = 1,
    val folders: List<String> = emptyList(),
    val clips: List<ExportedClip> = emptyList(),
)

@Serializable
class ExportedClip(
    val id: Long? = null, // id of the clip on the device that wrote the file, used to find the clip again on import
    val text: String,
    val timestamp: Long = 0L,
    val pinned: Boolean = false,
    val folder: String? = null, // folder name, null if the clip is in no folder
)

object ClipboardJson {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; isLenient = true }

    const val INSTRUCTIONS = "This is a clipboard history. To organize it, set \"folder\" of each clip to a folder name. " +
        "Use short folder names (at most 40 characters), new names are allowed and create the folder. " +
        "null means no folder and leaves the clip where it is. " +
        "Do not change \"id\", \"text\" or \"timestamp\" and do not remove clips. " +
        "Return the complete file as valid JSON. Importing it moves the clips into the folders, nothing is deleted."

    /** with [onlyUnfiled] only the clips in no folder are exported, all folder names are still listed */
    fun toExport(entries: List<ClipboardHistoryEntry>, folders: List<ClipboardFolder>, onlyUnfiled: Boolean = false): ClipboardExport {
        val folderNames = folders.associate { it.id to it.name }
        return ClipboardExport(
            instructions = INSTRUCTIONS,
            folders = folders.map { it.name },
            clips = entries.filter { it.filename == null && it.text != null && (!onlyUnfiled || it.folderId == null) }
                .map { ExportedClip(it.id, it.text!!, it.timeStamp, it.isPinned, it.folderId?.let { id -> folderNames[id] }) }
        )
    }

    fun encode(export: ClipboardExport): String = json.encodeToString(ClipboardExport.serializer(), export)

    /**
     * throws if the text is not a clipboard export.
     * Text around the JSON is ignored, AI assistants often wrap the file in a code block.
     */
    fun decode(text: String): ClipboardExport {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object" }
        return json.decodeFromString(ClipboardExport.serializer(), text.substring(start, end + 1))
    }
}
