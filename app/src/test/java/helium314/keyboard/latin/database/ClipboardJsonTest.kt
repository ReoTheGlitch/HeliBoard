// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ClipboardJsonTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun exportHasTextClipsAndFoldersButNoImages() {
        val dao = newDao(context, "export-${UUID.randomUUID()}.db")
        val work = assertNotNull(dao.createFolder("Work"))
        dao.createFolder("Empty")
        dao.insertNewEntry(1000, false, "plain", null, null, null)
        dao.insertNewEntry(2000, true, "in \"work\"\nline 2", null, null, null, work.id)
        dao.insertNewEntry(3000, false, "image label", "abc.png", listOf("image/png"), null)

        val export = ClipboardJson.decode(ClipboardJson.encode(ClipboardJson.toExport(dao.getAll(), dao.getFolders())))
        assertEquals(listOf("Work", "Empty"), export.folders)
        assertEquals(setOf("plain", "in \"work\"\nline 2"), export.clips.map { it.text }.toSet())
        val inWork = export.clips.single { it.folder == "Work" }
        assertEquals(2000, inWork.timestamp)
        assertTrue(inWork.pinned)
        assertNull(export.clips.single { it.text == "plain" }.folder)
    }

    @Test
    fun importMergesAndSkipsDuplicates() {
        val source = newDao(context, "source-${UUID.randomUUID()}.db")
        val work = assertNotNull(source.createFolder("Work"))
        source.insertNewEntry(1000, false, "shared", null, null, null, work.id)
        source.insertNewEntry(2000, true, "only in file", null, null, null, work.id)
        val json = ClipboardJson.encode(ClipboardJson.toExport(source.getAll(), source.getFolders()))

        val target = newDao(context, "target-${UUID.randomUUID()}.db")
        val existingFolder = assertNotNull(target.createFolder("work")) // same folder, other case
        target.insertNewEntry(500, true, "shared", null, null, null)
        target.insertNewEntry(600, false, "only here", null, null, null)

        assertEquals(1, target.importClips(ClipboardJson.decode(json)).added)
        assertEquals(setOf("shared", "only here", "only in file"), target.getAll().map { it.text }.toSet())
        // the existing clip is not changed
        val shared = target.getAll().single { it.text == "shared" }
        assertEquals(500, shared.timeStamp)
        assertTrue(shared.isPinned)
        assertEquals(existingFolder.id, shared.folderId) // but it is moved to the folder it has in the file
        // the new clip keeps date, pin and folder, the folder is matched by name
        val imported = target.getAll().single { it.text == "only in file" }
        assertEquals(2000, imported.timeStamp)
        assertTrue(imported.isPinned)
        assertEquals(existingFolder.id, imported.folderId)
        assertEquals(1, target.getFolders().size)

        val again = target.importClips(ClipboardJson.decode(json)) // importing again changes nothing
        assertEquals(0, again.added)
        assertEquals(0, again.moved)
        assertEquals(2, again.unchanged)
        assertEquals(3, target.getAll().size)
    }

    @Test
    fun importedClipsSurviveRestart() {
        val name = "import-restart-${UUID.randomUUID()}.db"
        val dao = newDao(context, name)
        val export = ClipboardExport(folders = listOf("A"), clips = listOf(ExportedClip(null, "one", 1, false, "A"), ExportedClip(null, "two", 2)))
        assertEquals(2, dao.importClips(export).added)

        val restarted = newDao(context, name)
        assertEquals(listOf("two", "one"), restarted.getAll().map { it.text })
        assertEquals(restarted.getFolders().single().id, restarted.getAll().single { it.text == "one" }.folderId)
    }

    @Test
    fun editedExportOrganizesTheHistory() {
        val dao = newDao(context, "organize-${UUID.randomUUID()}.db")
        val old = assertNotNull(dao.createFolder("Old"))
        dao.insertNewEntry(1000, true, "https://example.org/a", null, null, null)
        dao.insertNewEntry(2000, false, "buy milk", null, null, null, old.id)
        dao.insertNewEntry(3000, false, "stays", null, null, null, old.id)
        val exported = ClipboardJson.encode(ClipboardJson.toExport(dao.getAll(), dao.getFolders()))
        assertTrue(exported.contains("\"instructions\"") && exported.contains("\"folder\": null"))

        // what an AI assistant returns: folders set, wrapped in a code block with some text around it
        val edited = "Here is your file:\n```json\n" + exported
            .replace(Regex("(\"text\": \"https://example.org/a\",[^}]*?\"folder\": )null"), "$1\"Links\"")
            .replace(Regex("(\"text\": \"buy milk\",[^}]*?\"folder\": )\"Old\""), "$1\"Shopping\"") + "\n```\nDone!"
        val result = dao.importClips(ClipboardJson.decode(edited))
        assertEquals(0, result.added)
        assertEquals(2, result.moved)
        assertEquals(1, result.unchanged)

        fun folderOf(text: String) = dao.getFolders().single { it.id == dao.getAll().single { clip -> clip.text == text }.folderId }.name
        assertEquals("Links", folderOf("https://example.org/a"))
        assertEquals("Shopping", folderOf("buy milk"))
        assertEquals("Old", folderOf("stays"))
        assertEquals(3, dao.getAll().size)
        assertTrue(dao.getAll().single { it.text == "https://example.org/a" }.isPinned) // pin and date are kept
        assertEquals(1000, dao.getAll().single { it.text == "https://example.org/a" }.timeStamp)
    }

    @Test
    fun otherFilesAreRejected() {
        assertFailsWith<Exception> { ClipboardJson.decode("not json") }
        assertFailsWith<Exception> { ClipboardJson.decode("[1, 2]") }
    }

    private fun newDao(context: Context, name: String): ClipboardDao {
        val dbCtor = Database::class.java.getDeclaredConstructor(Context::class.java, String::class.java)
        dbCtor.isAccessible = true
        val db = dbCtor.newInstance(context, name)
        val daoCtor = ClipboardDao::class.java.getDeclaredConstructor(Database::class.java)
        daoCtor.isAccessible = true
        return daoCtor.newInstance(db)
    }
}
