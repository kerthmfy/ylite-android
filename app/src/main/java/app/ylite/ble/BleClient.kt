package app.ylite.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.ylite.Graph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "BleClient"
private const val STALL_MS = 4_000L
private const val INVITE_BACKOFF_CAP_MS = 30_000L

/** How long to wait for the stack to answer a connectGatt() before dropping it. */
private const val CONNECT_WAIT_MS = 8_000L

/** How long the link may sit subscription-ready without a single notification. */
private const val SILENCE_MS = 10_000L
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Official app: ack1 came 199 ms after handshake #1, ack2 481 ms after #2. */
private const val ACK_TIMEOUT_MS = 1_500L
private const val HEARTBEAT_PERIOD_MS = 1_000L
private const val HEARTBEAT_B_DELAY_MS = 100L

data class ScanHit(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    /**
     * Raw advertisement payload (service UUIDs, manufacturer data) in hex.
     * Captured as evidence: if the CCU ever advertises a model code, it shows up
     * here rather than in the GATT table.
     */
    val advertised: String? = null,
)

data class BleFrameEvent(
    val time: Long,
    val address: String,
    val service: String,
    val characteristic: String,
    val data: ByteArray,
)

/**
 * GATT client for the Yamaha CCU.
 *
 * Android allows one GATT operation at a time per device, so every operation goes
 * through [ops] and is executed by a single worker coroutine. If an operation gets
 * no answer within [STALL_MS] the connection is torn down and rebuilt — a stalled
 * GATT stack never recovers on its own.
 *
 * Connect flow (see [CcuProtocol]): discover → subscribe 6e400003 (CCCD) → MTU 247 →
 * handshake #1 → ack1 → handshake #2 → ack2 → stream, with heartbeat A/B every second.
 * The CCU hangs up ~2 s after connect if it gets no handshake.
 *
 * The only characteristic writes are the handshake and heartbeat frames seen from the
 * official app. Nothing else is ever sent.
 */
class BleClient(private val context: Context) {

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _scanResults = MutableStateFlow<List<ScanHit>>(emptyList())
    val scanResults: StateFlow<List<ScanHit>> = _scanResults.asStateFlow()

    /** Frames stream out here; RideService persists them while a ride is recording. */
    private val _frames = MutableSharedFlow<BleFrameEvent>(extraBufferCapacity = 256)
    val frames: SharedFlow<BleFrameEvent> = _frames.asSharedFlow()

    /** null means "the user does not want a connection"; non-null means "keep retrying". */
    @Volatile
    private var wanted: Pair<String, String?>? = null

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var current: GattOp? = null

    private var scanning = false
    private var reconnectJob: Job? = null
    private var connectAttempts = 0
    private var bondReceiver: BroadcastReceiver? = null
    private var pendingBondDevice: BluetoothDevice? = null

    /** When the current link came up, so a drop can say how long it survived. */
    @Volatile
    private var connectedAt = 0L

    /** Frames seen on the current link, used by the silence watchdog. */
    @Volatile
    private var framesOnLink = 0

    /** Bumped on every drop, so a connect attempt knows the stack already gave up. */
    @Volatile
    private var dropSeq = 0

    /** Handshake and heartbeat for the current link; cancelled on every drop. */
    @Volatile
    private var handshakeJob: Job? = null

    @Volatile
    private var heartbeatJob: Job? = null

    /** Ack payloads (00 / 01) from the CCU, fed by onCharacteristicChanged. New per link. */
    @Volatile
    private var acks = Channel<Int>(Channel.UNLIMITED)

    /** Frames on this link whose checksum did not add up (usually means the MTU did not apply). */
    @Volatile
    private var badFramesOnLink = 0

    /** Debug experiment (handover test plan step 4): skip heartbeats until this time. */
    @Volatile
    private var heartbeatPausedUntil = 0L

    /** One automatic attempt per process: an explicit DISCONNECT stays disconnected. */
    private var autoConnectDone = false

    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    /** A GATT status is a number to everyone except the person reading the log. */
    private fun statusHint(status: Int): String = when (status) {
        0 -> ""
        8 -> " (timeout — the CCU stopped answering)"
        19 -> " (the CCU closed the link)"
        133 -> " (GATT error — usually a stale pairing; forget the CCU and pair again)"
        137 -> " (bond lost — pair again)"
        147 -> " (link terminated by the phone)"
        else -> ""
    }

    /**
     * One timestamped line for the Debug tab. The whole point is that a dropped link,
     * a rejected CCCD write and a quiet CCU stop looking identical on screen.
     */
    private fun logLink(line: String) {
        val stamp = clock.format(java.util.Date())
        Graph.setLive { it.copy(linkLog = (listOf("$stamp $line") + it.linkLog).take(60)) }
    }

    private val ops = Channel<GattOp>(Channel.UNLIMITED)

    private val worker = scope.launch {
        for (op in ops) {
            current = op
            val answered = withTimeoutOrNull(STALL_MS) { op.run() } ?: false
            current = null
            if (!answered) {
                Log.w(TAG, "GATT operation stalled: ${op.label}")
                op.abort()
                teardown("GATT stalled on ${op.label}")
            } else if (op is DiscoverOp) {
                configure(op.gatt)
            }
        }
    }

    // ---------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val hit = ScanHit(
                address = device.address,
                name = result.scanRecord?.deviceName,
                rssi = result.rssi,
                lastSeen = System.currentTimeMillis(),
                advertised = describeAdvertisement(result),
            )
            _scanResults.update { list ->
                (list.filterNot { it.address == hit.address } + hit).sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Graph.setLive { it.copy(scanning = false, status = "Scan failed (code $errorCode)") }
        }
    }

    /** Flattens the advertisement into something you can read in a screenshot. */
    private fun describeAdvertisement(result: ScanResult): String? = runCatching {
        val record = result.scanRecord ?: return@runCatching null
        val parts = mutableListOf<String>()
        record.serviceUuids?.forEach { parts += "svc:${shortUuid(it.uuid)}" }
        record.manufacturerSpecificData?.let { data ->
            for (index in 0 until data.size()) {
                val hex = data.valueAt(index)
                    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                parts += "mfr:%04x:%s".format(data.keyAt(index), hex)
            }
        }
        parts.joinToString(" ").ifEmpty { null }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission()) {
            Graph.setLive { it.copy(status = "Missing Bluetooth scan permission") }
            return
        }
        if (scanning) return
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Graph.setLive { it.copy(status = "Bluetooth is off or unavailable") }
            return
        }
        _scanResults.value = emptyList()
        scanning = true
        Graph.setLive { it.copy(scanning = true, status = "Scanning… (turn the scooter on)") }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
        scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        Graph.setLive { it.copy(scanning = false, status = if (it.connected) "Connected" else "Idle") }
    }

    // ------------------------------------------------------------- connection

    fun connect(address: String, name: String?) {
        if (!hasConnectPermission()) {
            Graph.setLive { it.copy(status = "Missing Bluetooth connect permission") }
            return
        }
        wanted = address to name
        // Remembered so the app can return to it by itself on the next ignition.
        Graph.settings.ccuAddress = address
        name?.let { Graph.settings.ccuName = it }
        connectAttempts = 0
        stopScan()
        reconnectJob?.cancel()
        logLink("connect $address (${name ?: "no name"})")
        Graph.setLive {
            it.copy(
                status = "Connecting to ${name ?: address}…",
                deviceAddress = address,
                deviceName = name,
            )
        }
        scope.launch { open(address) }
    }

    /**
     * Reconnect on launch: the CCU is only awake while the ignition is on, so this
     * either works straight away or the retry loop catches it when the scooter starts.
     */
    fun autoConnect() {
        if (autoConnectDone || wanted != null) return
        if (!hasConnectPermission() || hasScanPermission().not()) return
        val address = Graph.settings.ccuAddress ?: return
        val name = Graph.settings.ccuName
        autoConnectDone = true
        wanted = address to name
        connectAttempts = 0
        logLink("auto-connect to saved CCU $address")
        Graph.setLive {
            it.copy(status = "Reconnecting to ${name ?: address}…", deviceAddress = address, deviceName = name)
        }
        scope.launch {
            open(address)
            scheduleReconnect()
        }
    }

    /** The Debug tab's RECONNECT button: start over with the remembered device. */
    fun reconnectNow() {
        val address = Graph.settings.ccuAddress
        if (address == null) {
            Graph.setLive { it.copy(status = "No CCU saved yet — open Manage and connect once") }
            return
        }
        wanted = address to Graph.settings.ccuName
        connectAttempts = 0
        reconnectJob?.cancel()
        logLink("manual reconnect to $address")
        scope.launch {
            open(address)
            scheduleReconnect()
        }
    }

    fun disconnect() {
        wanted = null
        reconnectJob?.cancel()
        logLink("disconnect requested")
        unregisterBondReceiver()
        stopLinkJobs()
        scope.launch {
            val g = gatt
            runCatching { g?.disconnect() }
            runCatching { g?.close() }
            if (gatt === g) gatt = null
            Graph.setLive { it.copy(connected = false, status = "Disconnected") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(address: String) {
        val device = runCatching { manager?.adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            Graph.setLive { it.copy(status = "Unknown device $address") }
            return
        }
        runCatching { gatt?.close() }
        gatt = null
        val g = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (g == null) {
            logLink("connectGatt failed for $address")
            Graph.setLive { it.copy(status = "connectGatt failed for $address") }
            scheduleReconnect()
            return
        }
        gatt = g
        Graph.setLive {
            it.copy(deviceAddress = address, deviceName = it.deviceName ?: wanted?.second)
        }
    }

    private fun closeGatt(g: BluetoothGatt) {
        stopLinkJobs()
        runCatching { g.close() }
        if (gatt === g) gatt = null
    }

    /** Heartbeats must never outlive the link they were sent on. */
    private fun stopLinkJobs() {
        handshakeJob?.cancel()
        handshakeJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Debug: stop sending heartbeats for [ms], to find out whether the CCU needs them
     * (handover test plan step 4). The link log shows whether the link survives.
     */
    fun pauseHeartbeat(ms: Long = 10_000L) {
        heartbeatPausedUntil = System.currentTimeMillis() + ms
        logLink("heartbeat paused for ${ms / 1000}s (experiment)")
    }

    /** Drop the connection, cancel queued work, and try again if a connection is still wanted. */
    private fun teardown(reason: String) {
        logLink("teardown: $reason")
        stopLinkJobs()
        Graph.setLive { it.copy(connected = false, status = reason) }
        val g = gatt
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
        if (gatt === g) gatt = null
        while (true) {
            val queued = ops.tryReceive().getOrNull() ?: break
            queued.abort()
        }
        scheduleReconnect()
    }

    /**
     * Keep trying for as long as a connection is wanted.
     *
     * The CCU only exists while the ignition is on, so "no answer" is the normal state
     * of a parked scooter. This loop backs off to one attempt every 30 s and never gives
     * up, so switching the scooter on is enough to get connected — no tapping.
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        if (wanted == null) return
        reconnectJob = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                val target = wanted ?: break
                if (Graph.live.value.connected) {
                    backoff = 2_000L
                    delay(2_000)
                    continue
                }
                val startSeq = dropSeq
                if (gatt == null) {
                    connectAttempts++
                    logLink("connect attempt #$connectAttempts")
                    open(target.first)
                }

                // Wait for the stack to answer, and stop waiting the moment it reports a
                // drop: a failed attempt should not cost the full timeout on top of backoff.
                val deadline = System.currentTimeMillis() + CONNECT_WAIT_MS
                while (isActive && System.currentTimeMillis() < deadline &&
                    !Graph.live.value.connected && dropSeq == startSeq
                ) {
                    delay(500)
                }
                if (Graph.live.value.connected) {
                    backoff = 2_000L
                    continue
                }

                logLink("attempt failed — retry in ${backoff / 1000}s")
                val dead = gatt
                runCatching { dead?.close() }
                if (gatt === dead) gatt = null
                Graph.setLive {
                    it.copy(connected = false, status = "Scooter not answering — retrying in ${backoff / 1000}s")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(INVITE_BACKOFF_CAP_MS)
            }
        }
    }

    // --------------------------------------------------------------- callbacks

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "connected"
                BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                BluetoothProfile.STATE_CONNECTING -> "connecting"
                BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
                else -> "state $newState"
            }
            val lived = if (connectedAt > 0) {
                " after %.1f s".format((System.currentTimeMillis() - connectedAt) / 1000.0)
            } else {
                ""
            }
            logLink("$stateName$lived (status $status${statusHint(status)})")

            // 5 = insufficient authentication, 15 = insufficient encryption: the CCU wants
            // a bonded link. Blindly reconnecting here is what produced the repeated
            // pairing attempts and the "incorrect PIN" error. Handle it explicitly.
            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
            ) {
                Graph.setLive { it.copy(connected = false, status = "The CCU needs a paired link") }
                closeGatt(g)
                if (g.device.bondState == BluetoothDevice.BOND_BONDED) {
                    wanted = null
                    Graph.setLive {
                        it.copy(
                            status = "Pairing key rejected. Forget the CCU in Bluetooth settings, then pair again.",
                        )
                    }
                } else {
                    requestBond(g.device)
                }
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_DISCONNECTED) {
                Graph.setLive { it.copy(connected = false, status = "GATT error $status") }
                closeGatt(g)
                scheduleReconnect()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectAttempts = 0
                    connectedAt = System.currentTimeMillis()
                    framesOnLink = 0
                    badFramesOnLink = 0
                    Graph.setLive {
                        it.copy(
                            connected = true,
                            status = "Connected — discovering services…",
                            deviceAddress = g.device.address,
                            bond = bondLabel(g.device.bondState),
                        )
                    }
                    enqueue(DiscoverOp(g))
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    dropSeq++
                    Graph.setLive {
                        it.copy(
                            connected = false,
                            status = "Link lost (status $status) — reconnecting",
                        )
                    }
                    connectedAt = 0
                    closeGatt(g)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            current?.onDiscover(g, status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            current?.onRead(g, characteristic, status)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            current?.onDescriptorWrite(g, descriptor, status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            current?.onWrite(g, characteristic, status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            current?.onMtu(g, mtu, status)
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            framesOnLink++
            val service = characteristic.service?.uuid?.toString().orEmpty()
            val event = BleFrameEvent(
                time = System.currentTimeMillis(),
                address = g.device.address,
                service = service,
                characteristic = characteristic.uuid.toString(),
                data = value,
            )
            _frames.tryEmit(event)

            // One frame per notification; a bad checksum almost always means a truncated
            // frame, i.e. the MTU did not apply.
            if (characteristic.uuid == CcuProtocol.NUS_TX && value.isNotEmpty()) {
                if (!CcuProtocol.checksumOk(value)) {
                    badFramesOnLink++
                    if (badFramesOnLink <= 5) {
                        logLink("bad checksum on ${value.size} B frame (type %02x)".format(value[0].toInt() and 0xff))
                    }
                } else if ((value[0].toInt() and 0xff) == CcuProtocol.T_ACK) {
                    val p = CcuProtocol.ackPayload(value)
                    if (p != null) acks.trySend(p) else logLink("unrecognised 5a frame: ${value.hex()}")
                }
            }

            val decoded = runCatching {
                ProtocolDecoder.decode(
                    characteristic.service?.uuid ?: UUID(0, 0),
                    characteristic.uuid,
                    value,
                )
            }.getOrDefault(app.ylite.data.Telemetry())

            val line = app.ylite.data.FrameLine(System.currentTimeMillis(), value.hex())

            Graph.setLive {
                it.copy(
                    frameCount = it.frameCount + 1,
                    lastFrameHex = value.hex(),
                    telemetry = it.telemetry.merge(decoded),
                    recentFrames = (listOf(line) + it.recentFrames).take(60),
                )
            }
        }
    }

    // --------------------------------------------------------------- GATT work

    /**
     * Dump the whole GATT table, then read every readable characteristic and
     * subscribe to every notify/indicate characteristic.
     */
    @SuppressLint("MissingPermission")
    private suspend fun configure(g: BluetoothGatt) {
        val dump = StringBuilder()
        var readable = 0
        var streams = 0
        val streamMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE

        for (service in g.services) {
            dump.appendLine("Service ${service.uuid}")
            for (c in service.characteristics) {
                dump.appendLine("  char ${c.uuid}  [${properties(c.properties)}]")
                for (d in c.descriptors) dump.appendLine("    desc ${d.uuid}")
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) readable++
                if (c.properties and streamMask != 0) streams++
            }
        }

        val bondState = g.device.bondState
        Graph.setLive { it.copy(gattDump = dump.toString(), bond = bondLabel(bondState)) }

        // Writing the notification descriptor on an unbonded link makes Android start
        // pairing in the middle of the write, which is exactly the failure the user saw.
        // Ask for the bond first; after bonding we reconnect and subscribe on a paired link.
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Graph.setLive {
                it.copy(status = "CCU found — $streams notify channel(s). Pairing needed.")
            }
            requestBond(g.device)
            return
        }

        // No characteristic reads: the official app does none, and the CCU only gives a
        // client ~2 s to send its handshake. Subscribe, then go straight to the handshake.
        for (service in g.services) {
            for (c in service.characteristics) {
                if (c.properties and streamMask != 0) enqueue(SubscribeOp(g, c))
            }
        }

        logLink("services discovered: $readable readable, $streams notify channel(s), bond ${bondLabel(bondState)}")

        // Capability, not confirmation: whether the subscribe actually took is what the
        // link log reports, one line per characteristic.
        Graph.setLive {
            it.copy(status = "Connected — $readable readable, $streams notify channel(s)")
        }
        startHandshake(g)
        watchSilence(g)
    }

    /**
     * "Subscribed but nothing arrives" and "the link died" looked the same on the Ride
     * tab, so say which one it is once the link has had time to speak.
     */
    private fun watchSilence(g: BluetoothGatt) {
        val startFrames = framesOnLink
        scope.launch {
            delay(SILENCE_MS)
            if (gatt !== g || !Graph.live.value.connected) return@launch
            if (framesOnLink != startFrames) return@launch
            logLink("no notifications in ${SILENCE_MS / 1000}s (subscribed, link still up)")
            Graph.setLive {
                it.copy(status = "Subscribed, but the CCU is silent — see the link log")
            }
        }
    }

    /**
     * MTU → handshake #1 → ack → handshake #2 → ack → heartbeat. Ack-gated, like the
     * official app (it sent #2 13 ms after ack1). Runs beside the op worker, never in it,
     * because it waits for ops the worker has yet to run.
     */
    private fun startHandshake(g: BluetoothGatt) {
        val rx = g.getService(CcuProtocol.NUS_SERVICE)?.getCharacteristic(CcuProtocol.NUS_RX)
        if (rx == null) {
            logLink("no Nordic UART RX characteristic — not a CCU? staying subscribe-only")
            return
        }
        val hs1 = CcuProtocol.parseHex(Graph.settings.ccuHandshake1)
        val hs2 = CcuProtocol.parseHex(Graph.settings.ccuHandshake2)
        val problem = CcuProtocol.validateHandshake(hs1, 0)?.let { "handshake #1: $it" }
            ?: CcuProtocol.validateHandshake(hs2, 1)?.let { "handshake #2: $it" }
        if (problem != null || hs1 == null || hs2 == null) {
            logLink("no usable handshake set ($problem) — the CCU will hang up; add it under Scooter › Settings")
            Graph.setLive { it.copy(status = "No CCU handshake set — see Scooter › Settings") }
            return
        }

        handshakeJob?.cancel()
        heartbeatJob?.cancel()
        val ackChannel = Channel<Int>(Channel.UNLIMITED)
        acks = ackChannel

        handshakeJob = scope.launch {
            // Queued behind the CCCD write, so this also means "subscribed".
            val mtuDone = CompletableDeferred<Int?>()
            enqueue(MtuOp(g, CcuProtocol.MTU, mtuDone))
            val mtu = withTimeoutOrNull(STALL_MS * 3) { mtuDone.await() }
            if (gatt !== g) return@launch
            when {
                mtu == null -> logLink("MTU request got no answer — sending handshake anyway")
                mtu < 80 -> logLink("MTU $mtu — too small for 76 B frames, expect bad checksums")
                else -> logLink("MTU $mtu")
            }

            enqueue(WriteOp(g, rx, hs1, "handshake #1"))
            val ack1 = withTimeoutOrNull(ACK_TIMEOUT_MS) { ackChannel.receive() }
            if (gatt !== g) return@launch
            if (ack1 == null) {
                teardown("no ack to handshake #1 within ${ACK_TIMEOUT_MS} ms")
                return@launch
            }

            if (ack1 == 0) {
                logLink("ack1 (00) — sending handshake #2")
                enqueue(WriteOp(g, rx, hs2, "handshake #2"))
                val ack2 = withTimeoutOrNull(ACK_TIMEOUT_MS) { ackChannel.receive() }
                if (gatt !== g) return@launch
                if (ack2 == null) {
                    teardown("no ack to handshake #2 within ${ACK_TIMEOUT_MS} ms")
                    return@launch
                }
                logLink("ack2 (%02x) — handshake done".format(ack2))
            } else {
                // Seen on the official app's reconnects, which then sent no #2 and got no
                // stream. Meaning unknown; try streaming and let the link log tell.
                logLink("ack1 payload %02x (UNTESTED) — skipping handshake #2".format(ack1))
            }

            Graph.setLive { it.copy(status = "Handshake done — waiting for frames") }
            startHeartbeat(g, rx)
        }
    }

    /** Heartbeat A, then B 100 ms later, every second. seq runs A=0, B=1, A=2… per link. */
    private fun startHeartbeat(g: BluetoothGatt, rx: BluetoothGattCharacteristic) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var seq = 0
            var wasPaused = false
            while (isActive && gatt === g) {
                val paused = System.currentTimeMillis() < heartbeatPausedUntil
                if (wasPaused && !paused) logLink("heartbeat resumed")
                wasPaused = paused
                if (!paused) {
                    enqueue(WriteOp(g, rx, CcuProtocol.heartbeatA(java.util.Calendar.getInstance(), seq), null))
                    seq = (seq + 1) and 0xff
                    delay(HEARTBEAT_B_DELAY_MS)
                    if (!isActive || gatt !== g) break
                    enqueue(WriteOp(g, rx, CcuProtocol.heartbeatB(seq), null))
                    seq = (seq + 1) and 0xff
                    delay(HEARTBEAT_PERIOD_MS - HEARTBEAT_B_DELAY_MS)
                } else {
                    delay(HEARTBEAT_PERIOD_MS)
                }
            }
        }
    }

    private fun enqueue(op: GattOp) {
        ops.trySend(op)
    }

    private fun properties(mask: Int): String {
        val names = mutableListOf<String>()
        if (mask and BluetoothGattCharacteristic.PROPERTY_READ != 0) names += "read"
        if (mask and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names += "write"
        if (mask and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names += "write-no-response"
        if (mask and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names += "notify"
        if (mask and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names += "indicate"
        if (mask and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) names += "broadcast"
        if (mask and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) names += "signed-write"
        return names.joinToString(",")
    }

    // ------------------------------------------------------------------ pairing

    private fun bondLabel(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "pairing"
        BluetoothDevice.BOND_NONE -> "unbonded"
        else -> "state $state"
    }

    @SuppressLint("MissingPermission")
    private fun requestBond(device: BluetoothDevice) {
        pendingBondDevice = device
        registerBondReceiver()
        Graph.setLive { it.copy(bond = bondLabel(device.bondState)) }
        if (device.bondState != BluetoothDevice.BOND_NONE) return

        Graph.setLive {
            it.copy(status = "Pairing with the CCU — approve the prompt (PIN is usually 000000)")
        }
        val started = runCatching { device.createBond() }.getOrDefault(false)
        if (!started) {
            Graph.setLive {
                it.copy(status = "Could not start pairing. Pair the CCU in Android Bluetooth settings first.")
            }
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                ) ?: return
                if (pendingBondDevice?.address != device.address) return

                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                Graph.setLive { it.copy(bond = bondLabel(state)) }
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        Graph.setLive { it.copy(status = "Paired — connecting…") }
                        unregisterBondReceiver()
                        val target = wanted ?: return
                        scope.launch { open(target.first) }
                    }

                    BluetoothDevice.BOND_NONE -> {
                        Graph.setLive {
                            it.copy(
                                connected = false,
                                status = "Pairing failed. Forget the CCU in Bluetooth settings, then try again (PIN 000000).",
                            )
                        }
                        wanted = null
                        reconnectJob?.cancel()
                        unregisterBondReceiver()
                    }
                }
            }
        }
        bondReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterBondReceiver() {
        val receiver = bondReceiver ?: return
        runCatching { context.unregisterReceiver(receiver) }
        bondReceiver = null
        pendingBondDevice = null
    }

    // ------------------------------------------------------------- permissions

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

// ------------------------------------------------------------------ operations

/**
 * One GATT operation. [run] returns false when the operation could not be issued or
 * got no answer, which makes the client tear the connection down and rebuild it.
 */
private abstract class GattOp(val label: String) {
    abstract suspend fun run(): Boolean
    abstract fun abort()

    open fun onDiscover(g: BluetoothGatt, status: Int) {}
    open fun onRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {}
    open fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {}
    open fun onWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {}
    open fun onMtu(g: BluetoothGatt, mtu: Int, status: Int) {}
}

/** Ask for a bigger MTU; [result] gets the negotiated value, or null if the request failed. */
private class MtuOp(
    private val gatt: BluetoothGatt,
    private val mtu: Int,
    private val result: CompletableDeferred<Int?>,
) : GattOp("requestMtu $mtu") {
    private val done = CompletableDeferred<Unit>()

    @SuppressLint("MissingPermission")
    override suspend fun run(): Boolean {
        if (!gatt.requestMtu(mtu)) {
            // Not fatal here: the stack may already be at a larger MTU. The caller logs it.
            result.complete(null)
            return true
        }
        done.await()
        return true
    }

    override fun abort() {
        result.complete(null)
        done.complete(Unit)
    }

    override fun onMtu(g: BluetoothGatt, mtu: Int, status: Int) {
        result.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else null)
        done.complete(Unit)
    }
}

/**
 * One write-without-response to the CCU's RX characteristic. Android still reports
 * completion through onCharacteristicWrite, which is what keeps writes from piling up.
 * [label] is logged; pass null for high-rate frames. Bytes are never logged — the
 * handshake carries the CCU serial and token.
 */
private class WriteOp(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
    private val value: ByteArray,
    private val logLabel: String?,
) : GattOp("write ${logLabel ?: "heartbeat"}") {
    private val done = CompletableDeferred<Boolean>()

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override suspend fun run(): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = type
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            report("write rejected by the stack")
            return false
        }
        val ok = done.await()
        if (!ok) report("write failed")
        else logLabel?.let { report("sent (${value.size} B)") }
        return true
    }

    override fun abort() {
        done.complete(false)
    }

    override fun onWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        done.complete(status == BluetoothGatt.GATT_SUCCESS)
    }

    private fun report(outcome: String) {
        val line = "→ ${logLabel ?: "heartbeat"}: $outcome"
        Graph.setLive { it.copy(linkLog = (listOf(line) + it.linkLog).take(60)) }
    }
}

private class DiscoverOp(val gatt: BluetoothGatt) : GattOp("discoverServices") {
    private val done = CompletableDeferred<Boolean>()

    @SuppressLint("MissingPermission")
    override suspend fun run(): Boolean {
        if (!gatt.discoverServices()) return false
        return done.await()
    }

    override fun abort() {
        done.complete(false)
    }

    override fun onDiscover(g: BluetoothGatt, status: Int) {
        done.complete(status == BluetoothGatt.GATT_SUCCESS)
    }
}

private class SubscribeOp(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
) : GattOp("subscribe ${shortUuid(characteristic.uuid)}") {

    private val done = CompletableDeferred<Boolean>()

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override suspend fun run(): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            report("local notify enable failed")
            return false
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            report("no CCCD on this characteristic")
            return true
        }

        val value =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
        descriptor.value = value
        if (!gatt.writeDescriptor(descriptor)) {
            report("descriptor write rejected by the stack")
            return false
        }
        return done.await()
    }

    override fun abort() {
        done.complete(false)
    }

    /**
     * The CCCD write is the whole subscription: if it comes back non-zero, no
     * notifications will ever arrive however "subscribed" the UI looks.
     */
    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        val ok = status == BluetoothGatt.GATT_SUCCESS
        report(if (ok) "subscribed ok" else "subscribe failed (status $status)")
        done.complete(ok)
    }

    private fun report(outcome: String) {
        val line = "notify ${shortUuid(characteristic.uuid)}: $outcome"
        Graph.setLive {
            it.copy(
                linkLog = (listOf(line) + it.linkLog).take(30),
                status = if (outcome.startsWith("subscribed ok")) {
                    "Subscribed to ${shortUuid(characteristic.uuid)} — waiting for frames"
                } else {
                    "Subscription problem on ${shortUuid(characteristic.uuid)}: $outcome"
                },
            )
        }
    }
}
