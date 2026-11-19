// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** esc, tab, ctrl, alt, shift and arrow keys are on their own layer, opened with the Alt key on the symbols */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodService::class], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AltLayerTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()

    private fun tap(code: Int) {
        keyboardSwitcher.onPressKey(code, 1, 0, null)
        keyboardSwitcher.onReleaseKey(code, false, 0, null)
        keyboardSwitcher.onEvent(helium314.keyboard.event.Event.createSoftwareKeypressEvent(code, 0, 0, 0, false), 0, null)
    }

    @Test
    fun altKeyOpensLayerWithPcKeys() {
        val inputView = keyboardSwitcher.onCreateInputView(latinIME, false)
        keyboardSwitcher.reloadMainKeyboard()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(inputView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root = inputView
        val letters = assertNotNull(keyboardSwitcher.keyboard)
        assertTrue(letters.sortedKeys.none { it.code == KeyCode.CTRL })

        tap(KeyCode.SYMBOL_ALPHA)
        val symbols = assertNotNull(keyboardSwitcher.keyboard)
        assertEquals(KeyboardElement.SYMBOLS, symbols.mId.element)
        // the layers have the same height, so nothing moves under the finger when switching
        assertEquals(letters.mOccupiedHeight, symbols.mOccupiedHeight)
        val altKey = assertNotNull(symbols.getKey(KeyCode.DPAD), "symbols: ${symbols.sortedKeys.map { it.label }}")
        assertEquals("Alt", altKey.label)

        screenshot("symbols")
        tap(KeyCode.DPAD)
        val alt = assertNotNull(keyboardSwitcher.keyboard)
        assertEquals(KeyboardElement.DPAD, alt.mId.element)
        assertEquals(letters.mOccupiedHeight, alt.mOccupiedHeight)
        val codes = alt.sortedKeys.map { it.code }.toSet()
        listOf(KeyCode.ESCAPE, KeyCode.TAB, KeyCode.CTRL, KeyCode.ALT, KeyCode.SHIFT_META, KeyCode.ARROW_LEFT, KeyCode.ARROW_UP,
            KeyCode.ARROW_DOWN, KeyCode.ARROW_RIGHT, KeyCode.SYMBOL, KeyCode.DELETE, 'c'.code, 'v'.code, 'x'.code, 'z'.code, 'y'.code, 'a'.code)
            .forEach { assertTrue(it in codes, "missing $it in ${alt.sortedKeys.map { k -> k.label }}") }
        // all keys are inside the keyboard and not tiny
        val keys = alt.sortedKeys.filter { !it.isSpacer }
        assertTrue(keys.all { it.x >= 0 && it.x + it.width <= alt.mOccupiedWidth && it.y + it.height <= alt.mOccupiedHeight })
        assertTrue(keys.all { it.width >= alt.mOccupiedWidth / 12 && it.height >= letters.mMostCommonKeyHeight * 0.85f }, "${keys.map { "${it.label}:${it.width}x${it.height}" }} letter ${letters.mMostCommonKeyWidth}x${letters.mMostCommonKeyHeight} width ${alt.mOccupiedWidth}")

        screenshot("alt-layer")
        // ?123 goes back to the symbols
        tap(KeyCode.SYMBOL)
        assertEquals(KeyboardElement.SYMBOLS, keyboardSwitcher.keyboard?.mId?.element)
    }

    private lateinit var root: View

    private fun screenshot(name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        if (root.width == 0 || root.height == 0) return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF202020.toInt())
        root.draw(canvas)
        val dir = File("build/reports/clipboard-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
