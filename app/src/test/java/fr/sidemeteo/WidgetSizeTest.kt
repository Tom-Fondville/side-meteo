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
    fun `three cells or more and two rows tall gets the full layout`() {
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 216, heightDp = 155))
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 400, heightDp = 155))
        // 200dp of height is deliberately LARGE now, which is what the daily rows need.
        assertEquals(WidgetSize.LARGE, widgetSizeFor(widthDp = 400, heightDp = 200))
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
    fun `two cells wide and two tall gets the tall layout, with its hourly rows`() {
        assertEquals(WidgetSize.TALL, widgetSizeFor(widthDp = 144, heightDp = 155))
        assertEquals(WidgetSize.TALL, widgetSizeFor(widthDp = 199, heightDp = 300))
    }

    @Test
    fun `three cells wide and three tall gets the large layout, with its daily rows`() {
        assertEquals(WidgetSize.LARGE, widgetSizeFor(widthDp = 216, heightDp = 232))
        assertEquals(WidgetSize.LARGE, widgetSizeFor(widthDp = 288, heightDp = 232))
    }

    @Test
    fun `the shapes the user already approved do not move`() {
        // Regression guards: these five footprints were checked on the device and must keep the
        // shape they were checked with.
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 72, heightDp = 78))
        assertEquals(WidgetSize.COMPACT, widgetSizeFor(widthDp = 144, heightDp = 78))
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 216, heightDp = 78))
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 216, heightDp = 155))
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 288, heightDp = 155))
    }

    @Test
    fun `height never promotes a narrow tile beyond what its width can hold`() {
        // One cell wide stays tiny however tall it gets, and two cells never reaches ROW or FULL:
        // neither has the horizontal room those shapes need.
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 72, heightDp = 200))
        assertEquals(WidgetSize.TALL, widgetSizeFor(widthDp = 144, heightDp = 400))
    }

    @Test
    fun `the vertical thresholds are exact too`() {
        assertEquals(WidgetSize.COMPACT, widgetSizeFor(widthDp = 144, heightDp = 99))
        assertEquals(WidgetSize.TALL, widgetSizeFor(widthDp = 144, heightDp = 100))
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 250, heightDp = 199))
        assertEquals(WidgetSize.LARGE, widgetSizeFor(widthDp = 250, heightDp = 200))
    }

    @Test
    fun `nonsense dimensions degrade to the tiny layout instead of throwing`() {
        // The options bundle reports 0 before a launcher has ever measured the widget.
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 0, heightDp = 0))
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = -50, heightDp = -50))
    }
}
