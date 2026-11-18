// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** esc, tab, ctrl, alt and arrow keys are a row above the symbols, the letters are unchanged */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodService::class], qualifiers = "w360dp-h760dp-xhdpi")
class PcKeysRowTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()

    @Test
    fun symbolsHaveCtrlAltRowButLettersDoNot() {
        keyboardSwitcher.onCreateInputView(latinIME, false)
        keyboardSwitcher.reloadMainKeyboard()
        val letters = assertNotNull(keyboardSwitcher.keyboard)
        assertTrue(letters.sortedKeys.none { it.code == KeyCode.CTRL || it.code == KeyCode.ESCAPE })

        keyboardSwitcher.onPressKey(KeyCode.SYMBOL_ALPHA, 1, 0, null)
        keyboardSwitcher.onReleaseKey(KeyCode.SYMBOL_ALPHA, false, 0, null)
        val symbols = assertNotNull(keyboardSwitcher.keyboard)
        val keys = symbols.sortedKeys.filter { !it.isSpacer }
        val top = keys.filter { it.y == keys.minOf { k -> k.y } }.sortedBy { it.x }
        assertEquals(listOf(KeyCode.ESCAPE, KeyCode.TAB, KeyCode.CTRL, KeyCode.ALT,
            KeyCode.ARROW_LEFT, KeyCode.ARROW_DOWN, KeyCode.ARROW_UP, KeyCode.ARROW_RIGHT), top.map { it.code })
        // the row fills the width
        assertTrue(top.all { it.x >= 0 && it.x + it.width <= symbols.mOccupiedWidth && it.width >= symbols.mOccupiedWidth / 9 })
        assertTrue(keys.all { it.y + it.height <= symbols.mOccupiedHeight })
        // the keys are as high as the other symbol keys, the keyboard is higher instead
        assertTrue(top.first().height >= letters.mMostCommonKeyHeight * 0.8f, "key height ${top.first().height}")
        assertTrue(symbols.mOccupiedHeight > letters.mOccupiedHeight)
        // the letters of the common shortcuts are in the row below
        val secondY = keys.filter { it.y > top.first().y }.minOf { it.y }
        assertEquals("acvxzysf".map { it.code }, keys.filter { it.y == secondY }.sortedBy { it.x }.map { it.code })
        println("PC ROW key ${top.first().width}x${top.first().height}, letter key ${letters.mMostCommonKeyWidth}x${letters.mMostCommonKeyHeight}")
    }
}
