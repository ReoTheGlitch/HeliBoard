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

    private fun newDao(context: Context, name: String): ClipboardDao {
        val dbCtor = Database::class.java.getDeclaredConstructor(Context::class.java, String::class.java)
        dbCtor.isAccessible = true
        val db = dbCtor.newInstance(context, name)
        val daoCtor = ClipboardDao::class.java.getDeclaredConstructor(Database::class.java)
        daoCtor.isAccessible = true
        return daoCtor.newInstance(db)
    }
}
