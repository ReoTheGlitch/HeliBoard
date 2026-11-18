// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps copies of the clipboard history (text clips and folders) as JSON files in the storage of the app,
 * so the clips can be brought back if the database is damaged or clips are lost.
 * One file per day is written a short time after the last change, old files are removed.
 * The files have the same format as the export, restoring only adds what is missing.
 */
object ClipboardAutoBackup {
    private const val TAG = "ClipboardAutoBackup"
    private const val DIRECTORY = "clipboard_backup"
    private const val PREFIX = "clipboard_"
    private const val SUFFIX = ".json"
    private const val KEEP_FILES = 7
    private const val DELAY_SECONDS = 15L

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, TAG).apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    /** writes a backup soon, several changes in a short time result in a single write */
    @Synchronized fun schedule(context: Context) {
        val appContext = context.applicationContext ?: context
        pending?.cancel(false)
        pending = executor.schedule({ ClipboardDao.getInstance(appContext)?.let { backupNow(appContext, it) } }, DELAY_SECONDS, TimeUnit.SECONDS)
    }

    /** the folder is readable with a file manager or from a computer where the system allows it, no permission is needed */
    fun directory(context: Context): File =
        File(runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir, DIRECTORY)

    /** newest first */
    fun list(context: Context): List<File> =
        directory(context).listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** returns the written file, or null if there is nothing to save or the file can't be written */
    @Synchronized fun backupNow(context: Context, dao: ClipboardDao, date: Date = Date()): File? {
        try {
            val export = ClipboardJson.toExport(dao.snapshot(), dao.getFolders())
            // an empty history never replaces a backup, this may be the situation the backup is made for
            if (export.clips.isEmpty()) return null
            val dir = directory(context)
            dir.mkdirs()
            val file = File(dir, PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date) + SUFFIX)
            // the old file of today is only replaced when the new one is written completely
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(ClipboardJson.encode(export))
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) return null
            }
            list(context).drop(KEEP_FILES).forEach { it.delete() }
            return file
        } catch (e: Throwable) {
            Log.w(TAG, "could not write clipboard backup", e)
            return null
        }
    }

    /** adds the clips and folders of the backup that are missing in the history, nothing is removed */
    fun restore(file: File, dao: ClipboardDao): ClipboardImportResult = dao.importClips(ClipboardJson.decode(file.readText()))
}
