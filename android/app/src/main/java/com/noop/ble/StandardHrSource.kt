package com.noop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.noop.data.HrRow
import com.noop.data.RrRow
import com.noop.data.StreamBatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * An ISOLATED standard-Bluetooth Heart-Rate source for generic HR straps (Polar / Wahoo / Coospo /
 * Garmin HRM / Amazfit Helio broadcast) that expose the standard BLE Heart Rate Service (0x180D)
 * with the Heart Rate Measurement characteristic (0x2A37).
 *
 * Faithful Kotlin twin of Strand/BLE/StandardHRSource.swift.
 *
 * WHOOP-FIRST ISOLATION: this class runs its OWN scan + [BluetoothGatt] and never imports, calls, or
 * shares state with [WhoopBleClient]. The WHOOP path cannot regress because of anything here — the two
 * BLE flows are fully independent. The only shared surfaces are injected closures:
 *   - [liveSink]  pushes the strap's live HR/R-R into whatever the UI observes (the [SourceCoordinator]
 *                 wires it to the WHOOP client's published state, so live HR shows in the same place).
 *   - [persist]   wired by the app to `repository.insert(StreamBatch, deviceId)` for the active strap.
 *
 * The pure HR parse lives in [StandardHeartRate]; the HR→rows mapping (HrRow + RrRow only) is inline
 * here, mirroring the Swift `StandardHRMapping`.
 *
 * Android runtime-permission notes (same contract as [WhoopBleClient]): the caller must hold
 * BLUETOOTH_SCAN + BLUETOOTH_CONNECT before [scan]/[connect]. Every android.bluetooth call is
 * @SuppressLint("MissingPermission") — the caller owns the grant.
 */
@SuppressLint("MissingPermission")
class StandardHrSource(
    context: Context,
    /** Datastore device id every sample is stamped with (the active strap's registry id). */
    private val deviceId: String,
    /** Push live HR (bpm) + R-R (ms) into whatever the UI observes. Called on the main looper. */
    private val liveSink: (hr: Int, rr: List<Int>) -> Unit,
    /** Persist a batch under [deviceId] — wired to `repository.insert`. Called off the main looper is
     *  fine; the implementation hops to its own IO scope (see [SourceCoordinator]). */
    private val persist: (StreamBatch, String) -> Unit,
) {

    /** A generic HR strap seen during a scan (UI affordance). */
    data class DiscoveredStrap(val address: String, val name: String, val rssi: Int)

    private val _discovered = MutableStateFlow<List<DiscoveredStrap>>(emptyList())
    /** Straps discovered during the current scan, keyed by address (newest RSSI wins). */
    val discovered: StateFlow<List<DiscoveredStrap>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    /** True while a scan is running. */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // MARK: - Android Bluetooth handles (OWN scanner + GATT, separate from WHOOP)

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    /** Peripherals seen in the current scan, retained by address so a chosen one survives to connect. */
    private val seen = ConcurrentHashMap<String, BluetoothDevice>()
    /** A device asked to connect before a scan result for it landed (connect-by-address path). */
    private var pendingConnectAddress: String? = null

    /** All BLE work hops onto the main looper, matching the WHOOP client + CBCentralManager(queue:.main). */
    private val handler = Handler(Looper.getMainLooper())

    // MARK: - Sample buffer (flushed in batches off the per-notification hot loop)

    private data class Sample(val hr: Int, val rr: List<Int>, val ts: Long)
    private val bufferLock = Any()
    private val buffer = ArrayList<Sample>()
    private var lastFlushMs = System.currentTimeMillis()
    private val flushCount = 30
    private val flushIntervalMs = 30_000L

    // MARK: - Scanning

    /** Begin scanning for generic HR straps advertising the 0x180D service. */
    fun scan() {
        synchronized(bufferLock) { /* keep buffer across a rescan */ }
        seen.clear()
        _discovered.value = emptyList()
        _scanning.value = true
        val sc = scanner ?: run { _scanning.value = false; return }
        if (adapter?.isEnabled != true) { _scanning.value = false; return }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        sc.startScan(listOf(filter), settings, scanCallback)
    }

    /** Stop an in-progress scan. Idempotent. */
    fun stopScan() {
        _scanning.value = false
        if (adapter?.isEnabled == true) runCatching { scanner?.stopScan(scanCallback) }
    }

    // MARK: - Connecting

    /** Connect to the chosen discovered strap (by address) and start streaming its HR. */
    fun connect(address: String) {
        stopScan()
        val device = seen[address] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) { pendingConnectAddress = address; return }
        connectToDevice(device)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        // Tear down any prior link first so we never run two GATTs for this source.
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    /** Tear down: cancel the connection and stop scanning, persisting anything still buffered. Idempotent. */
    fun stop() {
        stopScan()
        pendingConnectAddress = null
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        flush()
    }

    // MARK: - Buffer / persistence

    private fun enqueue(hr: Int, rr: List<Int>) {
        val shouldFlush = synchronized(bufferLock) {
            buffer.add(Sample(hr, rr, System.currentTimeMillis() / 1000L))
            buffer.size >= flushCount ||
                System.currentTimeMillis() - lastFlushMs >= flushIntervalMs
        }
        if (shouldFlush) flush()
    }

    private fun flush() {
        val snapshot = synchronized(bufferLock) {
            lastFlushMs = System.currentTimeMillis()
            if (buffer.isEmpty()) return
            val s = ArrayList(buffer); buffer.clear(); s
        }
        // Mirror StandardHRMapping: HrRow + RrRow only. HR is range-gated (off-wrist 0/garbage dropped);
        // R-R is gated to physiologically plausible beat-to-beat ms, matching ingestStandardHr.
        val hrRows = ArrayList<HrRow>()
        val rrRows = ArrayList<RrRow>()
        for (s in snapshot) {
            if (s.hr in 30..220) hrRows.add(HrRow(s.ts, s.hr))
            for (r in s.rr) if (r in 250..3000) rrRows.add(RrRow(s.ts, r))
        }
        if (hrRows.isNotEmpty() || rrRows.isNotEmpty()) {
            persist(StreamBatch(hr = hrRows, rr = rrRows), deviceId)
        }
    }

    // MARK: - Scan callback

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            seen[address] = device
            val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
                ?: "Heart Rate Strap"
            val strap = DiscoveredStrap(address = address, name = name, rssi = result.rssi)
            val list = _discovered.value.toMutableList()
            val i = list.indexOfFirst { it.address == address }
            if (i >= 0) list[i] = strap else list.add(strap)
            _discovered.value = list
            // Replay a connect intent that arrived before the device was discovered.
            if (pendingConnectAddress == address) {
                pendingConnectAddress = null
                handler.post { connectToDevice(device) }
            }
        }
    }

    // MARK: - GATT callback

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    flush()
                    if (gatt === g) { runCatching { g.close() }; gatt = null }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = g.getService(HEART_RATE_SERVICE) ?: return
            val ch = svc.getCharacteristic(HEART_RATE_CHAR) ?: return
            g.setCharacteristicNotification(ch, true)
            // Explicit CCCD write (CoreBluetooth's setNotifyValue does this implicitly).
            val cccd = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid != HEART_RATE_CHAR) return
            handleHr(value)
        }

        // Legacy (< API 33) characteristic-changed callback: read the value off the characteristic.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != HEART_RATE_CHAR) return
            handleHr(ch.value ?: return)
        }
    }

    private fun handleHr(data: ByteArray) {
        val parsed = StandardHeartRate.parse(data) ?: return
        // Surface live HR on the main looper (the UI's StateFlow expects main-thread updates).
        handler.post { liveSink(parsed.hr, parsed.rr) }
        enqueue(parsed.hr, parsed.rr)
    }

    companion object {
        /** Standard BLE Heart Rate service + measurement characteristic + the CCCD. */
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_CHAR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
