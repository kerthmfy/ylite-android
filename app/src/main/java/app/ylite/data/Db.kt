package app.ylite.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.Update
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** The scooter model this ride was recorded on, e.g. "Yamaha Mio Gravis". */
    val model: String? = null,
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

/**
 * One fill-up. The odometer reading is the whole point: two of these give real
 * fuel consumption long before the CCU's fuel field is decoded, and they also
 * anchor the maintenance counters.
 */
@Entity(tableName = "fillups")
data class Fillup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val odometerKm: Double,
    val litres: Double,
    val costTotal: Double? = null,
    /**
     * False for a partial fill. Consumption is only computed across full tanks,
     * so the flag changes the arithmetic, not just a label.
     */
    @ColumnInfo(defaultValue = "1") val isFull: Boolean = true,
    val note: String? = null,
    /** Null when the fill-up was logged without picking a station. */
    val stationId: Long? = null,
)

/**
 * A filling station you have used. Kept separately from the fill-ups so it can be
 * renamed once and starred, and so "how often do I fill here" is a real number.
 */
@Entity(tableName = "stations")
data class Station(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
)

/** Something was done to the scooter at a known odometer reading. */
@Entity(tableName = "services")
data class ServiceEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val odometerKm: Double,
    val time: Long,
)

/**
 * One maintenance job and how often it comes round. Editable, because every owner
 * and every manual disagrees about the numbers.
 */
@Entity(tableName = "intervals")
data class Interval(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val everyKm: Double,
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

    @Insert
    suspend fun insertFillup(fillup: Fillup): Long

    @Query("DELETE FROM fillups WHERE id = :id")
    suspend fun deleteFillup(id: Long)

    @Query("SELECT * FROM fillups ORDER BY odometerKm ASC")
    fun fillups(): Flow<List<Fillup>>

    @Insert
    suspend fun insertService(service: ServiceEntry): Long

    @Query("SELECT * FROM services ORDER BY odometerKm ASC")
    fun services(): Flow<List<ServiceEntry>>

    /** Keeps history attached when a job is renamed. */
    @Query("UPDATE services SET kind = :to WHERE kind = :from")
    suspend fun renameServiceKind(from: String, to: String)

    @Insert
    suspend fun insertStation(station: Station): Long

    @Query("UPDATE stations SET favorite = :favorite WHERE id = :id")
    suspend fun setStationFavorite(id: Long, favorite: Boolean)

    @Query("SELECT * FROM stations ORDER BY favorite DESC, name ASC")
    fun stations(): Flow<List<Station>>

    @Insert
    suspend fun insertInterval(interval: Interval): Long

    @Update
    suspend fun updateInterval(interval: Interval)

    @Query("DELETE FROM intervals WHERE id = :id")
    suspend fun deleteInterval(id: Long)

    @Query("SELECT * FROM intervals ORDER BY kind ASC")
    fun intervals(): Flow<List<Interval>>
}

@Database(
    entities = [
        Ride::class,
        GpsPoint::class,
        BleFrame::class,
        Fillup::class,
        ServiceEntry::class,
        Station::class,
        Interval::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): YDao

    companion object {
        /**
         * v2 adds rides.model. Written by hand rather than wiping the database, so
         * the rides you already recorded survive the upgrade.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN model TEXT")
            }
        }

        /** v3 adds the fill-up log and the service log. Both start empty. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fillups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, " +
                        "`odometerKm` REAL NOT NULL, " +
                        "`litres` REAL NOT NULL, " +
                        "`costTotal` REAL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `services` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`odometerKm` REAL NOT NULL, " +
                        "`time` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v4 adds Fuelio's notion of a full tank, plus a free note. Existing rows
         * are full fills, which is what they were assumed to be already.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fillups ADD COLUMN isFull INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE fillups ADD COLUMN note TEXT")
            }
        }

        /** v5 adds the stations list and links fill-ups to it. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`favorite` INTEGER NOT NULL DEFAULT 0, " +
                        "`lat` REAL, " +
                        "`lon` REAL)",
                )
                db.execSQL("ALTER TABLE fillups ADD COLUMN stationId INTEGER")
            }
        }

        /** v6 makes the maintenance intervals editable rows instead of a fixed list. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `intervals` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`everyKm` REAL NOT NULL)",
                )
                // Start from the same list the app used to hard-code, so nothing
                // disappears from the screen after upgrading.
                for (interval in Maintenance.defaults) {
                    db.execSQL(
                        "INSERT INTO intervals (kind, everyKm) VALUES (?, ?)",
                        arrayOf<Any>(interval.kind, interval.everyKm),
                    )
                }
            }
        }
    }
}
