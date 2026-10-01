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
    private val radioLock = Any()

    @Volatile private var bluetoothWanted = false
    @Volatile private var wifiWanted = false
    @Volatile private var stopped = false
    @Volatile private var currentAsk: AskWait? = null

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
        synchronized(radioLock) {
            stopBluetoothLocked()
            stopWifiLocked()
        }
        helloThread?.interrupt()
        snapshotLinks().forEach { closeLink(it) }
        io.shutdownNow()
    }

    fun ask(raw: String) {
        io.execute {
            if (stopped) return@execute
            if (currentAsk != null) {
                onAsk("Already asking a nearby phone.")
                return@execute
            }
            val key = parseNearbyContractKey(raw)
            if (key == null || key.size != 32) {
                onAsk("Paste a contract key: 64 hex characters, base58, or a URL whose last part is that key.")
                return@execute
            }
            if (!nearbyNodeIsRunning(NodeRepository.state.value.state)) {
                onAsk("Start the node on this phone before saving a contract from a nearby phone.")
                return@execute
            }
            val open = snapshotLinks().let { links ->
                if (links.any { it.kind == "wifi" }) links.filter { it.kind == "wifi" } else links
            }
            if (open.isEmpty()) {
                onAsk("No nearby phone is connected yet. Turn on Bluetooth or Wi-Fi on both phones and wait.")
                return@execute
            }
            val wait = AskWait(open.map { it.id }.toSet())
            currentAsk = wait
            onAsk("Asking ${open.size} nearby link${if (open.size == 1) "" else "s"}…")
            for (link in open) {
                send(link, NearbyLimits.TYPE_REQUEST, key)
            }
            when (val outcome = wait.await(ASK_TIMEOUT_MS)) {
                is AskOutcome.Contract -> importContract(outcome.bytes)
                is AskOutcome.Refused -> onAsk(outcome.text)
                AskOutcome.Timeout -> onAsk("No nearby phone answered within 90 seconds.")
            }
            if (currentAsk === wait) currentAsk = null
        }
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
        acquireMulticastLock()
        io.execute {
            while (wifiWanted && !stopped) {
                try {
                    val socket = server.accept()
                    adoptTcp(socket)
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
            adoptTcp(socket)
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
            adoptOpenBluetooth(address, socket)
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
        adoptOpenBluetooth(address, socket)
    }

    private fun adoptOpenBluetooth(address: String, socket: BluetoothSocket) {
        val link = Link(
            id = nextLinkId.getAndIncrement(),
            kind = "bluetooth",
            address = address,
            maxBytes = NearbyLimits.BLUETOOTH_MAX_BYTES,
            input = socket.inputStream,
            output = socket.outputStream,
            closeSocket = { runCatching { socket.close() } },
        )
        addLink(link)
        sendHello(link)
        io.execute { readLoop(link) }
    }

    private fun adoptTcp(socket: Socket) {
        socket.soTimeout = 60_000
        val address = socket.inetAddress?.hostAddress ?: "tcp"
        val link = Link(
            id = nextLinkId.getAndIncrement(),
            kind = "wifi",
            address = address,
            maxBytes = NearbyLimits.WIFI_MAX_BYTES,
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            closeSocket = { runCatching { socket.close() } },
        )
        addLink(link)
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
                val batch = decoder.push(buffer, count, link.maxBytes)
                if (batch.overflow) {
                    Log.i(TAG, "Closing nearby link that advertised an oversized frame")
                    break
                }
                for (frame in batch.frames) {
                    handleFrame(link, frame)
                }
            }
        } catch (error: IOException) {
            Log.i(TAG, "Nearby link closed: ${error.message}")
        } finally {
            removeLink(link)
        }
    }

    private fun handleFrame(link: Link, frame: NearbyFrame) {
        when (frame.type) {
            NearbyLimits.TYPE_HELLO -> Unit
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
            sendOwned = policy.nearbySendOwned,
            fetchMissing = policy.nearbyFetchMissing,
            fetchedBytes = fetchedBytesToday(policy.nearbyFetchedDay, policy.nearbyFetchedBytes, now),
            capBytes = nearbyCapBytes(policy.nearbyDailyCapMb),
            networkAllowed = connectivity.isAllowed(policy.networkData),
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
        if (payload != null && send(link, NearbyLimits.TYPE_CONTRACT, payload)) return
        val reason = if (parsed.status == "local" || parsed.status == "fetched") {
            NearbyLimits.REFUSE_TOO_LARGE
        } else {
            refuseForExportStatus(parsed.status)
        }
        send(link, NearbyLimits.TYPE_REFUSE, byteArrayOf(reason.toByte()))
    }

    private fun importContract(bytes: ByteArray) {
        if (bytes.size > NearbyLimits.WIFI_MAX_BYTES) {
            onAsk("That contract is too large.")
            return
        }
        val file = File(appContext.cacheDir, "nearby-in-${System.nanoTime()}.bin")
        try {
            file.writeBytes(bytes)
            val json = NativeBridge.nearbyImportContract(NearbyLimits.WEBSOCKET_PORT, file.absolutePath)
            onAsk(parseNearbyImportMessage(json))
        } catch (error: IOException) {
            onAsk("This phone could not save the contract.")
            Log.i(TAG, "Nearby import write failed: ${error.message}")
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun sendHello(link: Link) {
        val name = (android.os.Build.MODEL ?: "phone").take(40).encodeToByteArray()
        send(link, NearbyLimits.TYPE_HELLO, name)
    }

    private fun send(link: Link, type: Int, payload: ByteArray): Boolean {
        if (!link.open) return false
        if (type == NearbyLimits.TYPE_CONTRACT && payload.size > link.maxBytes) return false
        val frame = encodeNearbyFrame(type, payload)
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
        val maxBytes: Int,
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
        const val ASK_TIMEOUT_MS = 90_000L
    }
}
