package dev.panasko.photoliz

import dev.panasko.photoliz.camera.AspectRatio
import dev.panasko.photoliz.camera.CropMath
import dev.panasko.photoliz.camera.ExposureScales
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureScalesTest {

    @Test
    fun shutterListIsInsideRangeAndSorted() {
        val list = ExposureScales.shuttersIn(125_000L, 30_000_000_000L) // 1/8000 .. 30 s
        assertEquals("1/8000", list.first().label)
        assertEquals("30\"", list.last().label)
        assertTrue(list.any { it.label == "1/125" })
        for (i in 1 until list.size) assertTrue(list[i].seconds > list[i - 1].seconds)
    }

    @Test
    fun shutterListFallsBackToMaxWhenNothingFits() {
        val list = ExposureScales.shuttersIn(1_000L, 1_000L)
        assertEquals(1, list.size)
    }

    @Test
    fun formatsShutterSpeeds() {
        assertEquals("1/125", ExposureScales.formatShutter(8_000_000L))
        assertEquals("1/60", ExposureScales.formatShutter(16_666_667L))
        assertEquals("1\"", ExposureScales.formatShutter(1_000_000_000L))
        assertEquals("2.5\"", ExposureScales.formatShutter(2_500_000_000L))
        assertEquals("--", ExposureScales.formatShutter(0L))
    }

    @Test
    fun isoStopsIncludeExtremes() {
        val stops = ExposureScales.isoStopsIn(44, 7100)
        assertEquals(44, stops.first())
        assertEquals(7100, stops.last())
        assertTrue(stops.contains(100))
        assertTrue(stops.contains(6400))
        for (i in 1 until stops.size) assertTrue(stops[i] > stops[i - 1])
    }

    @Test
    fun isoStopsDropNearDuplicateExtremes() {
        val stops = ExposureScales.isoStopsIn(98, 6400)
        assertEquals(100, stops.first())
    }

    @Test
    fun isoSnapsMagnetically() {
        assertEquals(100, ExposureScales.snapIso(98.0, 50, 6400, 1.0 / 12))
        assertEquals(110, ExposureScales.snapIso(110.0, 50, 6400, 1.0 / 12))
        assertEquals(50, ExposureScales.snapIso(10.0, 50, 6400, 1.0 / 12))
        assertEquals(6400, ExposureScales.snapIso(99999.0, 50, 6400, 1.0 / 12))
    }

    @Test
    fun isoStopsRoundTrip() {
        val s = ExposureScales.isoToStops(400.0, 50)
        assertEquals(3.0, s, 1e-9)
        assertEquals(400.0, ExposureScales.stopsToIso(s, 50), 1e-6)
    }

    @Test
    fun aspectCycleRespectsExtras() {
        assertEquals(listOf(AspectRatio.TRUE, AspectRatio.R16_9, AspectRatio.R4_3), AspectRatio.cycle(emptySet()))
        val withSquare = AspectRatio.cycle(setOf("1:1", "2.39:1"))
        assertTrue(withSquare.contains(AspectRatio.R1_1))
        assertTrue(withSquare.contains(AspectRatio.R239))
        assertFalse(withSquare.contains(AspectRatio.R185))
    }

    @Test
    fun cropLandscape169() {
        val c = CropMath.centered(4000, 3000, 16.0 / 9.0, 1.0)
        assertEquals(4000, c.width)
        assertEquals(2250, c.height)
        assertEquals(0, c.left)
        assertEquals(374, c.top) // aligned to even
    }

    @Test
    fun cropSquareAndPortrait() {
        val sq = CropMath.centered(4000, 3000, 1.0, 1.0)
        assertEquals(3000, sq.width)
        assertEquals(3000, sq.height)
        assertEquals(500, sq.left)
        val portrait = CropMath.centered(3000, 4000, 16.0 / 9.0, 1.0)
        assertEquals(2250, portrait.width)
        assertEquals(4000, portrait.height)
    }

    @Test
    fun cropZoomAndTrue() {
        val full = CropMath.centered(4000, 3000, null, 1.0)
        assertEquals(4000, full.width)
        assertEquals(3000, full.height)
        val z = CropMath.centered(4000, 3000, null, 2.0)
        assertEquals(2000, z.width)
        assertEquals(1500, z.height)
        assertEquals(1000, z.left)
        assertEquals(750, z.top)
        // Narrow ratio on a wide frame keeps the short side.
        val movietone = CropMath.centered(4000, 3000, 1.19, 1.0)
        assertEquals(3000, movietone.height)
        assertEquals(3570, movietone.width)
    }
}
