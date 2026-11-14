package app.ylite.data

/** One fill-up: enough to compute consumption without any CCU data. */
data class FuelStats(
    val distanceKm: Double,
    val litres: Double,
    val cost: Double?,
) {
    /** km per litre, the figure riders actually quote. */
    val kmPerL: Double? get() = if (litres > 0.0) distanceKm / litres else null

    val costPerKm: Double? get() = cost?.takeIf { distanceKm > 0.0 }?.div(distanceKm)
}

/**
 * Treats every fill-up as a full tank: the odometer jump between two of them is
 * the distance burned on the fuel logged at the second. One fill-up is not
 * enough to say anything, so this returns null rather than a made-up number.
 */
fun fuelStats(fillups: List<Fillup>): FuelStats? {
    val sorted = fillups.sortedBy { it.odometerKm }
    if (sorted.size < 2) return null

    val distance = sorted.last().odometerKm - sorted.first().odometerKm
    if (distance <= 0.0) return null

    val litres = sorted.drop(1).sumOf { it.litres }
    val cost = sorted.drop(1).mapNotNull { it.costTotal }.takeIf { it.isNotEmpty() }?.sum()
    return FuelStats(distanceKm = distance, litres = litres, cost = cost)
}
