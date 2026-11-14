package app.ylite.fuelio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are shaped like real Fuelio backups: several `## Sections` in one
 * file, every field quoted, optional columns empty or absent, and a trailing
 * cost section that must NOT be read as fill-ups.
 */
class FuelioCsvTest {

    private val kmLitres = """
        "## Vehicle",,,,,,,,,,,,,,
        "Name","Description","DistUnit","FuelUnit","ConsumptionUnit","ImportCSVDateFormat","VIN","Insurance","Plate","Make","Model","Year"
        "Mio Gravis","Scooter","1","1","1","yyyy-MM-dd","","","","Yamaha","Mio Gravis","2023"
        "## Log",,,,,,,,,,,
        "Data","Odo (km)","Fuel (l)","Full","Price (optional)","km/l (optional)","latitude (optional)","longitude (optional)","City (optional)","Notes (optional)","Missed"
        "2026-01-10","1200","3.50","1","57.14","0.0","0","0","","first tank","0"
        "2026-01-20","1240","2.00","0","58.00","0.0","0","0","","top-up, half","0"
        "2026-02-01","1320","4.00","1","55.00","0.0","0","0","","","0"
        "## CostCategories",,,,,,,,,,,
        "Name","Color"
        "Fuel","#ff0000"
    """.trimIndent()

    @Test
    fun readsVehicleNameAndLogRows() {
        val result = FuelioCsv.parse(kmLitres)

        assertEquals("Mio Gravis", result.vehicle)
        assertEquals(3, result.fills.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun keepsLitresAndConvertsPricePerLitreToTotal() {
        val first = FuelioCsv.parse(kmLitres).fills.first()

        assertEquals(1200.0, first.odometerKm, 0.001)
        assertEquals(3.50, first.litres, 0.001)
        // Price is per litre in Fuelio, so the receipt total is 3.50 * 57.14.
        assertEquals(199.99, first.costTotal ?: 0.0, 0.5)
        assertTrue(first.isFull)
    }

    @Test
    fun marksPartialFillsAndKeepsQuotedNotesWithCommas() {
        val partial = FuelioCsv.parse(kmLitres).fills[1]

        assertEquals(false, partial.isFull)
        assertEquals(2.0, partial.litres, 0.001)
        assertEquals("top-up, half", partial.note)
    }

    @Test
    fun convertsMilesAndGallonsWhenTheHeaderSaysSo() {
        val imperial = """
            "## Vehicle",,,,,,,,,
            "Name","Description","DistUnit","FuelUnit","ConsumptionUnit","ImportCSVDateFormat"
            "Car","","2","2","2","MM/dd/yyyy"
            "## Log",,,,,,,,,,
            "Data","Odo (mi)","Fuel (us gallons)","Full","Price (optional)","mpg (optional)","latitude (optional)","longitude (optional)","City (optional)","Notes (optional)","Missed"
            "12/31/2026","1000","2.0","1","3.00","0","0","0","","","0"
        """.trimIndent()

        val result = FuelioCsv.parse(imperial)

        assertEquals(1, result.fills.size)
        val fill = result.fills.first()
        assertEquals(1000 * 1.609344, fill.odometerKm, 0.01)
        assertEquals(2.0 * 3.785411784, fill.litres, 0.001)
        assertNotNull(result.conversion)
        // 2 US gallons at 3.00 per gallon.
        assertEquals(6.0, fill.costTotal ?: 0.0, 0.001)
    }

    @Test
    fun usesTheDateFormatFromTheVehicleSection() {
        val dotted = """
            "## Vehicle"
            "Name","Description","DistUnit","FuelUnit","ConsumptionUnit","ImportCSVDateFormat"
            "Bike","","1","1","1","dd.MM.yyyy"
            "## Log"
            "Data","Odo (km)","Fuel (l)","Full","Price (optional)"
            "31.12.2026","5000","4.0","1",""
        """.trimIndent()

        val fills = FuelioCsv.parse(dotted).fills

        assertEquals(1, fills.size)
        assertEquals(5000.0, fills.first().odometerKm, 0.001)
        // No price column value: cost stays unknown rather than becoming zero.
        assertEquals(null, fills.first().costTotal)
    }

    @Test
    fun skipsJunkRowsAndReportsThem() {
        val messy = """
            "## Log"
            "Data","Odo (km)","Fuel (l)","Full"
            "not a date","1200","3.5","1"
            "2026-03-01","","3.5","1"
            "2026-03-02","1400","0","1"
            "2026-03-03","1500","3.5","1"
        """.trimIndent()

        val result = FuelioCsv.parse(messy)

        assertEquals(1, result.fills.size)
        assertEquals(3, result.skipped)
        assertEquals(1500.0, result.fills.first().odometerKm, 0.001)
    }

    @Test
    fun reportsAFileThatIsNotAFuelioBackup() {
        val result = FuelioCsv.parse("date,litres\n2026-01-01,3.0")

        assertTrue(result.fills.isEmpty())
        assertNotNull(result.conversion)
    }
}
