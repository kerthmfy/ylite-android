package app.ylite.data

/** A recurring job, measured in kilometres because that is all a scooter knows. */
data class ServiceInterval(
    val kind: String,
    val everyKm: Double,
    val note: String,
)

/**
 * Intervals are deliberately conservative round numbers, not a service manual:
 * a Mio Gravis owner rides stop-and-go city kilometres, which wear oil and the
 * CVT faster than the handbook's highway figures. Check your own manual before
 * trusting any of this with a warranty.
 */
object Maintenance {

    val intervals = listOf(
        ServiceInterval("Engine oil", 2_000.0, "City riding, so short intervals"),
        ServiceInterval("CVT belt & rollers", 10_000.0, "The one most owners skip"),
        ServiceInterval("Air filter", 8_000.0, "Sooner on dusty roads"),
        ServiceInterval("Spark plug", 8_000.0, "Cheap insurance for easy starting"),
        ServiceInterval("Brake fluid", 24_000.0, "Or every two years, whichever comes first"),
    )

    /** Where the next one falls, given when this job was last done. */
    fun dueAtKm(interval: ServiceInterval, lastDoneKm: Double?): Double? =
        lastDoneKm?.let { it + interval.everyKm }
}
