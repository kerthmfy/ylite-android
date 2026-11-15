package app.ylite.data

/** Where a job comes round, and where its next one falls. */
object Maintenance {

    /**
     * What a new install starts with. These are deliberately conservative round
     * numbers for stop-and-go city riding, not a Yamaha schedule — they are seed
     * values for an editable list, and the manual always wins.
     */
    val defaults = listOf(
        Interval(kind = "Engine oil", everyKm = 2_000.0),
        Interval(kind = "CVT belt & rollers", everyKm = 10_000.0),
        Interval(kind = "Air filter", everyKm = 8_000.0),
        Interval(kind = "Spark plug", everyKm = 8_000.0),
        Interval(kind = "Brake fluid", everyKm = 24_000.0),
    )

    /** Where the next one falls, given when this job was last done. */
    fun dueAtKm(everyKm: Double, lastDoneKm: Double?): Double? =
        lastDoneKm?.let { it + everyKm }
}
