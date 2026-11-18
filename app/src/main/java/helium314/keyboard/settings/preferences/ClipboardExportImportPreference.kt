// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardAutoBackup
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.ClipboardJson
import helium314.keyboard.latin.utils.IntentUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.filePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Saves the clipboard history and folders to a JSON file, or adds the clips of such a file to the history. */
@Composable
fun ClipboardExportImportPreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showExportChoice by rememberSaveable { mutableStateOf(false) }
    var onlyUnfiled by rememberSaveable { mutableStateOf(false) }
    var message: String? by rememberSaveable { mutableStateOf(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = filePicker { uri ->
        scope.launch(Dispatchers.IO) {
            message = try {
                val dao = ClipboardDao.getInstance(ctx)!!
                val export = ClipboardJson.toExport(dao.snapshot(), dao.getFolders(), onlyUnfiled)
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(ClipboardJson.encode(export).toByteArray()) }
                ctx.getString(R.string.clipboard_export_done, export.clips.size)
            } catch (e: Throwable) {
                Log.w("ClipboardExportImport", "could not export clipboard history", e)
                ctx.getString(R.string.clipboard_export_error)
            }
        }
    }
    val importLauncher = filePicker { uri ->
        scope.launch(Dispatchers.IO) {
            message = try {
                val dao = ClipboardDao.getInstance(ctx)!!
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.reader().readText() }
                val export = ClipboardJson.decode(text)
                val result = dao.importClips(export)
                ctx.getString(R.string.clipboard_import_done, result.added, result.moved, result.unchanged)
            } catch (e: Throwable) {
                Log.w("ClipboardExportImport", "could not import clipboard history", e)
                ctx.getString(R.string.file_read_error)
            }
        }
    }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.clipboard_export_import)) },
            content = { Text(stringResource(R.string.clipboard_export_import_message)) },
            confirmButtonText = stringResource(R.string.clipboard_export),
            neutralButtonText = stringResource(R.string.clipboard_import),
            onNeutral = {
                showDialog = false
                val intent = IntentUtils.getResolvableTypeIntent(ctx, "application/json")
                    .setAction(Intent.ACTION_OPEN_DOCUMENT)
                importLauncher.launch(intent)
            },
            onConfirmed = { showExportChoice = true }
        )
    }
    if (showExportChoice) {
        // a smaller file with only the clips that are in no folder is easier to handle for an AI assistant
        ConfirmationDialog(
            onDismissRequest = { showExportChoice = false },
            title = { Text(stringResource(R.string.clipboard_export)) },
            content = { Text(stringResource(R.string.clipboard_export_choice_message)) },
            confirmButtonText = stringResource(R.string.clipboard_export_all),
            neutralButtonText = stringResource(R.string.clipboard_export_unfiled),
            onNeutral = {
                showExportChoice = false
                onlyUnfiled = true
                exportLauncher.launch(exportIntent(ctx))
            },
            onConfirmed = {
                onlyUnfiled = false
                exportLauncher.launch(exportIntent(ctx))
            }
        )
    }
    message?.let { InfoDialog(it) { message = null } }
}

private fun exportIntent(ctx: android.content.Context): Intent {
    val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
    return IntentUtils.getResolvableTypeIntent(ctx, "application/json")
        .setAction(Intent.ACTION_CREATE_DOCUMENT)
        .putExtra(Intent.EXTRA_TITLE, "clipboard_history_$currentDate.json")
}

/** Shows the automatic backups of the clipboard history, the clips of a backup can be added to the history again. */
@Composable
fun ClipboardAutoBackupPreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var message: String? by rememberSaveable { mutableStateOf(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Preference(name = setting.title, description = stringResource(R.string.clipboard_auto_backup_summary), onClick = {
        if (ClipboardAutoBackup.list(ctx).isEmpty()) message = ctx.getString(R.string.clipboard_auto_backup_none)
        else showDialog = true
    })
    if (showDialog) {
        val files = ClipboardAutoBackup.list(ctx)
        ListPickerDialog(
            onDismissRequest = { showDialog = false },
            items = files,
            onItemSelected = { file ->
                scope.launch(Dispatchers.IO) {
                    message = try {
                        val result = ClipboardAutoBackup.restore(file, ClipboardDao.getInstance(ctx)!!)
                        ctx.getString(R.string.clipboard_import_done, result.added, result.moved, result.unchanged)
                    } catch (e: Throwable) {
                        Log.w("ClipboardExportImport", "could not restore clipboard backup", e)
                        ctx.getString(R.string.file_read_error)
                    }
                }
            },
            title = { Text(stringResource(R.string.clipboard_auto_backup_restore)) },
            getItemName = { it.name.removePrefix("clipboard_").removeSuffix(".json") + "  (" + Formatter.formatShortFileSize(ctx, it.length()) + ")" },
            showRadioButtons = false,
            description = stringResource(R.string.clipboard_auto_backup_message, ClipboardAutoBackup.directory(ctx).path),
        )
    }
    message?.let { InfoDialog(it) { message = null } }
}
