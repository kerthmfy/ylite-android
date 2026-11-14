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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_DISCONNECTED) {
                Graph.setLive { it.copy(connected = false, status = "GATT error $status") }
                closeGatt(g)
                scheduleReconnect()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Graph.setLive {
                        it.copy(
                            connected = true,
                            status = "Connected — discovering services…",
                            deviceAddress = g.device.address,
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
            }.getOrDefault(emptyMap())

            Graph.setLive {
                it.copy(
                    frameCount = it.frameCount + 1,
                    lastFrameHex = value.hex(),
                    decoded = decoded,
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
        var subscribed = 0

        for (service in g.services) {
            dump.appendLine("Service ${service.uuid}")
            for (c in service.characteristics) {
                dump.appendLine("  char ${c.uuid}  [${properties(c.properties)}]")
                for (d in c.descriptors) dump.appendLine("    desc ${d.uuid}")

                if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    enqueue(ReadOp(g, c))
                    readable++
                }
                val streams = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
                if (c.properties and streams != 0) {
                    enqueue(SubscribeOp(g, c))
                    subscribed++
                }
            }
        }

        Graph.setLive {
            it.copy(
                gattDump = dump.toString(),
                status = "Connected — $readable readable, $subscribed subscribed",
            )
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
