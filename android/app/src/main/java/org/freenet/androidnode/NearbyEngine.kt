package org.freenet.androidnode

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal fun bluetoothRadioEnabled(context: Context): Boolean? {
    val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
    val adapter = manager.adapter ?: return null
    return runCatching { adapter.isEnabled }.getOrNull()
}

/**
 * Local radios for one contract at a time.
 * Bluetooth is discovery plus a paired socket. Wi-Fi is the LAN both phones
 * are already on, found with NSD, not Wi-Fi Direct.
 */
internal class NearbyEngine(
    context: Context,
    private val onPeers: (Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onAsk: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val io = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "freenet-nearby").apply { isDaemon = true }
    }
    private val serviceName: String = "fn" +
        Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(0x1000000))
            .padStart(6, '0')
    private val links = LinkedHashMap<Long, Link>()
    private val nextLinkId = AtomicLong(1)
    private val bluetoothLive = ConcurrentHashMap.newKeySet<String>()
    private val bluetoothAttempts = ConcurrentHashMap<String, Long>()
    private val recentServes = ConcurrentHashMap<String, Long>()
    private val recentAutoAsks = ConcurrentHashMap<String, Long>()
    private val askLock = Any()
    private val identity = NearbyIdentity.load(File(appContext.noBackupFilesDir, "freenet"))

    init {
        NearbyHub.publishFingerprint(identity.fingerprint)
    }
    private val seenMessages = ConcurrentHashMap<String, Long>()
    private val localRequests = ConcurrentHashMap<String, Long>()
    private val seekReturn = ConcurrentHashMap<String, Long>()
    private val recentApplied = ConcurrentHashMap<String, Long>()
    private val radioLock = Any()

    @Volatile private var bluetoothWanted = false
    @Volatile private var wifiWanted = false
    @Volatile private var stopped = false
    @Volatile private var currentAsk: AskWait? = null
    @Volatile private var currentRequestId: ByteArray? = null
    private var forwardThread: Thread? = null

    private var advertiserCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var rfcommServer: BluetoothServerSocket? = null
    private var tcpServer: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val infoWatches = ArrayList<InfoWatch>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var bluetoothNote = ""
    private var wifiNote = ""
    private var helloThread: Thread? = null

    fun apply(bluetooth: Boolean, wifi: Boolean) {
        if (stopped) return
        synchronized(radioLock) {
            if (bluetooth && !bluetoothWanted) startBluetoothLocked()
            if (!bluetooth && bluetoothWanted) stopBluetoothLocked()
            if (wifi && !wifiWanted) startWifiLocked()
            if (!wifi && wifiWanted) stopWifiLocked()
        }
        publishStatus()
        publishPeers()
    }

    fun stop() {
        stopped = true
        currentAsk = null
        currentRequestId = null
        synchronized(radioLock) {
            stopBluetoothLocked()
            stopWifiLocked()
        }
        helloThread?.interrupt()
        forwardThread?.interrupt()
        NativeBridge.nearbyWatchStop()
        snapshotLinks().forEach { closeLink(it) }
        io.shutdownNow()
    }

    fun ask(raw: String) {
        io.execute { synchronized(askLock) { performAsk(raw, automatic = false) } }
    }

    fun askAll(raws: List<String>) {
        if (raws.isEmpty()) return
        io.execute {
            synchronized(askLock) {
                var saved = false
                var failed = false
                var noLink = false
                for (raw in raws) {
                    when (performAsk(raw, automatic = true)) {
                        AutoAsk.Saved -> saved = true
                        AutoAsk.Failed -> failed = true
                        AutoAsk.NoLink -> noLink = true
                        AutoAsk.Skipped -> Unit
                    }
                }
                when {
                    saved -> NearbyWaitNotice.saved(appContext)
                    noLink && !failed -> NearbyWaitNotice.noLink(appContext)
                    failed -> NearbyWaitNotice.failed(appContext)
                }
            }
        }
    }

    private fun performAsk(raw: String, automatic: Boolean): AutoAsk {
        if (stopped) return AutoAsk.Skipped
        if (currentAsk != null) {
            if (!automatic) onAsk("Already asking a nearby phone.")
            return AutoAsk.Skipped
        }
        val key = parseNearbyContractKey(raw)
        if (key == null || key.size != 32) {
            if (!automatic) {
                onAsk("Paste a contract key: 64 hex characters, base58, or a URL whose last part is that key.")
            }
            return AutoAsk.Skipped
        }
        val keyHex = nearbyKeyHex(key)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (automatic) {
            val previous = recentAutoAsks[keyHex]
            if (previous != null && nowElapsed - previous < AUTO_ASK_DEDUP_MS) return AutoAsk.Skipped
            recentAutoAsks[keyHex] = nowElapsed
        }
        if (!nearbyNodeIsRunning(NodeRepository.state.value.state)) {
            if (!automatic) {
                onAsk("Start the node on this phone before saving a contract from a nearby phone.")
            }
            return AutoAsk.Skipped
        }
        val open = snapshotLinks().filter { it.session?.established == true }
        if (open.isEmpty()) {
            NearbyTrace.add("Secure links open: 0. Request not sent. key=${keyHex.take(8)}")
            if (!automatic) {
                onAsk("No nearby phone is connected yet. Turn on Bluetooth or Wi-Fi on both phones and wait.")
            }
            return AutoAsk.NoLink
        }
        val requestId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val requestHex = nearbyKeyHex(requestId)
        rememberSeen(requestHex)
        localRequests[requestHex] = nowElapsed
        currentRequestId = requestId
        val wait = AskWait(open.map { it.id }.toSet())
        currentAsk = wait
        val senderLimit = NodePolicyRepository.state.value.nearbyHopLimit
        val payload = encodeNearbyHop(senderLimit, 0, requestId, key, ByteArray(0))
        if (automatic) NearbyWaitNotice.waiting(appContext)
        NearbyTrace.add("Request sent on ${open.size} secure links. key=${keyHex.take(8)}")
        onAsk("Asking ${open.size} nearby link${if (open.size == 1) "" else "s"}…")
        for (link in open) {
            send(link, NearbyLimits.TYPE_SEEK, payload)
        }
        val result = when (val outcome = wait.await(ASK_TIMEOUT_MS)) {
            is AskOutcome.Contract -> {
                val saved = importContract(outcome.bytes, key)
                NearbyTrace.add(
                    if (saved) "Contract saved. key=${keyHex.take(8)}"
                    else "Save failed. key=${keyHex.take(8)}",
                )
                if (saved) AutoAsk.Saved else AutoAsk.Failed
            }
            is AskOutcome.Refused -> {
                onAsk(outcome.text)
                NearbyTrace.add("Nearby phone refused: ${outcome.text} key=${keyHex.take(8)}")
                AutoAsk.Failed
            }
            AskOutcome.Timeout -> {
                onAsk("No nearby phone answered within 3 minutes.")
                NearbyTrace.add("No reply within 3 minutes. key=${keyHex.take(8)}")
                AutoAsk.Failed
            }
        }
        if (currentAsk === wait) currentAsk = null
        if (currentRequestId?.contentEquals(requestId) == true) currentRequestId = null
        return if (automatic) result else AutoAsk.Skipped
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothLocked() {
        bluetoothWanted = true
        if (!hasBluetoothPermission()) {
            bluetoothNote = "Bluetooth permission is missing."
            bluetoothWanted = false
            return
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null) {
            bluetoothNote = "This phone has no Bluetooth."
            bluetoothWanted = false
            return
        }
        if (!adapter.isEnabled) {
            bluetoothNote = "Turn Bluetooth on in Android settings."
            bluetoothWanted = false
            return
        }
        bluetoothNote = "Bluetooth is listening. Android may ask you to pair."
        startHelloLoop()
        startForwardLoop()
        var l2capPsm: Int? = null
        try {
            val server = if (Build.VERSION.SDK_INT >= 29) {
                adapter.listenUsingL2capChannel().also { l2capPsm = it.psm }
            } else {
                adapter.listenUsingRfcommWithServiceRecord("freenet-nearby", NearbyLimits.SERVICE_UUID)
            }
            rfcommServer = server
            io.execute {
                while (bluetoothWanted && !stopped) {
                    try {
                        val socket = server.accept()
                        adoptBluetooth(socket)
                    } catch (error: IOException) {
                        if (bluetoothWanted) Log.i(TAG, "Bluetooth accept stopped: ${error.message}")
                        break
                    }
                }
            }
        } catch (error: Exception) {
            bluetoothNote = "Bluetooth could not open a listening socket."
            Log.i(TAG, "Bluetooth listen failed: ${error.message}")
        }
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser != null) {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build()
            val serviceUuid = ParcelUuid(NearbyLimits.SERVICE_UUID)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(serviceUuid)
                .apply {
                    val psm = l2capPsm
                    if (psm != null) {
                        // 0xFFFF is the Bluetooth test company id. The two bytes are the L2CAP channel.
                        addManufacturerData(0xFFFF, byteArrayOf((psm shr 8).toByte(), psm.toByte()))
                    }
                }
                .build()
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.i(TAG, "BLE advertise started")
                }

                override fun onStartFailure(errorCode: Int) {
                    bluetoothNote = "Bluetooth advertising failed ($errorCode)."
                    publishStatus()
                    Log.i(TAG, "BLE advertise failed: $errorCode")
                }
            }
            advertiserCallback = callback
            runCatching { advertiser.startAdvertising(settings, data, callback) }
                .onFailure { Log.i(TAG, "BLE advertise threw: ${it.message}") }
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(NearbyLimits.SERVICE_UUID)).build(),
            )
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    considerBluetoothDevice(result)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { considerBluetoothDevice(it) }
                }
            }
            scanCallback = callback
            runCatching { scanner.startScan(filters, scanSettings, callback) }
                .onFailure { Log.i(TAG, "BLE scan threw: ${it.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothLocked() {
        bluetoothWanted = false
        bluetoothNote = ""
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        val advertiser = adapter?.bluetoothLeAdvertiser
        val advertiseCallback = advertiserCallback
        if (advertiser != null && advertiseCallback != null && adapter.isEnabled) {
            runCatching { advertiser.stopAdvertising(advertiseCallback) }
        }
        advertiserCallback = null
        val scanner = adapter?.bluetoothLeScanner
        val callback = scanCallback
        if (scanner != null && callback != null && adapter.isEnabled) {
            runCatching { scanner.stopScan(callback) }
        }
        scanCallback = null
        runCatching { rfcommServer?.close() }
        rfcommServer = null
        snapshotLinks().filter { it.kind == "bluetooth" }.forEach { closeLink(it) }
        bluetoothLive.clear()
    }

    private fun startWifiLocked() {
        wifiWanted = true
        if (!hasWifiPermission()) {
            wifiNote = "Wi-Fi nearby permission is missing."
            wifiWanted = false
            return
        }
        val nsd = appContext.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            wifiNote = "This phone cannot announce a service on the local network."
            wifiWanted = false
            return
        }
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(0))
        } catch (error: IOException) {
            wifiNote = "Wi-Fi could not open a listening socket."
            Log.i(TAG, "TCP bind failed: ${error.message}")
            runCatching { server.close() }
            wifiWanted = false
            return
        }
        tcpServer = server
        wifiNote = "Wi-Fi is listening on this network."
        startHelloLoop()
        startForwardLoop()
        acquireMulticastLock()
        io.execute {
            while (wifiWanted && !stopped) {
                try {
                    val socket = server.accept()
                    adoptTcp(socket, initiator = false)
                } catch (error: IOException) {
                    if (wifiWanted) Log.i(TAG, "TCP accept stopped: ${error.message}")
                    break
                }
            }
        }
        val info = NsdServiceInfo().apply {
            serviceName = serviceName
            serviceType = NearbyLimits.NSD_TYPE
            port = server.localPort
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD registered ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                wifiNote = "This phone could not announce itself on the local network."
                publishStatus()
                Log.i(TAG, "NSD register failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = registration
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure { Log.i(TAG, "NSD register threw: ${it.message}") }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == serviceName) return
                watchService(nsd, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                wifiNote = "This phone could not look for other phones on the local network."
                publishStatus()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = discovery
        runCatching { nsd.discoverServices(NearbyLimits.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { Log.i(TAG, "NSD discover threw: ${it.message}") }
    }

    private fun stopWifiLocked() {
        wifiWanted = false
        wifiNote = ""
        val nsd = appContext.getSystemService(NsdManager::class.java)
        val registration = registrationListener
        if (nsd != null && registration != null) {
            runCatching { nsd.unregisterService(registration) }
        }
        registrationListener = null
        val discovery = discoveryListener
        if (nsd != null && discovery != null) {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
        discoveryListener = null
        if (nsd != null) {
            infoWatches.forEach { watch -> runCatching { watch.unregister(nsd) } }
        }
        infoWatches.clear()
        runCatching { tcpServer?.close() }
        tcpServer = null
        snapshotLinks().filter { it.kind == "wifi" }.forEach { closeLink(it) }
        val lock = multicastLock
        multicastLock = null
        if (lock != null && lock.isHeld) runCatching { lock.release() }
    }

    private fun watchService(nsd: NsdManager, serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    connectResolved(updated)
                }

                override fun onServiceLost() = Unit

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            val watch = object : InfoWatch {
                override fun unregister(manager: NsdManager) {
                    runCatching { manager.unregisterServiceInfoCallback(callback) }
                }
            }
            infoWatches.add(watch)
            runCatching { nsd.registerServiceInfoCallback(serviceInfo, io, callback) }
                .onFailure { Log.i(TAG, "NSD callback failed: ${it.message}") }
            return
        }
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(unresolved: NsdServiceInfo, errorCode: Int) {
                    Log.i(TAG, "NSD resolve failed: $errorCode")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    connectResolved(resolved)
                }
            })
        }.onFailure { Log.i(TAG, "NSD resolve threw: ${it.message}") }
    }

    private fun connectResolved(info: NsdServiceInfo) {
        if (!wifiWanted || stopped) return
        if (info.serviceName == serviceName) return
        val port = info.port
        if (port <= 0) return
        for (host in hostsOf(info)) {
            io.execute { connectTcp(host, port) }
        }
    }

    private fun hostsOf(info: NsdServiceInfo): List<InetAddress> {
        if (Build.VERSION.SDK_INT >= 34) {
            val addresses = info.hostAddresses
            if (!addresses.isNullOrEmpty()) return addresses
        }
        @Suppress("DEPRECATION")
        val host = info.host
        return if (host != null) listOf(host) else emptyList()
    }

    private fun connectTcp(host: InetAddress, port: Int) {
        if (!wifiWanted || stopped) return
        if (host.isAnyLocalAddress) return
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 8_000)
            adoptTcp(socket, initiator = true)
        } catch (error: IOException) {
            runCatching { socket.close() }
            Log.i(TAG, "TCP connect to $host:$port failed: ${error.message}")
        }
    }

    private fun considerBluetoothDevice(result: ScanResult) {
        val device = result.device ?: return
        if (!bluetoothWanted || stopped) return
        val address = device.address ?: return
        if (bluetoothLive.contains(address)) return
        val now = SystemClock.elapsedRealtime()
        val previous = bluetoothAttempts.put(address, now)
        if (previous != null && now - previous < 15_000L) {
            bluetoothAttempts[address] = previous
            return
        }
        val psm = result.scanRecord
            ?.getManufacturerSpecificData(0xFFFF)
            ?.takeIf { it.size >= 2 }
            ?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
        io.execute { connectBluetooth(device, psm) }
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(device: BluetoothDevice, psm: Int?) {
        if (!bluetoothWanted || stopped || !hasBluetoothPermission()) return
        val address = device.address ?: return
        if (!bluetoothLive.add(address)) return
        val socket = runCatching {
            if (psm != null && psm > 0 && Build.VERSION.SDK_INT >= 29) {
                device.createL2capChannel(psm)
            } else {
                device.createRfcommSocketToServiceRecord(NearbyLimits.SERVICE_UUID)
            }
        }.getOrElse {
            bluetoothLive.remove(address)
            return
        }
        try {
            socket.connect()
            adoptOpenBluetooth(address, socket, initiator = true)
        } catch (error: IOException) {
            bluetoothLive.remove(address)
            runCatching { socket.close() }
            Log.i(TAG, "Bluetooth connect to $address failed: ${error.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun adoptBluetooth(socket: BluetoothSocket) {
        val address = runCatching { socket.remoteDevice.address }.getOrNull()
        if (address == null) {
            runCatching { socket.close() }
            return
        }
        if (!bluetoothLive.add(address)) {
            runCatching { socket.close() }
            return
        }
        adoptOpenBluetooth(address, socket, initiator = false)
    }

    private fun adoptOpenBluetooth(address: String, socket: BluetoothSocket, initiator: Boolean) {
        val link = Link(
            id = nextLinkId.getAndIncrement(),
            kind = "bluetooth",
            address = address,
            initiator = initiator,
            maxBytes = NearbyLimits.BLUETOOTH_MAX_BYTES,
            input = socket.inputStream,
            output = socket.outputStream,
            closeSocket = { runCatching { socket.close() } },
        )
        openLink(link)
    }

    private fun adoptTcp(socket: Socket, initiator: Boolean) {
        socket.soTimeout = 60_000
        val address = socket.inetAddress?.hostAddress ?: "tcp"
        val link = Link(
            id = nextLinkId.getAndIncrement(),
            kind = "wifi",
            address = address,
            initiator = initiator,
            maxBytes = NearbyLimits.WIFI_MAX_BYTES,
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            closeSocket = { runCatching { socket.close() } },
        )
        openLink(link)
    }

    private fun openLink(link: Link) {
        link.session = NearbySession(identity, link.initiator)
        addLink(link)
        val first = link.session?.start()
        if (first != null) send(link, NearbyLimits.TYPE_HANDSHAKE, first)
        sendHello(link)
        io.execute { readLoop(link) }
    }

    private fun readLoop(link: Link) {
        val decoder = NearbyDecoder()
        val buffer = ByteArray(16 * 1024)
        try {
            while (!stopped && link.open) {
                val count = try {
                    link.input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (count < 0) break
                val batch = decoder.push(buffer, count, NearbyLimits.HARD_MAX_BYTES)
                if (batch.overflow) {
                    Log.i(TAG, "Closing nearby link that advertised an oversized frame")
                    break
                }
                for (frame in batch.frames) {
                    val payload = unwrap(link, frame) ?: break
                    handleFrame(link, NearbyFrame(frame.type, payload))
                }
            }
        } catch (error: IOException) {
            Log.i(TAG, "Nearby link closed: ${error.message}")
        } finally {
            removeLink(link)
        }
    }

    private fun unwrap(link: Link, frame: NearbyFrame): ByteArray? {
        val session = link.session
        if (
            session?.established == true &&
            frame.type != NearbyLimits.TYPE_HANDSHAKE &&
            frame.type != NearbyLimits.TYPE_HELLO
        ) {
            val plain = session.open(frame.payload)
            if (plain == null || session.failed) {
                link.open = false
                return null
            }
            return plain
        }
        return frame.payload
    }

    private fun handleFrame(link: Link, frame: NearbyFrame) {
        if (
            frame.type != NearbyLimits.TYPE_HELLO &&
            frame.type != NearbyLimits.TYPE_HANDSHAKE &&
            link.session?.established != true
        ) {
            return
        }
        when (frame.type) {
            NearbyLimits.TYPE_HELLO -> Unit
            NearbyLimits.TYPE_HANDSHAKE -> {
                val reply = link.session?.receive(frame.payload)
                if (link.session?.failed == true) {
                    link.open = false
                    return
                }
                if (reply != null) send(link, NearbyLimits.TYPE_HANDSHAKE, reply)
            }
            NearbyLimits.TYPE_CHAT -> {
                val payload = frame.payload.copyOf()
                io.execute { handleChat(link, payload) }
            }
            NearbyLimits.TYPE_REQUEST -> {
                if (frame.payload.size == 32) {
                    io.execute { serve(link, frame.payload.copyOf()) }
                } else {
                    send(link, NearbyLimits.TYPE_REFUSE, byteArrayOf(NearbyLimits.REFUSE_NODE_ERROR.toByte()))
                }
            }
            NearbyLimits.TYPE_REFUSE -> {
                val reason = frame.payload.firstOrNull()?.toInt()?.and(0xff) ?: NearbyLimits.REFUSE_NODE_ERROR
                currentAsk?.refuse(link.id, reason)
            }
            NearbyLimits.TYPE_CONTRACT -> currentAsk?.contract(frame.payload.copyOf())
            NearbyLimits.TYPE_SEEK -> {
                val payload = frame.payload.copyOf()
                io.execute { handleSeek(link, payload) }
            }
            NearbyLimits.TYPE_DELIVER -> {
                val parsed = parseNearbyDelivery(frame.payload) ?: return
                val id = parsed.first
                if (localRequests.containsKey(nearbyKeyHex(id))) {
                    currentAsk?.contract(parsed.second)
                } else {
                    val copyId = id.copyOf()
                    val blob = parsed.second.copyOf()
                    io.execute { relayDelivery(copyId, blob) }
                }
            }
            NearbyLimits.TYPE_UPDATE -> {
                val payload = frame.payload.copyOf()
                io.execute { handleUpdate(link, payload) }
            }
            NearbyLimits.TYPE_SEEK_REFUSE -> {
                if (frame.payload.size < 17) return
                val id = frame.payload.copyOfRange(0, 16)
                val origin = currentRequestId
                if (origin != null && origin.contentEquals(id)) {
                    val reason = frame.payload[16].toInt() and 0xff
                    currentAsk?.refuse(link.id, reason)
                }
            }
        }
    }

    private fun serve(link: Link, key: ByteArray) {
        if (!link.open || stopped) return
        val token = nearbyKeyHex(key)
        val nowElapsed = SystemClock.elapsedRealtime()
        val previousServe = recentServes.put(token, nowElapsed)
        if (previousServe != null && nowElapsed - previousServe < 120_000L) {
            recentServes[token] = previousServe
            return
        }
        val policy = NodePolicyRepository.state.value
        val now = System.currentTimeMillis()
        val connectivity = AndroidConnectivityMonitor(appContext) {}.currentSnapshot()
        val decision = nearbyDecision(
            nodeRunning = nearbyNodeIsRunning(NodeRepository.state.value.state),
            sessionExpired = nearbySessionExpired(
                policy.nearbySessionStartedEpochMs,
                policy.nearbySessionMinutes,
                now,
            ),
            fetchMissing = policy.nearbyFetchMissing,
            fetchedBytes = fetchedBytesToday(policy.nearbyFetchedDay, policy.nearbyFetchedBytes, now),
            capBytes = nearbyCapBytes(policy.nearbyDailyCapMb),
            networkAllowed = connectivity.isAllowed(policy.networkData),
            peersConnected = freenetPeersConnected(),
        )
        if (!decision.callNative) {
            send(link, NearbyLimits.TYPE_REFUSE, byteArrayOf(decision.refuseReason.toByte()))
            return
        }
        val directory = File(appContext.cacheDir, "nearby")
        val json = NativeBridge.nearbyExportContract(
            NearbyLimits.WEBSOCKET_PORT,
            token,
            directory.absolutePath,
            decision.allowSend,
            decision.allowFetch,
        )
        val parsed = parseNearbyExport(json)
        Log.i(TAG, "Nearby export status=${parsed.status} bytes=${parsed.bytes} fetched=${parsed.fetched}")
        if (parsed.fetched && parsed.bytes > 0L) {
            NodePolicyRepository.addNearbyFetchedBytes(appContext, parsed.bytes)
        }
        val file = parsed.path.takeIf { it.isNotBlank() }?.let(::File)
        val payload = if (parsed.status == "local" || parsed.status == "fetched") {
            file?.takeIf { it.isFile && it.length() in 1..link.maxBytes.toLong() }?.readBytes()
        } else {
            null
        }
        if (file != null) runCatching { file.delete() }
        if (payload != null && fits(link, payload) && send(link, NearbyLimits.TYPE_CONTRACT, payload)) return
        val reason = if (parsed.status == "local" || parsed.status == "fetched") {
            NearbyLimits.REFUSE_TOO_LARGE
        } else {
            refuseForExportStatus(parsed.status)
        }
        send(link, NearbyLimits.TYPE_REFUSE, byteArrayOf(reason.toByte()))
    }

    private fun importContract(bytes: ByteArray, knownKey: ByteArray? = null): Boolean {
        if (bytes.size > NearbyLimits.HARD_MAX_BYTES) {
            onAsk("That contract is too large.")
            return false
        }
        val json = writeImport(bytes)
        if (json == null) {
            onAsk("This phone could not save the contract.")
            return false
        }
        val imported = runCatching {
            org.json.JSONObject(json).optString("status") == "imported"
        }.getOrDefault(false)
        val saved = if (imported) parseNearbyImportKey(json) ?: knownKey else null
        if (saved != null && saved.size == 32) {
            NativeBridge.nearbyWatchAddKey(nearbyKeyHex(saved))
        }
        onAsk(parseNearbyImportMessage(json))
        return imported
    }

    private fun writeImport(bytes: ByteArray): String? {
        val file = File(appContext.cacheDir, "nearby-in-${System.nanoTime()}.bin")
        return try {
            file.writeBytes(bytes)
            NativeBridge.nearbyImportContract(NearbyLimits.WEBSOCKET_PORT, file.absolutePath)
        } catch (error: IOException) {
            Log.i(TAG, "Nearby import write failed: ${error.message}")
            null
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun handleSeek(from: Link, payload: ByteArray) {
        val hop = parseNearbyHop(payload) ?: return
        if (hop.key.size != 32 || hop.body.isNotEmpty()) return
        val idHex = nearbyKeyHex(hop.id)
        if (!rememberSeen(idHex)) return
        val origin = currentRequestId
        if (origin != null && origin.contentEquals(hop.id)) return
        seekReturn[idHex] = from.id
        val hopsUsed = hop.hopsUsed + 1
        val policy = NodePolicyRepository.state.value
        val now = System.currentTimeMillis()
        val nodeUp = nearbyNodeIsRunning(NodeRepository.state.value.state)
        if (!nodeUp || nearbySessionExpired(policy.nearbySessionStartedEpochMs, policy.nearbySessionMinutes, now)) {
            replySeekRefuse(from, hop.id, if (nodeUp) NearbyLimits.REFUSE_SESSION else NearbyLimits.REFUSE_NODE_DOWN)
            return
        }
        val connectivity = AndroidConnectivityMonitor(appContext) {}.currentSnapshot()
        val decision = nearbyDecision(
            nodeRunning = true,
            sessionExpired = false,
            fetchMissing = policy.nearbyFetchMissing,
            fetchedBytes = fetchedBytesToday(policy.nearbyFetchedDay, policy.nearbyFetchedBytes, now),
            capBytes = nearbyCapBytes(policy.nearbyDailyCapMb),
            networkAllowed = connectivity.isAllowed(policy.networkData),
            peersConnected = freenetPeersConnected(),
        )
        val directory = File(appContext.cacheDir, "nearby")
        val json = NativeBridge.nearbyExportContract(
            NearbyLimits.WEBSOCKET_PORT,
            nearbyKeyHex(hop.key),
            directory.absolutePath,
            decision.allowSend,
            decision.allowFetch,
        )
        val parsed = parseNearbyExport(json)
        val shortKey = nearbyKeyHex(hop.key).take(8)
        NearbyTrace.add("Request received. key=$shortKey reply=${parsed.status} fetched=${parsed.fetched}")
        Log.i(TAG, "Nearby seek status=${parsed.status} bytes=${parsed.bytes} fetched=${parsed.fetched}")
        if (parsed.fetched && parsed.bytes > 0L) {
            NodePolicyRepository.addNearbyFetchedBytes(appContext, parsed.bytes)
        }
        val file = parsed.path.takeIf { it.isNotBlank() }?.let(::File)
        val blob = if (parsed.status == "local" || parsed.status == "fetched") {
            file?.takeIf { it.isFile && it.length() in 1..maxBytes(from.kind).toLong() }?.readBytes()
        } else {
            null
        }
        if (file != null) runCatching { file.delete() }
        if (parsed.status == "local" || parsed.status == "fetched") {
            if (blob != null) {
                NativeBridge.nearbyWatchAddKey(nearbyKeyHex(hop.key))
                val sent = send(from, NearbyLimits.TYPE_DELIVER, encodeNearbyDelivery(hop.id, blob))
                NearbyTrace.add(if (sent) "Contract sent. key=$shortKey" else "Contract was not sent. key=$shortKey")
            } else {
                replySeekRefuse(from, hop.id, NearbyLimits.REFUSE_TOO_LARGE)
            }
            return
        }
        if (parsed.status == "refused") {
            replySeekRefuse(from, hop.id, NearbyLimits.REFUSE_OWNED_OFF)
            return
        }
        if (parsed.status == "too_large") {
            replySeekRefuse(from, hop.id, NearbyLimits.REFUSE_TOO_LARGE)
            return
        }
        if (nearbyForward(policy.nearbyHopLimit, hop.senderLimit, hopsUsed)) {
            val forwarded = encodeNearbyHop(hop.senderLimit, hopsUsed, hop.id, hop.key, ByteArray(0))
            val sent = snapshotLinks().any { other ->
                other.id != from.id && send(other, NearbyLimits.TYPE_SEEK, forwarded)
            }
            if (sent) return
        }
        val reason = if (parsed.status == "absent" || parsed.status == "unavailable") {
            NearbyLimits.REFUSE_MISSING
        } else {
            refuseForExportStatus(parsed.status)
        }
        replySeekRefuse(from, hop.id, reason)
    }

    private fun relayDelivery(id: ByteArray, blob: ByteArray) {
        if (blob.size > NearbyLimits.HARD_MAX_BYTES) return
        val json = writeImport(blob)
        val saved = json?.let { parseNearbyImportKey(it) }
        if (saved != null && saved.size == 32) {
            NativeBridge.nearbyWatchAddKey(nearbyKeyHex(saved))
        }
        val returnLinkId = seekReturn[nearbyKeyHex(id)]
        val previous = returnLinkId?.let { linkId -> snapshotLinks().find { it.id == linkId } }
        if (previous == null) return
        sendDelivery(previous, id, blob)
    }

    private fun handleUpdate(from: Link, payload: ByteArray) {
        val hop = parseNearbyHop(payload) ?: return
        if (hop.key.size != 32 || hop.body.isEmpty()) return
        if (hop.body.size > maxBytes(from.kind)) return
        val idHex = nearbyKeyHex(hop.id)
        if (!rememberSeen(idHex)) return
        val hopsUsed = hop.hopsUsed + 1
        val hash = sha256(hop.body)
        recentApplied[hash] = SystemClock.elapsedRealtime()
        val file = File(appContext.cacheDir, "nearby-apply-${System.nanoTime()}.bin")
        val applied = try {
            file.writeBytes(hop.body)
            val json = NativeBridge.nearbyApplyUpdate(
                NearbyLimits.WEBSOCKET_PORT,
                nearbyKeyHex(hop.key),
                file.absolutePath,
            )
            runCatching { org.json.JSONObject(json).optString("status") }.getOrDefault("error")
        } catch (error: IOException) {
            Log.i(TAG, "Nearby update write failed: ${error.message}")
            "error"
        } finally {
            runCatching { file.delete() }
        }
        if (applied == "applied") {
            NativeBridge.nearbyWatchAddKey(nearbyKeyHex(hop.key))
        }
        val policy = NodePolicyRepository.state.value
        if (!nearbyForward(policy.nearbyHopLimit, hop.senderLimit, hopsUsed)) return
        val forwarded = encodeNearbyHop(hop.senderLimit, hopsUsed, hop.id, hop.key, hop.body)
        snapshotLinks().forEach { other ->
            if (other.id != from.id && fits(other, forwarded)) {
                send(other, NearbyLimits.TYPE_UPDATE, forwarded)
            }
        }
    }

    private fun originateUpdate(keyHex: String, body: ByteArray) {
        val key = decodeNearbyKeyToken(keyHex) ?: return
        if (key.size != 32 || body.isEmpty()) return
        val hash = sha256(body)
        val seenAt = recentApplied[hash]
        val nowElapsed = SystemClock.elapsedRealtime()
        if (seenAt != null && nowElapsed - seenAt < 60_000L) return
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
        rememberSeen(nearbyKeyHex(id))
        val limit = NodePolicyRepository.state.value.nearbyHopLimit
        val payload = encodeNearbyHop(limit, 0, id, key, body)
        snapshotLinks().forEach { link ->
            if (fits(link, payload)) send(link, NearbyLimits.TYPE_UPDATE, payload)
        }
    }

    private fun startForwardLoop() {
        if (forwardThread?.isAlive == true) return
        val directory = File(appContext.cacheDir, "nearby")
        if (!directory.isDirectory && !directory.mkdirs()) return
        NativeBridge.nearbyWatchStart(NearbyLimits.WEBSOCKET_PORT, directory.absolutePath)
        val thread = Thread({
            while (!stopped && (bluetoothWanted || wifiWanted)) {
                val json = NativeBridge.nearbyWatchPoll(1_000)
                if (stopped) break
                val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: continue
                val status = obj.optString("status")
                val key = obj.optString("key", "")
                if (status == "missing" && key.isNotBlank()) {
                    NearbyTrace.add("Subscribed contract has no data. key=${key.take(8)}")
                    askAll(listOf(key))
                    continue
                }
                if (status != "update") continue
                val path = obj.optString("path", "")
                if (path.isBlank() || key.isBlank()) continue
                val file = File(path)
                val body = runCatching { file.readBytes() }.getOrNull()
                runCatching { file.delete() }
                if (body != null && body.isNotEmpty()) originateUpdate(key, body)
            }
        }, "freenet-nearby-forward")
        thread.isDaemon = true
        forwardThread = thread
        thread.start()
    }

    private fun sendDelivery(link: Link, id: ByteArray, blob: ByteArray) {
        val payload = encodeNearbyDelivery(id, blob)
        if (!fits(link, payload)) {
            replySeekRefuse(link, id, NearbyLimits.REFUSE_TOO_LARGE)
            return
        }
        send(link, NearbyLimits.TYPE_DELIVER, payload)
    }

    private fun replySeekRefuse(link: Link, id: ByteArray, reason: Int) {
        val payload = ByteArray(17)
        id.copyInto(payload, 0, 0, 16)
        payload[16] = reason.toByte()
        send(link, NearbyLimits.TYPE_SEEK_REFUSE, payload)
    }

    private fun rememberSeen(idHex: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = seenMessages.putIfAbsent(idHex, now)
        if (previous != null) return false
        if (seenMessages.size > 400) {
            seenMessages.entries.removeIf { now - it.value > 10 * 60_000L }
        }
        return true
    }

    private fun freenetPeersConnected(): Boolean {
        val state = NodeRepository.state.value
        return state.state == "RunningNetwork" && state.peers > 0
    }

    private fun maxBytes(kind: String): Int {
        val policy = NodePolicyRepository.state.value
        return nearbyMaxBytes(policy.nearbyBluetoothMaxMb, policy.nearbyWifiMaxMb, kind)
    }

    private fun fits(link: Link, payload: ByteArray): Boolean = payload.size <= maxBytes(link.kind)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sendHello(link: Link) {
        val name = (android.os.Build.MODEL ?: "phone").take(40).encodeToByteArray()
        send(link, NearbyLimits.TYPE_HELLO, name)
    }

    fun sendChat(text: String) {
        val clean = text.trim().take(4_000)
        if (clean.isEmpty()) return
        val open = snapshotLinks().filter { it.session?.established == true }
        if (open.isEmpty()) {
            onAsk("The nearby link is not secure yet.")
            return
        }
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
        rememberSeen(nearbyKeyHex(id))
        val limit = NodePolicyRepository.state.value.nearbyHopLimit
        val textBytes = clean.encodeToByteArray()
        val signature = identity.sign(nearbyChatSigned(id, limit, identity.ed25519Public, textBytes))
        val payload = encodeNearbyHop(limit, 0, id, identity.ed25519Public, signature + textBytes)
        NearbyHub.addChat(identity.fingerprint, clean, mine = true)
        open.forEach { link ->
            if (fits(link, payload)) send(link, NearbyLimits.TYPE_CHAT, payload)
        }
    }

    private fun handleChat(from: Link, payload: ByteArray) {
        val hop = parseNearbyHop(payload) ?: return
        if (hop.key.size != 32 || hop.body.size < 64) return
        val signature = hop.body.copyOfRange(0, 64)
        val textBytes = hop.body.copyOfRange(64, hop.body.size)
        if (!nearbyVerify(hop.key, nearbyChatSigned(hop.id, hop.senderLimit, hop.key, textBytes), signature)) {
            return
        }
        if (!rememberSeen(nearbyKeyHex(hop.id))) return
        val text = runCatching { textBytes.toString(Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty() || text.length > 4_000) return
        if (!hop.key.contentEquals(identity.ed25519Public)) {
            NearbyHub.addChat(nearbyFingerprint(hop.key), text, mine = false)
        }
        val hopsUsed = hop.hopsUsed + 1
        val policy = NodePolicyRepository.state.value
        if (!nearbyForward(policy.nearbyHopLimit, hop.senderLimit, hopsUsed)) return
        val forwarded = encodeNearbyHop(hop.senderLimit, hopsUsed, hop.id, hop.key, hop.body)
        snapshotLinks().forEach { other ->
            if (other.id != from.id && other.session?.established == true && fits(other, forwarded)) {
                send(other, NearbyLimits.TYPE_CHAT, forwarded)
            }
        }
    }

    private fun send(link: Link, type: Int, payload: ByteArray): Boolean {
        if (!link.open) return false
        if (
            type != NearbyLimits.TYPE_HELLO &&
            type != NearbyLimits.TYPE_HANDSHAKE &&
            link.session?.established != true
        ) {
            return false
        }
        if (
            (type == NearbyLimits.TYPE_CONTRACT ||
                type == NearbyLimits.TYPE_DELIVER ||
                type == NearbyLimits.TYPE_UPDATE ||
                type == NearbyLimits.TYPE_CHAT) &&
            !fits(link, payload)
        ) {
            return false
        }
        val body = if (
            link.session?.established == true &&
            type != NearbyLimits.TYPE_HANDSHAKE &&
            type != NearbyLimits.TYPE_HELLO
        ) {
            link.session?.seal(payload) ?: return false
        } else {
            payload
        }
        val frame = encodeNearbyFrame(type, body)
        return synchronized(link.output) {
            try {
                link.output.write(frame)
                link.output.flush()
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    private fun startHelloLoop() {
        if (helloThread?.isAlive == true) return
        val thread = Thread({
            while (!stopped && (bluetoothWanted || wifiWanted)) {
                try {
                    Thread.sleep(HELLO_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                snapshotLinks().forEach { sendHello(it) }
            }
        }, "freenet-nearby-hello")
        thread.isDaemon = true
        helloThread = thread
        thread.start()
    }

    private fun addLink(link: Link) {
        synchronized(links) { links[link.id] = link }
        publishPeers()
        Log.i(TAG, "Nearby ${link.kind} link up (${link.address})")
    }

    private fun removeLink(link: Link) {
        val removed = synchronized(links) { links.remove(link.id) != null }
        if (link.kind == "bluetooth") bluetoothLive.remove(link.address)
        link.open = false
        link.closeSocket()
        currentAsk?.drop(link.id)
        if (removed) publishPeers()
    }

    private fun closeLink(link: Link) {
        removeLink(link)
    }

    private fun snapshotLinks(): List<Link> = synchronized(links) { links.values.toList() }

    private fun publishPeers() {
        onPeers(snapshotLinks().size)
    }

    private fun publishStatus() {
        onStatus(listOf(bluetoothNote, wifiNote).filter { it.isNotBlank() }.joinToString(" "))
    }

    private fun acquireMulticastLock() {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return
        val lock = wifi.createMulticastLock("freenet-nearby")
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
        multicastLock = lock
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            granted("android.permission.BLUETOOTH_CONNECT") &&
                granted("android.permission.BLUETOOTH_SCAN") &&
                granted("android.permission.BLUETOOTH_ADVERTISE")
        } else {
            granted("android.permission.BLUETOOTH") &&
                granted("android.permission.ACCESS_FINE_LOCATION")
        }
    }

    private fun hasWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return granted("android.permission.NEARBY_WIFI_DEVICES")
    }

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private class Link(
        val id: Long,
        val kind: String,
        val address: String,
        val initiator: Boolean,
        val maxBytes: Int,
        var session: NearbySession? = null,
        val input: InputStream,
        val output: OutputStream,
        val closeSocket: () -> Unit,
    ) {
        @Volatile var open: Boolean = true
    }

    private interface InfoWatch {
        fun unregister(manager: NsdManager)
    }

    private class AskWait(ids: Set<Long>) {
        private val lock = Object()
        private val waiting = ids.toMutableSet()
        private val reasons = LinkedHashSet<Int>()
        private var contract: ByteArray? = null
        private var done = false

        fun contract(bytes: ByteArray) {
            synchronized(lock) {
                if (done || bytes.isEmpty()) return
                contract = bytes
                done = true
                lock.notifyAll()
            }
        }

        fun refuse(linkId: Long, reason: Int) {
            synchronized(lock) {
                if (done) return
                if (!waiting.remove(linkId)) return
                reasons.add(reason)
                if (waiting.isEmpty()) {
                    done = true
                    lock.notifyAll()
                }
            }
        }

        fun drop(linkId: Long) {
            synchronized(lock) {
                if (done) return
                waiting.remove(linkId)
                if (waiting.isEmpty()) {
                    done = true
                    lock.notifyAll()
                }
            }
        }

        fun await(timeoutMs: Long): AskOutcome {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(lock) {
                while (!done) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    lock.wait(remaining)
                }
                val blob = contract
                if (blob != null) return AskOutcome.Contract(blob)
                if (reasons.isNotEmpty()) {
                    return AskOutcome.Refused(reasons.joinToString(" ") { nearbyRefuseText(it) })
                }
                if (done) return AskOutcome.Refused("The nearby phone disconnected.")
                return AskOutcome.Timeout
            }
        }
    }

    private sealed interface AskOutcome {
        class Contract(val bytes: ByteArray) : AskOutcome
        class Refused(val text: String) : AskOutcome
        data object Timeout : AskOutcome
    }

    private companion object {
        const val TAG = "FreenetNearby"
        const val HELLO_INTERVAL_MS = 25_000L
        const val ASK_TIMEOUT_MS = 180_000L
        const val AUTO_ASK_DEDUP_MS = 3 * 60_000L
    }

    private enum class AutoAsk {
        Saved,
        Failed,
        NoLink,
        Skipped,
    }
}
