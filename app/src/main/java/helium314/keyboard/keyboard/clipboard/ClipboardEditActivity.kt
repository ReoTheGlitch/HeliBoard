// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao

/**
 * Edits the text of one clip in a normal text field, so the keyboard with all its keys and the cursor can be used.
 * The clipboard panel can't do that, because an input method can't type into its own window.
 */
class ClipboardEditActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = ClipboardDao.getInstance(this)
        val id = intent.getLongExtra(EXTRA_CLIP_ID, -1L)
        val clip = dao?.get(id)
        if (dao == null || clip?.text == null || clip.filename != null) {
            finish()
            return
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
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
        val cancel = Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        val save = Button(this).apply {
            setText(R.string.save)
            setOnClickListener {
                if (dao.updateClipText(id, field.text.toString())) finish()
                else Toast.makeText(context, R.string.clipboard_edit_clip_invalid, Toast.LENGTH_LONG).show()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancel)
            addView(save)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(padding, padding, padding, padding)
            addView(title)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        editField = field
        setContentView(content)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.requestFocus()
    }

    private var editField: EditText? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // a very long text is too large for the saved state, it is loaded from the history again
        editField?.text?.takeIf { it.length <= MAX_SAVED_LENGTH }?.let { outState.putString(STATE_TEXT, it.toString()) }
    }

    companion object {
        const val EXTRA_CLIP_ID = "clip_id"
        private const val STATE_TEXT = "text"
        private const val MAX_SAVED_LENGTH = 50_000
    }
}
