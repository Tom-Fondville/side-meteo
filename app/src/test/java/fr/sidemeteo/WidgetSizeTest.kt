package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizeTest {

    @Test
    fun `a two-by-one footprint gets the tiny layout`() {
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 110, heightDp = 40))
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 180, heightDp = 40))
    }

    @Test
    fun `a wide but short footprint gets the row layout`() {
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 250, heightDp = 40))
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 400, heightDp = 90))
    }

    @Test
    fun `a wide and tall footprint gets the full layout`() {
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 250, heightDp = 110))
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 400, heightDp = 200))
    }

    @Test
    fun `height decides between row and full, width between tiny and row`() {
        // One dp under each threshold falls to the smaller layout, so the boundaries are exact
        // rather than approximately right.
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 250, heightDp = 99))
        assertEquals(WidgetSize.FULL, widgetSizeFor(widthDp = 250, heightDp = 100))
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 199, heightDp = 40))
        assertEquals(WidgetSize.ROW, widgetSizeFor(widthDp = 200, heightDp = 40))
    }

    @Test
    fun `a tall but narrow footprint stays tiny rather than claiming space it lacks`() {
        // Tall enough for the full layout, too narrow for its four hour cells.
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 120, heightDp = 200))
    }

    @Test
    fun `nonsense dimensions degrade to the tiny layout instead of throwing`() {
        // The options bundle returns 0 before a launcher has ever reported a size.
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = 0, heightDp = 0))
        assertEquals(WidgetSize.TINY, widgetSizeFor(widthDp = -50, heightDp = -50))
    }
}
