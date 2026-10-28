package com.remotephone

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.max

/**
 * Tablets, foldables, landscape and split screen: the screens are one column of cards written
 * for a phone held upright. Instead of keeping a second copy of each layout, the column is
 * reshaped in code from the window width (Configuration.screenWidthDp, which follows split
 * screen, unfolding and rotation; the activity is recreated on each change):
 *
 *  - compact (< [TWO_COLUMNS_DP]): one column, as before, capped to a readable width;
 *  - wider: the main screen splits into two columns, the cards that set things up (audio, web
 *    client, accessibility, keyboard, settings) going to the second one.
 */
object AdaptiveLayout {
    const val TWO_COLUMNS_DP = 700
    private const val COLUMN_MAX_DP = 560
    private const val TWO_COLUMNS_MAX_DP = 1120
    private const val GUTTER_DP = 24

    /** The vertical LinearLayout inside the activity's ScrollView. */
    private fun content(activity: Activity): LinearLayout? {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? ScrollView ?: return null
        return root.getChildAt(0) as? LinearLayout
    }

    private fun dp(activity: Activity, v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    /** Centres the column and caps its width at [maxDp] (padding grows on wide windows). */
    fun centerColumn(activity: Activity, maxDp: Int = COLUMN_MAX_DP) {
        val content = content(activity) ?: return
        val width = activity.resources.configuration.screenWidthDp
        val side = max(GUTTER_DP, (width - maxDp) / 2)
        content.setPadding(dp(activity, side), content.paddingTop, dp(activity, side), content.paddingBottom)
    }

    /**
     * Main screen: two columns when the window is wide enough. Views that are, or contain, one
     * of [secondColumnIds] move to the second column, keeping their order; the rest stay first.
     */
    fun mainScreen(activity: Activity, secondColumnIds: IntArray) {
        val content = content(activity) ?: return
        // Short windows (phone in landscape, half of a split screen): less air above the title
        if (activity.resources.configuration.screenHeightDp < 520) {
            (content.getChildAt(0)?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(activity, 8)
        }
        if (activity.resources.configuration.screenWidthDp < TWO_COLUMNS_DP) {
            centerColumn(activity)
            return
        }
        centerColumn(activity, TWO_COLUMNS_MAX_DP)

        val children = (0 until content.childCount).map { content.getChildAt(it) }
        // The title stays above both columns
        val header = children.takeWhile { it is android.widget.TextView && it !is android.widget.Button }
        val rest = children.drop(header.size)
        content.removeAllViews()
        header.forEach { content.addView(it) }

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val first = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val second = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (child in rest) {
            val target = if (secondColumnIds.any { child.id == it || child.findViewById<View>(it) != null }) second else first
            // A spacer without an id (bottom gap before Settings) belongs with Settings
            val spacer = child.id == View.NO_ID && child !is ViewGroup
            (if (spacer) second else target).addView(child)
        }
        row.addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(activity, GUTTER_DP)
        })
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
