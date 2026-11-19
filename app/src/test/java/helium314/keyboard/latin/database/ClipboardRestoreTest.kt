// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import helium314.keyboard.latin.utils.GestureDataDao
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** restoring the database of a backup made by upstream HeliBoard (database version 3, no folders) */
@RunWith(RobolectricTestRunner::class)
class ClipboardRestoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun upstreamBackupIsMergedIntoTheHistory() {
        val dao = assertNotNull(ClipboardDao.getInstance(context))
        val folder = assertNotNull(dao.createFolder("Mine"))
        dao.insertNewEntry(5000, false, "already here", null, null, null, folder.id)
        dao.insertNewEntry(6000, false, "in both", null, null, null)

        val backup = context.getDatabasePath(Database.NAME + "_restored")
        backup.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(backup, null).use { old ->
            old.execSQL(ClipboardDao.CREATE_TABLE)
            old.execSQL(GestureDataDao.CREATE_TABLE)
            old.execSQL(ClipboardDao.ADD_FILE_COLUMN)
            old.execSQL(ClipboardDao.ADD_MIME_TYPE_COLUMN)
            old.execSQL("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT) VALUES (1000, 1, 'pinned on main keyboard')")
            old.execSQL("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT) VALUES (2000, 0, 'recent on main keyboard')")
            old.execSQL("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT) VALUES (3000, 1, 'in both')")
            old.execSQL("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT, FILE, MIME_TYPE) VALUES (4000, 0, 'image', 'abc.png', 'image/png')")
            old.version = 3
        }

        Database.copyFromDb(backup, context)

        val texts = dao.getAll().map { it.text }
        assertEquals(setOf("already here", "in both", "pinned on main keyboard", "recent on main keyboard", "image"), texts.toSet())
        assertEquals(5, texts.size) // "in both" is not added twice
        val pinned = dao.getAll().single { it.text == "pinned on main keyboard" }
        assertTrue(pinned.isPinned)
        assertEquals(1000, pinned.timeStamp)
        assertNull(pinned.folderId)
        assertEquals("abc.png", dao.getAll().single { it.text == "image" }.filename)
        // what was here before is not changed
        assertFalse(dao.getAll().single { it.text == "in both" }.isPinned)
        assertEquals(folder.id, dao.getAll().single { it.text == "already here" }.folderId)
        assertFalse(backup.exists())

        Database.copyFromDb(backup, context) // no file, nothing happens
        assertEquals(5, dao.getAll().size)
    }
}
