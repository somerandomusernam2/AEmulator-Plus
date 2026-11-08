package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class DisplayGeometryTest {
    @Test fun tabletRotationFitsWithoutSwappingOrStretchingGuestPixels() {
        val landscape = DisplayGeometry.fit(1280, 800, 480, 800, 52, 0, false)
        assertEquals(480f / 800f, landscape.width.toFloat() / landscape.height, 0.002f)
        val side = DisplayGeometry.fit(1280, 800, 480, 800, 52, 1, false)
        assertEquals(480, side.width)
        assertEquals(800, side.height)
        assertTrue(side.left + side.width <= 1280 - 52)
    }
    @Test fun keepsControlsAtPhysicalBottomForAllTurns() {
        assertEquals(listOf(DisplayGeometry.Edge.BOTTOM, DisplayGeometry.Edge.RIGHT, DisplayGeometry.Edge.TOP, DisplayGeometry.Edge.LEFT),
            (0..3).map(DisplayGeometry::edge))
    }
    @Test fun reservesOnlyTheCurrentControlEdge() {
        val bottom = DisplayGeometry.fit(100, 200, 100, 200, 20, 0, false)
        assertEquals(180, bottom.height)
        val right = DisplayGeometry.fit(200, 100, 200, 100, 20, 1, false)
        assertEquals(180, right.width)
        val left = DisplayGeometry.fit(200, 100, 200, 100, 20, 3, false)
        assertEquals(20, left.left)
        val top = DisplayGeometry.fit(100, 200, 100, 200, 20, 2, false)
        assertEquals(20, top.top)
    }
    @Test fun hostResolutionFillsBehindControls() {
        assertEquals(DisplayGeometry.Rect(100, 200, 0, 0), DisplayGeometry.fit(100, 200, 100, 200, 20, 0, true))
        assertEquals(DisplayGeometry.Rect(200, 100, 0, 0), DisplayGeometry.fit(200, 100, 200, 100, 20, 1, true))
    }
    @Test fun convertsCurrentResolutionToNaturalDimensions() {
        assertEquals(1080 to 2400, DisplayGeometry.natural(2400, 1080, 1))
        assertEquals(1080 to 2400, DisplayGeometry.natural(1080, 2400, 0))
    }
    @Test fun availableAreaSkipsTheControlStrip() {
        assertEquals(DisplayGeometry.Rect(100, 180, 0, 0), DisplayGeometry.available(100, 200, 20, 0, false))
        assertEquals(DisplayGeometry.Rect(180, 100, 20, 0), DisplayGeometry.available(200, 100, 20, 3, false))
        assertEquals(DisplayGeometry.Rect(100, 200, 0, 0), DisplayGeometry.available(100, 200, 20, 0, true))
    }
    @Test fun draggedCornerKeepsOppositeCornerAndStaysInside() {
        val area = DisplayGeometry.Rect(100, 200, 0, 0)
        val cur = DisplayGeometry.Rect(60, 100, 20, 30)
        val a = DisplayGeometry.dragCorner(area, cur, DisplayGeometry.Corner.BOTTOM_RIGHT, 90, 180, 20, 20)
        assertEquals(DisplayGeometry.Rect(70, 150, 20, 30), a)
        val out = DisplayGeometry.dragCorner(area, cur, DisplayGeometry.Corner.BOTTOM_RIGHT, 500, 900, 20, 20)
        assertEquals(DisplayGeometry.Rect(80, 170, 20, 30), out)
        val tl = DisplayGeometry.dragCorner(area, cur, DisplayGeometry.Corner.TOP_LEFT, -50, -50, 20, 20)
        assertEquals(DisplayGeometry.Rect(80, 130, 0, 0), tl)
        val tiny = DisplayGeometry.dragCorner(area, cur, DisplayGeometry.Corner.TOP_LEFT, 79, 129, 20, 20)
        assertEquals(DisplayGeometry.Rect(20, 20, 60, 110), tiny)
    }
    @Test fun placementSurvivesAChangedAreaAndNeverLeavesIt() {
        val f = DisplayGeometry.fractions(DisplayGeometry.Rect(100, 200, 0, 0), DisplayGeometry.Rect(50, 100, 10, 20))
        val p = DisplayGeometry.place(DisplayGeometry.Rect(200, 100, 0, 0), f, 10, 10)
        assertEquals(DisplayGeometry.Rect(100, 50, 20, 10), p)
        val big = DisplayGeometry.place(DisplayGeometry.Rect(100, 100, 5, 5), DisplayGeometry.Frac(0.9f, 0.9f, 0.9f, 0.9f), 10, 10)
        assertTrue(big.left >= 5 && big.top >= 5 && big.left + big.width <= 105 && big.top + big.height <= 105)
    }
}
