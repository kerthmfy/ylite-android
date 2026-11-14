package app.ylite.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/** One ride: one press of Start, one press of Stop. */
@Entity(tableName = "rides")
data class Ride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val name: String? = null,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
)

/** One GPS fix. `time` is the fix time, not the insert time. */
@Entity(tableName = "gps_points", indices = [Index("rideId")])
data class GpsPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double? = null,
    val speedMps: Float? = null,
    val bearing: Float? = null,
    val accuracyM: Float? = null,
)

/**
 * One BLE notification. Stamped on arrival, so it is only approximately in step
 * with GPS fixes. Raw bytes are stored so a ride can be re-decoded later.
 */
@Entity(tableName = "ble_frames", indices = [Index("rideId")])
data class BleFrame(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val time: Long,
    val address: String,
    val service: String,
    val characteristic: String,
    val data: ByteArray,
)

/** SQLite has no byte type, so raw frames are stored as hex text. */
class Converters {
    @TypeConverter
    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @TypeConverter
    fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) return ByteArray(0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

@Dao
interface YDao {
    @Insert
    suspend fun insertRide(ride: Ride): Long

    @Query("UPDATE rides SET endedAt = :endedAt WHERE id = :rideId AND endedAt IS NULL")
    suspend fun endRide(rideId: Long, endedAt: Long)

    @Query("SELECT * FROM rides ORDER BY startedAt DESC")
    fun rides(): Flow<List<Ride>>

    @Query("SELECT * FROM rides WHERE id = :rideId")
    fun ride(rideId: Long): Flow<Ride?>

    @Insert
    suspend fun insertPoint(point: GpsPoint): Long

    @Insert
    suspend fun insertFrame(frame: BleFrame): Long

    @Query("SELECT * FROM gps_points WHERE rideId = :rideId ORDER BY time ASC")
    suspend fun points(rideId: Long): List<GpsPoint>

    @Query("SELECT * FROM ble_frames WHERE rideId = :rideId ORDER BY time ASC")
    suspend fun frames(rideId: Long): List<BleFrame>

    @Query("SELECT COUNT(*) FROM gps_points WHERE rideId = :rideId")
    suspend fun pointCount(rideId: Long): Int

    @Query("SELECT COUNT(*) FROM ble_frames WHERE rideId = :rideId")
    suspend fun frameCount(rideId: Long): Int

    @Query("SELECT * FROM gps_points ORDER BY time DESC LIMIT 1")
    suspend fun lastPoint(): GpsPoint?
}

@Database(
    entities = [Ride::class, GpsPoint::class, BleFrame::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): YDao
}
