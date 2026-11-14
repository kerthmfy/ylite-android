package app.ylite.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import app.ylite.data.Fillup
import app.ylite.data.FuelStats
import app.ylite.data.Maintenance
import app.ylite.data.ServiceEntry
import app.ylite.data.fuelStats
import app.ylite.export.Exporters
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

    var parked by remember { mutableStateOf<ParkedFix?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var exportLabel by remember { mutableStateOf<String?>(null) }

    // A ride that ends is also a scooter that just parked, so re-read after it.
    LaunchedEffect(live.recording, live.gpsPoints) {
        if (!live.recording) {
            parked = withContext(Dispatchers.IO) {
                Graph.dao.lastPoint()?.let { ParkedFix(it.lat, it.lon, it.time) }
            }
        }
    }

    val odometer = currentOdometerKm(live, fillups)
    val stats = fuelStats(fillups)

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
                Metric("km / l", stats?.kmPerL?.let { "%.1f".format(it) } ?: "—", null, Modifier.weight(1f), stats?.kmPerL != null)
                VDivider()
                Metric(
                    "cost / km",
                    stats?.costPerKm?.let { "%.2f".format(it) } ?: "—",
                    null,
                    Modifier.weight(1f),
                    stats?.costPerKm != null,
                )
                VDivider()
                Metric("fill-ups", fillups.size.toString(), null, Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (fillups.size < 2) {
                    "Log two fill-ups and the app can compute real km/L from your odometer, no CCU required."
                } else {
                    stats?.distanceKm?.let { "%.0f km tracked since your first fill-up.".format(it) }
                        ?: "Two fill-ups needed."
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
                    FillupRow(fillup) {
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
            Maintenance.intervals.forEach { interval ->
                val last = services.filter { it.kind == interval.kind }.maxByOrNull { it.odometerKm }
                val due = Maintenance.dueAtKm(interval, last?.odometerKm)
                val remaining = if (due != null && odometer != null) due - odometer else null
                val overdue = remaining != null && remaining <= 0

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(interval.kind, fontSize = 14.sp, color = colors.text)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when {
                                last == null -> "not logged yet · every %.0f km".format(interval.everyKm)
                                remaining == null -> "due at %.0f km".format(due)
                                overdue -> "overdue by %.0f km".format(-remaining)
                                else -> "in %.0f km".format(remaining)
                            },
                            style = MonoCode,
                            color = if (overdue) colors.accent else colors.muted,
                        )
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
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                HorizontalDivider(color = colors.line, thickness = 1.dp)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Intervals are cautious round numbers for city riding, not a Yamaha schedule. Check your manual.",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.muted,
            )
        }

        Section("DATA") {
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
                                exporters.everything(rides, points, frames, fillups, services)
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
        FillupSheet(lastOdometer = odometer, onDismiss = { addOpen = false })
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
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(title, style = MicroLabel, color = colors.muted)
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = colors.line, thickness = 1.dp)
    }
}

@Composable
private fun FillupRow(fillup: Fillup, onDelete: () -> Unit) {
    val colors = LocalYColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "%.1f km · %.2f L".format(fillup.odometerKm, fillup.litres),
                style = MonoValue,
                color = colors.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(fmtDay(fillup.time), style = MonoCode, color = colors.muted)
        }
        fillup.costTotal?.let {
            Text("%.2f".format(it), style = MonoValue, color = colors.muted)
            Spacer(Modifier.width(14.dp))
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
private fun FillupSheet(lastOdometer: Double?, onDismiss: () -> Unit) {
    val colors = LocalYColors.current
    val scope = rememberCoroutineScope()
    // A hint, never a value: a prefilled field swallows the first digit you type.
    var odometer by rememberSaveable { mutableStateOf("") }
    var litres by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }

    val km = odometer.toNumberOrNull()
    val amount = litres.toNumberOrNull()
    val valid = km != null && amount != null && amount > 0

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
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ADD FILL-UP", style = MicroLabel, color = colors.muted)
            Text(
                "Odometer and litres are enough. Copy the odometer from the dash when you fill up.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.muted,
            )

            NumberField(
                label = "ODOMETER (KM)",
                value = odometer,
                placeholder = lastOdometer?.let { "last %.1f".format(it) } ?: "12345.0",
                onValueChange = { odometer = it },
            )
            NumberField("LITRES", litres, "3.50") { litres = it }
            NumberField("COST (OPTIONAL)", cost, "200.00") { cost = it }

            Spacer(Modifier.height(2.dp))
            PillButton(
                label = "SAVE",
                filled = true,
                enabled = valid,
                onClick = {
                    val odometerKm = km ?: return@PillButton
                    val amountLitres = amount ?: return@PillButton
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            Graph.dao.insertFillup(
                                Fillup(
                                    time = System.currentTimeMillis(),
                                    odometerKm = odometerKm,
                                    litres = amountLitres,
                                    costTotal = cost.toNumberOrNull(),
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
private fun NumberField(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val colors = LocalYColors.current
    Column {
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

/** Accepts both 3.5 and 3,5 because both keyboards happen. */
private fun String.toNumberOrNull(): Double? =
    trim().replace(",", ".").toDoubleOrNull()
