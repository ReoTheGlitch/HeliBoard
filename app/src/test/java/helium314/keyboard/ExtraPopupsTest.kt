// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.graphics.Paint
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.PopupKeysKeyboard
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

/** the symbols added to the long-press of letters, and the emoticons on the emoji key */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodService::class], qualifiers = "w360dp-h760dp-xhdpi")
class ExtraPopupsTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()

    @Test
    fun lettersHaveSymbolsAndEmojiKeyHasEmoticons() {
        keyboardSwitcher.onCreateInputView(latinIME, false)
        keyboardSwitcher.reloadMainKeyboard()
        val keyboard = assertNotNull(keyboardSwitcher.keyboard)

        fun popups(code: Int) = assertNotNull(keyboard.getKey(code), "no key $code").popupKeys.orEmpty().map { it.mLabel }
        assertTrue(popups('a'.code).containsAll(listOf("à", "@", "α")), "a: ${popups('a'.code)}")
        assertTrue(popups('s'.code).containsAll(listOf("ß", "§", "$")), "s: ${popups('s'.code)}")
        assertTrue(popups('.'.code).containsAll(listOf("?", "!", "…", "~")), "period: ${popups('.'.code)}")

        val emojiKey = assertNotNull(keyboard.getKey(KeyCode.EMOJI), "the emoji key is shown by default")
        val emoticons = emojiKey.popupKeys.orEmpty()
        assertEquals(16, emoticons.size)
        assertTrue(emoticons.any { it.mLabel == "¯\\_(ツ)_/¯" && it.mOutputText == "¯\\_(ツ)_/¯" }, "emoticons: ${emoticons.map { it.mLabel to it.mOutputText }}")
        assertTrue(emoticons.any { it.mLabel == "\\o/" })

        // the emoticon panel fits on the screen
        val paint = Paint().apply { textSize = keyboard.mMostCommonKeyHeight * 0.4f }
        val panel = PopupKeysKeyboard.Builder(latinIME, emojiKey, keyboard, false, emojiKey.width, emojiKey.height, paint).build()
        assertTrue(panel.mOccupiedWidth <= keyboard.mOccupiedWidth, "panel ${panel.mOccupiedWidth} keyboard ${keyboard.mOccupiedWidth}")
        println("EMOTICON PANEL ${panel.mOccupiedWidth}x${panel.mOccupiedHeight} keyboard ${keyboard.mOccupiedWidth}x${keyboard.mOccupiedHeight}")
    }
}
