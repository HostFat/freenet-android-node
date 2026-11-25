package org.freenet.androidnode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Android honors at most 200dp of a left-edge exclusion, and only the bottom
// part of anything taller, so the band stays this tall and centered on the tab.
private val MenuEdgeBandHeight = 200.dp
private val MenuEdgeBandMinWidth = 24.dp
private val MenuTabHeight = 120.dp
private val MenuEdgeOpenDrag = 48.dp

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
private fun NodeScreen(nodeViewModel: NodeViewModel) {
    val context = LocalContext.current
    val nodeState by nodeViewModel.state.collectAsState()
    val crashReportPending by CrashReportOffer.pending.collectAsState()
    val policyState by nodeViewModel.policies.collectAsState()
    val updateState by UpdateCheckRepository.state.collectAsState()
    val includePrereleases by UpdateCheckRepository.includePrereleases.collectAsState()
    val updateInstallState by nodeViewModel.updateInstall.collectAsState()
    val crashUploadState by nodeViewModel.crashUpload.collectAsState()
    val resetMessage by nodeViewModel.resetMessage.collectAsState()
    val restoreMessage by nodeViewModel.restoreMessage.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var restrictionSnapshot by remember { mutableStateOf(readRestrictionSnapshot(context)) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showSites by rememberSaveable { mutableStateOf(false) }
    var showConfigEditor by rememberSaveable { mutableStateOf(false) }
    var showNoEditor by rememberSaveable { mutableStateOf(false) }
    var showExternalGuideConfirm by rememberSaveable { mutableStateOf(false) }
    var changelogLine by remember { mutableStateOf(AppChangelog.pendingLine(context)) }
    var pendingRestart by remember { mutableStateOf<PendingRestart?>(null) }
    var pendingReset by rememberSaveable { mutableStateOf(false) }
    var showCrashDialog by rememberSaveable { mutableStateOf(false) }
    var crashComment by rememberSaveable { mutableStateOf("") }
    var configFingerprint by remember { mutableStateOf(ConfigToml.fingerprint(context)) }
    var awaitingExternalConfigEdit by rememberSaveable { mutableStateOf(false) }

    val showingSubPanel = showDiagnostics || showConfigEditor || showSites

    fun adoptConfigFingerprint(userInitiatedEdit: Boolean) {
        val now = ConfigToml.fingerprint(context)
        val changed = now != configFingerprint
        configFingerprint = now
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

    BackHandler(enabled = drawerState.isOpen || showingSubPanel) {
        when {
            drawerState.isOpen -> closeDrawer()
            showConfigEditor -> showConfigEditor = false
            showSites -> showSites = false
            else -> showDiagnostics = false
        }
    }

    val menuSeen = remember { mutableStateOf(MenuEdgeHint.hasOpened(context)) }
    var scheduleNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(
        policyState.power,
        policyState.scheduleEveryHours,
        policyState.scheduleOnMinutes,
        policyState.scheduleWindowStartedEpochMs,
        policyState.scheduleWindowEndedEpochMs,
        policyState.scheduleConnectedSinceEpochMs,
        policyState.suspendedByUser,
    ) {
        if (policyState.power != NodePowerPolicy.Schedule) return@LaunchedEffect
        while (true) {
            scheduleNowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val menuPeek = remember { Animatable(0f) }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            MenuEdgeHint.markOpened(context)
            menuSeen.value = true
            menuPeek.snapTo(0f)
        }
    }
    LaunchedEffect(menuSeen.value, showingSubPanel, changelogLine) {
        if (menuSeen.value || showingSubPanel || changelogLine != null) {
            return@LaunchedEffect
        }
        delay(600)
        if (drawerState.isOpen || MenuEdgeHint.hasOpened(context)) return@LaunchedEffect
        menuPeek.animateTo(40f, tween(320))
        delay(450)
        menuPeek.animateTo(0f, tween(280))
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen && !showingSubPanel,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
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
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                stringResource(R.string.unofficial_not_affiliated),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    val blockReason = menuBlockReason(
                        status = nodeState.state,
                        detail = nodeState.detail,
                        lastNetworkError = nodeState.lastNetworkError,
                        udpPortInUse = nodeState.udpPortInUse,
                    )
                    if (blockReason != null) {
                        Text(
                            blockReason,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    val scheduleLine = scheduleMenuLine(scheduleNowMs, policyState)
                    if (
                        scheduleLine != null &&
                        scheduleLine != blockReason &&
                        (scheduleLine.startsWith("The node stays on") ||
                            scheduleLine.startsWith("Waiting for a connection"))
                    ) {
                        Text(
                            scheduleLine,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                showSites = true
                                showDiagnostics = false
                                showConfigEditor = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.freenet_sites))
                    }
                    HorizontalDivider()
                    NodeControlStrip(
                        state = nodeState,
                        lastUpEpochMs = policyState.lastNetworkUpEpochMs,
                        resetMessage = resetMessage,
                        onStartNetwork = {
                            withNotificationPermission(nodeViewModel::startNetworkNode)
                        },
                        onStop = nodeViewModel::stopNode,
                        onRestart = {
                            withNotificationPermission(nodeViewModel::restartNetworkNode)
                        },
                        crashReportPending = crashReportPending,
                        onDismissCrashLog = {
                            CrashReportOffer.clear(context)
                            nodeViewModel.clearCrashUploadState()
                        },
                        onSendCrashLog = {
                            crashComment = presetCrashReportComment(appVersionName(context))
                            nodeViewModel.clearCrashUploadState()
                            showCrashDialog = true
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
                        onScheduleEveryHours = nodeViewModel::setScheduleEveryHours,
                        onScheduleOnMinutes = nodeViewModel::setScheduleOnMinutes,
                        scheduleWaiting = scheduleConnectNowVisible(scheduleNowMs, policyState),
                        onConnectNow = {
                            withNotificationPermission(nodeViewModel::startNetworkNode)
                        },
                        startOnBoot = policyState.startOnBoot,
                        onStartOnBoot = nodeViewModel::setStartOnBoot,
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
                    SettingsCheckbox(
                        checked = policyState.notifyUpdate,
                        label = stringResource(R.string.notify_update),
                        onCheckedChange = nodeViewModel::setNotifyUpdate,
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
                                stringResource(R.string.checking_updates)
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
                    val apkVersion = updateState.apkVersion
                    if (apkVersion != null) {
                        Button(
                            enabled = !updateInstallState.installing,
                            onClick = { nodeViewModel.downloadAndInstallUpdate(apkVersion) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (updateInstallState.installing) {
                                    stringResource(R.string.update_downloading)
                                } else {
                                    stringResource(R.string.update_download_install)
                                },
                            )
                        }
                        if (updateInstallState.installing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            val progress = updateInstallState.progress
                            if (progress != null && progress.total > 0) {
                                val percent = (progress.downloaded * 100 / progress.total)
                                    .coerceIn(0, 100)
                                Text(
                                    "$percent%",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (updateInstallState.installStarted) {
                            Text(
                                stringResource(R.string.update_install_started),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        updateInstallState.error?.let { error ->
                            Text(
                                stringResource(R.string.update_download_failed, error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    val releaseUrl = updateState.releaseUrl?.takeIf { it.isNotBlank() }
                    if (releaseUrl != null) {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)),
                                    )
                                }
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
                                showSites = false
                                showDiagnostics = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.for_nerds_title))
                    }
                    val appVersion = remember {
                        runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull()
                    }
                    val nodeVersion = remember { NodeBackend.coreVersion() }
                    val (appLabel, nodeLabel) = panelVersionLabels(appVersion, nodeVersion)
                    val versionLink = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                    Text(
                        buildAnnotatedString {
                            withLink(
                                LinkAnnotation.Url(
                                    APP_REPO_URL,
                                    TextLinkStyles(style = versionLink),
                                ),
                            ) {
                                append(appLabel)
                            }
                            append(" · ")
                            withLink(
                                LinkAnnotation.Url(
                                    CORE_REPO_URL,
                                    TextLinkStyles(style = versionLink),
                                ),
                            ) {
                                append(nodeLabel)
                            }
                        },
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
            } else if (showSites) {
                SitesPanel(
                    modifier = Modifier.fillMaxSize(),
                    onClose = { showSites = false },
                    nodeConnected = nodeIsConnected(nodeState.state, nodeState.peers),
                    onStartNode = {
                        withNotificationPermission(nodeViewModel::startNetworkNode)
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
                        configFingerprint = ConfigToml.fingerprint(context)
                        showSites = false
                        showConfigEditor = true
                    },
                    onOpenExternal = {
                        if (!ConfigToml.isComplete(ConfigToml.read(context))) {
                            showConfigEditor = true
                        } else {
                            configFingerprint = ConfigToml.fingerprint(context)
                            val intent = runCatching { ConfigToml.editorIntent(context) }.getOrNull()
                            if (intent == null) {
                                showNoEditor = true
                            } else {
                                awaitingExternalConfigEdit = true
                                externalConfigLauncher.launch(intent)
                            }
                        }
                    },
                    policies = policyState,
                    udpPortInUse = nodeState.udpPortInUse,
                    notifyUdpBusy = policyState.notifyUdpBusy,
                    onNotifyUdpBusy = nodeViewModel::setNotifyUdpBusy,
                    minConnections = policyState.minConnections,
                    maxConnections = policyState.maxConnections,
                    onSaveConnectionLimits = ::saveConnectionLimits,
                    notifyConnected = policyState.notifyConnected,
                    notifyStopped = policyState.notifyStopped,
                    onNotifyConnected = nodeViewModel::setNotifyConnected,
                    onNotifyStopped = nodeViewModel::setNotifyStopped,
                    includePrereleases = includePrereleases,
                    onIncludePrereleases = {
                        UpdateCheckRepository.setIncludePrereleases(context, it)
                    },
                    onSaveUdpPortSettings = ::saveUdpPortSettings,
                    onUseSavedUdp = { applyUdpFallback(UdpPortMode.Saved) },
                    onUseRandomUdp = { applyUdpFallback(UdpPortMode.Random) },
                    onReset = { pendingReset = true },
                    canRestoreBackup = !nodeState.serviceActive,
                    autoRestartOnCrash = policyState.autoRestartOnCrash,
                    onAutoRestartOnCrash = nodeViewModel::setAutoRestartOnCrash,
                    restoreMessage = restoreMessage,
                    onRestoreBackup = nodeViewModel::restoreBackup,
                )
            } else {
                DashboardPanel(
                    state = nodeState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (menuPeek.value > 0.5f && drawerState.isClosed && !showingSubPanel) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(menuPeek.value.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            if (drawerState.isClosed && !showingSubPanel) {
                val density = LocalDensity.current
                val gestureInset = with(density) {
                    WindowInsets.systemGestures
                        .getLeft(this, LocalLayoutDirection.current)
                        .toDp()
                }
                val stripWidth = gestureInset.coerceAtLeast(MenuEdgeBandMinWidth)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(stripWidth)
                        .height(MenuEdgeBandHeight)
                        .systemGestureExclusion { coordinates ->
                            val originX = coordinates.positionInRoot().x
                            Rect(
                                left = -originX,
                                top = 0f,
                                right = -originX + coordinates.size.width,
                                bottom = coordinates.size.height.toFloat(),
                            )
                        },
                )
                val openMenu = stringResource(R.string.open_menu)
                Surface(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = menuPeek.value.dp)
                        .width(24.dp)
                        .height(MenuTabHeight)
                        .pointerInput(drawerState) {
                            val openDrag = MenuEdgeOpenDrag.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                val origin = down.position
                                var opened = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    if (!change.pressed) break
                                    val dx = change.position.x - origin.x
                                    val dy = change.position.y - origin.y
                                    if (!opened && dx >= openDrag && dx > abs(dy) * 2f) {
                                        opened = true
                                        change.consume()
                                        scope.launch { drawerState.open() }
                                    } else if (opened) {
                                        change.consume()
                                    }
                                }
                            }
                        }
                        .semantics { contentDescription = openMenu },
                    shape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp,
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .width(10.dp)
                                    .height(2.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface,
                                        RoundedCornerShape(1.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }

    changelogLine?.let { line ->
        ChangelogDialog(
            line = line,
            onDismiss = {
                AppChangelog.markSeen(context)
                changelogLine = null
            },
        )
    }

    pendingRestart?.let { restart ->
        RestartPromptDialog(
            restart = restart,
            onConfirm = {
                pendingRestart = null
                withNotificationPermission(nodeViewModel::restartNetworkNode)
                closeDrawer()
            },
            onDismiss = { pendingRestart = null },
        )
    }

    if (showCrashDialog) {
        CrashReportDialog(
            comment = crashComment,
            onCommentChange = { crashComment = it },
            uploadState = crashUploadState,
            onSend = { nodeViewModel.uploadCrashReport(crashComment) },
            onDismiss = { showCrashDialog = false },
        )
    }

    if (pendingReset) {
        ResetConfirmDialog(
            onConfirm = {
                pendingReset = false
                nodeViewModel.resetNode()
            },
            onDismiss = { pendingReset = false },
        )
    }

    if (showNoEditor) {
        NoEditorDialog(onDismiss = { showNoEditor = false })
    }

    if (showExternalGuideConfirm) {
        ExternalGuideDialog(
            onConfirm = {
                showExternalGuideConfirm = false
                openDontKillMyAppGuide(context, restrictionSnapshot.vendor)
                closeDrawer()
            },
            onDismiss = { showExternalGuideConfirm = false },
        )
    }
}
