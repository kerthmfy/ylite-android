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
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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
 * Read-only GATT client.
 *
 * Android allows one GATT operation at a time per device, so every operation goes
 * through [ops] and is executed by a single worker coroutine. If an operation gets
 * no answer within [STALL_MS] the connection is torn down and rebuilt — a stalled
 * GATT stack never recovers on its own.
 *
 * Nothing here writes a characteristic value. The only write is the standard
 * notification-enable descriptor (CCCD).
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
        connectAttempts = 0
        stopScan()
        reconnectJob?.cancel()
        Graph.setLive {
            it.copy(
                status = "Connecting to ${name ?: address}…",
                deviceAddress = address,
                deviceName = name,
            )
        }
        scope.launch { open(address) }
    }

    fun disconnect() {
        wanted = null
        reconnectJob?.cancel()
        unregisterBondReceiver()
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
        runCatching { g.close() }
        if (gatt === g) gatt = null
    }

    /** Drop the connection, cancel queued work, and try again if a connection is still wanted. */
    private fun teardown(reason: String) {
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

    private fun scheduleReconnect() {
        val target = wanted ?: return
        connectAttempts++
        if (connectAttempts > 6) {
            wanted = null
            reconnectJob?.cancel()
            Graph.setLive {
                it.copy(connected = false, status = "Gave up reconnecting. Open Manage and tap Connect.")
            }
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val stillWanted = wanted ?: break
                if (gatt != null && !stillWanted.first.isEmpty()) {
                    // A GATT object already exists; wait for its callback rather than opening another.
                    delay(3_000)
                    if (Graph.live.value.connected) break
                }
                Graph.setLive { it.copy(status = "Reconnecting in ${backoff / 1000}s…") }
                delay(backoff)
                if (wanted == null) break
                if (gatt == null) open(stillWanted.first)
                delay(5_000)
                backoff = (backoff * 2).coerceAtMost(INVITE_BACKOFF_CAP_MS)
            }
        }
    }

    // --------------------------------------------------------------- callbacks

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")

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
                    Graph.setLive { it.copy(connected = false, status = "Disconnected") }
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

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            val service = characteristic.service?.uuid?.toString().orEmpty()
            val event = BleFrameEvent(
                time = System.currentTimeMillis(),
                address = g.device.address,
                service = service,
                characteristic = characteristic.uuid.toString(),
                data = value,
            )
            _frames.tryEmit(event)

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
                it.copy(status = "CCU found — $streams notification channel(s). Pairing needed.")
            }
            requestBond(g.device)
            return
        }

        for (service in g.services) {
            for (c in service.characteristics) {
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) enqueue(ReadOp(g, c))
                if (c.properties and streamMask != 0) enqueue(SubscribeOp(g, c))
            }
        }

        Graph.setLive {
            it.copy(status = "Connected — $readable readable, $streams subscribed")
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

private class ReadOp(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
) : GattOp("read ${shortUuid(characteristic.uuid)}") {

    private val done = CompletableDeferred<Unit>()

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override suspend fun run(): Boolean {
        if (!gatt.readCharacteristic(characteristic)) return false
        done.await()
        return true
    }

    override fun abort() {
        done.complete(Unit)
    }

    override fun onRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        done.complete(Unit)
    }
}

private class SubscribeOp(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
) : GattOp("subscribe ${shortUuid(characteristic.uuid)}") {

    private val done = CompletableDeferred<Unit>()

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override suspend fun run(): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false

        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return true
        val value =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
        descriptor.value = value
        if (!gatt.writeDescriptor(descriptor)) return false
        done.await()
        return true
    }

    override fun abort() {
        done.complete(Unit)
    }

    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        done.complete(Unit)
    }
}
