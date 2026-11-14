package app.ylite.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ylite.Graph
import app.ylite.LiveState
import app.ylite.ble.ScanHit
import app.ylite.data.GpsPoint
import app.ylite.data.Ride
import app.ylite.data.Scooters
import app.ylite.export.Exporters
import app.ylite.service.RideService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ----------------------------------------------------------------------- shell

@Composable
fun YLiteApp() {
    val colors = LocalYColors.current
    val live by Graph.live.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableLongStateOf(-1L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions())
    }

    if (detailId > 0) {
        RideDetailScreen(rideId = detailId, onBack = { detailId = -1L })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> RideScreen(live = live)
                1 -> HistoryScreen(onOpen = { detailId = it })
                else -> DebugScreen(live = live)
            }
        }
        TabBar(selected = tab, onSelect = { tab = it })
    }
}

/** Three text tabs with a hairline indicator. No icon bar, no floating pill. */
@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalYColors.current
    val tabs = listOf("Ride", "History", "Debug")

    Column(Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding()) {
        HorizontalDivider(color = colors.line, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            tabs.forEachIndexed { index, label ->
                val active = index == selected
                val indicator by animateDpAsState(if (active) 18.dp else 0.dp, label = "indicator")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = label.uppercase(),
                            style = MicroLabel,
                            color = if (active) colors.text else colors.muted,
                        )
                        Box(
                            Modifier
                                .width(indicator)
                                .height(2.dp)
                                .background(colors.accent),
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- ride tab

@Composable
private fun RideScreen(live: LiveState) {
    val context = LocalContext.current
    val colors = LocalYColors.current

    var sheetOpen by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var lastKnown by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hits by Graph.ble.scanResults.collectAsStateWithLifecycle()

    LaunchedEffect(live.rideId, live.gpsPoints) {
        val id = live.rideId
        track = if (id == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { Graph.dao.points(id).map { it.lat to it.lon } }
        }
    }

    LaunchedEffect(live.recording, live.gpsPoints) {
        if (!live.recording) {
            lastKnown = withContext(Dispatchers.IO) {
                Graph.dao.lastPoint()?.let { it.lat to it.lon }
            }
        }
    }

    LaunchedEffect(live.recording) {
        while (live.recording) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val latitude = live.lat
    val longitude = live.lon
    val here: Pair<Double, Double>? =
        if (latitude != null && longitude != null) latitude to longitude else lastKnown
    // The scooter's own speedo wins when the CCU is decoded; GPS is the fallback.
    val kmh = live.telemetry.speedKph?.roundToInt()
        ?: live.speedMps?.let { (it * 3.6f).roundToInt() }
    val elapsed = live.rideStartedAt?.let { (now - it).coerceAtLeast(0L) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // With no fix and no recorded ride there is nothing to centre on; an empty
            // map would sit at 0,0 in the ocean. Show the state honestly instead.
            if (here == null && track.isEmpty()) {
                MapPlaceholder()
            } else {
                OsmMap(track = track, current = here, modifier = Modifier.fillMaxSize())
            }

            // OSM tiles are light in both themes, so status-bar icons need a scrim
            // to stay readable when the map runs under them.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.background.copy(alpha = 0.92f), Color.Transparent),
                        ),
                    ),
            )

            LinkPill(
                live = live,
                onClick = { sheetOpen = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(14.dp),
            )
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = kmh?.toString() ?: "0",
                    style = DisplayNumber,
                    color = if (kmh == null) colors.muted else colors.text,
                )
                Column(Modifier.padding(start = 10.dp, bottom = 14.dp)) {
                    Text("km/h", style = MicroLabel, color = colors.muted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (live.recording) "REC" else "IDLE",
                        style = MicroLabel,
                        color = if (live.recording) colors.accent else colors.muted,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "GPS %.2f km · ${live.frameCount} frames".format(live.distanceMeters / 1000.0),
                style = MonoCode,
                color = colors.muted,
            )

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Metric(
                    "trip",
                    fmtKm1(live.telemetry.tripKm),
                    "km",
                    Modifier.weight(1f),
                    available = live.telemetry.tripKm != null,
                )
                VDivider()
                Metric(
                    "odo",
                    fmtKm1(live.telemetry.odoKm),
                    "km",
                    Modifier.weight(1f),
                    available = live.telemetry.odoKm != null,
                )
                VDivider()
                Metric(
                    "fuel",
                    fmtWhole(live.telemetry.fuelPercent),
                    "%",
                    Modifier.weight(1f),
                    available = live.telemetry.fuelPercent != null,
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Metric(
                    "temp",
                    fmtWhole(live.telemetry.tempC),
                    "°C",
                    Modifier.weight(1f),
                    available = live.telemetry.tempC != null,
                )
                VDivider()
                Metric(
                    "battery",
                    fmtWhole(live.telemetry.batteryPercent),
                    "%",
                    Modifier.weight(1f),
                    available = live.telemetry.batteryPercent != null,
                )
                VDivider()
                Metric(
                    "elapsed",
                    elapsed?.let { fmtDuration(it) } ?: "—",
                    null,
                    Modifier.weight(1f),
                    available = elapsed != null,
                )
            }

            if (live.telemetry.isEmpty) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Scooter values stay blank until the CCU frames are decoded.",
                    fontSize = 11.sp,
                    color = colors.muted,
                )
            }

            Spacer(Modifier.height(20.dp))

            RecordButton(
                recording = live.recording,
                onClick = {
                    if (live.recording) {
                        RideService.stop(context)
                    } else if (hasFineLocation(context)) {
                        RideService.start(context)
                    } else {
                        Toast.makeText(
                            context,
                            "Precise location is required to record a ride.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { sheetOpen = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (live.connected) colors.good else colors.muted),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = live.model ?: "Scooter link",
                        fontSize = 14.sp,
                        color = if (live.model != null) colors.text else colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (live.connected) {
                            "connected · ${live.deviceAddress.orEmpty()}"
                        } else {
                            "not connected"
                        },
                        style = MicroLabel,
                        color = colors.muted,
                        maxLines = 1,
                    )
                }
                Text("MANAGE", style = MicroLabel, color = colors.accent)
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (sheetOpen) {
        DeviceSheet(live = live, hits = hits, onDismiss = { sheetOpen = false })
    }
}

@Composable
private fun MapPlaceholder() {
    val colors = LocalYColors.current
    Box(
        modifier = Modifier.fillMaxSize().background(colors.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(28.dp).height(2.dp).background(colors.accent))
            Spacer(Modifier.height(16.dp))
            Text("NO POSITION YET", style = MicroLabel, color = colors.text)
            Spacer(Modifier.height(7.dp))
            Text("Start a ride to put yourself on the map.", fontSize = 12.sp, color = colors.muted)
        }
    }
}

@Composable
private fun LinkPill(live: LiveState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalYColors.current
    val                 label = when {
                    live.connected -> if (live.frameCount > 0) "LIVE · ${live.frameCount}" else "LIVE"
                    live.scanning -> "SCANNING"
                    else -> "OFFLINE"
                }
    val dot = when {
        live.connected -> colors.good
        live.scanning -> colors.warn
        else -> colors.muted
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface.copy(alpha = 0.94f))
            .border(1.dp, colors.line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(label, style = MicroLabel, color = colors.text)
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    available: Boolean = true,
) {
    val colors = LocalYColors.current
    Column(modifier) {
        Text(label.uppercase(), style = MicroLabel, color = colors.muted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                color = if (available) colors.text else colors.muted,
                maxLines = 1,
            )
            if (unit != null && available) {
                Text(
                    text = unit,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    fontSize = 11.sp,
                    color = colors.muted,
                )
            }
        }
    }
}

@Composable
private fun VDivider() {
    val colors = LocalYColors.current
    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .width(1.dp)
            .height(34.dp)
            .background(colors.line),
    )
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val colors = LocalYColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (recording) Color.Transparent else colors.accent)
            .border(
                width = 1.dp,
                color = if (recording) colors.accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (recording) "STOP RIDE" else "START RIDE",
            style = MicroLabel.copy(fontSize = 12.sp),
            color = if (recording) colors.accent else colors.onAccent,
        )
    }
}

// --------------------------------------------------------------- device sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(live: LiveState, hits: List<ScanHit>, onDismiss: () -> Unit) {
    val colors = LocalYColors.current
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.text,
        dragHandle = { Box(Modifier.padding(vertical = 12.dp).width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(colors.line)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SCOOTER", style = MicroLabel, color = colors.muted)
            Text(
                text = live.model ?: "Model not set",
                fontSize = 26.sp,
                letterSpacing = (-0.6).sp,
                color = if (live.model != null) colors.text else colors.muted,
            )
            Text(
                text = if (live.connected) {
                    "Connected — ${live.deviceAddress.orEmpty()}"
                } else {
                    "Not connected"
                },
                fontSize = 13.sp,
                color = colors.muted,
            )
            Text(live.status, fontSize = 13.sp, lineHeight = 19.sp, color = colors.muted)
            Text("bond: ${live.bond}", style = MonoCode, color = colors.muted)

            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(label = if (picking) "CLOSE LIST" else "CHANGE MODEL") {
                    picking = !picking
                }
            }

            if (picking) {
                Column {
                    Scooters.models.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    Graph.settings.scooterModel = model
                                    Graph.setLive { it.copy(model = model) }
                                    picking = false
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = model,
                                fontSize = 14.sp,
                                color = colors.text,
                                modifier = Modifier.weight(1f),
                            )
                            if (model == live.model) {
                                Text("SET", style = MicroLabel, color = colors.accent)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    label = when {
                        live.connected -> "DISCONNECT"
                        live.scanning -> "STOP SCAN"
                        else -> "SCAN"
                    },
                    filled = !live.connected && !live.scanning,
                ) {
                    when {
                        live.connected -> Graph.ble.disconnect()
                        live.scanning -> Graph.ble.stopScan()
                        else -> Graph.ble.startScan()
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Text("CCU ADDRESS", style = MicroLabel, color = colors.muted)
            MacField(value = address, onValueChange = { address = it })
            PillButton(
                label = "CONNECT",
                filled = address.trim().length >= 17,
                enabled = address.trim().length >= 17,
            ) {
                Graph.ble.connect(address.trim(), null)
            }

            if (hits.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("NEARBY", style = MicroLabel, color = colors.muted)
                hits.take(6).forEach { hit ->
                    DeviceRow(hit) { Graph.ble.connect(hit.address, hit.name) }
                }
            }

            PillButton(label = "BLUETOOTH SETTINGS") {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "The CCU only offers its data over a paired link, so Android must bond with it before frames flow. If it asks for a PIN, try 000000. Forget the CCU in Bluetooth settings if a pairing attempt went wrong.",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.muted,
            )
            Text(
                "Read-only: Y-Lite never writes to the scooter. The CCU only powers up while the ignition is on, so scan with the scooter switched on and parked.",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun DeviceRow(hit: ScanHit, onClick: () -> Unit) {
    val colors = LocalYColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = hit.name ?: "unnamed device",
                fontSize = 14.sp,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(hit.address, style = MonoCode, color = colors.muted)
            hit.advertised?.let { Text(it, style = MonoCode, color = colors.accent, maxLines = 2) }
        }
        Text("${hit.rssi} dBm", style = MicroLabel, color = colors.muted)
    }
}

@Composable
private fun PillButton(
    label: String,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalYColors.current
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) colors.accent else colors.surfaceRaised)
            .border(
                width = 1.dp,
                color = if (filled) Color.Transparent else colors.line,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MicroLabel.copy(fontSize = 11.sp),
            color = when {
                !enabled -> colors.muted
                filled -> colors.onAccent
                else -> colors.text
            },
        )
    }
}

@Composable
private fun MacField(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalYColors.current
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
            Text("AA:BB:CC:DD:EE:FF", style = MonoValue, color = colors.muted)
        }
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.uppercase()) },
            singleLine = true,
            textStyle = MonoValue.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------- history tab

@Composable
private fun HistoryScreen(onOpen: (Long) -> Unit) {
    val colors = LocalYColors.current
    val ridesFlow = remember { Graph.dao.rides() }
    val rides by ridesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("History", trailing = "${rides.size} rides")

        if (rides.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(28.dp).height(2.dp).background(colors.accent))
                    Spacer(Modifier.height(14.dp))
                    Text("Nothing recorded yet", fontSize = 15.sp, color = colors.text)
                    Spacer(Modifier.height(4.dp))
                    Text("Start a ride on the Ride tab.", fontSize = 12.sp, color = colors.muted)
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(rides, key = { it.id }) { ride ->
                RideRow(ride = ride, onClick = { onOpen(ride.id) })
                HorizontalDivider(color = colors.line, thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun RideRow(ride: Ride, onClick: () -> Unit) {
    val colors = LocalYColors.current
    val end = ride.endedAt ?: System.currentTimeMillis()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = ride.name ?: fmtDay(ride.startedAt),
                fontSize = 16.sp,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(fmtClock(ride.startedAt), ride.model).joinToString(" · "),
                fontSize = 12.sp,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtDuration(end - ride.startedAt), style = MonoValue, color = colors.text)
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (ride.endedAt == null) "NO END" else "RIDE ${ride.id}",
                style = MicroLabel,
                color = if (ride.endedAt == null) colors.accent else colors.muted,
            )
        }
    }
}

// ----------------------------------------------------------------- ride detail

@Composable
private fun RideDetailScreen(rideId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalYColors.current
    val scope = rememberCoroutineScope()

    val rideFlow = remember(rideId) { Graph.dao.ride(rideId) }
    val ride by rideFlow.collectAsStateWithLifecycle(initialValue = null)

    var points by remember { mutableStateOf<List<GpsPoint>>(emptyList()) }
    var frameCount by remember { mutableStateOf(0) }
    val exporters = remember { Exporters(context) }

    LaunchedEffect(rideId) {
        points = withContext(Dispatchers.IO) { Graph.dao.points(rideId) }
        frameCount = withContext(Dispatchers.IO) { Graph.dao.frameCount(rideId) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.text,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("RIDE $rideId", style = MicroLabel, color = colors.muted, modifier = Modifier.padding(end = 14.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            val loadedRide: Ride? = ride
            val end = loadedRide?.endedAt ?: System.currentTimeMillis()

            Text(
                text = loadedRide?.name ?: "Ride $rideId",
                fontSize = 30.sp,
                letterSpacing = (-0.8).sp,
                color = colors.text,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = loadedRide?.let { fmtDate(it.startedAt) }.orEmpty(),
                fontSize = 13.sp,
                color = colors.muted,
            )
            val rideModel = loadedRide?.model
            if (rideModel != null) {
                Text(rideModel, fontSize = 13.sp, color = colors.text)
            }

            Spacer(Modifier.height(22.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Metric(
                    "duration",
                    loadedRide?.let { fmtDuration(end - it.startedAt) } ?: "—",
                    null,
                    Modifier.weight(1f),
                )
                VDivider()
                Metric("distance", "%.2f".format(pointsDistance(points) / 1000.0), "km", Modifier.weight(1f))
                VDivider()
                Metric("max", fmtSpeed(maxSpeed(points)), "km/h", Modifier.weight(1f))
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Metric("points", points.size.toString(), null, Modifier.weight(1f))
                VDivider()
                Metric("frames", frameCount.toString(), null, Modifier.weight(1f))
                VDivider()
                Metric("started", fmtClock(loadedRide?.startedAt), null, Modifier.weight(1f))
            }

            Spacer(Modifier.height(22.dp))

            OsmMap(
                track = points.map { it.lat to it.lon },
                current = null,
                follow = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(16.dp)),
            )

            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(label = "EXPORT GPX", filled = true) {
                    val r = loadedRide ?: return@PillButton
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { exporters.gpx(r, points) }
                        share(context, file, "application/gpx+xml")
                    }
                }
                PillButton(label = "FRAMES CSV") {
                    val r = loadedRide ?: return@PillButton
                    scope.launch {
                        val frames = withContext(Dispatchers.IO) { Graph.dao.frames(rideId) }
                        val file = withContext(Dispatchers.IO) { exporters.framesCsv(r, frames) }
                        share(context, file, "text/csv")
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- debug tab

@Composable
private fun DebugScreen(live: LiveState) {
    val colors = LocalYColors.current

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader("Debug", trailing = "${live.frameCount} frames")

        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("LINK", style = MicroLabel, color = colors.muted)
            Text(live.status, fontSize = 14.sp, lineHeight = 20.sp, color = colors.text)
            live.deviceAddress?.let { Text(it, style = MonoValue, color = colors.muted) }
            Text("bond: ${live.bond}", style = MonoCode, color = colors.muted)
            live.model?.let { Text("model: $it", style = MonoCode, color = colors.muted) }

            Spacer(Modifier.height(10.dp))
            Text("FRAMES (NEWEST FIRST)", style = MicroLabel, color = colors.muted)
            if (live.recentFrames.isEmpty()) {
                MonoBlock(
                    "No frames yet. Connect to the CCU: every notification lands here with its arrival time.",
                )
            } else {
                MonoBlock(
                    live.recentFrames.joinToString("\n") { "${fmtClock(it.time)}  ${it.hex}" },
                )
            }

            if (live.telemetry.extra.isNotEmpty()) {
                Text("DECODED", style = MicroLabel, color = colors.muted)
                live.telemetry.extra.forEach { (key, value) ->
                    Text("$key = $value", style = MonoValue, color = colors.text)
                }
            } else {
                Text(
                    "The decoder is a stub: it returns no values until the CCU's payloads are known.",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = colors.muted,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("GATT TABLE", style = MicroLabel, color = colors.muted)
            MonoBlock(
                live.gattDump.ifEmpty {
                    "Not connected. Connect to the CCU and its services, characteristics and subscriptions appear here."
                },
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Phase 0: capture first, decode second. Keep the official app closed while Y-Lite holds the connection.",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun MonoBlock(text: String) {
    val colors = LocalYColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(text, style = MonoCode, color = colors.text)
        }
    }
}

// --------------------------------------------------------------------- pieces

@Composable
private fun ScreenHeader(title: String, trailing: String? = null) {
    val colors = LocalYColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MicroLabel.copy(fontSize = 11.sp),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = MonoCode, color = colors.muted)
        }
    }
}

// --------------------------------------------------------------------- helpers

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_SCAN
        permissions += Manifest.permission.BLUETOOTH_CONNECT
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }
    return permissions.toTypedArray()
}

private fun share(context: Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }.onFailure {
        Toast.makeText(context, "Could not share: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

private fun fmtDate(epochMs: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun fmtDay(epochMs: Long): String =
    SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(epochMs))

private fun fmtClock(epochMs: Long?): String =
    epochMs?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun fmtDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%d:%02d:%02d".format(hours, minutes, seconds)
}

private fun fmtKm1(value: Double?): String = value?.let { "%.1f".format(it) } ?: "—"

private fun fmtWhole(value: Float?): String = value?.let { "%.0f".format(it) } ?: "—"

private fun fmtSpeed(metresPerSecond: Float?): String =
    metresPerSecond?.let { "%.0f".format(it * 3.6f) } ?: "—"

private fun maxSpeed(points: List<GpsPoint>): Float? =
    points.mapNotNull { it.speedMps }.maxOrNull()

private fun pointsDistance(points: List<GpsPoint>): Double {
    var total = 0.0
    for (index in 1 until points.size) {
        total += distanceMetres(points[index - 1], points[index])
    }
    return total
}

private fun distanceMetres(a: GpsPoint, b: GpsPoint): Double {
    val earth = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earth * asin(sqrt(h))
}
