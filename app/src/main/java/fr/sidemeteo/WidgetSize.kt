package fr.sidemeteo

/** The three shapes the home-screen widget can take, smallest first. */
enum class WidgetSize { TINY, ROW, FULL }

/** Below this width there is no room for the city name beside a temperature and a range. */
private const val ROW_MIN_WIDTH_DP = 200

/** Below this height the four hour cells cannot be stacked without clipping. */
private const val FULL_MIN_HEIGHT_DP = 100

/**
 * Picks the layout for a footprint the launcher reports in dp.
 *
 * Width gates the jump from [WidgetSize.TINY] to [WidgetSize.ROW] and height the jump from
 * [WidgetSize.ROW] to [WidgetSize.FULL], because that is what each layout actually runs out of:
 * the row needs horizontal room for city + temperature + range, the full layout needs vertical
 * room for the hour strip. A tall but narrow tile therefore stays tiny rather than claiming space
 * it does not have.
 *
 * Absurd input degrades to [WidgetSize.TINY] rather than throwing: an options bundle reports 0
 * before any launcher has measured the widget, and this runs on the render path.
 */
fun widgetSizeFor(widthDp: Int, heightDp: Int): WidgetSize = when {
    widthDp < ROW_MIN_WIDTH_DP -> WidgetSize.TINY
    heightDp < FULL_MIN_HEIGHT_DP -> WidgetSize.ROW
    else -> WidgetSize.FULL
}
