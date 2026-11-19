// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.Database
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** many clips: select hundreds of them, move, pin, drag-select while the list scrolls */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodService::class], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardStressTest {
    // other tests in the same process may have filled the history, start with an empty one (before the keyboard is created)
    @Suppress("unused")
    private val historyReset = listOf(ClipboardDao::class.java, Database::class.java).forEach {
        it.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    private val dao = ClipboardDao.getInstance(latinIME)!!
    private lateinit var root: View
    private lateinit var list: RecyclerView
    private lateinit var bar: View

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun labels(view: View, out: MutableList<Pair<String, View>> = mutableListOf()): List<Pair<String, View>> {
        if (view.isShown) {
            if (view is TextView) out.add(view.text.toString() to view)
            view.contentDescription?.let { out.add(it.toString() to view) }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) labels(view.getChildAt(i), out)
        return out
    }

    private fun click(parent: View, label: String) {
        val view = assertNotNull(labels(parent).firstOrNull { it.first == label }?.second, "no '$label' in ${labels(parent).map { it.first }}")
        assertTrue(view.performClick())
        idle()
    }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        list.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun hundredsOfClips() {
        val work = assertNotNull(dao.createFolder("Work"))
        val other = assertNotNull(dao.createFolder("Other"))
        for (i in 1..1200) dao.insertNewEntry(1_700_000_000_000 + i * 60_000L, false, "clip number $i with some more text to make it longer", null, null, null)
        val inputView = keyboardSwitcher.onCreateInputView(latinIME, false)
        keyboardSwitcher.reloadMainKeyboard()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(inputView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        keyboardSwitcher.setClipboardKeyboard()
        idle()
        root = inputView
        list = root.findViewById(R.id.clipboard_list)
        bar = root.findViewById(R.id.clipboard_folder_bar)

        // select all, move
        assertTrue(list.getChildAt(0).performLongClick())
        idle()
        click(bar, "Select all")
        click(bar, "Move to folder")
        click(bar, "Work")
        assertEquals(1200, dao.getAll().count { it.folderId == work.id })

        // drag-select with the finger resting at the bottom edge, the list scrolls and selects
        val first = list.getChildAt(0)
        val downTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, first.left + 5f, first.top + 5f, downTime)
        assertTrue(first.performLongClick())
        touch(MotionEvent.ACTION_MOVE, first.left + 5f, list.height - 2f, downTime)
        touch(MotionEvent.ACTION_MOVE, first.left + 6f, list.height - 2f, downTime)
        repeat(400) { shadowOf(Looper.getMainLooper()).runOneTask() }
        touch(MotionEvent.ACTION_UP, first.left + 5f, list.height - 2f, downTime)
        idle()
        val selected = labels(bar).map { it.first }.first { it.endsWith("selected") }.substringBefore(" ").toInt()
        assertTrue(selected > 20, "selected by dragging: $selected")
        click(bar, "Move to folder")
        click(bar, "Other")
        assertEquals(selected, dao.getAll().count { it.folderId == other.id })

        // folder view, select all there, pin, then unpin
        click(bar, "Other")
        assertEquals(selected, list.adapter!!.itemCount)
        assertTrue(list.getChildAt(0).performLongClick())
        idle()
        click(bar, "Select all")
        click(bar, "Pin")
        assertEquals(selected, dao.getAll().count { it.isPinned })

        // search in many clips and move the results
        click(bar, "All")
        click(bar, "Search")
        val keyboard = root.findViewById<View>(R.id.clipboard_mini_keyboard)
        click(keyboard, "?123")
        click(keyboard, "7")
        click(bar, "Select all")
        click(bar, "Move to folder")
        click(bar, "No folder")
        assertEquals(dao.getAll().count { it.text!!.contains("7") }, dao.getAll().count { it.folderId == null })
    }
}
