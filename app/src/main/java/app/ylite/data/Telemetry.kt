package app.ylite.data

/**
 * What the scooter tells us.
 *
 * Every field is nullable on purpose:
 *  - nothing is filled in until the CCU's frame layout is known, and
 *  - a single frame may carry only some values, so the rest must survive.
 *
 * The UI shows "—" for anything still unknown, which is honest rather than fake.
 * Add a field here when the capture proves the CCU sends it.
 */
data class Telemetry(
    val speedKph: Float? = null,
    val fuelPercent: Float? = null,
    val tempC: Float? = null,
    val tripKm: Double? = null,
    val odoKm: Double? = null,
    val batteryPercent: Float? = null,
    /** Decoded values with no dedicated field yet; the Debug tab prints these. */
    val extra: Map<String, String> = emptyMap(),
) {

    val isEmpty: Boolean
        get() = speedKph == null &&
            fuelPercent == null &&
            tempC == null &&
            tripKm == null &&
            odoKm == null &&
            batteryPercent == null

    /**
     * Newer non-null values win. A frame that only carries fuel must not wipe
     * the speed that arrived in the frame before it.
     */
    fun merge(next: Telemetry): Telemetry = Telemetry(
        speedKph = next.speedKph ?: speedKph,
        fuelPercent = next.fuelPercent ?: fuelPercent,
        tempC = next.tempC ?: tempC,
        tripKm = next.tripKm ?: tripKm,
        odoKm = next.odoKm ?: odoKm,
        batteryPercent = next.batteryPercent ?: batteryPercent,
        extra = if (next.extra.isEmpty()) extra else next.extra,
    )
}

/** One received BLE frame, as listed in the Debug tab during a capture. */
data class FrameLine(val time: Long, val hex: String)
