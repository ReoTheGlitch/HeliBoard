// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.Database
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** drives the real clipboard panel in a window: folders, search, selection, drag-select, swipe */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodService::class], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardPanelTest {
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
    private lateinit var bar: ClipboardFolderBar

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun openPanel() {
        val inputView = keyboardSwitcher.onCreateInputView(latinIME, false)
        keyboardSwitcher.reloadMainKeyboard()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(inputView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        keyboardSwitcher.setClipboardKeyboard()
        idle()
        root = inputView
        list = root.findViewById(R.id.clipboard_list)
        bar = root.findViewById(R.id.clipboard_folder_bar)
    }

    // shown views by their text, or by their description for icon buttons
    private fun labels(view: View, out: MutableList<Pair<String, View>> = mutableListOf()): List<Pair<String, View>> {
        if (view.isShown) {
            if (view is TextView) out.add(view.text.toString() to view)
            view.contentDescription?.let { out.add(it.toString() to view) }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) labels(view.getChildAt(i), out)
        return out
    }

    private fun button(parent: View, label: String): View =
        assertNotNull(labels(parent).firstOrNull { it.first == label }?.second, "no shown button '$label' in ${labels(parent).map { it.first }}")

    private fun hasButton(parent: View, label: String) = labels(parent).any { it.first == label }

    private fun click(parent: View, label: String) {
        assertTrue(button(parent, label).performClick())
        idle()
    }

    private fun shownClipTexts() = (0 until list.childCount).map {
        list.getChildAt(it).findViewById<TextView>(R.id.clipboard_entry_text_content).text.toString()
    }

    // while the finger is down after a long-press the list runs a task every frame, so the looper is only idled after the finger is up
    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        list.dispatchTouchEvent(event)
        event.recycle()
        if (action == MotionEvent.ACTION_UP) idle()
    }

    private fun screenshot(name: String) {
        if (root.width == 0 || root.height == 0) return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF202020.toInt())
        root.draw(canvas)
        val dir = File("build/reports/clipboard-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun panelWorks() {
        val work = assertNotNull(dao.createFolder("Work"))
        for (i in 1..40) dao.insertNewEntry(1_700_000_000_000 + i * 60_000L, false, "clip number $i", null, null, null)
        dao.insertNewEntry(1_700_000_000_000, false, "apple pie", null, null, null, work.id)
        dao.insertNewEntry(1_700_000_001_000, false, "apple juice", null, null, null)
        openPanel()
        screenshot("1-list")

        // the list only creates the rows that fit, newest clip first, each with a date
        assertTrue(list.childCount in 2..30, "rows created: ${list.childCount}")
        assertEquals(42, list.adapter!!.itemCount)
        assertEquals("clip number 40", shownClipTexts().first())
        assertTrue(list.getChildAt(0).findViewById<TextView>(R.id.clipboard_entry_date).text.isNotEmpty())
        assertTrue(hasButton(bar, "All") && hasButton(bar, "Unfiled") && hasButton(bar, "Work") && hasButton(bar, "New folder"))

        // folder filter
        click(bar, "Work")
        assertEquals(listOf("apple pie"), shownClipTexts())
        click(bar, "All")
        assertEquals(42, list.adapter!!.itemCount)

        // long-press starts the selection, dragging selects the range
        val first = list.getChildAt(0)
        val downTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, first.left + 5f, first.top + 5f, downTime)
        assertTrue(first.performLongClick())
        assertTrue(hasButton(bar, "1 selected"), "bar shows ${labels(bar).map { it.first }}")
        val target = list.getChildAt(3)
        val targetPosition = list.getChildAdapterPosition(target)
        touch(MotionEvent.ACTION_MOVE, target.left + 5f, target.top + 5f, downTime)
        assertTrue(hasButton(bar, "${targetPosition + 1} selected"), "bar shows ${labels(bar).map { it.first }}")
        // dragging back shrinks the range
        val back = list.getChildAt(1)
        touch(MotionEvent.ACTION_MOVE, back.left + 5f, back.top + 5f, downTime)
        assertTrue(hasButton(bar, "${list.getChildAdapterPosition(back) + 1} selected"), "bar shows ${labels(bar).map { it.first }}")
        touch(MotionEvent.ACTION_UP, back.left + 5f, back.top + 5f, downTime)
        assertTrue(list.getChildAt(0).findViewById<View>(R.id.clipboard_entry_selected_mark).isVisible)
        assertFalse(list.getChildAt(3).findViewById<View>(R.id.clipboard_entry_selected_mark).isVisible)
        screenshot("2-selection")

        // all selection buttons fit in the bar
        for (label in listOf("Close", "Select all", "Pin", "Move to folder", "Copy all", "Paste all")) {
            val b = button(bar, label)
            val location = IntArray(2).also { b.getLocationInWindow(it) }
            val barLocation = IntArray(2).also { bar.getLocationInWindow(it) }
            assertTrue(b.width > 0 && location[0] + b.width <= barLocation[0] + bar.width, "'$label' does not fit: x=${location[0]} w=${b.width} bar=${bar.width}")
        }

        // a tap toggles, nothing is pasted while selecting
        ShadowInputMethodService.reset()
        assertTrue(list.getChildAt(0).performClick())
        idle()
        assertEquals("", ShadowInputMethodService.text)
        assertFalse(hasButton(bar, "${list.getChildAdapterPosition(back) + 1} selected"))

        // select all, unselect all, close
        click(bar, "Select all")
        assertTrue(hasButton(bar, "42 selected"))
        click(bar, "Select all")
        assertTrue(hasButton(bar, "0 selected"))
        click(bar, "Close")
        assertTrue(hasButton(bar, "Search"))

        // search with the small keyboard, select all results, move them to a folder
        click(bar, "Search")
        val miniKeyboard = root.findViewById<View>(R.id.clipboard_mini_keyboard)
        assertTrue(miniKeyboard.isVisible)
        assertFalse(root.findViewById<View>(R.id.bottom_row_keyboard).isVisible)
        "apple".forEach { click(miniKeyboard, it.toString()) }
        click(miniKeyboard, "␣")
        click(miniKeyboard, "?123")
        click(miniKeyboard, "7")
        assertEquals(0, list.adapter!!.itemCount) // "apple 7" is in no clip
        assertFalse(hasButton(bar, "Select all"))
        assertTrue(hasButton(root, "No clips found"))
        screenshot("3b-no-results")
        click(miniKeyboard, "⌫")
        click(miniKeyboard, "⌫")
        click(miniKeyboard, "ABC")
        assertTrue(hasButton(miniKeyboard, "q"))
        screenshot("3-search")
        assertEquals(setOf("apple juice", "apple pie"), shownClipTexts().toSet())
        click(bar, "Select all")
        assertTrue(hasButton(bar, "2 selected"))
        assertFalse(miniKeyboard.isVisible)
        click(bar, "Move to folder")
        screenshot("4-move")
        click(bar, "Work")
        assertEquals(setOf("apple juice", "apple pie"), dao.getAll().filter { it.folderId == work.id }.map { it.text }.toSet())
        assertEquals(44 - 2, dao.getAll().size)
        click(bar, "Close") // ends the search
        assertEquals(42, list.adapter!!.itemCount)

        // pin from the selection
        assertTrue(list.getChildAt(0).performLongClick())
        idle()
        click(bar, "Pin")
        assertEquals(listOf("clip number 40"), dao.getAll().filter { it.isPinned }.map { it.text })
        assertEquals("clip number 40", shownClipTexts().first())
        assertTrue(list.getChildAt(0).findViewById<View>(R.id.clipboard_entry_pinned_icon).isVisible)
        assertEquals(1_700_000_000_000 + 40 * 60_000L, dao.getAll().single { it.isPinned }.timeStamp) // pinning keeps the date

        // a tap pastes the clip when nothing is selected
        ShadowInputMethodService.reset()
        assertTrue(list.getChildAt(1).performClick())
        idle()
        assertEquals("clip number 39", ShadowInputMethodService.text)
        screenshot("5-pinned")

        // paste two selected clips, one per line
        ShadowInputMethodService.reset()
        assertTrue(list.getChildAt(1).performLongClick())
        idle()
        assertTrue(hasButton(bar, "Edit clip")) // one text clip is selected
        assertTrue(list.getChildAt(2).performClick())
        idle()
        assertFalse(hasButton(bar, "Edit clip"))
        click(bar, "Paste all")
        assertEquals("clip number 39\nclip number 38", ShadowInputMethodService.text)
        assertTrue(hasButton(bar, "Search")) // the selection is closed

        // swipe a clip away (the list calls this), undo brings it back
        val adapter = list.adapter as ClipboardAdapter
        adapter.removeEntryAt(1)
        idle()
        assertTrue(hasButton(bar, "Clip deleted"))
        assertEquals(41, adapter.itemCount)
        assertEquals(42, dao.getAll().size) // still in the history while undo is possible
        screenshot("5b-undo")
        click(bar, "Undo")
        assertEquals(42, adapter.itemCount)
        assertEquals("clip number 39", shownClipTexts()[1])
        // without undo, the clip is removed when the bar is closed
        adapter.removeEntryAt(1)
        idle()
        click(bar, "Close")
        assertEquals(41, dao.getAll().size)
        assertFalse(dao.getAll().any { it.text == "clip number 39" })

        // folders: type a name with capital letter and emoticon, then delete the folder, its clips are kept
        click(bar, "New folder")
        click(miniKeyboard, "⇧")
        click(miniKeyboard, "F")
        click(miniKeyboard, "u")
        click(miniKeyboard, "n")
        click(miniKeyboard, "␣")
        click(miniKeyboard, ":-)")
        screenshot("5c-emoticons")
        click(miniKeyboard, "\\o/")
        click(bar, "OK")
        assertTrue(dao.getFolders().any { it.name == "Fun \\o/" }, "folders: ${dao.getFolders().map { it.name }}")
        assertTrue(button(bar, "Work").performLongClick())
        idle()
        screenshot("5d-folder-actions")
        click(bar, "Delete folder")
        click(bar, "Delete")
        assertFalse(dao.getFolders().any { it.name == "Work" })
        assertEquals(41, dao.getAll().size)
        assertTrue(dao.getAll().none { it.folderId == work.id })
        click(bar, "All")

        // the panel again in a dark theme with key borders, for the screenshots
        latinIME.prefs().edit().putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_DARK)
            .putBoolean(Settings.PREF_THEME_DAY_NIGHT, false).putBoolean(Settings.PREF_THEME_KEY_BORDERS, true).commit()
        Settings.getInstance().loadSettings(latinIME)
        keyboardSwitcher.setThemeNeedsReload()
        openPanel()
        screenshot("6-dark-list")
        assertTrue(list.getChildAt(1).performLongClick())
        idle()
        screenshot("7-dark-selection")
        click(bar, "Close")
        click(bar, "Search")
        screenshot("8-dark-search")
    }
}
