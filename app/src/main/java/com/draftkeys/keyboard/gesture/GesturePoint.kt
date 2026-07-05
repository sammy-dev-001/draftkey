package com.draftkeys.keyboard.gesture

/**
 * A single captured touch point during a swipe gesture.
 *
 * @param x       Screen x-coordinate in pixels.
 * @param y       Screen y-coordinate in pixels.
 * @param timeMs  [MotionEvent.eventTime] — used for velocity but not currently scored.
 */
data class GesturePoint(
    val x: Float,
    val y: Float,
    val timeMs: Long
)
