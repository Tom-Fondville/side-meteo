package fr.sidemeteo

/** The four shapes the home-screen widget can take, smallest first. */
enum class WidgetSize { TINY, COMPACT, ROW, FULL }

/** Below this width only the temperature and the city fit — one launcher cell. */
private const val COMPACT_MIN_WIDTH_DP = 100

/** Below this width there is no room for the condition label beside the temperature. */
private const val ROW_MIN_WIDTH_DP = 200

/** Below this height the four hour cells cannot be stacked without clipping. */
private const val FULL_MIN_HEIGHT_DP = 100

/**
 * Picks the layout for a footprint the launcher reports in dp.
 *
 * Width drives the first two steps and height only the last, because that is what each shape
 * actually runs out of: the compact shape needs horizontal room for a range beside the
 * temperature, the row shape needs more for the condition label, and only the full shape needs
 * vertical room, for its hour strip. So a tall tile one cell wide stays tiny rather than claiming
 * space it does not have.
 *
 * Absurd input degrades to [WidgetSize.TINY] rather than throwing: an options bundle reports 0
 * before any launcher has measured the widget, and this runs on the render path.
 */
fun widgetSizeFor(widthDp: Int, heightDp: Int): WidgetSize = when {
    widthDp < COMPACT_MIN_WIDTH_DP -> WidgetSize.TINY
    widthDp < ROW_MIN_WIDTH_DP -> WidgetSize.COMPACT
    heightDp < FULL_MIN_HEIGHT_DP -> WidgetSize.ROW
    else -> WidgetSize.FULL
}
