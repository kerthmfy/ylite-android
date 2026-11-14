package app.ylite.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ylite.Graph
import app.ylite.MainActivity
import app.ylite.R
import app.ylite.data.BleFrame
import app.ylite.data.GpsPoint
import app.ylite.data.Ride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Records one ride: GPS fixes from the platform GPS provider, and every BLE frame
 * the client receives while the ride is running.
 *
 * START_NOT_STICKY on purpose: if Android kills the process you get a ride with a
 * null end time, rather than a new ride you did not ask for.
 */
class RideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private var frameJob: Job? = null
    private var lastFix: Location? = null

    @Volatile
    private var rideId: Long = 0L

    // A full object, not a lambda: older Android versions call the other methods too.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location)
        }

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) {
            Graph.setLive { it.copy(status = "GPS disabled") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRide()
            ACTION_STOP -> endRide()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------- ride start

    private fun beginRide() {
        if (rideId != 0L) return
        if (!hasFineLocation()) {
            Toast.makeText(this, "Precise location is required to record a ride.", Toast.LENGTH_LONG)
                .show()
            stopSelf()
            return
        }

        startInForeground()

        scope.launch {
            val live = Graph.live.value
            val startedAt = System.currentTimeMillis()
            rideId = Graph.dao.insertRide(
                Ride(
                    startedAt = startedAt,
                    deviceName = live.deviceName,
                    deviceAddress = live.deviceAddress,
                    model = live.model,
                ),
            )
            Graph.setLive {
                it.copy(
                    rideId = rideId,
                    rideStartedAt = startedAt,
                    recording = true,
                    gpsPoints = 0,
                    distanceMeters = 0.0,
                )
            }
            startLocation()
            collectFrames()
        }
    }

    /**
     * Android 14+ requires the Bluetooth permission before a connectedDevice foreground
     * service may start. If it was never granted, fall back to a location-only service
     * instead of crashing on the first Start.
     */
    private fun startInForeground() {
        val notification = buildNotification("Recording…")
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, foregroundType())
        }.isSuccess
        if (!started) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val provider = LocationManager.GPS_PROVIDER
        if (!locationManager.isProviderEnabled(provider)) {
            Graph.setLive { it.copy(status = "GPS provider is off") }
        }
        runCatching {
            locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
        }.onFailure { error ->
            Graph.setLive { it.copy(status = "Location updates failed: ${error.message}") }
        }
    }

    private fun collectFrames() {
        frameJob?.cancel()
        frameJob = scope.launch {
            Graph.ble.frames.collect { event ->
                Graph.dao.insertFrame(
                    BleFrame(
                        rideId = rideId,
                        time = event.time,
                        address = event.address,
                        service = event.service,
                        characteristic = event.characteristic,
                        data = event.data,
                    ),
                )
            }
        }
    }

    // --------------------------------------------------------------- one fix

    private fun onFix(location: Location) {
        val id = rideId
        if (id == 0L) return

        val step = lastFix?.let { previous ->
            distanceMetres(previous.latitude, previous.longitude, location.latitude, location.longitude)
        } ?: 0.0
        lastFix = location

        val point = GpsPoint(
            rideId = id,
            time = location.time,
            lat = location.latitude,
            lon = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearing = if (location.hasBearing()) location.bearing else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
        )

        scope.launch {
            Graph.dao.insertPoint(point)
            Graph.setLive {
                it.copy(
                    gpsPoints = it.gpsPoints + 1,
                    distanceMeters = it.distanceMeters + step,
                    speedMps = point.speedMps,
                    lat = point.lat,
                    lon = point.lon,
                    altitude = point.altitude,
                )
            }
            updateNotification()
        }
    }

    // --------------------------------------------------------------- ride end

    private fun endRide() {
        val id = rideId
        rideId = 0L
        runCatching { locationManager.removeUpdates(listener) }
        frameJob?.cancel()
        frameJob = null
        lastFix = null
        scope.launch {
            if (id != 0L) Graph.dao.endRide(id, System.currentTimeMillis())
            Graph.setLive { it.copy(recording = false, rideId = null, rideStartedAt = null) }
            ServiceCompat.stopForeground(this@RideService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(listener) }
        frameJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ride recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateNotification() {
        val live = Graph.live.value
        val text = "%.2f km · %s · %d frames".format(
            live.distanceMeters / 1000.0,
            live.speedMps?.let { "%.0f km/h".format(it * 3.6f) } ?: "no speed",
            live.frameCount,
        )
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Y-Lite — recording")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop ride", stop)
            .build()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "app.ylite.action.START_RIDE"
        const val ACTION_STOP = "app.ylite.action.STOP_RIDE"
        private const val CHANNEL_ID = "ride"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RideService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }

        private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earth = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * earth * asin(sqrt(a))
        }
    }
}
