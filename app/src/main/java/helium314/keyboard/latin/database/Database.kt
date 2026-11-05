// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.core.database.sqlite.transaction
import helium314.keyboard.latin.utils.GestureDataDao
import helium314.keyboard.latin.utils.Log
import java.io.File

class Database private constructor(context: Context, name: String = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ClipboardDao.CREATE_TABLE)
        onUpgrade(db, 0, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion <= 1) {
            db.execSQL(GestureDataDao.CREATE_TABLE)
        }
        if (oldVersion <= 2) {
            db.execSQL(ClipboardDao.ADD_FILE_COLUMN)
            db.execSQL(ClipboardDao.ADD_MIME_TYPE_COLUMN)
        }
        if (oldVersion <= 3) {
            db.execSQL(ClipboardDao.CREATE_FOLDER_TABLE)
            db.execSQL(ClipboardDao.ADD_FOLDER_ID_COLUMN)
        }
    }

    companion object {
        private val TAG = Database::class.java.simpleName
        private const val VERSION = 4
        const val NAME = "heliboard.db"
        private var instance: Database? = null
        fun getInstance(context: Context): Database {
            if (instance == null)
                instance = Database(context)
            return instance!!
        }

        // needs to be in sync with db version
        fun copyFromDb(file: File, context: Context) {
            if (!file.exists())
                return
            val otherDb = Database(context, file.name) // this upgrades the DB if necessary
            val clipDao = ClipboardDao.getInstance(context) // insert to dao because of cache
            val db = getInstance(context)

            try {
                db.writableDatabase.transaction {
                    if (clipDao == null) {
                        Log.e(TAG, "can't transfer clipboard data because ClipboardDao is null")
                    } else {
                        // merge into the current history, nothing is removed: folders are matched by name,
                        // and a clip is skipped if the same text (or file) is already in the history
                        val knownTexts = clipDao.getAll().mapNotNullTo(HashSet()) { it.text }
                        val knownFiles = clipDao.getAll().mapNotNullTo(HashSet()) { it.filename }
                        val folderIds = HashMap<Long, Long>() // folder id in the backup -> folder id here
                        otherDb.readableDatabase.rawQuery("SELECT ID, NAME FROM FOLDER", null).use {
                            while (it.moveToNext()) {
                                clipDao.getOrCreateFolder(it.getString(1))?.let { folder -> folderIds[it.getLong(0)] = folder.id }
                            }
                        }
                        otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TEXT, FILE, MIME_TYPE, FOLDER_ID FROM CLIPBOARD", null)
                            .use {
                                while (it.moveToNext()) {
                                    val text = it.getStringOrNull(2)
                                    val file = it.getStringOrNull(3)
                                    val duplicate = (text != null && text in knownTexts) || (file != null && file in knownFiles)
                                    if (!duplicate) {
                                        val folderId = it.getLongOrNull(5)?.let { backupId -> folderIds[backupId] }
                                        clipDao.insertNewEntry(
                                            it.getLong(0),
                                            it.getInt(1) != 0,
                                            text,
                                            file,
                                            it.getStringOrNull(4)?.split("§"),
                                            null,
                                            folderId
                                        )
                                        text?.let { knownTexts.add(it) }
                                        file?.let { knownFiles.add(it) }
                                    }
                                }
                            }
                    }
                    db.writableDatabase.execSQL("DELETE FROM GESTURE_DATA")
                    otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, WORD, EXPORTED, SOURCE_ACTIVE, DATA FROM GESTURE_DATA", null)
                        .use { c ->
                            while (c.moveToNext()) {
                                execSQL("INSERT INTO GESTURE_DATA (TIMESTAMP, WORD, EXPORTED, SOURCE_ACTIVE, DATA) " +
                                    "VALUES (${c.getLong(0)},?,${c.getInt(2)},${c.getInt(3)},?)", arrayOf(c.getString(1), c.getString(4)))
                            }
                        }
                }
            } finally {
                otherDb.close()
                file.delete()
            }
        }
    }
}
