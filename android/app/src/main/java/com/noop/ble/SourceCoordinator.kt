package com.noop.ble

import android.content.Context
import com.noop.data.DeviceRegistry
import com.noop.data.PairedDeviceRow
import com.noop.data.StreamBatch
import com.noop.data.WhoopRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs exactly ONE device's live BLE at a time, driven by [DeviceRegistry]'s active device id.
 *
 * Faithful Kotlin twin of Strand/BLE/SourceCoordinator.swift.
 *
 * WHOOP-FIRST, ZERO REGRESSION
 * ----------------------------
 * This coordinator is a deliberate NO-OP whenever the active device is the WHOOP (id "my-whoop", any
 * `brand == "WHOOP"` row, OR an unknown id). That is the default state and EVERY state where no generic
 * strap is paired: WHOOP is active, the coordinator does nothing, and the existing WHOOP flow
 * ([WhoopBleClient.connect] via [AppViewModel.connect]) runs exactly as it does today. It only ever
 * *acts* when the active device is a NON-WHOOP generic HR strap:
 *
 *   • switching TO a generic strap → [stopWhoop] (WHOOP's existing disconnect), then start the isolated
 *     [StandardHrSource] for that strap's deviceId.
 *   • switching BACK to WHOOP     → stop the [StandardHrSource], then [startWhoop] (WHOOP's existing scan
 *     entry) — but only if we had actually been on a strap, so a plain launch with WHOOP active does NOT
 *     re-trigger a redundant WHOOP scan.
 *
 * It never imports or references [WhoopBleClient] internals: the WHOOP start/stop are injected closures
 * from the composition root, so the two BLE flows stay fully decoupled (mirrors [StandardHrSource]'s
 * isolation). Live HR from a strap is pushed through [liveSink]; the app wires that to the SAME live
 * state the UI observes (e.g. `ble::publishExternalLiveHr`).
 *
 * On Android the registry exposes the active id as a one-shot suspend read (not a published flow like
 * Swift's `@Published activeDeviceId`), so the app calls [onActiveDeviceChanged] after any registry
 * mutation that can change the active device (the Devices screen's setActive — the next task), and
 * [start] reconciles once against the current active id at launch (a no-op for a single-WHOOP install).
 */
class SourceCoordinator(
    private val context: Context,
    private val registry: DeviceRegistry,
    private val repository: WhoopRepository,
    /** Push a strap's live HR/R-R into whatever the UI observes (e.g. `ble::publishExternalLiveHr`). */
    private val liveSink: (hr: Int, rr: List<Int>) -> Unit,
    /** Re-trigger WHOOP's EXISTING scan/connect entry point (e.g. `AppViewModel.connect`). */
    private val startWhoop: () -> Unit,
    /** Pause WHOOP via its EXISTING teardown (e.g. `AppViewModel.disconnect` → `ble.disconnect`). */
    private val stopWhoop: () -> Unit,
    /** Background scope for the suspend registry reads + persist. SupervisorJob keeps one failure from
     *  cancelling the others; IO keeps DB work off the main thread. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** The lazily-created generic-strap source. null until the first switch to a strap; reused after. */
    private var standardSource: StandardHrSource? = null
    /** The deviceId [standardSource] is currently running for (so we don't churn on the same id). */
    private var activeStrapId: String? = null
    /** True once we've transitioned onto a generic strap. While false (the default / WHOOP-active state)
     *  switching to WHOOP is a pure no-op — we never issue a redundant WHOOP (re)scan. */
    private var onStrap = false
    /** The last active id we reconciled, so a repeated [onActiveDeviceChanged] for the same id is a no-op
     *  (mirrors Swift's `removeDuplicates()` on the published active id). */
    private var lastSeenId: String? = null

    /**
     * Reconcile once against the CURRENT active id (launch). For a single-WHOOP install this resolves to
     * the WHOOP and is a pure no-op, so the existing WHOOP startup is untouched.
     */
    fun start() {
        scope.launch {
            val id = registry.activeDeviceId() ?: WhoopBleClient.DEFAULT_DEVICE_ID
            reconcile(id)
        }
    }

    /**
     * Called by the app after a registry mutation that can change the active device (Devices-screen
     * setActive). Resolves the device for [id] and reconciles which live source runs. Idempotent: a
     * repeated call for the same id is dropped (the `removeDuplicates()` equivalent).
     */
    fun onActiveDeviceChanged(id: String) {
        scope.launch { reconcile(id) }
    }

    private suspend fun reconcile(id: String) {
        if (id == lastSeenId) return
        lastSeenId = id
        if (isWhoop(id, registry.all())) switchToWhoop() else switchToStrap(id)
    }

    /**
     * Active device is the WHOOP. If we'd been on a strap, tear that source down and resume WHOOP;
     * otherwise (the dormant default) this is a pure no-op so the existing WHOOP startup is untouched.
     */
    private fun switchToWhoop() {
        if (!onStrap) return   // already WHOOP-mode (incl. first launch) → no churn
        standardSource?.stop()
        activeStrapId = null
        onStrap = false
        startWhoop()
    }

    /**
     * Active device is a generic strap. Pause WHOOP (once, on the WHOOP→strap edge) and run the isolated
     * [StandardHrSource] for this strap's deviceId. Re-running for the SAME id is a no-op.
     */
    private fun switchToStrap(id: String) {
        if (activeStrapId == id) return   // already streaming this strap → no churn
        if (!onStrap) stopWhoop()         // leaving WHOOP for the first strap → pause its BLE
        standardSource?.stop()            // strap→strap: stop the previous source first

        val source = StandardHrSource(
            context = context,
            deviceId = id,
            liveSink = liveSink,
            persist = { batch: StreamBatch, deviceId: String ->
                scope.launch { runCatching { repository.insert(batch, deviceId) } }
            },
        )
        source.scan()   // discover + connect the chosen strap on its own scanner/GATT
        standardSource = source
        activeStrapId = id
        onStrap = true
    }

    companion object {
        /**
         * Classify a device id as WHOOP vs a generic strap. WHOOP if the id is the canonical "my-whoop",
         * the registry row's `brand` is "WHOOP" (case-insensitive), OR the id is unknown — unknown ids
         * default to WHOOP so the coordinator stays dormant rather than ever stealing the WHOOP's BLE.
         * Mirrors Swift `SourceCoordinator.isWhoop`.
         */
        fun isWhoop(id: String, devices: List<PairedDeviceRow>): Boolean {
            if (id == WhoopBleClient.DEFAULT_DEVICE_ID) return true
            val device = devices.firstOrNull { it.id == id } ?: return true
            return isWhoop(device)
        }

        /** A device is WHOOP when its id is "my-whoop" or its brand is "WHOOP" (the seeded row's brand). */
        fun isWhoop(device: PairedDeviceRow): Boolean =
            device.id == WhoopBleClient.DEFAULT_DEVICE_ID ||
                device.brand.equals("WHOOP", ignoreCase = true)
    }
}
