package fr.sidemeteo

/** The six shapes the home-screen widget can take, smallest first. */
enum class WidgetSize { TINY, COMPACT, TALL, ROW, FULL, LARGE }

/** Below this width only the temperature and the city fit — one launcher cell. */
private const val COMPACT_MIN_WIDTH_DP = 100

/** Below this width there is no room for the condition label beside the temperature. */
private const val ROW_MIN_WIDTH_DP = 200

/** Below this height nothing can be stacked under the current conditions. */
private const val STACKED_MIN_HEIGHT_DP = 100

/** Below this height there is no room for daily rows under the hour strip. */
private const val LARGE_MIN_HEIGHT_DP = 200

/**
 * Picks the layout for a footprint the launcher reports in dp.
 *
 * Width decides how much can sit side by side, height how much can be stacked, and the two are
 * read in that order because a narrow tile cannot borrow a wide shape however tall it grows: at two
 * cells across, hours have to be rows rather than columns, which is what [WidgetSize.TALL] is.
 *
 * Absurd input degrades to [WidgetSize.TINY] rather than throwing: an options bundle reports 0
 * before any launcher has measured the widget, and this runs on the render path.
 */
fun widgetSizeFor(widthDp: Int, heightDp: Int): WidgetSize = when {
    widthDp < COMPACT_MIN_WIDTH_DP -> WidgetSize.TINY
    widthDp < ROW_MIN_WIDTH_DP ->
        if (heightDp < STACKED_MIN_HEIGHT_DP) WidgetSize.COMPACT else WidgetSize.TALL
    heightDp < STACKED_MIN_HEIGHT_DP -> WidgetSize.ROW
    heightDp < LARGE_MIN_HEIGHT_DP -> WidgetSize.FULL
    else -> WidgetSize.LARGE
}
