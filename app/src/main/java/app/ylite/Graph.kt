package app.ylite

import android.content.Context
import androidx.room.Room
import app.ylite.ble.BleClient
import app.ylite.data.AppDb
import app.ylite.data.YDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the UI shows about the current session lives in one immutable value.
 * One writer at a time, many readers, no callbacks into Compose.
 */
data class LiveState(
    val status: String = "Idle",
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val gattDump: String = "",
    val frameCount: Int = 0,
    val lastFrameHex: String = "",
    val decoded: Map<String, String> = emptyMap(),
    val rideId: Long? = null,
    val rideStartedAt: Long? = null,
    val recording: Boolean = false,
    val gpsPoints: Int = 0,
    val distanceMeters: Double = 0.0,
    val speedMps: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitude: Double? = null,
)

/**
 * A tiny service locator: the database, the BLE client and the live state.
 * It is a singleton so the Activity and the foreground Service see the same objects.
 */
object Graph {
    private lateinit var appContext: Context

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    val db: AppDb by lazy {
        Room.databaseBuilder(appContext, AppDb::class.java, "ylite.db").build()
    }

    val dao: YDao get() = db.dao()

    val ble: BleClient by lazy { BleClient(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setLive(block: (LiveState) -> LiveState) {
        _live.update(block)
    }
}
