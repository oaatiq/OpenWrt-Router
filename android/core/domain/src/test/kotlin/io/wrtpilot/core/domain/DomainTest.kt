package io.wrtpilot.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class ScheduleGridTest {

    @Test
    fun `weekdays evening merges into one rule`() {
        var g = ScheduleGrid.empty()
        for (d in 0..4) for (h in 17 until 21) g = g.with(d, h, true)
        assertEquals(listOf("mon,tue,wed,thu,fri 17:00-21:00"), g.toRules())
    }

    @Test
    fun `several ranges and days`() {
        val g = ScheduleGrid.schoolNights()
        assertEquals(
            listOf(
                "mon,tue,wed,thu,fri 07:00-08:00",
                "mon,tue,wed,thu,fri 16:00-21:00",
                "sat,sun 09:00-22:00",
            ),
            g.toRules(),
        )
    }

    @Test
    fun `round trip including midnight crossing`() {
        val rules = listOf("fri 20:00-01:00", "mon,wed 00:00-24:00", "sat-sun 09:00-22:00", "daily 06:00-07:00")
        val g = ScheduleGrid.fromRules(rules)!!
        assertTrue(g.isAllowed(4, 23))
        assertTrue("spills into saturday", g.isAllowed(5, 0))
        assertFalse(g.isAllowed(5, 1))
        assertEquals(24, g.allowedHoursOn(0))
        assertEquals(g, ScheduleGrid.fromRules(g.toRules()))
    }

    @Test
    fun `canonical router format with sunday wrap`() {
        val g = ScheduleGrid.fromRules(listOf("sun,mon 10:00-11:00"))!!
        assertTrue(g.isAllowed(6, 10))
        assertTrue(g.isAllowed(0, 10))
        assertEquals(listOf("mon,sun 10:00-11:00"), g.toRules())
    }

    @Test
    fun `minutes that do not fit the grid`() {
        assertNull(ScheduleGrid.fromRules(listOf("mon 07:30-08:00")))
        assertNull(ScheduleGrid.fromRules(listOf("garbage")))
    }

    @Test
    fun `rules never use localised digits`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val g = ScheduleGrid.empty().with(0, 7, true)
            assertEquals(listOf("mon 07:00-08:00"), g.toRules())
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `full and empty`() {
        assertTrue(ScheduleGrid.full().isFull)
        assertTrue(ScheduleGrid.empty().isEmpty)
        assertEquals(listOf("mon,tue,wed,thu,fri,sat,sun 00:00-24:00"), ScheduleGrid.full().toRules())
        assertEquals(emptyList<String>(), ScheduleGrid.empty().toRules())
    }

    @Test
    fun `copy day`() {
        val g = ScheduleGrid.empty().with(0, 9, true).copyDay(0, listOf(1, 2))
        assertTrue(g.isAllowed(2, 9))
        assertEquals(listOf("mon,tue,wed 09:00-10:00"), g.toRules())
    }
}

class UnitsTest {
    @Test
    fun scaling() {
        assertEquals(Scaled(1.5, ByteUnit.GB), Units.scaleBytes(1_500_000_000))
        assertEquals(Scaled(999.0, ByteUnit.B), Units.scaleBytes(999))
        assertEquals(Scaled(50.0, RateUnit.MBPS), Units.scaleRate(50_000_000))
        assertEquals(Scaled(0.0, RateUnit.BPS), Units.scaleRate(0))
        assertEquals(1, Units.fractionDigits(12.3))
        assertEquals(2, Units.fractionDigits(1.23))
        assertEquals(0, Units.fractionDigits(123.0))
        assertEquals(5000, Units.mbpsToKbps(5.0))
    }
}

class ClassifierTest {
    @Test
    fun heuristics() {
        assertEquals(DeviceKind.PHONE, DeviceClassifier.classify(null, "Galaxy-S23", null))
        assertEquals(DeviceKind.TABLET, DeviceClassifier.classify("Kids iPad", null, "Apple"))
        assertEquals(DeviceKind.CONSOLE, DeviceClassifier.classify(null, "PS5-123", null))
        assertEquals(DeviceKind.TV, DeviceClassifier.classify(null, "LGwebOSTV", null))
        assertEquals(DeviceKind.IOT, DeviceClassifier.classify(null, "", "Espressif Inc"))
        assertEquals(DeviceKind.PHONE, DeviceClassifier.classify(null, null, "Apple"))
        assertEquals(DeviceKind.UNKNOWN, DeviceClassifier.classify(null, null, null))
    }
}

class OuiTest {
    @Test
    fun lookup() {
        val t = OuiTable.parse("F0D1A9\tApple\nB827EB\tRaspberry Pi Foundation\nbroken\n".reader().buffered())
        assertEquals(2, t.size)
        assertEquals("Apple", t.lookup("f0:d1:a9:12:34:56"))
        assertEquals("Raspberry Pi Foundation", t.lookup("B8-27-EB-00-00-01"))
        assertNull(t.lookup("00:11:22:33:44:55"))
        assertNull("randomised MAC", t.lookup("f2:d1:a9:12:34:56"))
        assertTrue(OuiTable.isRandomized("da:a1:19:00:00:01"))
    }
}

class CsvTest {
    @Test
    fun export() {
        val csv = CsvExport.write(
            listOf(UsageRecord("Home", "Kid, laptop", "aa:bb:cc:dd:ee:01", 1790000000, 100, 20)),
            ZoneOffset.UTC, daily = true,
        )
        assertEquals(
            "router,device,mac,date,download_bytes,upload_bytes\r\nHome,\"Kid, laptop\",aa:bb:cc:dd:ee:01,2026-09-21,100,20\r\n",
            csv,
        )
        assertEquals("'=cmd", CsvExport.escape("=cmd"))
    }
}
