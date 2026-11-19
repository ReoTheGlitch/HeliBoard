// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ClipboardFolderTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun createAndRenameFolders() {
        val dao = newDao(context, "folders-${UUID.randomUUID()}.db")
        val work = dao.createFolder("  Work   stuff ")
        assertNotNull(work)
        assertEquals("Work stuff", work.name)
        assertNull(dao.createFolder("work STUFF")) // names are unique, ignoring case
        assertNull(dao.createFolder("   ")) // empty names are not allowed
        val home = assertNotNull(dao.createFolder("Home"))
        assertFalse(dao.renameFolder(home.id, "WORK stuff"))
        assertTrue(dao.renameFolder(home.id, "Family"))
        assertEquals(listOf("Work stuff", "Family"), dao.getFolders().map { it.name })
    }

    @Test
    fun movesOnlyTheGivenClips() {
        val dao = newDao(context, "move-${UUID.randomUUID()}.db")
        dao.insertNewEntry(1000, false, "a", null, null, null)
        dao.insertNewEntry(2000, false, "b", null, null, null)
        dao.insertNewEntry(3000, true, "c", null, null, null)
        val folder = assertNotNull(dao.createFolder("Work"))
        fun folderOf(text: String) = dao.getAll().single { it.text == text }.folderId

        dao.moveClipsToFolder(dao.getAll().filter { it.text != "c" }.map { it.id }, folder.id)
        assertEquals(folder.id, folderOf("a"))
        assertEquals(folder.id, folderOf("b"))
        assertNull(folderOf("c"))

        dao.moveClipsToFolder(listOf(dao.getAll().single { it.text == "a" }.id), null) // back to no folder
        assertNull(folderOf("a"))

        dao.moveClipsToFolder(listOf(dao.getAll().single { it.text == "b" }.id), 9999L) // unknown folder is ignored
        assertEquals(folder.id, folderOf("b"))
    }

    @Test
    fun pinningOnlyChangesTheGivenClips() {
        val dao = newDao(context, "pin-${UUID.randomUUID()}.db")
        dao.insertNewEntry(1000, false, "a", null, null, null)
        dao.insertNewEntry(2000, false, "b", null, null, null)
        fun pinnedOf(text: String) = dao.getAll().single { it.text == text }.isPinned

        dao.setPinned(dao.getAll().filter { it.text == "a" }.map { it.id }, true)
        assertTrue(pinnedOf("a"))
        assertFalse(pinnedOf("b"))

        dao.setPinned(dao.getAll().filter { it.text == "a" }.map { it.id }, false)
        assertFalse(pinnedOf("a"))
    }

    @Test
    fun deletingAFolderKeepsItsClips() {
        val name = "delete-folder-${UUID.randomUUID()}.db"
        val dao = newDao(context, name)
        val folder = assertNotNull(dao.createFolder("Work"))
        val other = assertNotNull(dao.createFolder("Other"))
        dao.insertNewEntry(1000, false, "a", null, null, null, folder.id)
        dao.insertNewEntry(2000, true, "b", null, null, null, other.id)
        assertTrue(dao.deleteFolder(folder.id))
        assertFalse(dao.deleteFolder(folder.id))
        assertEquals(listOf("Other"), dao.getFolders().map { it.name })
        assertNull(dao.getAll().single { it.text == "a" }.folderId)
        assertEquals(other.id, dao.getAll().single { it.text == "b" }.folderId)

        val restarted = newDao(context, name)
        assertEquals(listOf("Other"), restarted.getFolders().map { it.name })
        assertEquals(2, restarted.getAll().size)
        assertNull(restarted.getAll().single { it.text == "a" }.folderId)
    }

    @Test
    fun editingAClipKeepsDatePinAndFolder() {
        val name = "edit-${UUID.randomUUID()}.db"
        val dao = newDao(context, name)
        val folder = assertNotNull(dao.createFolder("Work"))
        dao.insertNewEntry(1000, true, "old text", null, null, null, folder.id)
        dao.insertNewEntry(2000, false, "other", null, null, null)
        val id = dao.getAll().single { it.text == "old text" }.id
        assertFalse(dao.updateClipText(id, ""))
        assertFalse(dao.updateClipText(id, "other")) // another clip has this text
        assertTrue(dao.updateClipText(id, "new text\nline 2"))
        val edited = newDao(context, name).getAll().single { it.id == id }
        assertEquals("new text\nline 2", edited.text)
        assertEquals(1000, edited.timeStamp)
        assertTrue(edited.isPinned)
        assertEquals(folder.id, edited.folderId)
    }

    @Test
    fun foldersAndMembershipSurviveRestart() {
        val name = "restart-${UUID.randomUUID()}.db"
        val dao = newDao(context, name)
        val folder = assertNotNull(dao.createFolder("Work"))
        dao.insertNewEntry(1000, false, "clip", null, null, null)
        dao.moveClipsToFolder(dao.getAll().map { it.id }, folder.id)

        val restarted = newDao(context, name) // same database file, as after an app restart
        assertEquals(listOf("Work"), restarted.getFolders().map { it.name })
        assertEquals(folder.id, restarted.getAll().single().folderId)
    }

    @Test
    fun version3DatabaseIsUpgradedWithoutLosingClips() {
        val name = "v3-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(ClipboardDao.CREATE_TABLE)
            old.execSQL(ClipboardDao.ADD_FILE_COLUMN)
            old.execSQL(ClipboardDao.ADD_MIME_TYPE_COLUMN)
            old.execSQL("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT) VALUES (1000, 0, 'old clip')")
            old.version = 3
        }

        val dao = newDao(context, name) // opening it upgrades to the current version
        assertEquals(listOf("old clip"), dao.getAll().map { it.text })
        assertNull(dao.getAll().single().folderId)
        assertNotNull(dao.createFolder("New"))
    }

    @Test
    fun editedClipIsSavedAsNewOrReplacedAndPutInFolder() {
        val dao = newDao(context, "edit-${java.util.UUID.randomUUID()}.db")
        val work = assertNotNull(dao.createFolder("Work"))
        dao.insertNewEntry(1000, true, "original", null, null, null)
        dao.insertNewEntry(2000, false, "other", null, null, null)
        val original = dao.getAll().single { it.text == "original" }

        // save as new: the original is kept, the new clip is in the folder
        assertEquals(ClipboardDao.EditResult.SAVED, dao.saveEditedClip(original.id, "copy of original", work.id, asNew = true))
        assertEquals("original", dao.get(original.id)?.text)
        assertEquals(work.id, dao.getAll().single { it.text == "copy of original" }.folderId)
        assertEquals(3, dao.count())

        // replace: same clip, date and pin are kept
        assertEquals(ClipboardDao.EditResult.SAVED, dao.saveEditedClip(original.id, "changed", work.id, asNew = false))
        val changed = assertNotNull(dao.get(original.id))
        assertEquals("changed", changed.text)
        assertEquals(1000, changed.timeStamp)
        assertTrue(changed.isPinned)
        assertEquals(work.id, changed.folderId)

        // only the folder is changed
        assertEquals(ClipboardDao.EditResult.SAVED, dao.saveEditedClip(original.id, "changed", null, asNew = false))
        assertNull(dao.get(original.id)?.folderId)

        // the text of another clip: nothing is added, that clip is put in the folder
        assertEquals(ClipboardDao.EditResult.EXISTS, dao.saveEditedClip(original.id, "other", work.id, asNew = false))
        assertEquals(ClipboardDao.EditResult.EXISTS, dao.saveEditedClip(original.id, "other", work.id, asNew = true))
        assertEquals("changed", dao.get(original.id)?.text)
        assertEquals(work.id, dao.getAll().single { it.text == "other" }.folderId)
        assertEquals(3, dao.count())

        assertEquals(ClipboardDao.EditResult.EMPTY, dao.saveEditedClip(original.id, "", null, asNew = false))
        assertEquals(ClipboardDao.EditResult.GONE, dao.saveEditedClip(-5, "new text", null, asNew = false))
        // a clip that is gone can still be saved as new, a folder that is gone is ignored
        assertEquals(ClipboardDao.EditResult.SAVED, dao.saveEditedClip(-5, "new text", 12345, asNew = true))
        assertNull(dao.getAll().single { it.text == "new text" }.folderId)

        // a folder with the same name is used again
        assertEquals(work.id, dao.getOrCreateFolder(" work ")?.id)
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
