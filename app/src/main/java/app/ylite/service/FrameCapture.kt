package app.ylite.service

import app.ylite.Graph
import app.ylite.data.BleFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Stores CCU notifications without a ride attached.
 *
 * A ride is the natural container for frames, but the CCU only powers up with the
 * ignition on and you may not want to record a trip just to collect data. Captured
 * frames are filed under a reserved ride id, so History never lists them, and they
 * export as a CSV straight from the Debug tab.
 *
 * This runs in the app process: keep Y-Lite in the foreground while capturing.
 */
object FrameCapture {

    /** Not a real ride: History only lists rows that came from the rides table. */
    const val CAPTURE_RIDE_ID = -1L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val running: Boolean get() = job != null

    fun start() {
        if (job != null) return
        job = scope.launch {
            Graph.ble.frames.collect { event ->
                Graph.dao.insertFrame(
                    BleFrame(
                        rideId = CAPTURE_RIDE_ID,
                        time = event.time,
                        address = event.address,
                        service = event.service,
                        characteristic = event.characteristic,
                        data = event.data,
                    ),
                )
            }
        }
        Graph.setLive { it.copy(capturing = true) }
    }

    fun stop() {
        job?.cancel()
        job = null
        Graph.setLive { it.copy(capturing = false) }
    }
}
