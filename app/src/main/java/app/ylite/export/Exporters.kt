package app.ylite.export

import android.content.Context
import app.ylite.data.BleFrame
import app.ylite.data.Fillup
import app.ylite.data.GpsPoint
import app.ylite.data.Ride
import app.ylite.data.ServiceEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes ride data to app-specific external storage, where any file manager and
 * the share sheet can read it without storage permissions.
 *
 * Every format is built as text first. That way a single ride and the full
 * archive share one writer, and a bug in the GPX shows up in both.
 */
class Exporters(private val context: Context) {

    fun gpx(ride: Ride, points: List<GpsPoint>): File = write(ride, "gpx", gpxText(ride, points))

    fun framesCsv(ride: Ride, frames: List<BleFrame>): File =
        write(ride, "frames.csv", framesCsvText(frames))

    /** Frames captured without a ride, named by when the export was taken. */
    fun captureCsv(frames: List<BleFrame>): File {
        val file = File(directory(), "capture-${fileStamp(System.currentTimeMillis())}.csv")
        file.writeText(framesCsvText(frames))
        return file
    }

    /**
     * One zip with every ride, every frame and every log. The point is that a
     * lost phone costs you the phone, not the year of records on it.
     */
    fun everything(
        rides: List<Ride>,
        points: Map<Long, List<GpsPoint>>,
        frames: Map<Long, List<BleFrame>>,
        fillups: List<Fillup>,
        services: List<ServiceEntry>,
        stationNames: Map<Long, String> = emptyMap(),
    ): File {
        val file = File(directory(), "ylite-backup-${fileStamp(System.currentTimeMillis())}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }

            for (ride in rides) {
                val stamp = fileStamp(ride.startedAt)
                entry("gpx/ride-${ride.id}-$stamp.gpx", gpxText(ride, points[ride.id].orEmpty()))
                val rideFrames = frames[ride.id].orEmpty()
                if (rideFrames.isNotEmpty()) {
                    entry("frames/ride-${ride.id}-$stamp.csv", framesCsvText(rideFrames))
                }
            }
            entry("rides.csv", ridesCsvText(rides))
            entry("fillups.csv", fillupsCsvText(fillups, stationNames))
            entry("stations.csv", stationsCsvText(stationNames))
            entry("services.csv", servicesCsvText(services))
            entry(
                "README.txt",
                """
                Y-Lite backup, made ${isoTime(System.currentTimeMillis())}.

                gpx/       one GPX track per ride
                frames/    raw CCU notifications per ride, newest format first
                rides.csv  every ride with its start and end
                fillups.csv, services.csv  the logs from the Scooter tab

                Times are UTC in the CSVs and GPX, local in the file names.
                """.trimIndent() + "\n",
            )
        }
        return file
    }

    // ------------------------------------------------------------------- text

    private fun gpxText(ride: Ride, points: List<GpsPoint>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="Y-Lite" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <trk>")
        val trackName = listOfNotNull(ride.model, ride.name ?: "Ride ${ride.id}").joinToString(" · ")
        appendLine("    <name>${escape(trackName)}</name>")
        appendLine("    <trkseg>")
        for (point in points) {
            append("""      <trkpt lat="${point.lat}" lon="${point.lon}">""")
            point.altitude?.let { append("<ele>$it</ele>") }
            append("<time>${isoTime(point.time)}</time>")
            appendLine("</trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        appendLine("</gpx>")
    }

    private fun framesCsvText(frames: List<BleFrame>): String = buildString {
        appendLine("time_iso,time_ms,address,service,characteristic,hex")
        for (frame in frames) {
            appendLine(
                listOf(
                    isoTime(frame.time),
                    frame.time.toString(),
                    frame.address,
                    frame.service,
                    frame.characteristic,
                    frame.data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
                ).joinToString(","),
            )
        }
    }

    private fun ridesCsvText(rides: List<Ride>): String = buildString {
        appendLine("id,started_iso,ended_iso,duration_s,model,name,device_address")
        for (ride in rides) {
            appendLine(
                listOf(
                    ride.id.toString(),
                    isoTime(ride.startedAt),
                    ride.endedAt?.let { isoTime(it) }.orEmpty(),
                    ride.endedAt?.let { ((it - ride.startedAt) / 1000).toString() }.orEmpty(),
                    csv(ride.model),
                    csv(ride.name),
                    csv(ride.deviceAddress),
                ).joinToString(","),
            )
        }
    }

    private fun stationsCsvText(stationNames: Map<Long, String>): String = buildString {
        appendLine("id,name")
        for ((id, name) in stationNames.toSortedMap()) {
            appendLine(listOf(id.toString(), csv(name)).joinToString(","))
        }
    }

    private fun fillupsCsvText(fillups: List<Fillup>, stationNames: Map<Long, String>): String = buildString {
        appendLine("id,time_iso,odometer_km,litres,cost_total,full,station,note")
        for (fillup in fillups) {
            appendLine(
                listOf(
                    fillup.id.toString(),
                    isoTime(fillup.time),
                    "%.1f".format(Locale.US, fillup.odometerKm),
                    "%.3f".format(Locale.US, fillup.litres),
                    fillup.costTotal?.let { "%.2f".format(Locale.US, it) }.orEmpty(),
                    if (fillup.isFull) "1" else "0",
                    csv(fillup.stationId?.let { stationNames[it] }),
                    csv(fillup.note),
                ).joinToString(","),
            )
        }
    }

    private fun servicesCsvText(services: List<ServiceEntry>): String = buildString {
        appendLine("id,time_iso,kind,odometer_km")
        for (service in services) {
            appendLine(
                listOf(
                    service.id.toString(),
                    isoTime(service.time),
                    csv(service.kind),
                    "%.1f".format(Locale.US, service.odometerKm),
                ).joinToString(","),
            )
        }
    }

    // ------------------------------------------------------------------ files

    private fun write(ride: Ride, extension: String, text: String): File {
        val file = File(directory(), "ride-${ride.id}-${fileStamp(ride.startedAt)}.$extension")
        file.writeText(text)
        return file
    }

    private fun directory(): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "exports").apply { mkdirs() }
    }

    private fun fileStamp(epochMs: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(epochMs))

    private fun isoTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Quote a value only when it would otherwise break the row. */
    private fun csv(value: String?): String {
        if (value == null) return ""
        return if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
