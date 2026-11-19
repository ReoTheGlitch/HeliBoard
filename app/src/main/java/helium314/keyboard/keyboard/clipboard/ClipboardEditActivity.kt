// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.ClipboardFolder

/**
 * Edits the text of one clip in a normal text field, so the keyboard with all its keys and the cursor can be used.
 * The clipboard panel can't do that, because an input method can't type into its own window.
 * The text can replace the clip or be saved as a new clip, and the clip can be put in a folder.
 */
class ClipboardEditActivity : Activity() {
    private var editField: EditText? = null
    private var folders: List<ClipboardFolder> = emptyList()
    /** null for no folder */
    private var folderId: Long? = null
    private var folderSpinner: Spinner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = ClipboardDao.getInstance(this)
        val id = intent.getLongExtra(EXTRA_CLIP_ID, -1L)
        val clip = dao?.get(id)
        if (dao == null || clip?.text == null || clip.filename != null) {
            finish()
            return
        }
        folderId = if (savedInstanceState?.containsKey(STATE_FOLDER) == true)
            savedInstanceState.getLong(STATE_FOLDER).takeIf { it >= 0 }
        else clip.folderId
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val title = TextView(this).apply {
            setText(R.string.clipboard_edit_clip)
            textSize = 20f
            setPadding(0, 0, 0, padding / 2)
        }
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            setText(savedInstanceState?.getString(STATE_TEXT) ?: clip.text)
            setSelection(text.length)
        }
        editField = field
        val spinner = Spinner(this).apply { minimumHeight = (48 * density).toInt() }
        folderSpinner = spinner
        val folderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                setText(R.string.clipboard_edit_folder)
                setPadding(0, 0, padding, 0)
            })
            addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        fillFolders(dao)

        fun save(asNew: Boolean) {
            val message = when (dao.saveEditedClip(id, field.text.toString(), folderId, asNew)) {
                ClipboardDao.EditResult.SAVED -> null
                ClipboardDao.EditResult.EXISTS -> R.string.clipboard_edit_exists
                ClipboardDao.EditResult.GONE -> R.string.clipboard_edit_gone
                ClipboardDao.EditResult.EMPTY -> R.string.clipboard_edit_empty
            }
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            // the text is kept when the clip is gone or empty, so it can still be saved as new or changed
            if (message == null || message == R.string.clipboard_edit_exists) finish()
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(Button(context).apply {
                setText(android.R.string.cancel)
                setOnClickListener { finish() }
            })
            addView(Button(context).apply {
                setText(R.string.clipboard_save_as_new)
                setOnClickListener { save(asNew = true) }
            })
            addView(Button(context).apply {
                setText(R.string.clipboard_replace)
                setOnClickListener { save(asNew = false) }
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(padding, padding, padding, padding)
            addView(title)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(folderRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(content)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.requestFocus()
    }

    /** the list is: no folder, the folders, and an entry to create a folder */
    private fun fillFolders(dao: ClipboardDao) {
        val spinner = folderSpinner ?: return
        folders = dao.getFolders()
        if (folderId != null && folders.none { it.id == folderId }) folderId = null
        val names = listOf(getString(R.string.clipboard_selection_no_folder)) + folders.map { it.name } +
                getString(R.string.clipboard_folder_new_entry)
        spinner.onItemSelectedListener = null
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selected = folders.indexOfFirst { it.id == folderId } + 1
        spinner.setSelection(selected, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, rowId: Long) {
                when (position) {
                    0 -> folderId = null
                    names.lastIndex -> askNewFolder(dao)
                    else -> folderId = folders[position - 1].id
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** a folder with the same name is used if it exists */
    private fun askNewFolder(dao: ClipboardDao) {
        val input = EditText(this).apply {
            setHint(R.string.clipboard_folder_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(ClipboardDao.MAX_FOLDER_NAME_LENGTH))
            isSingleLine = true
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_folder_new)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val folder = dao.getOrCreateFolder(input.text.toString())
                if (folder == null) Toast.makeText(this, R.string.clipboard_folder_name_invalid, Toast.LENGTH_LONG).show()
                else folderId = folder.id
                fillFolders(dao)
            }
            .setNegativeButton(android.R.string.cancel, null)
            // the previous folder is selected again if no folder was created
            .setOnDismissListener { fillFolders(dao) }
            .show()
        input.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // a very long text is too large for the saved state, it is loaded from the history again
        editField?.text?.takeIf { it.length <= MAX_SAVED_LENGTH }?.let { outState.putString(STATE_TEXT, it.toString()) }
        outState.putLong(STATE_FOLDER, folderId ?: -1L)
    }

    companion object {
        const val EXTRA_CLIP_ID = "clip_id"
        private const val STATE_TEXT = "text"
        private const val STATE_FOLDER = "folder"
        private const val MAX_SAVED_LENGTH = 50_000
    }
}
