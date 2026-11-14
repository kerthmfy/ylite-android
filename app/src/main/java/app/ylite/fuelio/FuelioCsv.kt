package app.ylite.fuelio

import app.ylite.data.Fillup
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reads the CSV that Fuelio (Sygic) writes for its own backups.
 *
 * That file is not a table: it is several tables in one stream, each introduced
 * by a `## Section` line and each with its own header row. The fill-ups live
 * under `## Log`, and the units are declared in the header text itself
 * ("Odo (km)", "Fuel (l)"), which is the only place they are stated.
 *
 * Pure text in, records out: no Android types, so it can be tested on the JVM.
 */
object FuelioCsv {

    data class Result(
        val fills: List<Fillup>,
        val vehicle: String?,
        val skipped: Int,
        /** Set when units had to be converted, so the UI can say so. */
        val conversion: String? = null,
    )

    private val fallbackPatterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd",
        "dd.MM.yyyy HH:mm",
        "dd.MM.yyyy",
        "dd/MM/yyyy HH:mm",
        "dd/MM/yyyy",
        "MM/dd/yyyy HH:mm",
        "MM/dd/yyyy",
    )

    fun parse(text: String): Result {
        val rows = parseCsv(text.removePrefix("\uFEFF"))

        // "## Vehicle" is followed by its header row, then one row of values.
        val vehicleStart = rows.indexOfFirst { row ->
            row.firstOrNull()?.trim()?.startsWith("##") == true &&
                row.joinToString(" ").contains("vehicle", ignoreCase = true)
        }
        val vehicleHeader = if (vehicleStart >= 0) rows.getOrNull(vehicleStart + 1).orEmpty() else emptyList()
        val vehicleValues = if (vehicleStart >= 0) rows.getOrNull(vehicleStart + 2).orEmpty() else emptyList()
        val vehicle = vehicleValues.getOrNull(0)?.takeIf { it.isNotBlank() }
        val importFormat = vehicleHeader
            .indexOfFirst { it.contains("ImportCSVDateFormat", ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.let { vehicleValues.getOrNull(it) }

        val logHeaderIndex = rows.indexOfFirst { row ->
            row.firstOrNull()?.trim()?.startsWith("##", ignoreCase = false) == true &&
                row.joinToString(" ").contains("log", ignoreCase = true)
        }
        if (logHeaderIndex < 0) {
            return Result(fills = emptyList(), vehicle = vehicle, skipped = 0, conversion = "No ## Log section found.")
        }

        // The header row is the one that names the odometer column.
        var headerIndex = -1
        for (i in logHeaderIndex + 1 until rows.size) {
            val joined = rows[i].joinToString(" ")
            if (joined.contains("odo", ignoreCase = true)) {
                headerIndex = i
                break
            }
            if (rows[i].firstOrNull()?.trim()?.startsWith("##") == true) break
        }
        if (headerIndex < 0) {
            return Result(fills = emptyList(), vehicle = vehicle, skipped = 0, conversion = "No Log header row found.")
        }

        val header = rows[headerIndex].map { it.trim() }
        fun column(vararg needles: String): Int =
            header.indexOfFirst { cell -> needles.any { cell.contains(it, ignoreCase = true) } }

        val dateCol = column("data", "date")
        val odoCol = column("odo")
        val fuelCol = column("fuel")
        val fullCol = column("full")
        val priceCol = column("price")
        val cityCol = column("city")
        val noteCol = column("notes", "note")

        if (dateCol < 0 || odoCol < 0 || fuelCol < 0) {
            return Result(fills = emptyList(), vehicle = vehicle, skipped = 0, conversion = "Log columns not recognised.")
        }

        val miles = header[odoCol].contains("mi", ignoreCase = true) &&
            !header[odoCol].contains("km", ignoreCase = true)
        val gallons = header[fuelCol].contains("gallon", ignoreCase = true)
        val conversion = when {
            miles && gallons -> "Converted from miles and US gallons."
            miles -> "Converted from miles."
            gallons -> "Converted from US gallons."
            else -> null
        }

        val patterns = buildList {
            importFormat?.takeIf { it.isNotBlank() }?.let {
                add(it)
                add("$it HH:mm")
                add("$it HH:mm:ss")
            }
            addAll(fallbackPatterns)
        }

        val fills = mutableListOf<Fillup>()
        var skipped = 0
        for (i in headerIndex + 1 until rows.size) {
            val row = rows[i]
            if (row.all { it.isBlank() }) continue
            if (row.firstOrNull()?.trim()?.startsWith("##") == true) break

            val time = parseTime(row.getOrNull(dateCol).orEmpty(), patterns)
            val odo = number(row.getOrNull(odoCol).orEmpty())
            val fuel = number(row.getOrNull(fuelCol).orEmpty())
            if (time == null || odo == null || fuel == null || fuel <= 0.0) {
                skipped++
                continue
            }

            val litres = if (gallons) fuel * US_GALLON_LITRES else fuel
            val odometerKm = if (miles) odo * MILE_KM else odo
            // Fuelio's Price is per unit of fuel, not the total on the receipt.
            val pricePerUnit = if (priceCol >= 0) number(row.getOrNull(priceCol).orEmpty()) else null
            val costTotal = pricePerUnit?.let { it * fuel }

            fills += Fillup(
                time = time,
                odometerKm = odometerKm,
                litres = litres,
                costTotal = costTotal,
                isFull = fullCol < 0 || row.getOrNull(fullCol).orEmpty().trim().let { it == "1" || it.equals("true", true) },
                note = listOfNotNull(
                    row.getOrNull(cityCol)?.takeIf { it.isNotBlank() },
                    row.getOrNull(noteCol)?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { null },
            )
        }

        return Result(fills = fills, vehicle = vehicle, skipped = skipped, conversion = conversion)
    }

    // ------------------------------------------------------------------ helpers

    private fun parseTime(raw: String, patterns: List<String>): Long? {
        val value = raw.trim().trim('"')
        if (value.isEmpty()) return null
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getDefault()
            }
            val position = ParsePosition(0)
            val parsed: Date? = format.parse(value, position)
            if (parsed != null && position.index == value.length) return parsed.time
        }
        return null
    }

    private fun number(raw: String): Double? {
        val cleaned = raw.trim().trim('"').replace(" ", "")
        if (cleaned.isEmpty()) return null
        return cleaned.replace(',', '.').toDoubleOrNull()
    }

    /**
     * Fuelio quotes every field, and a quoted field may contain commas, quotes
     * (doubled) and line breaks. A split on "," loses notes and city names.
     */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows.add(row.toList())
            row.clear()
        }

        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> endField()
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    endRow()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private const val MILE_KM = 1.609344
    private const val US_GALLON_LITRES = 3.785411784
}
