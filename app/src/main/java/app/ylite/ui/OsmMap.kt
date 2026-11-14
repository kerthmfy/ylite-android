package app.ylite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.ylite.R
import org.osmdroid.api.IGeoPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * osmdroid inside Compose. Compose does not own a View lifecycle, so the map's
 * onResume/onPause/onDetach are driven by hand.
 *
 * @param track recorded GPS points, oldest first
 * @param current live position, or null to centre on the end of the track
 * @param follow keep the camera on the moving position (live) or fit the track once (history)
 */
@Composable
fun OsmMap(
    track: List<Pair<Double, Double>>,
    current: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
    follow: Boolean = true,
) {
    val context = LocalContext.current
    val colors = LocalYColors.current
    val accent = colors.accent.toArgb()
    val mapView = remember { MapView(context) }
    var fitted by remember { mutableStateOf(false) }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // AndroidView does not clip its content, and osmdroid happily paints past its
    // measured bounds. Without both clips the map bleeds under the numbers below it.
    Box(modifier.clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer { clip = true },
            factory = {
            mapView.apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                setTilesScaledToDpi(true)
                controller.setZoom(15.0)
            }
        },
        update = { map ->
            map.overlays.clear()

            if (track.size > 1) {
                val line = Polyline(map)
                line.setPoints(track.map { GeoPoint(it.first, it.second) })
                line.outlinePaint.color = accent
                line.outlinePaint.strokeWidth = 9f
                line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                line.outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                map.overlays.add(line)
            }

            val position = current ?: track.lastOrNull()
            if (position != null) {
                val marker = Marker(map)
                marker.position = GeoPoint(position.first, position.second)
                marker.icon = ContextCompat.getDrawable(context, R.drawable.ic_dot)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                map.overlays.add(marker)

                if (follow) {
                    map.controller.setCenter(marker.position)
                    if (map.zoomLevelDouble < 15.0) map.controller.setZoom(15.0)
                }
            }

            if (!follow && !fitted && track.size > 1) {
                fitted = true
                val points = ArrayList<IGeoPoint>(track.size)
                track.forEach { points.add(GeoPoint(it.first, it.second)) }
                map.post {
                    runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 70) }
                }
            }

            // OSM tiles require attribution; osmdroid draws it for us.
            map.overlays.add(CopyrightOverlay(context))
            map.invalidate()
            },
        )
    }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
