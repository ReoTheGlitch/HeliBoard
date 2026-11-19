// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ClipboardAutoBackupTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun lostClipsComeBackFromBackup() {
        ClipboardAutoBackup.list(context).forEach { it.delete() }
        val dao = newDao(context, "backup-${UUID.randomUUID()}.db")
        val work = assertNotNull(dao.createFolder("Work"))
        dao.insertNewEntry(1000, true, "kept", null, null, null, work.id)
        dao.insertNewEntry(2000, false, "lost", null, null, null)
        val file = assertNotNull(ClipboardAutoBackup.backupNow(context, dao))
        assertEquals(listOf(file), ClipboardAutoBackup.list(context))

        // an empty history, like after a damaged database, does not replace the backup
        val empty = newDao(context, "empty-${UUID.randomUUID()}.db")
        assertNull(ClipboardAutoBackup.backupNow(context, empty))
        val result = ClipboardAutoBackup.restore(file, empty)
        assertEquals(2, result.added)
        val kept = empty.getAll().single { it.text == "kept" }
        assertEquals(1000, kept.timeStamp)
        assertEquals("Work", empty.getFolders().single { it.id == kept.folderId }.name)

        // restoring again changes nothing
        assertEquals(0, ClipboardAutoBackup.restore(file, empty).added)
        assertEquals(2, empty.count())
    }

    @Test
    fun oneFilePerDayAndOldFilesAreRemoved() {
        ClipboardAutoBackup.list(context).forEach { it.delete() }
        val dao = newDao(context, "days-${UUID.randomUUID()}.db")
        dao.insertNewEntry(1000, false, "clip", null, null, null)
        val start = 1_700_000_000_000L
        for (i in 0 until 10) {
            ClipboardAutoBackup.backupNow(context, dao, Date(start + i * day))
            ClipboardAutoBackup.backupNow(context, dao, Date(start + i * day)) // same day, same file
        }
        val files = ClipboardAutoBackup.list(context)
        assertEquals(7, files.size)
        assertEquals(files.sortedByDescending { it.name }, files)
        assertEquals(1, ClipboardJson.decode(files.first().readText()).clips.size)
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
