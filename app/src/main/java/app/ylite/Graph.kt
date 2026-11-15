package app.ylite

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ylite.ble.BleClient
import app.ylite.data.AppDb
import app.ylite.data.FrameLine
import app.ylite.data.Maintenance
import app.ylite.data.Settings
import app.ylite.data.Telemetry
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
    /** Which scooter this is, from Settings. Null until the user picks one. */
    val model: String? = null,
    /** "system", "light" or "dark" — the user's choice, not the phone's. */
    val themeMode: String = "system",
    /** When true a fill-up can be logged without an odometer reading. */
    val odometerOptional: Boolean = false,
    val deviceAddress: String? = null,
    val bond: String = "unknown",
    val gattDump: String = "",
    /**
     * Newest first: what was actually read or subscribed to since connecting. The GATT
     * table shows what the CCU offers; this shows what the phone managed to turn on.
     */
    val linkLog: List<String> = emptyList(),
    /** True while notifications are being stored without a ride attached. */
    val capturing: Boolean = false,
    val frameCount: Int = 0,
    val lastFrameHex: String = "",
    /** Newest first, capped so a chatty CCU cannot grow this without bound. */
    val recentFrames: List<FrameLine> = emptyList(),
    val telemetry: Telemetry = Telemetry(),
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
        Room.databaseBuilder(appContext, AppDb::class.java, "ylite.db")
            .addMigrations(
                AppDb.MIGRATION_1_2,
                AppDb.MIGRATION_2_3,
                AppDb.MIGRATION_3_4,
                AppDb.MIGRATION_4_5,
                AppDb.MIGRATION_5_6,
                AppDb.MIGRATION_6_7,
            )
            // A brand new database never runs a migration, so it seeds here instead.
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (interval in Maintenance.defaults) {
                            db.execSQL(
                                "INSERT INTO intervals (kind, everyKm) VALUES (?, ?)",
                                arrayOf<Any>(interval.kind, interval.everyKm),
                            )
                        }
                    }
                },
            )
            .build()
    }

    val dao: YDao get() = db.dao()

    val settings: Settings by lazy { Settings(appContext) }

    val ble: BleClient by lazy { BleClient(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setLive(block: (LiveState) -> LiveState) {
        _live.update(block)
    }
}
