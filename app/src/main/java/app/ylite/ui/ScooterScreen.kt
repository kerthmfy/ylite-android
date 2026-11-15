package app.ylite.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ylite.Graph
import app.ylite.LiveState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.ylite.data.FillAmounts
import app.ylite.data.FillField
import app.ylite.data.Fillup
import app.ylite.data.Interval
import app.ylite.data.Maintenance
import app.ylite.data.ServiceEntry
import app.ylite.data.Station
import app.ylite.data.fuelLog
import app.ylite.export.Exporters
import app.ylite.fuelio.FuelioCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything about the scooter that is not the ride you are on: what you parked,
 * what it drinks, what it is due for, and a way to get all of it off the phone.
 *
 * The odometer drives this screen. Until the CCU's frames are decoded that means
 * the last fill-up; afterwards it can also come from the dashboard.
 */
@Composable
fun ScooterScreen(live: LiveState) {
    val colors = LocalYColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporters = remember { Exporters(context) }

    val fillups by remember { Graph.dao.fillups() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val services by remember { Graph.dao.services() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val stations by remember { Graph.dao.stations() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val intervals by remember { Graph.dao.intervals() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val visits = remember(fillups) {
        fillups.mapNotNull { it.stationId }.groupingBy { it }.eachCount()
    }

    var parked by remember { mutableStateOf<ParkedFix?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var exportLabel by remember { mutableStateOf<String?>(null) }
    var addingInterval by remember { mutableStateOf(false) }
    var editingInterval by remember { mutableStateOf<Interval?>(null) }
    var openedInterval by remember { mutableStateOf<Interval?>(null) }

    // A ride that ends is also a scooter that just parked, so re-read after it.
    LaunchedEffect(live.recording, live.gpsPoints) {
        if (!live.recording) {
            parked = withContext(Dispatchers.IO) {
                Graph.dao.lastPoint()?.let { ParkedFix(it.lat, it.lon, it.time) }
            }
        }
    }

    val odometer = currentOdometerKm(live, fillups)
    val log = fuelLog(fillups)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { reader -> reader.readText() }
                        ?.let { FuelioCsv.parse(it) }
                }.onFailure { Log.w("ylite-import", "could not read $uri", it) }.getOrNull()
            }

            if (parsed == null) {
                Log.w("ylite-import", "nothing parsed from $uri")
                Toast.makeText(context, "Could not read that file.", Toast.LENGTH_LONG).show()
                return@launch
            }
            Log.i("ylite-import", "parsed ${parsed.fills.size} rows, ${parsed.skipped} skipped, ${parsed.conversion}")
            if (parsed.fills.isEmpty()) {
                val why = parsed.conversion?.takeIf { it.startsWith("No ") } ?: "No fill-up rows found"
                Toast.makeText(context, why, Toast.LENGTH_LONG).show()
                return@launch
            }

            // Importing twice should not double the history: odometer is the key.
            val known = fillups.map { "%.1f".format(it.odometerKm) }.toSet()
            val fresh = parsed.fills.filterNot { "%.1f".format(it.odometerKm) in known }

            // Fuelio keeps the station in its "City" column, so the import builds the
            // station list as it goes: match by name, create what is missing, and star
            // what came from your own history. Visit counts then fall out of the links.
            val (imported, newStations) = withContext(Dispatchers.IO) {
                val byName = Graph.dao.stations().first()
                    .associateBy { it.name.trim().lowercase() }
                    .toMutableMap()
                var created = 0
                for (fill in fresh) {
                    val name = fill.stationName?.trim()?.takeIf { it.isNotEmpty() }
                    var station = name?.let { byName[it.lowercase()] }
                    if (station == null && name != null) {
                        val id = Graph.dao.insertStation(Station(name = name, favorite = true))
                        station = Station(id = id, name = name, favorite = true)
                        byName[name.lowercase()] = station
                        created++
                    }
                    Graph.dao.insertFillup(
                        Fillup(
                            time = fill.time,
                            odometerKm = fill.odometerKm,
                            litres = fill.litres,
                            costTotal = fill.costTotal,
                            isFull = fill.isFull,
                            note = fill.note,
                            stationId = station?.id,
                        ),
                    )
                }
                fresh.size to created
            }

            Log.i("ylite-import", "inserted $imported of ${fresh.size}, ${newStations} new station(s)")

            val parts = mutableListOf("Imported $imported fill-ups")
            if (newStations > 0) parts += "$newStations station(s)"
            val duplicates = parsed.fills.size - fresh.size
            if (duplicates > 0) parts += "$duplicates already here"
            if (parsed.skipped > 0) parts += "${parsed.skipped} unreadable"
            parsed.vehicle?.let { parts += it }
            parsed.conversion?.let { parts += it }
            Toast.makeText(context, parts.joinToString(" · "), Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        ScreenHeader("Scooter", trailing = odometer?.let { "%.1f km".format(it) } ?: "no odometer")

        Section("LAST PARKED") {
            val fix = parked
            if (fix == null) {
                Text(
                    "No position recorded yet. The last fix of a ride is kept here.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.muted,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(fmtDate(fix.time), fontSize = 14.sp, color = colors.text)
                        Spacer(Modifier.height(2.dp))
                        Text("%.5f, %.5f".format(fix.lat, fix.lon), style = MonoCode, color = colors.muted)
                    }
                    PillButton(label = "OPEN MAP") {
                        val uri = Uri.parse("geo:${fix.lat},${fix.lon}?q=${fix.lat},${fix.lon}(Parked)")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                }
            }
        }

        Section("FUEL") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Metric("km / l", log.kmPerL?.let { "%.1f".format(it) } ?: "—", null, Modifier.weight(1f), log.kmPerL != null)
                VDivider()
                Metric(
                    "cost / km",
                    log.costPerKm?.let { "%.2f".format(it) } ?: "—",
                    null,
                    Modifier.weight(1f),
                    log.costPerKm != null,
                )
                VDivider()
                Metric("fill-ups", fillups.size.toString(), null, Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = when {
                    log.kmPerL != null ->
                        "%.0f km between full tanks. Partial fills count towards the next tank, not on their own."
                            .format(log.distanceKm)
                    fillups.size < 2 ->
                        "Log two fill-ups and the app can compute real km/L from your odometer, no CCU required."
                    else ->
                        "Mark a fill-up as a full tank to close a tank and get its km/L."
                },
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.muted,
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(label = "ADD FILL-UP", filled = true) { addOpen = true }
            }

            if (fillups.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                fillups.takeLast(6).reversed().forEach { fillup ->
                    val economy = log.entries.firstOrNull { it.fillup.id == fillup.id }?.kmPerL
                    val stationName = stations.firstOrNull { it.id == fillup.stationId }?.name
                    FillupRow(fillup, economy, stationName) {
                        scope.launch { withContext(Dispatchers.IO) { Graph.dao.deleteFillup(fillup.id) } }
                    }
                }
            }
        }

        Section("MAINTENANCE") {
            if (odometer == null) {
                Text(
                    "Add a fill-up first: the counters need an odometer reading to count from.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.muted,
                )
            }
            intervals.forEach { interval ->
                val last = services.filter { it.kind == interval.kind }.maxByOrNull { it.odometerKm }
                val due = Maintenance.dueAtKm(interval.everyKm, last?.odometerKm)
                val remaining = if (due != null && odometer != null) due - odometer else null
                val overdue = remaining != null && remaining <= 0

                // How much of the interval is left, as a bar that empties towards the due date.
                val left = when {
                    remaining == null -> 0f
                    else -> (remaining / interval.everyKm).coerceIn(0.0, 1.0).toFloat()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { openedInterval = interval },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                interval.kind,
                                fontSize = 15.sp,
                                color = colors.text,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "›",
                                fontSize = 18.sp,
                                color = colors.muted,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when {
                                last == null -> "not logged yet · every %.0f km".format(interval.everyKm)
                                remaining == null -> "due at %.0f km".format(due)
                                overdue -> "overdue by %.0f km".format(-remaining)
                                else -> "%.0f km to go · every %.0f".format(remaining, interval.everyKm)
                            },
                            style = MonoCode,
                            color = if (overdue) colors.accent else colors.muted,
                        )
                        if (last != null) {
                            Spacer(Modifier.height(7.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(colors.line),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(left)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (overdue) colors.accent else colors.good),
                                )
                            }
                        }
                    }
                    Text(
                        text = "DONE",
                        style = MicroLabel,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (odometer == null) {
                                    Toast.makeText(
                                        context,
                                        "Add a fill-up so the app knows your odometer.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            Graph.dao.insertService(
                                                ServiceEntry(
                                                    kind = interval.kind,
                                                    odometerKm = odometer,
                                                    time = System.currentTimeMillis(),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            PillButton(label = "ADD ITEM") { addingInterval = true }
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap a job to rename it or change its interval — the seeded numbers are cautious city figures, not a Yamaha schedule.",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.muted,
            )
        }

        Section("SETTINGS") {
            Text("APPEARANCE", style = MicroLabel, color = colors.muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "SYSTEM", "light" to "LIGHT", "dark" to "DARK").forEach { (mode, label) ->
                    PillButton(label = label, filled = live.themeMode == mode) {
                        Graph.settings.themeMode = mode
                        Graph.setLive { it.copy(themeMode = mode) }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        val next = !live.odometerOptional
                        Graph.settings.odometerOptional = next
                        Graph.setLive { it.copy(odometerOptional = next) }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ODOMETER OPTIONAL", style = MicroLabel, color = colors.muted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Adds a NO READING switch to the fill-up form, for when you only have the receipt. Without a reading the row stays out of the km/L maths.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = colors.muted,
                    )
                }
                Text(
                    text = if (live.odometerOptional) "ON" else "OFF",
                    style = MicroLabel,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        Section("DATA") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(label = "IMPORT FUELIO CSV") {
                    importLauncher.launch(arrayOf("*/*"))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "In Fuelio: Settings → Export → CSV, then pick that file here. Full and partial tanks, litres, prices and notes are read; miles and US gallons are converted. Rows already in this log are skipped.",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.muted,
            )

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(label = exportLabel ?: "EXPORT EVERYTHING", filled = true, enabled = exportLabel == null) {
                    exportLabel = "BUILDING…"
                    scope.launch {
                        val archive = runCatching {
                            val rides = withContext(Dispatchers.IO) { Graph.dao.rides().first() }
                            val points = withContext(Dispatchers.IO) {
                                rides.associate { it.id to Graph.dao.points(it.id) }
                            }
                            val frames = withContext(Dispatchers.IO) {
                                rides.associate { it.id to Graph.dao.frames(it.id) }
                            }
                            withContext(Dispatchers.IO) {
                                exporters.everything(
                                    rides = rides,
                                    points = points,
                                    frames = frames,
                                    fillups = fillups,
                                    services = services,
                                    stationNames = stations.associate { it.id to it.name },
                                )
                            }
                        }
                        exportLabel = null
                        archive.onSuccess { share(context, it, "application/zip") }
                            .onFailure {
                                Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "One zip: every GPX, every raw frame file, and the ride, fill-up and service logs.",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.muted,
            )
        }
    }

    if (addOpen) {
        FillupSheet(
            lastOdometer = odometer,
            stations = stations,
            visits = visits,
            odometerOptional = live.odometerOptional,
            onDismiss = { addOpen = false },
        )
    }
    if (addingInterval) {
        IntervalSheet(existing = null, onDismiss = { addingInterval = false })
    }
    editingInterval?.let { interval ->
        IntervalSheet(existing = interval, onDismiss = { editingInterval = null })
    }
    openedInterval?.let { interval ->
        IntervalDetailSheet(
            interval = interval,
            services = services,
            odometer = odometer,
            onEdit = {
                openedInterval = null
                editingInterval = interval
            },
            onDismiss = { openedInterval = null },
        )
    }
}

// --------------------------------------------------------------------- pieces

private data class ParkedFix(val lat: Double, val lon: Double, val time: Long)

/** Latest odometer from the CCU if it is decoded, else the highest fill-up reading. */
private fun currentOdometerKm(live: LiveState, fillups: List<Fillup>): Double? {
    val fromCcu = live.telemetry.odoKm
    val fromLog = fillups.maxOfOrNull { it.odometerKm }
    return listOfNotNull(fromCcu, fromLog).maxOrNull()
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = LocalYColors.current
    Spacer(Modifier.height(18.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(16.dp).height(1.dp).background(colors.accent))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MicroLabel, color = colors.muted)
        }
        Spacer(Modifier.height(12.dp))
        content()
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = colors.line, thickness = 1.dp)
    }
}

@Composable
private fun FillupRow(fillup: Fillup, kmPerL: Double?, stationName: String?, onDelete: () -> Unit) {
    val colors = LocalYColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceRaised)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (fillup.odometerKnown) {
                    "%.1f km · %.2f L".format(fillup.odometerKm, fillup.litres)
                } else {
                    "%.2f L".format(fillup.litres)
                },
                style = MonoValue,
                color = colors.text,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(
                    fmtDay(fillup.time),
                    stationName,
                    if (fillup.isFull) null else "partial",
                    if (fillup.odometerKnown) null else "no reading",
                    fillup.note,
                ).joinToString(" · "),
                style = MonoCode,
                color = colors.muted,
                maxLines = 1,
            )
        }
        kmPerL?.let {
            Text(
                text = "%.1f".format(it),
                style = MonoValue.copy(fontSize = 12.sp),
                color = colors.onAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        fillup.costTotal?.let {
            Text("%.2f".format(it), style = MonoValue, color = colors.muted)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = "×",
            fontSize = 16.sp,
            color = colors.muted,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// --------------------------------------------------------------- fill-up sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FillupSheet(
    lastOdometer: Double?,
    stations: List<Station>,
    visits: Map<Long, Int>,
    odometerOptional: Boolean = false,
    onDismiss: () -> Unit,
) {
    val colors = LocalYColors.current
    val scope = rememberCoroutineScope()
    // A hint, never a value: a prefilled field swallows the first digit you type.
    var odometer by rememberSaveable { mutableStateOf("") }
    var trip by rememberSaveable { mutableStateOf("") }
    var noReading by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Station?>(null) }
    var renameText by remember { mutableStateOf("") }
    var amounts by rememberSaveable(stateSaver = fillAmountsSaver) { mutableStateOf(FillAmounts()) }
    var fullTank by rememberSaveable { mutableStateOf(true) }
    var stationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickingStation by rememberSaveable { mutableStateOf(false) }
    var newStation by rememberSaveable { mutableStateOf("") }

    // Either the dash reading, or the trip meter added to the last reading.
    val typedKm = odometer.toNumberOrNull()
    val tripKm = trip.toNumberOrNull()
    val resolvedKm = when {
        typedKm != null -> typedKm
        tripKm != null && lastOdometer != null -> lastOdometer + tripKm
        else -> null
    }
    val amount = amounts.litresValue
    val valid = amount != null && amount > 0.0 && (noReading || resolvedKm != null)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.text,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.line),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ADD FILL-UP", style = MicroLabel, color = colors.muted)
            Text(
                text = "Copy the odometer from the dash, then type whatever the receipt shows: any two of litres, price and total fill in the third.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.muted,
            )

            if (noReading) {
                Text(
                    text = "Litres and cost only. Without a reading this fill-up stays out of the km/L maths.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.muted,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = "ODOMETER (KM)",
                        value = odometer,
                        placeholder = lastOdometer?.let { "last %.1f".format(it) } ?: "12345.0",
                        modifier = Modifier.weight(1f),
                    ) { odometer = it }
                    if (lastOdometer != null) {
                        NumberField(
                            label = "OR TRIP (KM)",
                            value = trip,
                            placeholder = "12.4",
                            modifier = Modifier.weight(1f),
                        ) { value ->
                            trip = value
                            // Typing a trip means you are not copying the dash reading.
                            if (value.isNotBlank()) odometer = ""
                        }
                    }
                }
                if (tripKm != null && lastOdometer != null) {
                    Text(
                        text = "trip %.1f + last %.1f = %.1f km".format(
                            tripKm,
                            lastOdometer,
                            lastOdometer + tripKm,
                        ),
                        style = MonoCode,
                        color = colors.muted,
                    )
                }
            }

            if (odometerOptional) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { noReading = !noReading }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("NO READING", style = MicroLabel, color = colors.muted)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Skip the odometer entirely — just fuel and money.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = colors.muted,
                        )
                    }
                    Text(
                        text = if (noReading) "YES" else "NO",
                        style = MicroLabel,
                        color = colors.accent,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    label = "LITRES",
                    value = amounts.litres,
                    placeholder = "2.326",
                    modifier = Modifier.weight(1f),
                ) { amounts = amounts.with(FillField.LITRES, it) }
                NumberField(
                    label = "PRICE / L",
                    value = amounts.price,
                    placeholder = "86",
                    modifier = Modifier.weight(1f),
                ) { amounts = amounts.with(FillField.PRICE, it) }
            }
            NumberField(
                label = "TOTAL COST",
                value = amounts.total,
                placeholder = "200",
            ) { amounts = amounts.with(FillField.TOTAL, it) }

            val litresText = amounts.litresValue?.let { "%.3f".format(it) }
            val priceText = amounts.priceValue?.let { "%.2f".format(it) }
            val totalText = amounts.totalValue?.let { "%.2f".format(it) }
            if (litresText != null && priceText != null && totalText != null) {
                Text(
                    text = "$litresText L × $priceText = $totalText",
                    style = MonoCode,
                    color = colors.muted,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { fullTank = !fullTank }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("FULL TANK", style = MicroLabel, color = colors.muted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (fullTank) {
                            "Filled to the top — this closes the tank and gives its km/L."
                        } else {
                            "Partial fill — its litres count towards the next full tank."
                        },
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = colors.muted,
                    )
                }
                Text(
                    text = if (fullTank) "YES" else "NO",
                    style = MicroLabel,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Spacer(Modifier.height(2.dp))

            Text("GAS STATION", style = MicroLabel, color = colors.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val selected = stations.firstOrNull { it.id == stationId }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selected?.name ?: "Not set",
                        fontSize = 14.sp,
                        color = if (selected != null) colors.text else colors.muted,
                        maxLines = 1,
                    )
                    Text(
                        text = when {
                            selected == null -> "Pick one you have used before, or add it"
                            selected.favorite -> "favourite · ${visitLabel(visits[selected.id] ?: 0)}"
                            else -> visitLabel(visits[selected.id] ?: 0)
                        },
                        style = MonoCode,
                        color = colors.muted,
                    )
                }
                Text(
                    text = if (pickingStation) "CLOSE" else "SELECT",
                    style = MicroLabel,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { pickingStation = !pickingStation }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }

            if (pickingStation) {
                stations
                    .sortedWith(
                        compareByDescending<Station> { it.favorite }
                            .thenByDescending { visits[it.id] ?: 0 }
                            .thenBy { it.name },
                    )
                    .forEach { station ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    stationId = station.id
                                    pickingStation = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (station.favorite) "★" else "·",
                                fontSize = 14.sp,
                                color = if (station.favorite) colors.accent else colors.muted,
                                modifier = Modifier.width(18.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = station.name,
                                    fontSize = 14.sp,
                                    color = colors.text,
                                    maxLines = 1,
                                )
                                val count = visits[station.id] ?: 0
                                if (count > 0) {
                                    Text(visitLabel(count), style = MonoCode, color = colors.muted)
                                }
                            }
                            Text(
                                text = "RENAME",
                                style = MicroLabel,
                                color = colors.muted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        renaming = station
                                        renameText = station.name
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            Text(
                                text = "★",
                                fontSize = 16.sp,
                                color = if (station.favorite) colors.accent else colors.muted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                Graph.dao.setStationFavorite(station.id, !station.favorite)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                if (stations.isEmpty()) {
                    Text(
                        "No stations yet. Add the one you fill at most.",
                        fontSize = 11.sp,
                        color = colors.muted,
                    )
                }

                renaming?.let { station ->
                    Spacer(Modifier.height(10.dp))
                    Text("RENAME STATION", style = MicroLabel, color = colors.muted)
                    Spacer(Modifier.height(6.dp))
                    NumberField(
                        label = "NAME",
                        value = renameText,
                        placeholder = "Petron Sumulong",
                    ) { renameText = it }
                    Text(
                        "A place name like \"Antipolo\" becomes useful once you name the station you actually fill at. If the new name already exists, the two merge and their visits add up.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = colors.muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(
                            label = "SAVE NAME",
                            filled = true,
                            enabled = renameText.isNotBlank(),
                            onClick = {
                                val name = renameText.trim()
                                if (name.isEmpty()) return@PillButton
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val others = Graph.dao.stations().first()
                                        val clash = others.firstOrNull {
                                            it.id != station.id && it.name.trim().equals(name, ignoreCase = true)
                                        }
                                        if (clash != null) {
                                            Graph.dao.reassignFillups(station.id, clash.id)
                                            Graph.dao.deleteStation(station.id)
                                        } else {
                                            Graph.dao.updateStation(station.copy(name = name))
                                        }
                                    }
                                    renaming = null
                                }
                            },
                        )
                        PillButton(label = "CANCEL") { renaming = null }
                    }
                }

                Spacer(Modifier.height(6.dp))
                NumberField(
                    label = "ADD A STATION",
                    value = newStation,
                    placeholder = "Shell Sumulong",
                ) { newStation = it }
                PillButton(
                    label = "ADD STATION",
                    enabled = newStation.isNotBlank(),
                    onClick = {
                        val name = newStation.trim()
                        if (name.isEmpty()) return@PillButton
                        scope.launch {
                            val id = withContext(Dispatchers.IO) {
                                Graph.dao.insertStation(Station(name = name))
                            }
                            stationId = id
                            newStation = ""
                            pickingStation = false
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            PillButton(
                label = "SAVE",
                filled = true,
                enabled = valid,
                onClick = {
                    val amountLitres = amount ?: return@PillButton
                    val savedKm = resolvedKm ?: lastOdometer ?: 0.0
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            Graph.dao.insertFillup(
                                Fillup(
                                    time = System.currentTimeMillis(),
                                    odometerKm = savedKm,
                                    litres = amountLitres,
                                    costTotal = amounts.totalValue
                                        ?: amounts.priceValue?.let { price -> amountLitres * price },
                                    isFull = fullTank,
                                    stationId = stationId,
                                    odometerKnown = resolvedKm != null,
                                ),
                            )
                        }
                        onDismiss()
                    }
                },
            )
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    val colors = LocalYColors.current
    Column(modifier) {
        Text(label, style = MicroLabel, color = colors.muted)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.background)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = MonoValue, color = colors.muted)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MonoValue.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Everything about one job: where it stands, every time it was done, and a way to log
 * it. The history was previously invisible — logged services existed only in the export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalDetailSheet(
    interval: Interval,
    services: List<ServiceEntry>,
    odometer: Double?,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val history = services.filter { it.kind == interval.kind }.sortedByDescending { it.odometerKm }
    val last = history.maxByOrNull { it.odometerKm }
    val due = Maintenance.dueAtKm(interval.everyKm, last?.odometerKm)
    val remaining = if (due != null && odometer != null) due - odometer else null
    val overdue = remaining != null && remaining <= 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.text,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.line),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("MAINTENANCE ITEM", style = MicroLabel, color = colors.muted)
            Text(interval.kind, fontSize = 24.sp, color = colors.text)
            Text(
                text = buildString {
                    append("every %.0f km".format(interval.everyKm))
                    append(" · ")
                    append(
                        when {
                            history.isEmpty() -> "never logged"
                            remaining == null -> "last at %.0f km".format(last?.odometerKm ?: 0.0)
                            overdue -> "overdue by %.0f km".format(-remaining)
                            else -> "in %.0f km".format(remaining)
                        },
                    )
                },
                style = MonoCode,
                color = if (overdue) colors.accent else colors.muted,
            )

            HorizontalDivider(color = colors.line, thickness = 1.dp)

            Text("HISTORY", style = MicroLabel, color = colors.muted)
            if (history.isEmpty()) {
                Text(
                    "Nothing logged yet. DONE here records it at your current odometer.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.muted,
                )
            } else {
                history.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtDate(entry.time), fontSize = 13.sp, color = colors.text)
                            Text(
                                "%.1f km".format(entry.odometerKm),
                                style = MonoCode,
                                color = colors.muted,
                            )
                        }
                        Text(
                            text = "REMOVE",
                            style = MicroLabel,
                            color = colors.muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { Graph.dao.deleteService(entry.id) }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    HorizontalDivider(color = colors.line, thickness = 1.dp)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(label = "LOG SERVICE", filled = true) {
                    if (odometer == null) {
                        Toast.makeText(
                            context,
                            "Add a fill-up so the app knows your odometer.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Graph.dao.insertService(
                                    ServiceEntry(
                                        kind = interval.kind,
                                        odometerKm = odometer,
                                        time = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                    }
                }
                PillButton(label = "EDIT INTERVAL", onClick = onEdit)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSheet(existing: Interval?, onDismiss: () -> Unit) {
    val colors = LocalYColors.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(existing?.kind.orEmpty()) }
    var km by rememberSaveable {
        mutableStateOf(existing?.everyKm?.let { "%.0f".format(it) }.orEmpty())
    }
    val everyKm = km.toNumberOrNull()
    val valid = kind.isNotBlank() && everyKm != null && everyKm > 0.0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.text,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.line),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (existing == null) "NEW MAINTENANCE ITEM" else "EDIT MAINTENANCE ITEM",
                style = MicroLabel,
                color = colors.muted,
            )
            Text(
                text = "Name the job and how many kilometres between services. Your manual wins over the seeded numbers.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.muted,
            )

            NumberField(
                label = "NAME",
                value = kind,
                placeholder = "Chain lube",
            ) { kind = it }
            NumberField(
                label = "EVERY (KM)",
                value = km,
                placeholder = "500",
            ) { km = it }

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    label = "SAVE",
                    filled = true,
                    enabled = valid,
                    onClick = {
                        val name = kind.trim()
                        val value = everyKm ?: return@PillButton
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (existing == null) {
                                    Graph.dao.insertInterval(Interval(kind = name, everyKm = value))
                                } else {
                                    Graph.dao.updateInterval(
                                        existing.copy(kind = name, everyKm = value),
                                    )
                                    // History is stored by name, so a rename has to follow it.
                                    if (name != existing.kind) {
                                        Graph.dao.renameServiceKind(existing.kind, name)
                                    }
                                }
                            }
                            onDismiss()
                        }
                    },
                )

                if (existing != null) {
                    PillButton(label = "DELETE") {
                        scope.launch {
                            withContext(Dispatchers.IO) { Graph.dao.deleteInterval(existing.id) }
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/** "1 visit", not "1 visits" — the count is nearly always small. */
private fun visitLabel(count: Int): String = if (count == 1) "1 visit" else "$count visits"

/** Keeps a half-typed receipt alive across a rotation. */
private val fillAmountsSaver = listSaver<FillAmounts, String>(
    save = { amounts -> listOf(amounts.litres, amounts.price, amounts.total) },
    restore = { saved -> FillAmounts(litres = saved[0], price = saved[1], total = saved[2]) },
)

/** Accepts both 3.5 and 3,5 because both keyboards happen. */
private fun String.toNumberOrNull(): Double? =
    trim().replace(",", ".").toDoubleOrNull()
