package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ble.SourceCoordinator
import com.noop.ble.StandardHrSource
import com.noop.data.DeviceStatus
import com.noop.data.Metric
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import kotlinx.coroutines.launch

// MARK: - Devices
//
// Pair and manage the bands NOOP reads from. WHOOP-FIRST: the WHOOP is the primary, fully-supported
// device; generic heart-rate straps (Polar / Wahoo / Coospo / Garmin HRM …) are an early, in-development
// addition. The screen is a thin UI over [com.noop.data.DeviceRegistry] (the Phase 1A/1B data layer):
// every mutation goes through an [AppViewModel] registry op, and the [SourceCoordinator] (wired in
// NoopApplication) reacts to the active-device change — so this view never touches the BLE client or the
// WHOOP path directly. Faithful Kotlin twin of Strand/Screens/DevicesView.swift.
//
// The registry's reads are one-shot suspend (not a Flow), so the screen keeps the list in a remembered
// state and reloads it after every mutation via [reload].

@Composable
fun DevicesScreen(viewModel: AppViewModel) {
    val scope = rememberCoroutineScope()
    val live by viewModel.live.collectAsStateWithLifecycle()

    // The current device list, reloaded after each registry op. Null while the first read is in flight.
    var devices by remember { mutableStateOf<List<PairedDeviceRow>?>(null) }
    fun reload() {
        scope.launch { devices = viewModel.pairedDevices() }
    }
    LaunchedEffect(Unit) { devices = viewModel.pairedDevices() }

    // Sheets / dialogs (mirror the Swift @State targets).
    var showAddWizard by remember { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<PairedDeviceRow?>(null) }
    var renameTarget by remember { mutableStateOf<PairedDeviceRow?>(null) }
    var removeTarget by remember { mutableStateOf<PairedDeviceRow?>(null) }
    var deleteDataTarget by remember { mutableStateOf<PairedDeviceRow?>(null) }
    // After removing the ACTIVE device with other devices still paired, prompt to pick a new active one.
    var pickNewActive by remember { mutableStateOf(false) }

    val all = devices.orEmpty()
    val activeDevices = all.filter { it.status != DeviceStatus.archived.name }
    val removedDevices = all.filter { it.status == DeviceStatus.archived.name }
    val currentActiveName =
        all.firstOrNull { it.status == DeviceStatus.active.name }?.let { displayName(it) }
            ?: "Your current strap"

    ScreenScaffold(
        title = "Devices",
        subtitle = "Pair and manage the bands NOOP reads from.",
    ) {
        if (devices == null) {
            // The registry resolves a beat after launch. Show a calm pending note in that brief window.
            DataPendingNote(
                title = "Getting your devices ready",
                body = "NOOP is opening your on-device data. Your paired bands will appear here in a moment.",
            )
            return@ScreenScaffold
        }

        activeDevices.forEach { device ->
            DeviceCard(
                device = device,
                isActive = device.status == DeviceStatus.active.name,
                isLiveConnected = device.status == DeviceStatus.active.name && live.connected,
                onMakeActive = { switchTarget = device },
                onRename = { renameTarget = device },
                onRemove = { removeTarget = device },
            )
        }

        // Prominent "+ Add a device" button.
        AddDeviceButton(onClick = { showAddWizard = true })

        if (removedDevices.isNotEmpty()) {
            Overline("Removed", modifier = Modifier.padding(top = 4.dp))
            removedDevices.forEach { device ->
                DeviceCard(
                    device = device,
                    isActive = false,
                    isLiveConnected = false,
                    dimmed = true,
                    onMakeActive = { switchTarget = device },
                    onRename = { renameTarget = device },
                    onRemove = null,
                    onReAdd = { switchTarget = device },
                    onDeleteData = { deleteDataTarget = device },
                )
            }
        }

        WhoopFirstFooter()
    }

    // --- Add a heart-rate strap ---
    if (showAddWizard) {
        AddDeviceSheet(
            viewModel = viewModel,
            onClose = { showAddWizard = false; reload() },
        )
    }

    // --- Switch confirm ---
    switchTarget?.let { device ->
        ConfirmDialog(
            title = "Make this your active strap?",
            message = "Make ${displayName(device)} your active strap? From now on it provides your live data. " +
                "$currentActiveName's history stays exactly as it is — only new days come from ${displayName(device)}.",
            confirmLabel = "Make active",
            onConfirm = {
                scope.launch { viewModel.setActiveDevice(device.id); reload() }
                switchTarget = null
            },
            onDismiss = { switchTarget = null },
        )
    }

    // --- Rename ---
    renameTarget?.let { device ->
        RenameDialog(
            device = device,
            onSave = { name ->
                scope.launch { viewModel.renamePairedDevice(device.id, name); reload() }
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    // --- Remove confirm ---
    removeTarget?.let { device ->
        ConfirmDialog(
            title = "Remove this device?",
            message = "Remove ${displayName(device)}? NOOP will stop connecting to it. Its recorded data is " +
                "kept and you can re-add it any time.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                val wasActive = device.status == DeviceStatus.active.name
                scope.launch {
                    viewModel.archivePairedDevice(device.id)
                    devices = viewModel.pairedDevices()
                    // If the removed device was active and other paired devices remain, prompt to pick a
                    // new active one (the registry's reload demotes the active row to paired).
                    if (wasActive && devices.orEmpty().any { it.status != DeviceStatus.archived.name }) {
                        pickNewActive = true
                    }
                }
                removeTarget = null
            },
            onDismiss = { removeTarget = null },
        )
    }

    // --- Second, strongly-worded delete-data confirm (from the Removed card's secondary control) ---
    deleteDataTarget?.let { device ->
        ConfirmDialog(
            title = "Delete all of this device's data?",
            message = "This permanently deletes all data recorded from ${displayName(device)}. This can't be undone.",
            confirmLabel = "Delete data",
            destructive = true,
            onConfirm = {
                scope.launch { viewModel.deletePairedDeviceData(device.id); reload() }
                deleteDataTarget = null
            },
            onDismiss = { deleteDataTarget = null },
        )
    }

    // --- After removing the active device, offer to pick a new active one (if any remain) ---
    if (pickNewActive) {
        PickActiveDialog(
            devices = activeDevices,
            onPick = { device ->
                scope.launch { viewModel.setActiveDevice(device.id); reload() }
                pickNewActive = false
            },
            onLeaveNone = { pickNewActive = false },
        )
    }
}

// MARK: - Device card

/** One paired device as a [NoopCard]: name, brand·model, a capabilities line, a state pill, last-seen,
 *  and a per-device actions menu. The active device is tinted (gold) and carries an "Active" pill. */
@Composable
private fun DeviceCard(
    device: PairedDeviceRow,
    isActive: Boolean,
    isLiveConnected: Boolean,
    dimmed: Boolean = false,
    onMakeActive: () -> Unit,
    onRename: () -> Unit,
    onRemove: (() -> Unit)?,
    onReAdd: (() -> Unit)? = null,
    onDeleteData: (() -> Unit)? = null,
) {
    NoopCard(
        modifier = Modifier.alpha(if (dimmed) 0.6f else 1f),
        padding = 18.dp,
        tint = if (isActive) Palette.accent else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = deviceIcon(device),
                    contentDescription = null,
                    tint = if (isActive) Palette.accent else Palette.textSecondary,
                    modifier = Modifier.size(28.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(displayName(device), style = NoopType.headline, color = Palette.textPrimary)
                    Text("${device.brand} · ${device.model}", style = NoopType.subhead, color = Palette.textSecondary)
                }
                StatePill(device, isActive, isLiveConnected)
            }

            val capabilities = capabilityLine(device)
            if (capabilities.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(capabilities, style = NoopType.caption, color = Palette.textSecondary)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lastSeenLine(device, isLiveConnected),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                DeviceActionsMenu(
                    device = device,
                    isActive = isActive,
                    onMakeActive = onMakeActive,
                    onRename = onRename,
                    onRemove = onRemove,
                    onReAdd = onReAdd,
                    onDeleteData = onDeleteData,
                )
            }
        }
    }
}

@Composable
private fun StatePill(device: PairedDeviceRow, isActive: Boolean, isLiveConnected: Boolean) {
    when {
        device.status == DeviceStatus.archived.name ->
            StatePill("Removed", tone = StrandTone.Neutral, showsDot = false)
        isActive ->
            StatePill(
                if (isLiveConnected) "Active · Live" else "Active",
                tone = StrandTone.Positive,
                pulsing = isLiveConnected,
            )
        else -> StatePill("Paired", tone = StrandTone.Neutral)
    }
}

@Composable
private fun DeviceActionsMenu(
    device: PairedDeviceRow,
    isActive: Boolean,
    onMakeActive: () -> Unit,
    onRename: () -> Unit,
    onRemove: (() -> Unit)?,
    onReAdd: (() -> Unit)?,
    onDeleteData: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = "Device actions for ${displayName(device)}" },
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (device.status == DeviceStatus.archived.name) {
                if (onReAdd != null) {
                    MenuItem("Make active", Icons.Filled.Bolt) { open = false; onReAdd() }
                }
                MenuItem("Rename", Icons.Filled.Edit) { open = false; onRename() }
                if (onDeleteData != null) {
                    HorizontalDivider(color = Palette.hairline)
                    MenuItem("Delete this device's data…", Icons.Filled.Delete, destructive = true) {
                        open = false; onDeleteData()
                    }
                }
            } else {
                if (!isActive) {
                    MenuItem("Make active", Icons.Filled.Bolt) { open = false; onMakeActive() }
                }
                MenuItem("Rename", Icons.Filled.Edit) { open = false; onRename() }
                if (onRemove != null) {
                    HorizontalDivider(color = Palette.hairline)
                    MenuItem("Remove", Icons.Filled.RemoveCircleOutline, destructive = true) {
                        open = false; onRemove()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) Palette.statusCritical else Palette.textPrimary
    DropdownMenuItem(
        text = { Text(label, style = NoopType.body, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun AddDeviceButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.accent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Add a device" }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.goldDeepText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add a device", style = NoopType.headline, color = Palette.goldDeepText)
    }
}

@Composable
private fun WhoopFirstFooter() {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "WHOOP is NOOP's primary, fully-supported band. Other heart-rate straps are an early, " +
                "in-development addition — they stream live heart rate and HRV, but not WHOOP's deeper " +
                "sleep and recovery data.",
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
    }
}

// MARK: - Add device sheet (scan + name + add)
//
// "Add a heart-rate strap" — runs its OWN discovery-only [StandardHrSource] (it never connects here;
// the SourceCoordinator owns connection once the strap becomes active). Lists nearby straps live;
// tapping one reveals a name field and an Add button. On Add it registers a `paired` device, then offers
// to make it active. Renders its searching/empty state cleanly with no hardware present. Faithful twin of
// the macOS AddDeviceSheet.

@Composable
private fun AddDeviceSheet(viewModel: AppViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // A discovery-only source for this sheet — never persists, never connects; we only read its
    // `discovered` / `scanning` StateFlows while scanning. Created once per sheet.
    val scanner = remember { viewModel.makeStrapScanner() }
    val discovered by scanner.discovered.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<StandardHrSource.DiscoveredStrap?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    // After adding, ask whether to make the new strap active.
    var justAdded by remember { mutableStateOf<PairedDeviceRow?>(null) }

    // Scan while the sheet is shown; stop on dismiss.
    LaunchedEffect(Unit) { scanner.scan() }

    fun add(strap: StandardHrSource.DiscoveredStrap) {
        scanner.stopScan()
        val now = System.currentTimeMillis() / 1000
        val name = nameDraft.trim()
        val device = PairedDeviceRow(
            id = strap.address,
            brand = brandGuess(strap.name),
            model = name.ifEmpty { strap.name },
            nickname = null,
            sourceKind = SourceKind.liveBLE.name,
            capabilities = "hr,hrv",
            status = DeviceStatus.paired.name,
            addedAt = now,
            lastSeenAt = now,
        )
        scope.launch { viewModel.addPairedDevice(device) }
        justAdded = device
    }

    AlertDialog(
        onDismissRequest = { scanner.stopScan(); onClose() },
        containerColor = Palette.surfaceOverlay,
        title = {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Add a heart-rate strap", style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        "Polar, Wahoo, Coospo, Garmin HRM and other standard BLE straps.",
                        style = NoopType.caption,
                        color = Palette.textTertiary,
                    )
                }
                IconButton(onClick = { scanner.stopScan(); onClose() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.textTertiary, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            val chosen = selected
            if (chosen == null) {
                ScanStep(
                    scanning = scanning,
                    discovered = discovered,
                    onRescan = { scanner.scan() },
                    onSelect = { strap -> nameDraft = strap.name; selected = strap },
                )
            } else {
                NamingStep(
                    strap = chosen,
                    name = nameDraft,
                    onName = { nameDraft = it },
                    onBack = { selected = null },
                    onAdd = { add(chosen) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )

    // After adding, offer to make the new strap active.
    justAdded?.let { device ->
        ConfirmDialog(
            title = "Make this your active strap?",
            message = "Make ${displayName(device)} your active strap now? It will provide your live heart rate. " +
                "You can change this any time.",
            confirmLabel = "Make active",
            cancelLabel = "Not now",
            onConfirm = {
                scope.launch { viewModel.setActiveDevice(device.id) }
                justAdded = null
                onClose()
            },
            onDismiss = { justAdded = null; onClose() },
        )
    }
}

// Step 1 — live scan list / empty state.
@Composable
private fun ScanStep(
    scanning: Boolean,
    discovered: List<StandardHrSource.DiscoveredStrap>,
    onRescan: () -> Unit,
    onSelect: (StandardHrSource.DiscoveredStrap) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(
                if (scanning) "Searching…" else "Idle",
                tone = if (scanning) StrandTone.Accent else StrandTone.Neutral,
                pulsing = scanning,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRescan) {
                Text("Rescan", style = NoopType.subhead, color = Palette.accent)
            }
        }

        if (discovered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .frostedCardSurface(cornerRadius = 14.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(22.dp))
                Text("Searching for nearby straps…", style = NoopType.body, color = Palette.textPrimary)
                Text(
                    "Make sure your strap is awake and not connected to another app.",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            discovered.sortedByDescending { it.rssi }.forEach { strap ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .frostedCardSurface(cornerRadius = 12.dp)
                        .clickable { onSelect(strap) }
                        .semantics { contentDescription = "${strap.name}, signal ${SignalBars.level(strap.rssi)} of 4" }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SignalBars(strap.rssi)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(strap.name, style = NoopType.body, color = Palette.textPrimary)
                        Text(brandGuess(strap.name), style = NoopType.caption, color = Palette.textTertiary)
                    }
                }
            }
        }
    }
}

// Step 2 — name the chosen strap + Add.
@Composable
private fun NamingStep(
    strap: StandardHrSource.DiscoveredStrap,
    name: String,
    onName: (String) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .frostedCardSurface(cornerRadius = 12.dp)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalBars(strap.rssi)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(strap.name, style = NoopType.headline, color = Palette.textPrimary)
                Text(brandGuess(strap.name), style = NoopType.caption, color = Palette.textTertiary)
            }
        }

        Overline("Name")
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            placeholder = { Text("Strap name", style = NoopType.body, color = Palette.textTertiary) },
            colors = devicesFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Strap name" },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("Back", style = NoopType.body, color = Palette.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAdd, enabled = name.trim().isNotEmpty()) {
                Text("Add", style = NoopType.body, color = if (name.trim().isNotEmpty()) Palette.accent else Palette.textTertiary)
            }
        }
    }
}

// MARK: - Shared dialogs

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = { Text(title, style = NoopType.title2, color = Palette.textPrimary) },
        text = { Text(message, style = NoopType.subhead, color = Palette.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    style = NoopType.body,
                    color = if (destructive) Palette.statusCritical else Palette.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelLabel, style = NoopType.body, color = Palette.textSecondary)
            }
        },
    )
}

@Composable
private fun RenameDialog(
    device: PairedDeviceRow,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(device.nickname ?: displayName(device)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = { Text("Rename device", style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Give ${device.brand} ${device.model} a name you'll recognise.",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("Name", style = NoopType.body, color = Palette.textTertiary) },
                    colors = devicesFieldColors(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Device name" },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text("Save", style = NoopType.body, color = Palette.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = NoopType.body, color = Palette.textSecondary)
            }
        },
    )
}

@Composable
private fun PickActiveDialog(
    devices: List<PairedDeviceRow>,
    onPick: (PairedDeviceRow) -> Unit,
    onLeaveNone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLeaveNone,
        containerColor = Palette.surfaceOverlay,
        title = { Text("Pick a new active strap", style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "You removed your active strap. Choose which paired band provides your live data, or " +
                        "leave none active and pair one later.",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                devices.forEach { device ->
                    Text(
                        displayName(device),
                        style = NoopType.body,
                        color = Palette.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(device) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onLeaveNone) {
                Text("Leave none active", style = NoopType.body, color = Palette.textSecondary)
            }
        },
    )
}

// MARK: - Signal indicator
//
// A four-bar signal indicator derived from RSSI. RSSI is negative dBm: closer to 0 is stronger. Buckets
// are coarse on purpose — a precise dBm readout would be noise to the user. Mirrors the Swift SignalBars.

@Composable
private fun SignalBars(rssi: Int) {
    val level = SignalBars.level(rssi)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(18.dp),
    ) {
        for (i in 0 until 4) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < level) Palette.accent else Palette.hairlineStrong),
            )
        }
    }
}

private object SignalBars {
    /** RSSI (negative dBm) → 0..4 signal level, coarse buckets. Matches the Swift SignalBars.level. */
    fun level(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
}

// MARK: - Field colours

@Composable
private fun devicesFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    focusedContainerColor = Palette.surfaceInset,
    unfocusedContainerColor = Palette.surfaceInset,
)

// MARK: - Presentation helpers (mirror the Swift PairedDevice computed props)

/**
 * Collapsed display name (mirrors Swift `PairedDevice.displayName`): the nickname if present, else the
 * model if it already contains the brand (so the seeded WHOOP/WHOOP reads "WHOOP", not "WHOOP WHOOP"),
 * else "brand model".
 */
internal fun displayName(device: PairedDeviceRow): String {
    device.nickname?.takeIf { it.isNotBlank() }?.let { return it }
    return if (device.model.contains(device.brand, ignoreCase = true)) device.model
    else "${device.brand} ${device.model}"
}

/** SF-Symbol-equivalent icon: WHOOP keeps the band glyph; generic straps read as a heart-rate strap. */
private fun deviceIcon(device: PairedDeviceRow): ImageVector =
    if (SourceCoordinator.isWhoop(device)) Icons.Filled.Watch else Icons.Filled.FavoriteBorder

/** "Heart rate · HRV · …" from the device's capabilities, in a stable, readable order. Mirrors Swift. */
private fun capabilityLine(device: PairedDeviceRow): String {
    val labels = linkedMapOf(
        Metric.hr to "Heart rate",
        Metric.hrv to "HRV",
        Metric.spo2 to "Blood oxygen",
        Metric.skinTemp to "Skin temp",
        Metric.steps to "Steps",
        Metric.sleep to "Sleep",
        Metric.strainLoad to "Strain",
    )
    val have = device.capabilities.split(",").map { it.trim() }.toSet()
    return labels.entries.filter { it.key.name in have }.joinToString(" · ") { it.value }
}

private fun lastSeenLine(device: PairedDeviceRow, isLiveConnected: Boolean): String = when {
    device.status == DeviceStatus.archived.name -> "Removed · data kept"
    isLiveConnected -> "Connected now"
    else -> "Last seen ${relativeAgo(device.lastSeenAt)}"
}

/** Best-effort brand from the advertised name. Falls back to a neutral label. Mirrors Swift brandGuess. */
private fun brandGuess(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.contains("polar") -> "Polar"
        lower.contains("wahoo") || lower.contains("tickr") -> "Wahoo"
        lower.contains("coospo") -> "Coospo"
        lower.contains("garmin") || lower.contains("hrm") -> "Garmin"
        lower.contains("scosche") || lower.contains("rhythm") -> "Scosche"
        lower.contains("magene") -> "Magene"
        else -> "Heart-rate strap"
    }
}
