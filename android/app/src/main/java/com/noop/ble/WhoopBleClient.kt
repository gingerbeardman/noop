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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.noop.data.HrRow
import com.noop.data.RrRow
import com.noop.data.StreamBatch
import com.noop.data.StreamPersistence
import com.noop.data.WhoopRepository
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import com.noop.protocol.Framing
import com.noop.protocol.Reassembler
import com.noop.protocol.Streams
import com.noop.protocol.extractStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Immutable snapshot of the live connection + biometric state.
 *
 * Direct port of Strand's `LiveState` (Strand/BLE/LiveState.swift), reduced to the fields the
 * Android UI consumes. Where the Swift app used an `@Published` ObservableObject with closures
 * (`onDoubleTap`, `onWristChange`), the Android port surfaces the most-recent physical input through
 * [lastEvent] and exposes wrist-wear through [worn]; the ViewModel reacts to changes in this flow.
 *
 *  - [connected]   GATT connection is up (CBPeripheral didConnect)
 *  - [bonded]      one confirmed write to the command char has been ACKed (the WHOOP "bond")
 *  - [heartRate]   most-recent plausible BPM (30..220) from the standard 0x2A37 profile OR the
 *                  custom REALTIME_DATA frame
 *  - [rr]          most-recent R-R intervals (ms); the standard profile is the reliable source
 *  - [batteryPct]  battery percent (0x2A19 = whole %, or BATTERY_LEVEL event = u16/10)
 *  - [worn]        wrist-wear from WRIST_ON/WRIST_OFF events; defaults true (Swift parity) so
 *                  wear-gated features work before the first event lands
 *  - [lastEvent]   the most-recent strap EVENT string ("WRIST_ON(9)", "DOUBLE_TAP(14)", …)
 */
data class LiveState(
    val connected: Boolean = false,
    val bonded: Boolean = false,
    val heartRate: Int? = null,
    val rr: List<Int> = emptyList(),
    val batteryPct: Double? = null,
    val worn: Boolean = false,
    val lastEvent: String? = null,
    /** True while actively scanning for the strap (so the UI can show "Searching…"). */
    val scanning: Boolean = false,
    /** Human-readable reason for the current state (why it can't connect, what to try). */
    val statusNote: String? = null,
    /** A WHOOP 5/MG strap was found. It connects and its battery reads, but live data needs an
     *  MG secure handshake that isn't supported yet — so the UI explains that honestly instead of
     *  showing the generic "charge it and put it on" checklist. */
    val whoop5Detected: Boolean = false,
)

/**
 * Android CoreBluetooth-equivalent engine for the WHOOP 4.0.
 *
 * Direct port of [Strand/BLE/BLEManager.swift] (the CoreBluetooth engine) folded together with
 * [Strand/BLE/FrameRouter.swift] (the pure decode→state router). Hardware-verified protocol
 * behaviour from the Swift app is preserved exactly; only the framework calls change
 * (CoreBluetooth → android.bluetooth).
 *
 * Lifecycle, mirroring the verified Swift flow:
 *   1. [connect]  — scan by the WHOOP4 custom-service UUID (BLEManager.connect → scanForPeripherals).
 *   2. onScanResult — stop scan, `connectGatt` (centralManager didDiscover → central.connect).
 *   3. onConnectionStateChange(CONNECTED) — `discoverServices` (didConnect → discoverServices).
 *   4. onServicesDiscovered — for the custom service: capture the cmd-write char and fire THE BOND
 *      (one confirmed write of GET_BATTERY_LEVEL); subscribe to the three custom notify chars + the
 *      standard HR and battery chars (didDiscoverCharacteristicsFor).
 *   5. onCharacteristicWrite — the confirmed-write ACK == bonding succeeded; run the connect
 *      handshake EXACTLY ONCE (didWriteValueFor + connectHandshakeDone guard).
 *   6. onCharacteristicChanged — route inbound bytes (didUpdateValueFor):
 *        • HR char (0x2A37)      → parse standard HR + R-R
 *        • battery char (0x2A19) → first byte = percent
 *        • custom notify chars   → Reassembler.feed → Framing.parseFrame → update LiveState
 *
 * Android 12+ (API 31) runtime-permission notes:
 *   - The caller MUST hold BLUETOOTH_SCAN and BLUETOOTH_CONNECT at runtime before [connect].
 *   - On API <= 30, BLUETOOTH + BLUETOOTH_ADMIN are install-time, but a coarse/fine LOCATION
 *     runtime permission is required for BLE *scanning* to return results.
 *   - Declaring `android:usesPermissionFlags="neverForLocation"` on BLUETOOTH_SCAN lets you skip
 *     the location grant on API 31+ (we filter by service UUID, never deriving location).
 *   - Every android.bluetooth call below is annotated @SuppressLint("MissingPermission"); the
 *     ViewModel/Activity owns the permission request and must not call into here until granted.
 */
class WhoopBleClient(
    private val context: Context,
    /**
     * Local store the decoded live + historical streams are persisted into. Defaults to the
     * process-wide Room-backed repository so the existing `WhoopBleClient(context)` call site keeps
     * working unchanged. The Swift `BLEManager` wires a `WhoopStore`-backed `Collector`/`Backfiller`
     * the same way (BLEManager.bootstrapStore).
     */
    private val repository: WhoopRepository = WhoopRepository.from(context),
    /**
     * Stable device id; all rows are stamped with this. "my-whoop" matches the Swift default and
     * the rest of the Android app (AppViewModel reads "my-whoop").
     */
    private val deviceId: String = "my-whoop",
    /** Durable trim-cursor store for the offload safe-trim watermark (see [Backfiller]). */
    private val cursorStore: TrimCursorStore = PrefsTrimCursorStore(context),
) {

    companion object {
        private const val TAG = "WhoopBleClient"

        // MARK: GATT UUIDs (authoritative, from BLEManager.swift / FINDINGS.md).
        //
        // WHOOP 4.0 custom service + its four characteristics. The shared contract also lists a
        // WHOOP5 service UUID; we scan for both so a v5 strap is discoverable, but the verified
        // characteristic/bond flow is the v4 layout (the only hardware-verified path).
        val WHOOP4_SERVICE: UUID = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")
        private val CMD_WRITE_CHAR: UUID = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6")   // CMD → strap
        private val CMD_NOTIFY_CHAR: UUID = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6")  // responses
        private val EVENT_NOTIFY_CHAR: UUID = UUID.fromString("61080004-8d6d-82b8-614a-1c8cb0f8dcc6") // events
        private val DATA_NOTIFY_CHAR: UUID = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6")  // data (fragmented)

        val WHOOP5_SERVICE: UUID = UUID.fromString("fd4b0001-cce1-4033-93ce-002d5875f58a")

        // Standard BLE profiles. HR + R-R works UNBONDED; battery is a plain %.
        private val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_CHAR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor — written to enable notifications
        // (CoreBluetooth does this implicitly via setNotifyValue; Android requires the explicit write).
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Auto-rescan delay after an unintentional disconnect (BLEManager: "rescanning in 3s"). */
        private const val RECONNECT_DELAY_MS = 3_000L
        /** Give up a scan after this long with no strap found, and tell the user why. */
        private const val SCAN_TIMEOUT_MS = 20_000L

        // MARK: Live-persistence cadence (port of Swift CollectorPolicy.default).
        /** Flush the live buffer after this many frames OR [FLUSH_MAX_INTERVAL_MS], whichever first. */
        private const val FLUSH_MAX_FRAMES = 64
        private const val FLUSH_MAX_INTERVAL_MS = 30_000L

        // MARK: Historical-offload timers (ported from BLEManager.swift, same constants).
        /** Periodic re-offload of the type-47 store while connected+bonded. 900s = 15 min (matches WHOOP). */
        private const val BACKFILL_INTERVAL_MS = 900_000L
        /**
         * Idle watchdog: if no genuine offload frame arrives for this long mid-session, end the
         * session (the durable strap_trim cursor means the next session resumes where we left off).
         * Generous (60s, not 20s) because the type-43 raw flood eats BLE airtime between chunks.
         */
        private const val BACKFILL_IDLE_TIMEOUT_MS = 60_000L
        /** Deferral before the first connect-time offload, so SET_CLOCK/GET_DATA_RANGE round-trip first. */
        private const val INITIAL_BACKFILL_DELAY_MS = 1_500L

        /**
         * True when a frame is part of the historical offload (HISTORICAL_DATA=47, EVENT=48,
         * METADATA=49, CONSOLE_LOGS=50) rather than the live stream (REALTIME_DATA=40,
         * REALTIME_RAW_DATA=43). The live type-43 raw flood streams continuously and unprompted on
         * this firmware, so the backfill idle-watchdog must NOT be re-armed by it — only by genuine
         * offload progress. Port of Swift `BLEManager.isOffloadFrame`.
         */
        fun isOffloadFrame(frame: ByteArray): Boolean {
            if (frame.size <= 4) return false
            return when (frame[4].toInt() and 0xFF) {
                47, 48, 49, 50 -> true // HISTORICAL_DATA / EVENT / METADATA / CONSOLE_LOGS
                else -> false // 40 REALTIME_DATA, 43 REALTIME_RAW_DATA (live flood)
            }
        }

        /**
         * Newest plausible-unix marker in a GET_DATA_RANGE response = the strap's newest stored
         * record. Mirrors Swift `BLEManager.dataRangeNewestUnix`: scan u32 LE words in the response
         * body (starts at frame[7], after [type,seq,cmd]), keep those in the unix range, return max.
         */
        fun dataRangeNewestUnix(frame: ByteArray): Long? {
            if (frame.size <= 7) return null
            var newest: Long? = null
            var i = 7
            while (i + 4 <= frame.size) {
                val w = (frame[i].toLong() and 0xFFL) or
                    ((frame[i + 1].toLong() and 0xFFL) shl 8) or
                    ((frame[i + 2].toLong() and 0xFFL) shl 16) or
                    ((frame[i + 3].toLong() and 0xFFL) shl 24)
                if (w in 1_700_000_000L..1_900_000_000L) newest = maxOf(newest ?: 0L, w)
                i += 4
            }
            return newest
        }
    }

    // MARK: Published state — the single source of truth the UI observes.
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    // MARK: Android Bluetooth handles.
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null

    /** Frame reassembler for the fragmented custom notify chars (port of Reassembler). */
    private val reassembler = Reassembler()

    /** Rolling command sequence byte; `seq = seq &+ 1` before each send (Swift `seq: UInt8`). */
    private var seq: Int = 0

    /** True once the confirmed-write bond ACK lands (Swift `didBond`). */
    private var didBond = false

    /** Runs the connect handshake EXACTLY ONCE per connection (Swift `connectHandshakeDone`). */
    private var connectHandshakeDone = false

    /** True when the user asked to disconnect; suppresses the auto-rescan (Swift `intentionalDisconnect`). */
    private var intentionalDisconnect = false
    /// The strap family the user chose to pair, remembered so an auto-reconnect after a
    /// dropout re-scans for the same model instead of falling back to WHOOP 4.0.
    private var selectedModel = WhoopModel.WHOOP4

    /** True while a scan is active, so we never start a second scan (Android scanner is stateful). */
    private var scanning = false

    /** All BLE work hops onto the main looper, matching CBCentralManager(queue: .main). */
    private val handler = Handler(Looper.getMainLooper())

    /** Fired if a scan finds nothing in [SCAN_TIMEOUT_MS]; stops scanning and explains why. */
    private val scanTimeoutRunnable = Runnable {
        if (scanning && !_state.value.connected) {
            stopScan()
            log("No WHOOP strap found within ${SCAN_TIMEOUT_MS / 1000}s")
            _state.value = _state.value.copy(
                scanning = false,
                statusNote = "No strap found. Check it's charged and on your wrist, and that the " +
                    "official WHOOP app isn't connected to it (a strap will only pair with one app " +
                    "at a time). Then tap Connect again.",
            )
        }
    }

    // ====================================================================================
    // MARK: Persistence + historical offload (NEW — ports BLEManager.swift Collector/Backfiller)
    // ====================================================================================

    /**
     * Background scope for all DB writes (insert is a suspend Room call). SupervisorJob so one
     * failed insert never cancels the others; IO dispatcher keeps DB work off the main looper.
     * Cancelled in [shutdown].
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The offload state machine. Ack callback writes HISTORICAL_DATA_RESULT (with response). */
    private val backfiller = Backfiller(
        repository = repository,
        deviceId = deviceId,
        cursorStore = cursorStore,
        ackTrim = { trim, endData -> ackHistoricalChunk(trim, endData) },
    )

    /** True while a historical offload is in progress (offload frames route to the Backfiller). */
    @Volatile
    private var backfilling = false

    /** Guards the once-per-connect initial offload kick (Swift `backfillStarted`). */
    private var backfillStarted = false

    /** Newest unix the strap reports having (from GET_DATA_RANGE); refreshed each connect. */
    @Volatile
    private var strapNewestTs: Long? = null

    // --- Live-persistence buffer (port of Swift Collector: custom realtime/event/battery frames) ---

    /**
     * Live-persistence buffers, guarded by [collectorLock] (a plain monitor, NOT a coroutine Mutex,
     * because frames are appended synchronously from the single-threaded GATT callback thread and
     * only the suspend DB insert hops to [ioScope]). [batchStartedAtMs] tracks the flush interval.
     */
    private val collectorLock = Any()

    /** Buffered complete custom-channel frames awaiting a batched decode+insert. */
    private val liveBuffer = ArrayList<ByteArray>()
    private var batchStartedAtMs = System.currentTimeMillis()

    /** Standard 0x2A37 HR/RR buffer — the reliable, always-on stream (port of Collector.stdHR/stdRR). */
    private val stdHr = ArrayList<HrRow>()
    private val stdRr = ArrayList<RrRow>()

    // --- Offload frame drain (preserves START/data/END arrival order; port of routeBackfillFrame) ---

    /** Ordered queue of offload frames awaiting the serial Backfiller drain. */
    private val backfillFrameQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var backfillDraining = false

    /** Periodic re-offload + idle-watchdog tokens (handler-posted; cancelled on disconnect). */
    private val periodicBackfillRunnable = Runnable { triggerPeriodicBackfill() }
    private val backfillTimeoutRunnable = Runnable { onBackfillTimeout() }

    /**
     * Pending outbound writes. Android's GATT stack allows ONE in-flight write at a time:
     * a second writeCharacteristic before onCharacteristicWrite silently fails. The Swift app
     * leaned on CoreBluetooth's internal queue; here we serialise writes ourselves. Each queued
     * item is the fully-framed byte array + its write type (with/without response).
     */
    private data class PendingWrite(val frame: ByteArray, val withResponse: Boolean)
    private val writeQueue = ConcurrentLinkedQueue<PendingWrite>()
    private var writeInFlight = false

    /** Descriptor-write queue: enabling notifications is also a one-at-a-time GATT operation. */
    private val cccdQueue = ConcurrentLinkedQueue<BluetoothGattCharacteristic>()
    private var cccdInFlight = false

    // ====================================================================================
    // MARK: Public API  (port of BLEManager.connect / disconnect / send + buzz helper)
    // ====================================================================================

    /**
     * Begin scanning for the WHOOP custom service, then connect to the first match.
     * Port of `BLEManager.connect()` → `central.scanForPeripherals(withServices:[customService])`.
     */
    @SuppressLint("MissingPermission")
    fun connect(model: WhoopModel = WhoopModel.WHOOP4) {
        intentionalDisconnect = false
        selectedModel = model
        val adp = adapter
        // No Bluetooth LE hardware at all (most often an emulator / virtual device).
        if (adp == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            log("No Bluetooth LE on this device")
            _state.value = _state.value.copy(
                scanning = false,
                statusNote = "This device has no Bluetooth LE. NOOP has to run on a real phone with " +
                    "Bluetooth, near your strap. It can't connect from an emulator or virtual device.")
            return
        }
        if (!adp.isEnabled) {
            log("Bluetooth is off")
            _state.value = _state.value.copy(
                scanning = false, statusNote = "Bluetooth is off. Turn it on, then tap Connect.")
            return
        }
        val sc = scanner
        if (sc == null) {
            log("No BLE scanner available")
            _state.value = _state.value.copy(statusNote = "Bluetooth isn't ready yet. Try again in a moment.")
            return
        }
        if (scanning) {
            log("Scan already in progress — ignoring")
            return
        }
        // Filter to the strap the user picked — a single service, so a WHOOP 4.0
        // scan never lingers on a WHOOP 5/MG wrist (or the reverse). The user
        // chooses the model before this runs.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(model.service)).build(),
        )
        // LOW_LATENCY for a snappy first connect, mirroring the desktop app's eager scan.
        // We do NOT allow duplicates (CBCentralManagerScanOptionAllowDuplicatesKey: false).
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        log("Scanning for ${model.displayName}…")
        scanning = true
        _state.value = _state.value.copy(scanning = true, whoop5Detected = false, statusNote = "Searching for your ${model.displayName}…")
        try {
            sc.startScan(filters, settings, scanCallback)
        } catch (se: SecurityException) {
            // Android 12+: BLUETOOTH_SCAN/CONNECT not granted. This is the #1 reason connect fails.
            scanning = false
            log("Scan blocked (permission): ${se.message}")
            _state.value = _state.value.copy(
                scanning = false,
                statusNote = "NOOP needs the Nearby devices / Bluetooth permission. Allow it in " +
                    "Settings → Apps → NOOP → Permissions, then tap Connect.")
            return
        } catch (t: Throwable) {
            scanning = false
            log("Scan failed to start: ${t.message}")
            _state.value = _state.value.copy(scanning = false, statusNote = "Couldn't start scanning: ${t.message}")
            return
        }
        // Stop and explain if nothing turns up in time.
        handler.removeCallbacks(scanTimeoutRunnable)
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    /**
     * Intentionally tear down the link and stop scanning.
     * Port of `BLEManager.disconnect()` (sets intentionalDisconnect, cancels the connection).
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        intentionalDisconnect = true
        handler.removeCallbacks(scanTimeoutRunnable)
        stopScan()
        _state.value = _state.value.copy(scanning = false, statusNote = null)
        gatt?.disconnect()   // onConnectionStateChange(DISCONNECTED) does the teardown + close.
    }

    /**
     * Send a command to the strap.
     * Port of `BLEManager.send(_:payload:writeType:)` — builds the framed COMMAND packet via
     * [Framing.buildCommand] and writes it to the command characteristic (61080002).
     *
     * Default write type is WITHOUT response (matching the Swift default), so existing call sites
     * (toggleRealtimeHR, getBatteryLevel, runHapticsPattern) are link-cheap. The bond write and any
     * acked command use WITH response.
     */
    fun send(cmd: CommandNumber, payload: ByteArray = byteArrayOf(0), withResponse: Boolean = false) {
        val ch = cmdCharacteristic
        if (gatt == null || ch == null) {
            log("send(${cmd.name}) ignored — not connected")
            return
        }
        seq = (seq + 1) and 0xFF
        val frame = Framing.buildCommand(cmd, payload, seq)
        enqueueWrite(PendingWrite(frame, withResponse))
        log("→ ${cmd.name} payload=${payload.toHex()}")
    }

    /**
     * Fire a preset haptic buzz on the strap.
     * Port of `BLEManager.testAlarmBuzz()` / the contract's `buzz(loops:)`:
     * RUN_HAPTICS_PATTERN(79) with payload `[patternId=2, loops, 0, 0, 0]`.
     * patternId=2 is the graduated alarm buzz the official WHOOP app uses.
     */
    fun buzz(loops: Int = 2) {
        val n = loops.coerceIn(0, 255)
        send(CommandNumber.RUN_HAPTICS_PATTERN, byteArrayOf(2, n.toByte(), 0, 0, 0))
        log("Buzz: patternId=2 loops=$n")
    }

    // ====================================================================================
    // MARK: Scanning
    // ====================================================================================

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            // Adapter may have been turned off underneath us; nothing to clean up.
            log("stopScan threw: ${t.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "unknown"
            log("Discovered $name (rssi ${result.rssi}) — connecting")
            // Found it: cancel the not-found timeout and reflect progress in the UI.
            handler.removeCallbacks(scanTimeoutRunnable)
            _state.value = _state.value.copy(statusNote = "Found $name, connecting…")
            // Port of didDiscover: stop scanning, then connect to this peripheral.
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        // Reset per-connection state (mirrors the Swift flags cleared on connect/disconnect).
        reset()
        // autoConnect = false for a fast, direct connect (CoreBluetooth central.connect default).
        // TRANSPORT_LE pins the connection to BLE on dual-mode devices.
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    // ====================================================================================
    // MARK: GATT callback  (port of CBCentralManagerDelegate + CBPeripheralDelegate)
    // ====================================================================================

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Port of didConnect: mark connected, then discover services.
                    handler.removeCallbacks(scanTimeoutRunnable)
                    _state.value = _state.value.copy(connected = true, scanning = false, statusNote = null)
                    log("Connected — discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Port of didDisconnectPeripheral: tear down, then auto-rescan unless intentional.
                    handleDisconnect(status)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }
            // Port of didDiscoverServices → didDiscoverCharacteristicsFor, collapsed: Android
            // delivers ALL services+characteristics in one callback, so we walk them directly.

            // 1. Custom service: capture the cmd-write char, FIRE THE BOND, queue the notify subs.
            val whoop4 = g.getService(WHOOP4_SERVICE)
            val whoop5 = g.getService(WHOOP5_SERVICE)
            if (whoop4 == null && whoop5 != null) {
                // WHOOP 5.0 / MG exposes a different service + characteristic set we don't drive yet.
                // It still connects (battery reads over the standard profile), but the secure handshake
                // for live data is WHOOP-4-only for now — so we say that plainly rather than leaving the
                // user staring at the generic "charge it and put it on" checklist when nothing is wrong.
                log("WHOOP 5/MG detected — connected, but the live-data handshake is WHOOP 4.0 only for now.")
                _state.value = _state.value.copy(
                    whoop5Detected = true,
                    statusNote = "WHOOP 5/MG connected — battery reads, but live heart rate needs a secure " +
                        "handshake that's still in progress for 5/MG. Nothing's wrong with your strap. " +
                        "WHOOP 4.0 is fully supported today.",
                )
            }
            val custom = whoop4 ?: whoop5
            if (custom != null) {
                cmdCharacteristic = custom.getCharacteristic(CMD_WRITE_CHAR)

                // THE BONDING TRICK (port of didDiscoverCharacteristicsFor → cmdWriteChar branch):
                // one CONFIRMED write of GET_BATTERY_LEVEL triggers just-works bonding. We send it
                // directly (not via the normal queue) so it's unambiguously the first write, exactly
                // as the Swift code writes the bond frame inline before anything else.
                cmdCharacteristic?.let { writeBondFrame(g, it) }

                // Subscribe to the three custom notify characteristics (data is fragmented).
                custom.getCharacteristic(CMD_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
                custom.getCharacteristic(EVENT_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
                custom.getCharacteristic(DATA_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
            } else {
                log("Custom WHOOP service not found on this peripheral")
            }

            // 2. Standard HR profile (works unbonded — the reliable HR + R-R source).
            g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_CHAR)?.let { cccdQueue.add(it) }

            // 3. Standard battery profile (plain %).
            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_CHAR)?.let { cccdQueue.add(it) }

            // Drain the descriptor queue (enables notifications one at a time).
            drainCccdQueue(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // Port of didWriteValueFor: a CONFIRMED-write completion (no error) == bonding succeeded.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Confirmed write failed: status=$status")
            } else if (!didBond) {
                didBond = true
                _state.value = _state.value.copy(bonded = true)
                log("BONDED (confirmed write acknowledged) — custom channels should now flow")
            }

            // Run the connect handshake EXACTLY ONCE per connection. didWriteValueFor / onCharacteristicWrite
            // re-fires on EVERY with-response write (the bond write, etc.); the guard prevents re-blasting
            // the handshake at the strap mid-session — THE iOS "won't serve" root cause from the Swift notes.
            if (!connectHandshakeDone) {
                connectHandshakeDone = true
                runConnectHandshake()
            }

            // This with-response write is done; release the in-flight slot and send the next.
            writeInFlight = false
            drainWriteQueue()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Notify enable failed for ${descriptor.characteristic?.uuid}: status=$status")
            } else {
                log("Subscribed ${descriptor.characteristic?.uuid}")
            }
            // This CCCD write is done; enable the next characteristic's notifications.
            cccdInFlight = false
            drainCccdQueue(g)
        }

        // Android 13+ delivers the value as a parameter; older APIs read it off the characteristic.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onInbound(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33; retained for API 26..32 where the value-bearing overload isn't called")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onInbound(characteristic.uuid, value)
        }
    }

    // ====================================================================================
    // MARK: Inbound routing  (port of didUpdateValueFor + FrameRouter.handle)
    // ====================================================================================

    private fun onInbound(uuid: UUID, bytes: ByteArray) {
        when (uuid) {
            HEART_RATE_CHAR -> parseStandardHr(bytes)               // 0x2A37
            BATTERY_CHAR -> bytes.firstOrNull()?.let {              // 0x2A19 = percent
                setBattery((it.toInt() and 0xFF).toDouble())
            }
            CMD_NOTIFY_CHAR, EVENT_NOTIFY_CHAR, DATA_NOTIFY_CHAR -> {
                // Reassemble (no-op for already-complete frames) then route each complete frame.
                // Port of: for frame in reassembler.feed(bytes) { router.handle(frame:) }.
                for (frame in reassembler.feed(bytes)) {
                    handleFrame(frame)              // UI (always) — port of router.handle(frame:)

                    // Capture the strap's newest stored record from a GET_DATA_RANGE reply
                    // (frame[6] == GET_DATA_RANGE.rawValue), feeding the liveness watchdog.
                    if (frame.size > 6 && (frame[6].toInt() and 0xFF) == CommandNumber.GET_DATA_RANGE.rawValue) {
                        dataRangeNewestUnix(frame)?.let { strapNewestTs = it }
                    }

                    // PERSISTENCE / OFFLOAD ROUTING — port of the didUpdateValueFor tail block.
                    if (backfilling) {
                        // Historical offload: route ONLY genuine offload frames (47/48/49/50) through
                        // the serial drain (preserves chunk order) + re-arm the idle watchdog on them.
                        // The live type-40/43 flood is dropped here (extractHistoricalStreams ignores
                        // it; feeding it only delays each chunk's insert->trim-ack and stalls the strap).
                        if (isOffloadFrame(frame)) {
                            armBackfillTimeout()
                            routeBackfillFrame(frame)
                        }
                    } else {
                        // Live path: buffer the frame for a batched decode+insert (port of Collector.ingest).
                        ingestLiveFrame(frame)
                    }
                }
            }
            else -> { /* ignore */ }
        }
    }

    /**
     * Pure decode→state router for one COMPLETE frame.
     * Direct port of `FrameRouter.handle(frame:)`.
     */
    private fun handleFrame(frame: ByteArray) {
        val parsed = Framing.parseFrame(frame, DeviceFamily.WHOOP4)
        if (!parsed.ok) return
        // Reject frames that failed their checksum — never let bad bytes drive state.
        if (parsed.crcOk == false) return

        when (parsed.typeName) {
            "REALTIME_DATA" -> {
                // Reject 0 / out-of-range spikes; only accept physiologically plausible HR.
                (parsed.parsed["heart_rate"] as? Int)?.let { hr ->
                    if (hr in 30..220) _state.value = _state.value.copy(heartRate = hr)
                }
                // The realtime stream usually reports rr_count=0; only update R-R when this frame
                // actually carries intervals, so we don't wipe R-R sourced from the 0x2A37 profile.
                intArrayValue(parsed.parsed["rr_intervals"])?.let { rr ->
                    if (rr.isNotEmpty()) _state.value = _state.value.copy(rr = rr)
                }
            }

            "COMMAND_RESPONSE" -> {
                doubleValue(parsed.parsed["battery_pct"])?.let { setBattery(it) }
            }

            "EVENT" -> {
                (parsed.parsed["event"] as? String)?.let { ev ->
                    // Event strings are "NAME(rawValue)", e.g. "WRIST_ON(9)" (see Schema.enumName).
                    _state.value = _state.value.copy(lastEvent = ev)

                    // A BLE_BONDED event confirms the link is bonded (belt-and-suspenders; the
                    // confirmed-write ACK also sets this).
                    if (ev.startsWith("BLE_BONDED")) {
                        _state.value = _state.value.copy(bonded = true)
                    }

                    // Physical inputs the strap exposes — LIVE ONLY (this path never sees historical
                    // replay; that goes through the backfill path in the full app).
                    when {
                        ev.startsWith("DOUBLE_TAP") -> {
                            // Surfaced via lastEvent; the ViewModel maps it to the user's chosen action.
                        }
                        ev.startsWith("WRIST_ON") -> {
                            if (!_state.value.worn) _state.value = _state.value.copy(worn = true)
                        }
                        ev.startsWith("WRIST_OFF") -> {
                            if (_state.value.worn) _state.value = _state.value.copy(worn = false)
                        }
                    }
                }
            }

            else -> { /* ignore other packet types here (handled by the data layer in the full app) */ }
        }
    }

    /**
     * Parse a standard BLE Heart Rate Measurement (0x2A37).
     * Port of `BLEManager.parseStandardHR` + the StandardHeartRate parser:
     *   byte 0 = flags. bit0 = HR is u16 (else u8). bit4 = R-R intervals present (each u16 LE, 1/1024 s).
     * The standard profile is the RELIABLE source for both HR and R-R.
     */
    private fun parseStandardHr(data: ByteArray) {
        if (data.isEmpty()) return
        val flags = data[0].toInt() and 0xFF
        val hr16 = (flags and 0x01) != 0
        val rrPresent = (flags and 0x10) != 0

        var idx = 1
        val hr: Int
        if (hr16) {
            if (data.size < idx + 2) return
            hr = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
            idx += 2
        } else {
            if (data.size < idx + 1) return
            hr = data[idx].toInt() and 0xFF
            idx += 1
        }

        // Energy-expended field (bit3) precedes R-R if present — skip its 2 bytes.
        if ((flags and 0x08) != 0) idx += 2

        val rr = mutableListOf<Int>()
        if (rrPresent) {
            while (idx + 1 < data.size) {
                val raw = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
                idx += 2
                // Convert 1/1024 s units to milliseconds (matches the WHOOP store's R-R in ms).
                rr.add((raw * 1000) / 1024)
            }
        }

        // R-R: the standard profile is the reliable source — surface whenever present.
        if (rr.isNotEmpty()) _state.value = _state.value.copy(rr = rr)
        // HR: accept only physiologically plausible values; reject 0/garbage (off-wrist).
        if (hr in 30..220) _state.value = _state.value.copy(heartRate = hr)

        // Record it continuously — independent of the realtime stream or which screen is open.
        // Port of BLEManager.parseStandardHR -> collector.ingestStandardHR(hr:rr:at:).
        ingestStandardHr(hr, rr, (System.currentTimeMillis() / 1000L))
    }

    /** Single funnel for battery readings (port of LiveState.setBattery). */
    private fun setBattery(pct: Double) {
        _state.value = _state.value.copy(batteryPct = pct)
    }

    // ====================================================================================
    // MARK: Connect handshake  (port of the didWriteValueFor once-per-connection block)
    // ====================================================================================

    /**
     * WHOOP-faithful connect lifecycle, run EXACTLY ONCE per connection after the bond ACK.
     * Port of the post-bond block in `BLEManager.didWriteValueFor`:
     *   hello → set RTC → stop the type-43 realtime flood → refresh data range.
     *
     * The heavy historical-offload / keep-alive / backfill timers from the Swift app are owned by
     * the data layer in the full Android port; this BLE client establishes the link and the live
     * stream. We DO stop the unprompted type-43 raw flood (SEND_R10_R11_REALTIME [0x00]) because it
     * eats BLE airtime, exactly as the Swift app does on connect.
     */
    private fun runConnectHandshake() {
        send(CommandNumber.GET_HELLO_HARVARD)
        send(CommandNumber.SET_CLOCK, setClockPayload())
        send(CommandNumber.GET_CLOCK, byteArrayOf())               // strap expects an EMPTY payload
        send(CommandNumber.SEND_R10_R11_REALTIME, byteArrayOf(0))  // stop the type-43 realtime flood
        send(CommandNumber.GET_DATA_RANGE)                          // refresh stored range
        log("Connect handshake sent (hello/set-clock/get-clock/stop-raw/get-range)")

        // Historical offload: the type-47 store is the PRIMARY metric source. Kick it once on connect
        // (deferred so SET_CLOCK/GET_DATA_RANGE round-trip first, on a settled link — like the paced
        // Mac prototype), then re-offload every BACKFILL_INTERVAL_MS. Port of the didWriteValueFor
        // tail: asyncAfter(1.5s) { requestSync(.connect) } + startBackfillTimer().
        backfillStarted = true
        handler.postDelayed({ requestSync() }, INITIAL_BACKFILL_DELAY_MS)
        startBackfillTimer()
    }

    /**
     * SET_CLOCK(10) payload = the strap's 8-byte form: [seconds u32 LE][subseconds u32 LE].
     * Port of `BLEManager.setClockPayload`. A wrong-length SET_CLOCK is ack'd but NOT latched.
     */
    private fun setClockPayload(): ByteArray {
        val now = (System.currentTimeMillis() / 1000L)
        return byteArrayOf(
            (now and 0xFF).toByte(),
            ((now shr 8) and 0xFF).toByte(),
            ((now shr 16) and 0xFF).toByte(),
            ((now shr 24) and 0xFF).toByte(),
            0, 0, 0, 0,
        )
    }

    // ====================================================================================
    // MARK: Write + descriptor queues (Android GATT one-op-at-a-time serialisation)
    // ====================================================================================

    private fun enqueueWrite(item: PendingWrite) {
        writeQueue.add(item)
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val g = gatt ?: return
        val ch = cmdCharacteristic ?: return
        val item = writeQueue.poll() ?: return
        writeInFlight = true

        val writeType = if (item.withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT      // with response (acked)
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, item.frame, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = item.frame
                g.writeCharacteristic(ch)
            }
        }

        if (!ok) {
            // The stack rejected the write outright (no callback will come) — release the slot and
            // keep draining so one bad write doesn't wedge the queue.
            writeInFlight = false
            log("writeCharacteristic rejected by stack; dropping one frame")
            drainWriteQueue()
            return
        }

        // WITHOUT-response writes get NO onCharacteristicWrite callback, so free the slot promptly
        // on the main looper to let the next frame go (a short hop avoids back-to-back stack stalls).
        if (!item.withResponse) {
            handler.post {
                writeInFlight = false
                drainWriteQueue()
            }
        }
    }

    /**
     * Fire the bonding write directly (bypasses the normal queue so it is unambiguously first),
     * mirroring how the Swift code writes the bond frame inline in didDiscoverCharacteristicsFor.
     */
    @SuppressLint("MissingPermission")
    private fun writeBondFrame(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        seq = (seq + 1) and 0xFF
        val bondFrame = Framing.buildCommand(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0), seq)
        log("Bonding: confirmed write GET_BATTERY_LEVEL to 61080002")
        writeInFlight = true   // hold the slot until onCharacteristicWrite fires (with response).
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bondFrame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bondFrame
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) {
            writeInFlight = false
            log("Bond write rejected by stack")
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainCccdQueue(g: BluetoothGatt) {
        if (cccdInFlight) return
        val ch = cccdQueue.poll() ?: return
        cccdInFlight = true

        // Tell the local stack to surface notifications, then write the CCCD so the remote starts
        // sending them. CoreBluetooth's setNotifyValue(true) does both implicitly.
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD)
        if (cccd == null) {
            log("No CCCD on ${ch.uuid}; skipping")
            cccdInFlight = false
            drainCccdQueue(g)
            return
        }
        val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enableValue) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = enableValue
                g.writeDescriptor(cccd)
            }
        }
        if (!ok) {
            cccdInFlight = false
            log("writeDescriptor rejected for ${ch.uuid}")
            drainCccdQueue(g)
        }
    }

    // ====================================================================================
    // MARK: Live persistence  (port of Collector.ingest / flush / ingestStandardHR / flushStandardHR)
    // ====================================================================================

    /**
     * Buffer one complete custom-channel frame and flush on the cadence threshold. Port of
     * `Collector.ingest`: append, then when the buffer hits [FLUSH_MAX_FRAMES] or
     * [FLUSH_MAX_INTERVAL_MS] since the last flush, drain it. Unlike the Swift Collector this does
     * NOT gate on a clock ref — the live realtime decode uses an identity clock (the strap rarely
     * serves GET_CLOCK on this firmware) and REALTIME_DATA's `timestamp` is mapped through it; the
     * historical store, which is the real metric source, carries its own unix ts and needs no clock.
     */
    private fun ingestLiveFrame(frame: ByteArray) {
        val shouldFlush = synchronized(collectorLock) {
            liveBuffer.add(frame)   // synchronous append preserves GATT-callback arrival order
            liveBuffer.size >= FLUSH_MAX_FRAMES ||
                (System.currentTimeMillis() - batchStartedAtMs) >= FLUSH_MAX_INTERVAL_MS
        }
        if (shouldFlush) ioScope.launch { flushLive() }
    }

    /**
     * Decode the buffered live frames and persist them. Snapshot+clear under the lock BEFORE the
     * suspend insert so concurrent ingests accumulate into the next batch (port of Collector.flush).
     */
    private suspend fun flushLive() {
        val frames = synchronized(collectorLock) {
            if (liveBuffer.isEmpty()) return
            val snapshot = ArrayList(liveBuffer)
            liveBuffer.clear()
            batchStartedAtMs = System.currentTimeMillis()
            snapshot
        }
        // Identity clock ref: REALTIME_DATA timestamps are device-epoch, but with device==wall the
        // offset is a no-op. The dense, authoritative metric stream is the type-47 historical store
        // (which carries real unix ts); this live path captures live REALTIME_DATA/EVENT/battery.
        val now = (System.currentTimeMillis() / 1000L).toInt()
        val parsed = frames.map { Framing.parseFrame(it, DeviceFamily.WHOOP4) }
        val streams: Streams = extractStreams(parsed, deviceClockRef = now, wallClockRef = now)
        val batch = StreamPersistence.toBatch(streams)
        if (!batch.isEmpty) {
            try {
                repository.insert(batch, deviceId)
            } catch (t: Throwable) {
                // Re-buffer at the front so these frames retry on the next cadence (port of Collector).
                synchronized(collectorLock) { liveBuffer.addAll(0, frames) }
            }
        }
    }

    /**
     * Buffer one standard 0x2A37 reading (carries a wall-clock ts directly, no clock ref needed).
     * Auto-flushes ~every 30 readings. Port of `Collector.ingestStandardHR`.
     */
    private fun ingestStandardHr(hr: Int, rr: List<Int>, ts: Long) {
        val shouldFlush = synchronized(collectorLock) {
            if (hr in 30..220) stdHr.add(HrRow(ts, hr))
            for (r in rr) if (r in 250..3000) stdRr.add(RrRow(ts, r))
            stdHr.size + stdRr.size >= 30
        }
        if (shouldFlush) ioScope.launch { flushStandardHr() }
    }

    /** Persist the buffered standard HR/RR. Re-buffers on failure. Port of `Collector.flushStandardHR`. */
    private suspend fun flushStandardHr() {
        val (hr, rr) = synchronized(collectorLock) {
            if (stdHr.isEmpty() && stdRr.isEmpty()) return
            val h = ArrayList(stdHr); val r = ArrayList(stdRr)
            stdHr.clear(); stdRr.clear()
            h to r
        }
        try {
            repository.insert(StreamBatch(hr = hr, rr = rr), deviceId)
        } catch (t: Throwable) {
            synchronized(collectorLock) { stdHr.addAll(0, hr); stdRr.addAll(0, rr) }
        }
    }

    // ====================================================================================
    // MARK: Historical offload  (port of BLEManager backfill helpers + state machine)
    // ====================================================================================

    /**
     * Start a historical-offload session: tell the state machine to begin, flip the routing flag,
     * kick the strap with SEND_HISTORICAL_DATA, and arm the idle watchdog. Port of `beginBackfill`.
     *
     * Payload MUST be [0x00], NOT empty: verified on-device that this strap serves type-47 only with
     * [0x00] (the Mac ground-truth offload uses [0x00] too). Plain offload — the strap streams
     * HISTORY_START -> type-47 records -> HISTORY_END (acked) ... -> HISTORY_COMPLETE.
     */
    private fun beginBackfill() {
        if (!connectHandshakeDone) {
            log("Backfill: deferred — connect handshake not done yet")
            return
        }
        if (backfilling) return
        backfiller.begin()
        backfilling = true
        send(CommandNumber.SEND_HISTORICAL_DATA, byteArrayOf(0), withResponse = true)
        armBackfillTimeout()
        log("Backfill: session started — historical offload requested")
    }

    /**
     * The single gated entry point for every historical-offload kick. Runs only when connected +
     * bonded and NOT already mid-backfill. Port of `requestSync` minus the BackfillPolicy
     * rate-limiter (see FLAG: the policy gate isn't ported here — the only triggers wired are the
     * once-per-connect kick and the 900s periodic timer, which is itself the coarse rate limit).
     */
    private fun requestSync() {
        if (!_state.value.connected || !_state.value.bonded || backfilling) return
        beginBackfill()
    }

    /** Periodic-timer callback: re-runs the type-47 offload (the primary metric sync). */
    private fun triggerPeriodicBackfill() {
        requestSync()
        // Re-arm regardless so the cadence continues for the life of the connection.
        handler.postDelayed(periodicBackfillRunnable, BACKFILL_INTERVAL_MS)
    }

    private fun startBackfillTimer() {
        handler.removeCallbacks(periodicBackfillRunnable)
        handler.postDelayed(periodicBackfillRunnable, BACKFILL_INTERVAL_MS)
    }

    private fun stopBackfillTimer() {
        handler.removeCallbacks(periodicBackfillRunnable)
    }

    /**
     * Feed an offload frame to the Backfiller preserving exact arrival order. Frames are appended
     * synchronously (callback order) and drained sequentially by a single coroutine, so START/data/
     * END chunk assembly is never reordered. Port of `routeBackfillFrame` + the serial drain task.
     */
    private fun routeBackfillFrame(frame: ByteArray) {
        backfillFrameQueue.add(frame)
        if (backfillDraining) return
        backfillDraining = true
        ioScope.launch {
            while (true) {
                val f = backfillFrameQueue.poll() ?: break
                backfiller.ingest(f)
                // If the Backfiller consumed all historical data, exit the session cleanly.
                if (backfilling && !backfiller.isBackfilling) {
                    handler.post { exitBackfilling("HISTORY_COMPLETE") }
                }
            }
            backfillDraining = false
        }
    }

    /**
     * Re-arm the idle watchdog. Called on every offload frame during backfill; if the strap goes
     * silent the timer fires and we exit the session. Port of `armBackfillTimeout`.
     */
    private fun armBackfillTimeout() {
        handler.removeCallbacks(backfillTimeoutRunnable)
        handler.postDelayed(backfillTimeoutRunnable, BACKFILL_IDLE_TIMEOUT_MS)
    }

    private fun onBackfillTimeout() {
        backfiller.timeoutFired()
        exitBackfilling("timeout")
    }

    /** Tear down the backfill session. Port of `exitBackfilling`. Does NOT auto-start live HR. */
    private fun exitBackfilling(reason: String) {
        if (!backfilling) return
        backfilling = false
        handler.removeCallbacks(backfillTimeoutRunnable)
        backfillFrameQueue.clear()
        log("Backfill: session ended — reason=$reason")
    }

    /**
     * Ack one HISTORY_END chunk so the strap may trim it. Confirmed write (with response): the strap
     * forgets the chunk once this lands (link-layer half of safe-trim; decoded already persisted).
     *
     * Ack form (matches the verified Mac offload): HISTORICAL_DATA_RESULT(23) payload =
     * `[0x01] + end_data`, where end_data is the verbatim 8 bytes of the HISTORY_END
     * metadata.data[10:18]. Port of `BLEManager.ackHistoricalChunk`.
     */
    private fun ackHistoricalChunk(trim: Long, endData: ByteArray) {
        val payload = ByteArray(1 + endData.size)
        payload[0] = 0x01
        System.arraycopy(endData, 0, payload, 1, endData.size)
        send(CommandNumber.HISTORICAL_DATA_RESULT, payload, withResponse = true)
        log("Backfill: acked chunk trim=$trim")
    }

    // ====================================================================================
    // MARK: Disconnect / teardown  (port of didDisconnectPeripheral)
    // ====================================================================================

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(status: Int) {
        // Persist anything buffered before tearing down (port of the collector.flush() +
        // flushStandardHR() calls in didDisconnectPeripheral). Runs on the IO scope.
        ioScope.launch { flushLive(); flushStandardHr() }

        // Reset all per-connection state and clear UI flags.
        _state.value = _state.value.copy(connected = false, bonded = false)
        reset()

        gatt?.close()
        gatt = null
        cmdCharacteristic = null

        if (!intentionalDisconnect) {
            log("Disconnected (status=$status); rescanning in 3s")
            handler.postDelayed({
                if (!intentionalDisconnect) connect(selectedModel)
            }, RECONNECT_DELAY_MS)
        } else {
            log("Disconnected (intentional)")
        }
    }

    /** Clear per-connection state. Port of the flag resets in didConnect / didDisconnectPeripheral. */
    private fun reset() {
        didBond = false
        connectHandshakeDone = false
        seq = 0
        writeQueue.clear()
        cccdQueue.clear()
        writeInFlight = false
        cccdInFlight = false

        // Reset offload state so the next connect starts a fresh session (port of the backfill
        // flag resets in didDisconnectPeripheral). Timers are handler-posted, so cancel them here.
        backfillStarted = false
        backfilling = false
        backfillDraining = false
        backfillFrameQueue.clear()
        strapNewestTs = null
        handler.removeCallbacks(backfillTimeoutRunnable)
        stopBackfillTimer()
        // NOTE: the Reassembler is intentionally NOT reset here — the Swift BLEManager reuses a
        // single `let reassembler` instance across reconnects too. Its SOF-resync loop (firstIndex
        // of 0xAA) self-recovers from any partial-frame remnant on the next fragment.
    }

    /**
     * Permanently release this client's background scope. Call from the owner's teardown
     * (e.g. AppViewModel.onCleared) AFTER [disconnect]. Idempotent.
     */
    fun shutdown() {
        ioScope.cancel()
    }

    // ====================================================================================
    // MARK: Helpers
    // ====================================================================================

    /** Coerce a parsed value to an Int list (rr_intervals may arrive as List<Int> or IntArray). */
    @Suppress("UNCHECKED_CAST")
    private fun intArrayValue(v: Any?): List<Int>? = when (v) {
        is List<*> -> v.mapNotNull { (it as? Number)?.toInt() }
        is IntArray -> v.toList()
        else -> null
    }

    /** Coerce a parsed value to a Double (battery_pct may arrive as Double or Int). */
    private fun doubleValue(v: Any?): Double? = (v as? Number)?.toDouble()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun log(s: String) {
        Log.d(TAG, s)
    }
}
