package app.ylite.export

import android.content.Context
import app.ylite.data.BleFrame
import app.ylite.data.GpsPoint
import app.ylite.data.Ride
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes ride data to app-specific external storage, where any file manager and
 * the share sheet can read it without storage permissions.
 */
class Exporters(private val context: Context) {

    fun gpx(ride: Ride, points: List<GpsPoint>): File {
        val file = fileFor(ride, "gpx")
        file.bufferedWriter().use { out ->
            out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            out.newLine()
            out.write("""<gpx version="1.1" creator="Y-Lite" xmlns="http://www.topografix.com/GPX/1/1">""")
            out.newLine()
            out.write("  <trk>")
            out.newLine()
            out.write("    <name>${escape(ride.name ?: "Ride ${ride.id}")}</name>")
            out.newLine()
            out.write("    <trkseg>")
            out.newLine()
            for (point in points) {
                out.write("""      <trkpt lat="${point.lat}" lon="${point.lon}">""")
                point.altitude?.let { out.write("<ele>$it</ele>") }
                out.write("<time>${isoTime(point.time)}</time>")
                out.write("</trkpt>")
                out.newLine()
            }
            out.write("    </trkseg>")
            out.newLine()
            out.write("  </trk>")
            out.newLine()
            out.write("</gpx>")
            out.newLine()
        }
        return file
    }

    fun framesCsv(ride: Ride, frames: List<BleFrame>): File {
        val file = fileFor(ride, "frames.csv")
        file.bufferedWriter().use { out ->
            out.write("time_iso,time_ms,address,service,characteristic,hex")
            out.newLine()
            for (frame in frames) {
                out.write(
                    listOf(
                        isoTime(frame.time),
                        frame.time.toString(),
                        frame.address,
                        frame.service,
                        frame.characteristic,
                        frame.data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
                    ).joinToString(","),
                )
                out.newLine()
            }
        }
        return file
    }

    private fun fileFor(ride: Ride, extension: String): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(ride.startedAt))
        return File(dir, "ride-${ride.id}-$stamp.$extension")
    }

    private fun isoTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
