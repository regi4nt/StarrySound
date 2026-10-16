package com.fxsoundlite.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps the vertical Quick pager, which lives inside the horizontal page pager of [MainActivity].
 *
 * Without it, any slightly diagonal swipe up / down can be grabbed by the outer pager (RecyclerView only checks
 * "moved sideways more than the touch slop", not "moved sideways more than up / down") and the app jumps to
 * another tab. Here the direction is decided once the finger has moved past the touch slop:
 *  - mostly vertical (the common case, with a bias towards vertical): the outer pager is locked out until the
 *    finger is lifted, so the feed scrolls and a drifting thumb never changes tab;
 *  - clearly horizontal: the outer pager may take it, so swiping sideways still leaves Quick.
 */
class NestedVerticalHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** When true the outer pager never gets a swipe from here: the feed only scrolls up / down. */
    var verticalOnly = false

    /** Sideways has to win by this factor before it counts as a horizontal swipe. */
    private val horizontalBias = 1.6f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                // Hold the outer pager back until the direction is known.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(e.x - downX)
                val dy = abs(e.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    parent?.requestDisallowInterceptTouchEvent(verticalOnly || !(dx > dy * horizontalBias))
                }
            }
        }
        return super.onInterceptTouchEvent(e)
    }
}
