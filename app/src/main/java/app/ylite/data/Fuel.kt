package app.ylite.data

/** One logged fill-up, with the economy of the tank it closed (null for partials). */
data class FillupStat(
    val fillup: Fillup,
    val kmPerL: Double?,
)

data class FuelLog(
    val entries: List<FillupStat>,
    val kmPerL: Double?,
    val costPerKm: Double?,
    val distanceKm: Double,
)

/**
 * Consumption the way Fuelio works it out, because it is the honest way:
 *
 * - a **partial** fill adds fuel but closes nothing, so its litres stay pending;
 * - a **full** fill closes a tank: distance since the last full tank, divided by
 *   every litre put in since then, including this one;
 * - the tank you have open now is never reported, because you cannot know how
 *   much of it you have burned yet.
 *
 * Treating each fill-up as a tank of its own — which is what this did before —
 * quietly inflates consumption every time you top up early.
 */
fun fuelLog(fillups: List<Fillup>): FuelLog {
    val sorted = fillups.sortedBy { it.odometerKm }
    val entries = mutableListOf<FillupStat>()

    var lastFullKm: Double? = null
    var pendingLitres = 0.0
    var pendingCost = 0.0
    var distance = 0.0
    var litres = 0.0
    var cost = 0.0

    for (fillup in sorted) {
        // Logged without a reading: the litres are real but the distance they covered is
        // unknown, so they cannot help any tank's average. They stay out of the maths.
        if (!fillup.odometerKnown) {
            entries += FillupStat(fillup, kmPerL = null)
            continue
        }

        pendingLitres += fillup.litres
        pendingCost += fillup.costTotal ?: 0.0

        if (!fillup.isFull) {
            entries += FillupStat(fillup, kmPerL = null)
            continue
        }

        val base = lastFullKm
        if (base != null) {
            val segment = fillup.odometerKm - base
            if (segment > 0.0 && pendingLitres > 0.0) {
                entries += FillupStat(fillup, segment / pendingLitres)
                distance += segment
                litres += pendingLitres
                cost += pendingCost
            } else {
                entries += FillupStat(fillup, kmPerL = null)
            }
        } else {
            entries += FillupStat(fillup, kmPerL = null)
        }

        pendingLitres = 0.0
        pendingCost = 0.0
        lastFullKm = fillup.odometerKm
    }

    return FuelLog(
        entries = entries,
        kmPerL = if (litres > 0.0) distance / litres else null,
        costPerKm = if (cost > 0.0 && distance > 0.0) cost / distance else null,
        distanceKm = distance,
    )
}
