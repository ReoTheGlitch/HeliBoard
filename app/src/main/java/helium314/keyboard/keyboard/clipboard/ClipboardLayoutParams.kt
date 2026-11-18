// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import kotlin.math.max
import kotlin.math.min
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

class ClipboardLayoutParams(ctx: Context) {

    private val keyVerticalGap: Int
    private val keyHorizontalGap: Int
    private val listHeight: Int
    private val listHeightWithInput: Int
    val bottomRowKeyboardHeight: Int
    /** height of the folder bar at the top of the clipboard panel */
    val headerHeight: Int
    /** height of one row of the small input keyboard, which replaces the bottom row while typing */
    val inputRowHeight: Int

    init {
        val res = ctx.resources
        val sv = Settings.getValues()
        val defaultKeyboardHeight = ResourceUtils.getSecondaryKeyboardHeight(res, sv)
        val defaultKeyboardWidth = ResourceUtils.getKeyboardWidth(ctx, sv)

        keyVerticalGap = (res.getFraction(R.fraction.config_key_vertical_gap_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.mKeyGapScale).toInt()
        keyHorizontalGap = (res.getFraction(R.fraction.config_key_horizontal_gap_holo,
            defaultKeyboardWidth, defaultKeyboardWidth) * sv.mKeyGapScale).toInt()
        val bottomPadding = (res.getFraction(R.fraction.config_keyboard_bottom_padding_holo,
                defaultKeyboardHeight, defaultKeyboardHeight) * sv.mBottomPaddingScale).toInt()
        val topPadding = res.getFraction(R.fraction.config_keyboard_top_padding_holo,
                defaultKeyboardHeight, defaultKeyboardHeight).toInt()

        val rowCount = KeyboardParams.DEFAULT_KEYBOARD_ROWS + sv.mExtraRows
        bottomRowKeyboardHeight = (defaultKeyboardHeight - bottomPadding - topPadding) / rowCount - keyVerticalGap / 2
        // nothing that is tapped is lower than the recommended 48 dp for touch screens, unless the keys of the keyboard are smaller too
        val minTouchSize = (48 * res.displayMetrics.density).toInt()
        headerHeight = max(bottomRowKeyboardHeight * 3 / 4, min(minTouchSize, bottomRowKeyboardHeight))
        inputRowHeight = max(bottomRowKeyboardHeight * 17 / 20, min(minTouchSize, bottomRowKeyboardHeight))
        // height calculation is not good enough, probably also because keyboard top padding might be off by a pixel (see KeyboardParser)
        val offset = 1.25f * res.displayMetrics.density * sv.mKeyboardHeightScale
        listHeight = defaultKeyboardHeight - headerHeight - bottomRowKeyboardHeight - bottomPadding + offset.toInt()
        // while typing, the input keyboard takes the place of the bottom row
        // the list keeps room for about two rows of clips, the panel gets higher than the keyboard for that
        listHeightWithInput = max(bottomRowKeyboardHeight * 2,
            defaultKeyboardHeight - headerHeight - ClipboardMiniKeyboard.ROW_COUNT * inputRowHeight - bottomPadding + offset.toInt())
    }

    fun setListProperties(recycler: RecyclerView, inputKeyboardShown: Boolean = false) {
        (recycler.layoutParams as FrameLayout.LayoutParams).apply {
            height = if (inputKeyboardShown) listHeightWithInput else listHeight
            recycler.layoutParams = this
        }
    }

    fun setItemProperties(view: View) {
        (view.layoutParams as RecyclerView.LayoutParams).apply {
            topMargin = keyHorizontalGap / 2
            bottomMargin = keyVerticalGap / 2
            marginStart = keyHorizontalGap / 2
            marginEnd = keyHorizontalGap / 2
            view.layoutParams = this
        }
    }
}
