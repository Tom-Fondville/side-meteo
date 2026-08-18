package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizeTest {

    @Test
    fun `a one-cell footprint gets the tiny layout`() {
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 57, heightDp = 40))
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 72, heightDp = 40))
    }

    @Test
    fun `a two-cell footprint gets the compact layout, which has room for the range`() {
        assertEquals(WidgetSize.COMPACT, widgetSizeFor(widthDp = 110, heightDp = 40))
        assertEquals(WidgetSize.COMPACT, widgetSizeFor(widthDp = 180, heightDp = 40))
    }

    @Test
    fun `three cells or more and short gets the row layout`() {
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 216, heightDp = 40))
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 400, heightDp = 90))
    }

    @Test
    fun `three cells or more and tall gets the full layout`() {
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 216, heightDp = 155))
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 400, heightDp = 200))
    }

    @Test
    fun `the three thresholds are exact, not approximate`() {
        // One dp under each boundary falls to the smaller shape.
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 99, heightDp = 40))
        assertEquals(WidgetSize.COMPACT, widgetSizeFor(widthDp = 100, heightDp = 40))
        assertEquals(WidgetSize.COMPACT, widgetSizeFor(widthDp = 199, heightDp = 40))
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 200, heightDp = 40))
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 250, heightDp = 99))
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 250, heightDp = 100))
    }

    @Test
    fun `height never promotes a narrow tile beyond what its width can hold`() {
        // Tall but one cell wide stays tiny; tall but two cells stays compact. Neither has the
        // horizontal room the row and full shapes need.
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 72, heightDp = 200))
        assertEquals(WidgetSize.COMPACT, widgetSizeFor(widthDp = 144, heightDp = 200))
    }

    @Test
    fun `nonsense dimensions degrade to the tiny layout instead of throwing`() {
        // The options bundle reports 0 before a launcher has ever measured the widget.
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 0, heightDp = 0))
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = -50, heightDp = -50))
    }
}
