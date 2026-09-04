package com.ogesture.data

enum class GestureAction { BACK, HOME, RECENTS }

enum class ZoneId { BOTTOM, LEFT_EDGE, RIGHT_EDGE }

enum class SwipeDirection { UP, RIGHT, LEFT }

data class ZoneConfig(
    val id: ZoneId,
    val action: GestureAction,
    val longAction: GestureAction?,
    val lengthPercent: Int,
    val thicknessDp: Int,
) {
    val swipeDirection: SwipeDirection get() = when (id) {
        ZoneId.BOTTOM -> SwipeDirection.UP
        ZoneId.LEFT_EDGE -> SwipeDirection.RIGHT
        ZoneId.RIGHT_EDGE -> SwipeDirection.LEFT
    }
}

/**
 * The fixed gesture layout: swipe up from the bottom for Home (hold for Recents),
 * swipe in from either side edge for Back.
 */
val GESTURE_ZONES = listOf(
    ZoneConfig(ZoneId.BOTTOM, GestureAction.HOME, longAction = GestureAction.RECENTS, lengthPercent = 80, thicknessDp = 12),
    ZoneConfig(ZoneId.LEFT_EDGE, GestureAction.BACK, longAction = null, lengthPercent = 25, thicknessDp = 20),
    ZoneConfig(ZoneId.RIGHT_EDGE, GestureAction.BACK, longAction = null, lengthPercent = 25, thicknessDp = 20),
)
