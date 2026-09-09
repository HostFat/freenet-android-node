package org.freenet.androidnode

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val nodeViewModel: NodeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) {
                    darkColorScheme()
                } else {
                    lightColorScheme()
                },
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var disclaimerAccepted by remember {
                        mutableStateOf(AlphaDisclaimerAcceptance.isAccepted(this@MainActivity))
                    }
                    if (disclaimerAccepted) {
                        NodeScreen(nodeViewModel)
                    } else {
                        AlphaDisclaimerDialog(
                            onAccept = {
                                AlphaDisclaimerAcceptance.accept(this@MainActivity)
                                disclaimerAccepted = true
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlphaDisclaimerDialog(onAccept: () -> Unit) {
    var riskAccepted by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("Warning: This application runs a full Freenet node.") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "This is an unofficial, community-built app. It is not published, " +
                        "maintained, or endorsed by the Freenet Project.",
                    fontWeight = FontWeight.Bold,
                )
                Text("Freenet is not yet optimized for mobile devices.")
                Text("Running a node may result in:")
                Text(
                    "Significant battery drain\n" +
                        "High CPU usage and device heating\n" +
                        "Large Wi-Fi data usage\n" +
                        "Reduced device performance",
                )
                Text("This software is intended for developers and early adopters only.")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = riskAccepted,
                            role = Role.Checkbox,
                            onValueChange = { riskAccepted = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = riskAccepted,
                        onCheckedChange = null,
                    )
                    Text(
                        "I have read and accept the risks and notices in this disclaimer",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                enabled = riskAccepted,
            ) {
                Text("Accept and continue")
            }
        },
    )
}

@Composable
private fun NodeScreen(nodeViewModel: NodeViewModel) {
    val context = LocalContext.current
    val nodeState by nodeViewModel.state.collectAsState()
    val policyState by nodeViewModel.policies.collectAsState()
    val updateState by UpdateCheckRepository.state.collectAsState()
    val updateInterval by UpdateCheckRepository.interval.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var restrictionSnapshot by remember { mutableStateOf(readRestrictionSnapshot(context)) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showConfigEditor by remember { mutableStateOf(false) }
    var showNoEditor by remember { mutableStateOf(false) }
    var showExternalGuideConfirm by remember { mutableStateOf(false) }
    var changelogLine by remember { mutableStateOf(AppChangelog.pendingLine(context)) }
    var pendingRestart by remember { mutableStateOf<PendingRestart?>(null) }
    var configFingerprint by remember { mutableStateOf(ConfigToml.fingerprint(context)) }
    var awaitingExternalConfigEdit by rememberSaveable { mutableStateOf(false) }
    fun adoptConfigFingerprint(userInitiatedEdit: Boolean) {
        val now = ConfigToml.fingerprint(context)
        val changed = now != configFingerprint
        configFingerprint = now
        if (changed) {
            ConfigToml.syncLimitsFromFile(context)
        }
        val live = NodeRepository.state.value
        if (
            shouldPromptRestartForConfigFile(
                fingerprintChanged = changed,
                userInitiatedEdit = userInitiatedEdit,
                networkLive = networkNodeIsLive(live.state, live.mode),
            )
        ) {
            pendingRestart = PendingRestart.ConfigFile
        }
    }
    val externalConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        adoptConfigFingerprint(userInitiatedEdit = true)
        awaitingExternalConfigEdit = false
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingNotificationAction?.invoke()
        } else {
            nodeViewModel.reportNotificationPermissionRequired()
        }
        pendingNotificationAction = null
    }

    fun withNotificationPermission(action: () -> Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun saveUdpPortSettings(mode: UdpPortMode, port: Int) {
        val before = policyState
        nodeViewModel.setUdpPortSettings(mode, port)
        val after = nodeViewModel.policies.value
        if (
            after.udpPortMode == before.udpPortMode &&
            after.udpPort == before.udpPort
        ) {
            return
        }
        ConfigToml.syncUdpPortToFile(context, after.udpPortMode, after.udpPort)
        configFingerprint = ConfigToml.fingerprint(context)
        if (networkNodeIsLive(nodeState.state, nodeState.mode)) {
            pendingRestart = PendingRestart.UdpPort
        }
    }

    fun applyUdpFallback(mode: UdpPortMode) {
        saveUdpPortSettings(mode, policyState.udpPort)
        if (!networkNodeIsLive(nodeState.state, nodeState.mode)) {
            withNotificationPermission(nodeViewModel::startNetworkNode)
        }
    }

    fun saveConnectionLimits(min: Int, max: Int) {
        val before = policyState
        nodeViewModel.setConnectionLimits(min, max)
        val after = nodeViewModel.policies.value
        if (
            after.minConnections == before.minConnections &&
            after.maxConnections == before.maxConnections
        ) {
            return
        }
        ConfigToml.syncLimitsToFile(context, after.minConnections, after.maxConnections)
        configFingerprint = ConfigToml.fingerprint(context)
        if (networkNodeIsLive(nodeState.state, nodeState.mode)) {
            pendingRestart = PendingRestart.Connections(after.minConnections, after.maxConnections)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                restrictionSnapshot = readRestrictionSnapshot(context)
                if (!awaitingExternalConfigEdit) {
                    adoptConfigFingerprint(userInitiatedEdit = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        UpdateCheckRepository.checkAutomatic(context, nodeState.highestSeenPeerVersion)
    }

    BackHandler(enabled = drawerState.isOpen || showDiagnostics || showConfigEditor) {
        when {
            drawerState.isOpen -> closeDrawer()
            showConfigEditor -> showConfigEditor = false
            else -> showDiagnostics = false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                        )
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_freenet_logo),
                            contentDescription = null,
                            modifier = Modifier.height(32.dp),
                        )
                        Column {
                            Text("Freenet Android Node", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Unofficial · not affiliated with the Freenet Project",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                showDiagnostics = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Dashboard")
                    }
                    if (nodeState.state == "RunningNetwork" || nodeState.state == "RunningLocal") {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(DASHBOARD_URL)),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.open_dashboard_browser))
                        }
                    }
                    HorizontalDivider()
                    NodeControlStrip(
                        state = nodeState,
                        lastUpEpochMs = policyState.lastNetworkUpEpochMs,
                        onStartLocal = {
                            withNotificationPermission(nodeViewModel::startLocalNode)
                        },
                        onStartNetwork = {
                            withNotificationPermission(nodeViewModel::startNetworkNode)
                        },
                        onPause = {
                            nodeViewModel.pauseNode()
                        },
                        onStop = {
                            nodeViewModel.stopNode()
                        },
                        onCopyFingerprint = { fingerprint ->
                            copyToClipboard(context, fingerprint)
                        },
                        onUseSavedUdp = { applyUdpFallback(UdpPortMode.Saved) },
                        onUseRandomUdp = { applyUdpFallback(UdpPortMode.Random) },
                    )
                    HorizontalDivider()
                    PolicyControls(
                        policies = policyState,
                        udpPortInUse = nodeState.udpPortInUse,
                        onPowerPolicy = { policy ->
                            if (policy == NodePowerPolicy.Manual) {
                                nodeViewModel.setPowerPolicy(policy)
                            } else {
                                withNotificationPermission {
                                    nodeViewModel.setPowerPolicy(policy)
                                }
                            }
                        },
                        onNetworkDataPolicy = nodeViewModel::setNetworkDataPolicy,
                        onSaveConnectionLimits = ::saveConnectionLimits,
                        onSaveUdpPortSettings = ::saveUdpPortSettings,
                        onUseSavedUdp = { applyUdpFallback(UdpPortMode.Saved) },
                        onUseRandomUdp = { applyUdpFallback(UdpPortMode.Random) },
                        startOnBoot = policyState.startOnBoot,
                        onStartOnBoot = nodeViewModel::setStartOnBoot,
                        onAutoRestartOnCrash = nodeViewModel::setAutoRestartOnCrash,
                        onNotifyConnected = nodeViewModel::setNotifyConnected,
                        onNotifyStopped = nodeViewModel::setNotifyStopped,
                        onNotifyUdpBusy = nodeViewModel::setNotifyUdpBusy,
                        onNotifyUpdate = nodeViewModel::setNotifyUpdate,
                    )
                    HorizontalDivider()
                    BackgroundLimitsPanel(
                        snapshot = restrictionSnapshot,
                        onOpenSettings = {
                            openRestrictionSettings(context, restrictionSnapshot)
                            closeDrawer()
                        },
                        onOpenGuide = { showExternalGuideConfirm = true },
                    )
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.auto_check_interval),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    CompactChoiceRow(
                        options = UpdateCheckInterval.entries,
                        selected = updateInterval,
                        label = { "${it.hours}h" },
                        onSelect = { UpdateCheckRepository.setInterval(context, it) },
                    )
                    OutlinedButton(
                        enabled = !updateState.checking,
                        onClick = {
                            scope.launch {
                                UpdateCheckRepository.checkManual(
                                    context,
                                    nodeState.highestSeenPeerVersion,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (updateState.checking) {
                                "Checking…"
                            } else {
                                stringResource(R.string.check_for_updates)
                            },
                        )
                    }
                    updateState.message?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (updateState.releaseUrl != null) {
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(updateState.releaseUrl),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.open_github_release))
                        }
                    }
                    updateState.lastError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                showDiagnostics = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("For nerds")
                    }
                    val appVersion = remember {
                        runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull()
                    }
                    val nodeVersion = remember {
                        coreVersionFromBuildInfo(NativeBridge.freenetBuildInfo().getOrNull())
                    }
                    Text(
                        panelVersionLine(appVersion, nodeVersion),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            if (showConfigEditor) {
                ConfigEditorPanel(
                    modifier = Modifier.fillMaxSize(),
                    onClose = { showConfigEditor = false },
                    onSaved = {
                        adoptConfigFingerprint(userInitiatedEdit = true)
                    },
                )
            } else if (showDiagnostics) {
                DiagnosticsPanel(
                    modifier = Modifier.fillMaxSize(),
                    onClose = { showDiagnostics = false },
                    onIdentityRestored = {
                        val live = NodeRepository.state.value
                        if (networkNodeIsLive(live.state, live.mode)) {
                            pendingRestart = PendingRestart.Identity
                        }
                    },
                    onEditConfig = {
                        ConfigToml.ensureExists(context)
                        configFingerprint = ConfigToml.fingerprint(context)
                        showConfigEditor = true
                    },
                    onOpenExternal = {
                        ConfigToml.ensureExists(context)
                        configFingerprint = ConfigToml.fingerprint(context)
                        val intent = ConfigToml.editorIntent(context)
                        if (intent == null) {
                            showNoEditor = true
                        } else {
                            awaitingExternalConfigEdit = true
                            externalConfigLauncher.launch(intent)
                        }
                    },
                )
            } else {
                DashboardPanel(
                    state = nodeState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (drawerState.isClosed && !showDiagnostics && !showConfigEditor) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = 8.dp,
                            top = 8.dp + 48.dp + 12.dp,
                            end = 8.dp,
                            bottom = 8.dp,
                        ),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Text("☰", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }

    changelogLine?.let { line ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            title = { Text(stringResource(R.string.changelog_title)) },
            text = { Text(line) },
            confirmButton = {
                Button(
                    onClick = {
                        AppChangelog.markSeen(context)
                        changelogLine = null
                    },
                ) {
                    Text(stringResource(R.string.dismiss_changelog))
                }
            },
        )
    }

    pendingRestart?.let { restart ->
        AlertDialog(
            onDismissRequest = { pendingRestart = null },
            title = { Text(stringResource(R.string.restart_node_title)) },
            text = {
                Text(
                    when (restart) {
                        is PendingRestart.Connections -> stringResource(
                            R.string.restart_node_message,
                            restart.min,
                            restart.max,
                        )
                        PendingRestart.ConfigFile -> stringResource(R.string.restart_config_message)
                        PendingRestart.UdpPort -> stringResource(R.string.restart_udp_port_message)
                        PendingRestart.Identity -> stringResource(R.string.restart_identity_message)
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestart = null
                        withNotificationPermission(nodeViewModel::restartNetworkNode)
                        closeDrawer()
                    },
                ) {
                    Text(stringResource(R.string.restart_node_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestart = null }) {
                    Text(stringResource(R.string.restart_node_deny))
                }
            },
        )
    }

    if (showNoEditor) {
        AlertDialog(
            onDismissRequest = { showNoEditor = false },
            title = { Text(stringResource(R.string.open_config_external)) },
            text = { Text(stringResource(R.string.config_no_editor)) },
            confirmButton = {
                TextButton(onClick = { showNoEditor = false }) {
                    Text(stringResource(R.string.config_close))
                }
            },
        )
    }

    if (showExternalGuideConfirm) {
        AlertDialog(
            onDismissRequest = { showExternalGuideConfirm = false },
            title = { Text(stringResource(R.string.external_guide_title)) },
            text = { Text(stringResource(R.string.external_guide_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showExternalGuideConfirm = false
                        openDontKillMyAppGuide(context, restrictionSnapshot.vendor)
                        closeDrawer()
                    },
                ) {
                    Text(stringResource(R.string.external_guide_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExternalGuideConfirm = false }) {
                    Text(stringResource(R.string.external_guide_cancel))
                }
            },
        )
    }
}

private sealed class PendingRestart {
    data class Connections(val min: Int, val max: Int) : PendingRestart()
    data object ConfigFile : PendingRestart()
    data object UdpPort : PendingRestart()
    data object Identity : PendingRestart()
}

@Composable
private fun NodeControlStrip(
    state: NodeUiState,
    lastUpEpochMs: Long,
    onStartLocal: () -> Unit,
    onStartNetwork: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onCopyFingerprint: (String) -> Unit,
    onUseSavedUdp: () -> Unit,
    onUseRandomUdp: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val status = networkStatusLabel(
            state.state,
            state.mode,
            state.peers,
            state.serviceActive,
            state.natHint,
        )
        Text(
            if (status == "Connected") {
                "Node: Connected · ${state.peers} peers"
            } else {
                "Node: $status"
            },
        )
        if (status == "Connected") {
            Text(
                stringResource(R.string.peer_count_accuracy_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(R.string.last_up, formatLastUp(System.currentTimeMillis(), lastUpEpochMs)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.serviceActive && (state.bytesSent > 0 || state.bytesReceived > 0)) {
            Text(
                stringResource(
                    R.string.traffic_line,
                    formatTrafficBytes(state.bytesSent),
                    formatTrafficBytes(state.bytesReceived),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.identityFingerprint?.takeIf { it.isNotBlank() }?.let { fingerprint ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    fingerprint,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onCopyFingerprint(fingerprint) }) {
                    Text(stringResource(R.string.copy_fingerprint))
                }
            }
        }
        if (state.mode == "Network" && state.udpPort != null && state.udpPort > 0) {
            Text(
                "UDP port: ${state.udpPort}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.natHint?.let { hint ->
            Text(
                hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.udpPortInUse) {
            Text(
                stringResource(R.string.udp_port_in_use),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onUseSavedUdp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.udp_use_saved))
                }
                OutlinedButton(
                    onClick = onUseRandomUdp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.udp_use_random))
                }
            }
        } else if (state.lastNetworkError != null) {
            Text(
                text = state.lastNetworkError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.serviceActive) {
                OutlinedButton(
                    enabled = NativeBridge.isLoaded,
                    onClick = if (state.state == "Paused") onStartNetwork else onPause,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.state == "Paused") "Resume node" else "Pause node")
                }
                Button(
                    enabled = NativeBridge.isLoaded,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stop node")
                }
            } else {
                Button(
                    enabled = NativeBridge.isLoaded,
                    onClick = onStartNetwork,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start network node")
                }
                OutlinedButton(
                    enabled = NativeBridge.isLoaded,
                    onClick = onStartLocal,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start local node")
                }
            }
        }
        if (showRiverChatInvite(state.state, state.mode)) {
            Text(
                text = stringResource(R.string.river_chat_invite_running),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = linkedUrlText(
                    stringResource(R.string.river_chat_invite_get, RIVER_CHAT_INVITE_URL),
                    RIVER_CHAT_INVITE_URL,
                    MaterialTheme.colorScheme.primary,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun linkedUrlText(full: String, url: String, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        val start = full.indexOf(url)
        if (start < 0) {
            append(full)
            return@buildAnnotatedString
        }
        append(full.substring(0, start))
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            ),
        ) {
            append(url)
        }
        append(full.substring(start + url.length))
    }

@Composable
private fun <T> CompactChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = options.size,
                ),
                label = {
                    Text(
                        label(option),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Composable
private fun PolicyControls(
    policies: NodePolicyState,
    udpPortInUse: Boolean,
    onPowerPolicy: (NodePowerPolicy) -> Unit,
    onNetworkDataPolicy: (NetworkDataPolicy) -> Unit,
    onSaveConnectionLimits: (Int, Int) -> Unit,
    onSaveUdpPortSettings: (UdpPortMode, Int) -> Unit,
    onUseSavedUdp: () -> Unit,
    onUseRandomUdp: () -> Unit,
    startOnBoot: Boolean,
    onStartOnBoot: (Boolean) -> Unit,
    onAutoRestartOnCrash: (Boolean) -> Unit,
    onNotifyConnected: (Boolean) -> Unit,
    onNotifyStopped: (Boolean) -> Unit,
    onNotifyUdpBusy: (Boolean) -> Unit,
    onNotifyUpdate: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var draftMin by remember { mutableStateOf(policies.minConnections) }
    var draftMax by remember { mutableStateOf(policies.maxConnections) }
    var draftUdpMode by remember { mutableStateOf(policies.udpPortMode) }
    var draftUdpPort by remember { mutableStateOf(policies.udpPort) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(policies.minConnections, policies.maxConnections) {
        draftMin = policies.minConnections
        draftMax = policies.maxConnections
    }
    LaunchedEffect(policies.udpPortMode, policies.udpPort) {
        draftUdpMode = policies.udpPortMode
        draftUdpPort = policies.udpPort
    }
    val dirty = connectionLimitsAreDirty(
        draftMin,
        draftMax,
        policies.minConnections,
        policies.maxConnections,
    )
    val udpDirty = udpPortSettingsAreDirty(
        draftUdpMode,
        draftUdpPort,
        policies.udpPortMode,
        policies.udpPort,
    )
    val savedUdpPort = ConfigToml.parseInt(ConfigToml.read(context), ConfigToml.NETWORK_PORT_KEY)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.node_runs_when), style = MaterialTheme.typography.titleMedium)
        CompactChoiceRow(
            options = NodePowerPolicy.entries,
            selected = policies.power,
            label = { it.shortLabel },
            onSelect = onPowerPolicy,
        )
        Text(
            stringResource(R.string.node_runs_when_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = startOnBoot,
                    onValueChange = onStartOnBoot,
                    role = Role.Checkbox,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = startOnBoot, onCheckedChange = null)
            Text(
                stringResource(R.string.start_on_boot),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            stringResource(R.string.start_on_boot_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        SettingsCheckbox(
            checked = policies.autoRestartOnCrash,
            label = stringResource(R.string.auto_restart_on_crash),
            onCheckedChange = onAutoRestartOnCrash,
        )
        Text(
            stringResource(R.string.auto_restart_on_crash_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text(
            stringResource(R.string.event_notifications),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.event_notifications_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        SettingsCheckbox(
            checked = policies.notifyConnected,
            label = stringResource(R.string.notify_connected),
            onCheckedChange = onNotifyConnected,
        )
        SettingsCheckbox(
            checked = policies.notifyStopped,
            label = stringResource(R.string.notify_stopped),
            onCheckedChange = onNotifyStopped,
        )
        SettingsCheckbox(
            checked = policies.notifyUdpBusy,
            label = stringResource(R.string.notify_udp_busy),
            onCheckedChange = onNotifyUdpBusy,
        )
        SettingsCheckbox(
            checked = policies.notifyUpdate,
            label = stringResource(R.string.notify_update),
            onCheckedChange = onNotifyUpdate,
        )
        HorizontalDivider()
        Text(stringResource(R.string.network_data), style = MaterialTheme.typography.titleMedium)
        CompactChoiceRow(
            options = NetworkDataPolicy.entries,
            selected = policies.networkData,
            label = { it.shortLabel },
            onSelect = onNetworkDataPolicy,
        )
        Text(
            stringResource(R.string.network_data_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text(stringResource(R.string.peer_connections), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.connection_limits_resource_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.connection_limits_mobile_default),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.connection_limits_desktop_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.connection_limits_experimental),
            style = MaterialTheme.typography.bodySmall,
        )
        ConnectionLimitField(
            value = draftMin,
            label = stringResource(R.string.min_connections),
            upperBound = draftMax,
            rangeText = stringResource(
                R.string.connection_limits_min_range,
                draftMax,
            ),
            onChange = { next ->
                draftMin = next.coerceAtMost(draftMax)
            },
        )
        ConnectionLimitField(
            value = draftMax,
            label = stringResource(R.string.max_connections),
            upperBound = ConnectionLimits.Ceiling,
            rangeText = stringResource(R.string.connection_limits_range),
            onChange = { next ->
                draftMax = next
                if (draftMin > next) {
                    draftMin = next
                }
            },
        )
        Button(
            onClick = {
                focusManager.clearFocus()
                onSaveConnectionLimits(draftMin, draftMax)
            },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save_connection_limits))
        }
        Text(
            stringResource(R.string.connection_limits_apply_next_start),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text(stringResource(R.string.udp_port), style = MaterialTheme.typography.titleMedium)
        CompactChoiceRow(
            options = UdpPortMode.entries,
            selected = draftUdpMode,
            label = { it.shortLabel },
            onSelect = { draftUdpMode = it },
        )
        Text(
            stringResource(
                when (draftUdpMode) {
                    UdpPortMode.Saved -> R.string.udp_port_saved_hint
                    UdpPortMode.Custom -> R.string.udp_port_custom_hint
                    UdpPortMode.Random -> R.string.udp_port_random_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (draftUdpMode == UdpPortMode.Saved && savedUdpPort != null) {
            Text(
                stringResource(R.string.udp_port_current, savedUdpPort),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (draftUdpMode == UdpPortMode.Custom) {
            ConnectionLimitField(
                value = draftUdpPort,
                label = stringResource(R.string.udp_port),
                upperBound = UdpPorts.Ceiling,
                rangeText = stringResource(R.string.udp_port_range),
                ceiling = UdpPorts.Ceiling,
                onChange = { draftUdpPort = UdpPorts.coerce(it) },
            )
        }
        Button(
            onClick = {
                focusManager.clearFocus()
                onSaveUdpPortSettings(draftUdpMode, draftUdpPort)
            },
            enabled = udpDirty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save_udp_port))
        }
        if (udpPortInUse) {
            Text(
                stringResource(R.string.udp_port_in_use),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onUseSavedUdp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.udp_use_saved))
                }
                OutlinedButton(
                    onClick = onUseRandomUdp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.udp_use_random))
                }
            }
        }
        Text(
            stringResource(R.string.connection_limits_apply_next_start),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsCheckbox(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Checkbox,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ConnectionLimitField(
    value: Int,
    label: String,
    upperBound: Int,
    rangeText: String,
    onChange: (Int) -> Unit,
    ceiling: Int = ConnectionLimits.Ceiling,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val cap = minOf(upperBound, ceiling)
    LaunchedEffect(value) {
        if (!focused) {
            text = value.toString()
        }
    }

    fun commit() {
        val parsed = text.toIntOrNull()
        val next = (parsed ?: value).coerceIn(ConnectionLimits.Floor, cap)
        text = next.toString()
        if (next != value) {
            onChange(next)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { incoming ->
            val digits = incoming.filter { it.isDigit() }
            if (digits.isEmpty()) {
                text = ""
                return@OutlinedTextField
            }
            val parsed = digits.toLongOrNull() ?: return@OutlinedTextField
            if (parsed <= cap) {
                text = digits
                parsed.toInt().takeIf { it != value }?.let(onChange)
            }
        },
        label = { Text(label) },
        supportingText = { Text(rangeText) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commit()
                focusManager.clearFocus()
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                val nowFocused = focusState.isFocused
                if (focused && !nowFocused) {
                    commit()
                }
                focused = nowFocused
            },
    )
}

@Composable
private fun BackgroundLimitsPanel(
    snapshot: RestrictionSnapshot,
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val needsFix = snapshot.look == RestrictionLook.NeedsFix
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = if (needsFix) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            },
        ) {
            Text(
                stringResource(
                    if (needsFix) R.string.battery_limits_on else R.string.battery_limits_off,
                ),
            )
        }
        Text(
            stringResource(R.string.battery_limits_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(oemTipRes(snapshot.vendor)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (snapshot.xiaomiAutostart != XiaomiAutostartHint.NotXiaomi) {
            Text(
                stringResource(
                    when (snapshot.xiaomiAutostart) {
                        XiaomiAutostartHint.Enabled -> R.string.xiaomi_autostart_enabled
                        XiaomiAutostartHint.Disabled -> R.string.xiaomi_autostart_disabled
                        else -> R.string.xiaomi_autostart_unknown
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.xiaomi_autostart_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onOpenGuide,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.oem_guide))
        }
    }
}

private fun oemTipRes(vendor: OemVendor): Int = when (vendor) {
    OemVendor.Xiaomi -> R.string.oem_tip_xiaomi
    OemVendor.Samsung -> R.string.oem_tip_samsung
    OemVendor.Huawei, OemVendor.Honor -> R.string.oem_tip_huawei
    OemVendor.Oppo, OemVendor.Realme -> R.string.oem_tip_oppo
    OemVendor.Vivo -> R.string.oem_tip_vivo
    OemVendor.OnePlus -> R.string.oem_tip_oneplus
    OemVendor.Asus -> R.string.oem_tip_asus
    OemVendor.Generic -> R.string.oem_tip_generic
}

@Composable
private fun DashboardPanel(state: NodeUiState, modifier: Modifier = Modifier) {
    val running = state.state == "RunningLocal" || state.state == "RunningNetwork"
    if (running) {
        CoreDashboardWebView(
            reloadKey = "${state.mode}:${state.completedStartCycles}",
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.state == "Starting" || state.state == "Stopping") {
                    CircularProgressIndicator()
                }
                Text(state.detail)
                Text(
                    "The core dashboard becomes available at 127.0.0.1:7509 while the node runs.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CoreDashboardWebView(reloadKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var loading by remember(reloadKey) { mutableStateOf(true) }
    var error by remember(reloadKey) { mutableStateOf<String?>(null) }
    val webView = remember(reloadKey) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webViewClient = LoopbackDashboardClient(
                onLoading = {
                    loading = true
                    error = null
                },
                onReady = {
                    loading = false
                    error = null
                },
                onError = {
                    loading = false
                    error = it
                },
            )
            loadUrl(DASHBOARD_URL)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        error?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Dashboard is not ready yet")
                Text(message, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        loading = true
                        error = null
                        webView.reload()
                    },
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

private class LoopbackDashboardClient(
    private val onLoading: () -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url?.let(::isAllowedDashboardUri) == true) {
            onLoading()
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url?.let(::isAllowedDashboardUri) == true) {
            onReady()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        if (shouldOpenInExternalBrowser(request.url, request.isForMainFrame, request.hasGesture())) {
            val result = runCatching {
                view?.context?.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    ?: error("WebView context is unavailable")
            }
            if (result.isFailure) {
                onError("No browser is available to open this link")
            }
            return true
        }
        return !isAllowedDashboardUri(request.url)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val scheme = request.url.scheme?.lowercase()
        if (scheme == "http" && !isAllowedDashboardUri(request.url)) {
            return blockedResponse()
        }
        if (scheme == "https" && !isAllowedDashboardSubresource(request.url)) {
            return blockedResponse()
        }
        return null
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            onError(error.description?.toString() ?: "WebView could not load the dashboard")
        }
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked non-loopback request",
        emptyMap(),
        ByteArrayInputStream("Blocked by the Android dashboard allowlist".toByteArray()),
    )
}

@Composable
private fun DiagnosticsPanel(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onIdentityRestored: () -> Unit,
    onEditConfig: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf("Collecting diagnostics…") }
    var logs by remember { mutableStateOf("Collecting logs…") }
    var showLogs by remember { mutableStateOf(false) }
    var identityMessage by remember { mutableStateOf<String?>(null) }
    val exportIdentity = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            identityMessage = IdentityBackup.export(context, uri)
        }
    }
    val importIdentity = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val message = IdentityBackup.importFrom(context, uri)
            identityMessage = message
            if (identityRestoreSucceeded(message)) {
                onIdentityRestored()
            }
        }
    }
    var logsExportMessage by remember { mutableStateOf<String?>(null) }
    val exportLogs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            logsExportMessage = writeTextToUri(context, uri, logs)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = withContext(Dispatchers.Default) { diagnosticSnapshot() }
            logs = withContext(Dispatchers.Default) { formatRecentLogs() }
            delay(2_000)
        }
    }

    BackHandler(enabled = showLogs) {
        showLogs = false
    }

    if (showLogs) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { copyToClipboard(context, logs) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.copy_all_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { exportLogs.launch("freenet-node-logs.txt") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.export_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { showLogs = false }) {
                    Text(stringResource(R.string.config_close))
                }
            }
            logsExportMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            ScrollableMonospaceBox(
                text = logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.config_close))
        }
        Text(
            stringResource(R.string.config_toml_heading),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onEditConfig,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.edit_config_short))
            }
            OutlinedButton(
                onClick = onOpenExternal,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.open_config_editor_short),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            stringResource(R.string.config_open_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.identity_heading),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { exportIdentity.launch("freenet-identity-backup.zip") },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_identity_short))
            }
            OutlinedButton(
                onClick = { importIdentity.launch(arrayOf("application/zip", "*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.restore_identity_short))
            }
        }
        Text(
            stringResource(R.string.identity_backup_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        identityMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = { showLogs = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.recent_logs))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.diagnostics_json), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { copyToClipboard(context, snapshot) }) {
                Text(stringResource(R.string.copy_json))
            }
        }
        ScrollableMonospaceBox(
            text = snapshot,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun ScrollableMonospaceBox(
    text: String,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 10.dp)
                    .verticalScroll(scroll)
                    .padding(8.dp),
            ) {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(8.dp)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                val trackPx = with(density) { maxHeight.toPx() }
                val canScroll = scroll.maxValue > 0
                val visibleFraction = if (!canScroll) {
                    1f
                } else {
                    (scroll.viewportSize.toFloat() /
                        (scroll.viewportSize + scroll.maxValue).toFloat())
                        .coerceIn(0.12f, 1f)
                }
                val thumbPx = (trackPx * visibleFraction).coerceAtLeast(with(density) { 24.dp.toPx() })
                val yPx = if (!canScroll) {
                    0f
                } else {
                    scroll.value.toFloat() / scroll.maxValue.toFloat() * (trackPx - thumbPx)
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .align(Alignment.Center)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(2.dp),
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, yPx.toInt()) }
                        .width(3.dp)
                        .height(with(density) { thumbPx.toDp() })
                        .background(
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun ConfigEditorPanel(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember {
        mutableStateOf(
            run {
                ConfigToml.ensureExists(context)
                ConfigToml.read(context)
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    ConfigToml.write(context, text)
                    onSaved()
                },
            ) {
                Text(stringResource(R.string.config_save))
            }
            OutlinedButton(onClick = onClose) {
                Text(stringResource(R.string.config_close))
            }
        }
        Text(
            stringResource(R.string.config_open_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

private fun formatRecentLogs(): String {
    val raw = NativeBridge.recentLogs(DIAGNOSTIC_LOG_ENTRIES).getOrElse { error ->
        return error.message ?: "Could not read logs"
    }
    return runCatching {
        val entries = JSONObject(raw).getJSONObject("data").getJSONArray("entries")
        if (entries.length() == 0) return@runCatching "No log entries yet."
        buildString {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                append(entry.optString("level"))
                append("  ")
                append(entry.optString("message"))
                append('\n')
            }
        }.trimEnd()
    }.getOrElse { raw }
}

private fun diagnosticSnapshot(): String {
    fun resultOrError(result: Result<String>): Any = result.fold(
        onSuccess = { value ->
            runCatching { JSONObject(value) }.fold(
                onSuccess = { it },
                onFailure = { value },
            )
        },
        onFailure = { error -> JSONObject().put("error", error.message ?: "unknown JNI error") },
    )

    return JSONObject()
        .put("capturedAtEpochMs", System.currentTimeMillis())
        .put("nodeStatus", resultOrError(NativeBridge.nodeStatus()))
        .put("recentLogs", resultOrError(NativeBridge.recentLogs(DIAGNOSTIC_LOG_ENTRIES)))
        .put("androidAdapter", resultOrError(NativeBridge.buildInfo()))
        .put("freenetCore", resultOrError(NativeBridge.freenetBuildInfo()))
        .toString(2)
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Freenet diagnostics", value))
}

private fun writeTextToUri(context: Context, uri: Uri, text: String): String {
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        } ?: return context.getString(R.string.export_logs_failed)
        context.getString(R.string.export_logs_saved)
    }.getOrElse { context.getString(R.string.export_logs_failed) }
}

private fun isAllowedDashboardUri(value: String): Boolean =
    runCatching { isAllowedDashboardUri(Uri.parse(value)) }.getOrDefault(false)

private fun isAllowedDashboardUri(uri: Uri): Boolean =
    uri.scheme.equals("http", ignoreCase = true) &&
        uri.host == DASHBOARD_HOST &&
        uri.port == DASHBOARD_PORT

private fun isAllowedDashboardSubresource(uri: Uri): Boolean =
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host == DASHBOARD_LOGO_HOST &&
        uri.path == DASHBOARD_LOGO_PATH &&
        (uri.port == -1 || uri.port == 443)

internal fun shouldOpenInExternalBrowser(
    uri: Uri,
    isForMainFrame: Boolean,
    hasGesture: Boolean,
): Boolean {
    if (!isForMainFrame || !hasGesture) return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    return !isAllowedDashboardUri(uri) || uri.path?.startsWith(HOSTED_APP_PATH_PREFIX) == true
}

private const val DASHBOARD_HOST = "127.0.0.1"
private const val DASHBOARD_PORT = 7509
private const val DASHBOARD_URL = "http://$DASHBOARD_HOST:$DASHBOARD_PORT/"
private const val DASHBOARD_LOGO_HOST = "freenet.org"
private const val DASHBOARD_LOGO_PATH = "/freenet_logo.svg"
private const val HOSTED_APP_PATH_PREFIX = "/v1/contract/web/"
private const val DIAGNOSTIC_LOG_ENTRIES = 128
