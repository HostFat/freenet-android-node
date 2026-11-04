package org.freenet.androidnode

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Experimental backend: runs the official `freenet` binary, shipped as
 * `libfreenet.so` in jniLibs, as a child process. It speaks the same JSON
 * envelope as the JNI bridge so NodeService and NodeRepository stay unchanged.
 */
internal object PrebuiltNodeProcess {
    private const val TAG = "PrebuiltNode"
    private const val STARTUP_GRACE_MS = 8_000L
    private const val STOP_TIMEOUT_SECONDS = 10L
    private const val RECENT_LINES = 200
    private const val GATEWAYS_INDEX = "https://freenet.org/keys/gateways.toml"

    private val lock = Any()
    private var process: Process? = null
    private var logFile: File? = null
    private var mode = NodeMode.Local
    private var udpPort: Int? = null
    private var startedAtElapsed = 0L
    private var lastExitCode: Int? = null
    private var lastDetail = "Prebuilt Freenet process is not running"
    private val recentLines = ArrayDeque<String>()

    fun start(context: Context, configJson: String, network: Boolean): Result<String> = runCatching {
        appendInfoLog("start requested network=$network")
        stop()
        val config = JSONObject(configJson)
        val binary = File(context.applicationInfo.nativeLibraryDir, "libfreenet.so")
        require(binary.isFile) {
            "Prebuilt freenet binary is missing from ${binary.parent}"
        }
        binary.setExecutable(true)
        appendInfoLog("binary=${binary.absolutePath} exists=${binary.isFile}")

        // Use a directory owned by this backend so a config left by the JNI
        // path cannot interfere, and create every path the node opens.
        val root = File(context.filesDir, "freenet/prebuilt").apply { mkdirs() }
        val configDir = File(root, "config").apply { mkdirs() }
        val dataDir = File(root, "data").apply { mkdirs() }
        val logDir = File(root, "logs").apply { mkdirs() }
        synchronized(lock) { logFile = File(logDir, "node-process.log") }
        appendInfoLog(
            "dirs config=${configDir.isDirectory} data=${dataDir.isDirectory} " +
                "logs=${logDir.isDirectory}",
        )
        val websocketPort = config.optInt("websocketPort", 7509)
        val customPort = config.optString("udpPortMode") == "custom"

        // The node loads config.toml and does not create it. Write a complete
        // file once; a partial file fails the required-field parser.
        val contractsDir = File(dataDir, "contracts").apply { mkdirs() }
        val delegatesDir = File(dataDir, "delegates").apply { mkdirs() }
        val secretsDir = File(dataDir, if (network) "secrets" else "secrets_local").apply { mkdirs() }
        val dbDir = File(dataDir, "db").apply { mkdirs() }
        val eventLogDir = File(dataDir, "_EVENT_LOG").apply { mkdirs() }
        val wasmtimeDir = File(context.cacheDir, "wasmtime").apply { mkdirs() }
        val configFile = File(configDir, "config.toml")
        if (!configFile.isFile || !configFile.readText().contains("log_level")) {
            configFile.writeText(
                completeConfig(
                    network = network,
                    configDir = configDir,
                    dataDir = dataDir,
                    logDir = logDir,
                    contractsDir = contractsDir,
                    delegatesDir = delegatesDir,
                    secretsDir = secretsDir,
                    dbDir = dbDir,
                    eventLogDir = eventLogDir,
                    wasmtimeDir = wasmtimeDir,
                    websocketPort = websocketPort,
                    networkPort = if (customPort) {
                        config.optInt("udpPort", UdpPorts.Default)
                    } else {
                        UdpPorts.Default
                    },
                    minConnections = config.optInt(
                        "minConnections",
                        ConnectionLimits.DefaultMin,
                    ),
                    maxConnections = config.optInt(
                        "maxConnections",
                        ConnectionLimits.DefaultMax,
                    ),
                ),
            )
            appendInfoLog("config.toml written (${configFile.length()} bytes)")
        }

        // The musl binary cannot use Android's DNS resolver, so it fails to
        // fetch the gateway index. Download it with the app's TLS stack and
        // hand the node a local file instead.
        val gatewaysFile = File(configDir, "gateways.toml")
        val gatewaysReady = java.util.concurrent.atomic.AtomicBoolean(false)
        if (network) {
            appendInfoLog("checking gateway files")
            val downloader = Thread({
                runCatching {
                    gatewaysReady.set(ensureGateways(configDir, context.filesDir))
                }.onFailure {
                    appendInfoLog("gateway download failed: ${it.message}")
                }
            }, "gateways-download").apply {
                isDaemon = true
                start()
            }
            downloader.join(25_000)
            if (downloader.isAlive) {
                appendInfoLog("gateway download timed out")
                downloader.interrupt()
            }
        }
        val hasLocalGateways = network && gatewaysReady.get()
        Log.i(
            TAG,
            "config dir=${configDir.absolutePath} exists=${configDir.isDirectory} " +
                "file=${configFile.absolutePath} exists=${configFile.isFile} " +
                "data=${dataDir.isDirectory} logs=${logDir.isDirectory}",
        )

        val arguments = mutableListOf(
            binary.absolutePath,
            if (network) "network" else "local",
            "--config-dir", configDir.absolutePath,
            "--data-dir", dataDir.absolutePath,
            "--log-dir", logDir.absolutePath,
            "--ws-api-address", "127.0.0.1",
            "--ws-api-port", websocketPort.toString(),
            "--disable-auto-update",
        )
        if (network && customPort) {
            arguments += listOf("--network-port", config.optInt("udpPort", UdpPorts.Default).toString())
        }
        if (hasLocalGateways) {
            arguments += "--skip-load-from-network"
        }

        val builder = ProcessBuilder(arguments)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("XDG_CONFIG_HOME", File(context.filesDir, "freenet/config").absolutePath)
            put("XDG_DATA_HOME", File(context.filesDir, "freenet").absolutePath)
            put("XDG_CACHE_HOME", context.cacheDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("RUST_LOG", "debug")
            put("RUST_BACKTRACE", "1")
        }

        appendInfoLog("starting process: ${arguments.joinToString(" ")}")
        val started = builder.start()
        val nodeLog = File(logDir, "node-process.log")
        synchronized(lock) {
            process = started
            logFile = nodeLog
            mode = if (network) NodeMode.Network else NodeMode.Local
            udpPort = if (network) config.optInt("udpPort").takeIf { it > 0 } else null
            startedAtElapsed = SystemClock.elapsedRealtime()
            lastExitCode = null
            lastDetail = "Prebuilt Freenet process started"
        }
        readerThread(started, nodeLog)
        exitThread(started)
        appendInfoLog("process started")
        successEnvelope("Starting", "Prebuilt Freenet process started")
    }

    fun stop(): Result<String> = runCatching {
        val current = synchronized(lock) { process }
        if (current == null || !current.isAlive) {
            synchronized(lock) { process = null }
            return@runCatching successEnvelope("Stopped", "Prebuilt Freenet process was already stopped")
        }
        current.destroy()
        if (!current.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            current.destroyForcibly()
            current.waitFor(5, TimeUnit.SECONDS)
        }
        synchronized(lock) { process = null }
        successEnvelope("Stopped", "Prebuilt Freenet process stopped")
    }

    fun status(): Result<String> = runCatching {
        val snapshot = synchronized(lock) {
            Snapshot(
                alive = process?.isAlive == true,
                exitCode = lastExitCode,
                startedAt = startedAtElapsed,
                mode = mode,
                udpPort = udpPort,
                detail = lastDetail,
            )
        }
        when {
            !snapshot.alive && snapshot.exitCode != null ->
                errorEnvelope("Prebuilt Freenet process exited with code ${snapshot.exitCode}")
            !snapshot.alive ->
                errorEnvelope("Prebuilt Freenet process is not running")
            else -> {
                val elapsed = SystemClock.elapsedRealtime() - snapshot.startedAt
                val state = if (elapsed < STARTUP_GRACE_MS) {
                    "Starting"
                } else if (snapshot.mode == NodeMode.Network) {
                    "RunningNetwork"
                } else {
                    "RunningLocal"
                }
                successEnvelope(state, snapshot.detail, snapshot.mode, snapshot.udpPort)
            }
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { process?.isAlive == true }

    fun recentLogs(limit: Int): String {
        val buffered = synchronized(lock) { recentLines.toList() }
        if (buffered.isNotEmpty()) {
            return buffered.takeLast(limit).joinToString("\n")
        }
        val file = synchronized(lock) { logFile }
        if (file == null || !file.isFile) {
            return "No process output yet."
        }
        return runCatching {
            file.readLines().takeLast(limit).joinToString("\n")
        }.getOrElse { "Could not read ${file.absolutePath}: ${it.message}" }
    }

    fun updateConnectivity(@Suppress("UNUSED_PARAMETER") json: String): Result<String> =
        Result.success("""{"ok":true,"data":"connectivity is owned by the OS process"}""")

    fun appendInfoLog(detail: String) {
        Log.i(TAG, detail)
        synchronized(lock) {
            recentLines.addLast(detail)
            if (recentLines.size > RECENT_LINES) {
                recentLines.removeFirst()
            }
        }
        val file = synchronized(lock) { logFile } ?: return
        runCatching { file.appendText("$detail\n") }
    }

    /**
     * Ensure gateways.toml and every key it references exist. The node resolves
     * the relative key paths against its working directory, while the index is
     * read from --config-dir, so write the keys under both roots.
     */
    private fun ensureGateways(configDir: File, workingDir: File): Boolean {
        val gatewaysFile = File(configDir, "gateways.toml")
        val rawIndex = if (gatewaysFile.isFile && gatewaysFile.length() > 0L) {
            gatewaysFile.readText()
        } else {
            appendInfoLog("fetching $GATEWAYS_INDEX")
            val body = httpGet(GATEWAYS_INDEX, 5_000)
            require(body.isNotBlank()) { "empty gateway index" }
            body
        }
        // The node's resolver reads /etc/resolv.conf, absent on Android, and
        // fails before it can resolve a gateway hostname. Resolve here with the
        // Android DNS stack and persist the IP in the file.
        val index = resolveGatewayHosts(rawIndex)
        if (index != gatewaysFile.readText()) {
            gatewaysFile.writeText(index)
            appendInfoLog("gateways.toml written (${index.length} bytes)")
        }
        val keyPaths = Regex("""public[_-]key\s*=\s*"([^"]+)"""")
            .findAll(index)
            .map { it.groupValues[1] }
            .toList()
        require(keyPaths.isNotEmpty()) { "no gateway keys in the index" }
        val roots = listOf(configDir, workingDir).distinctBy { it.canonicalPath }
        var downloaded = 0
        var copied = 0
        for (path in keyPaths) {
            val present = roots.map { File(it, path) }
            if (present.all { it.isFile && it.length() > 0L }) {
                continue
            }
            val local = present.firstOrNull { it.isFile && it.length() > 0L }
            val pem = if (local != null) {
                local.readText()
            } else {
                downloaded += 1
                httpGet("https://freenet.org/$path", 5_000)
            }
            require(pem.isNotBlank()) { "empty key $path" }
            for (root in roots) {
                val target = File(root, path).canonicalFile
                val prefix = root.canonicalPath + File.separator
                require(target.path.startsWith(prefix)) { "unsafe key path $path" }
                target.parentFile?.mkdirs()
                target.writeText(pem)
            }
            if (local != null) {
                copied += 1
                appendInfoLog("gateway key $path copied to both roots")
            } else {
                appendInfoLog("gateway key $path (${pem.length} bytes)")
            }
        }
        appendInfoLog(
            "gateway files ready: ${keyPaths.size} keys, $downloaded downloaded, $copied copied",
        )
        return keyPaths.all { path ->
            roots.all { root ->
                val target = File(root, path)
                target.isFile && target.length() > 0L
            }
        }
    }

    /**
     * Mirrors the fields a released config.toml must carry. Secret paths are
     * deliberately omitted: the node resolves them from secrets_dir and
     * generates the key material there.
     */
    private fun completeConfig(
        network: Boolean,
        configDir: File,
        dataDir: File,
        logDir: File,
        contractsDir: File,
        delegatesDir: File,
        secretsDir: File,
        dbDir: File,
        eventLogDir: File,
        wasmtimeDir: File,
        websocketPort: Int,
        networkPort: Int,
        minConnections: Int,
        maxConnections: Int,
    ): String = buildString {
        appendLine("mode = \"${if (network) "network" else "local"}\"")
        appendLine("network-address = \"${if (network) "0.0.0.0" else "127.0.0.1"}\"")
        appendLine("network-port = $networkPort")
        appendLine("min-number-of-connections = $minConnections")
        appendLine("max-number-of-connections = $maxConnections")
        appendLine("skip_load_from_network = true")
        appendLine("ws-api-address = \"127.0.0.1\"")
        appendLine("ws-api-port = $websocketPort")
        appendLine("log_level = \"info\"")
        appendLine("is_gateway = false")
        appendLine("enable-event-log = false")
        appendLine("telemetry-enabled = false")
        appendLine("max_blocking_threads = 4")
        appendLine("config_dir = \"${configDir.absolutePath}\"")
        appendLine("data_dir = \"${dataDir.absolutePath}\"")
        appendLine("log_dir = \"${logDir.absolutePath}\"")
        appendLine("contracts_dir = \"${contractsDir.absolutePath}\"")
        appendLine("delegates_dir = \"${delegatesDir.absolutePath}\"")
        appendLine("secrets_dir = \"${secretsDir.absolutePath}\"")
        appendLine("db_dir = \"${dbDir.absolutePath}\"")
        appendLine("event_log = \"${eventLogDir.absolutePath}\"")
        appendLine("wasmtime_cache_dir = \"${wasmtimeDir.absolutePath}\"")
    }

    private fun resolveGatewayHosts(toml: String): String =
        Regex("""hostname\s*=\s*"([^":]+):(\d+)"""").replace(toml) { match ->
            val host = match.groupValues[1]
            val port = match.groupValues[2]
            val ip = runCatching {
                java.net.InetAddress.getByName(host).hostAddress
            }.getOrNull()
            if (ip != null) {
                appendInfoLog("resolved $host -> $ip")
                "hostname = \"$ip:$port\""
            } else {
                match.value
            }
        }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val connection = (java.net.URL(url).openConnection()
            as java.net.HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "freenet-android-node")
        }
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun readerThread(started: Process, nodeLog: File) {
        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(lock) {
                            lastDetail = line.take(400)
                            recentLines.addLast(line)
                            if (recentLines.size > RECENT_LINES) {
                                recentLines.removeFirst()
                            }
                        }
                        runCatching { nodeLog.appendText("$line\n") }
                        Log.i(TAG, line)
                    }
                }
            }
        }, "freenet-process-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun exitThread(started: Process) {
        Thread({
            val code = runCatching { started.waitFor() }.getOrDefault(-1)
            synchronized(lock) {
                lastExitCode = code
                val tail = recentLines.takeLast(3).joinToString(" | ")
                lastDetail = buildString {
                    append("Prebuilt Freenet process exited with code ")
                    append(code)
                    if (tail.isNotBlank()) {
                        append(": ")
                        append(tail.take(600))
                    }
                }
            }
            Log.i(TAG, "process exited with code $code")
        }, "freenet-process-wait").apply {
            isDaemon = true
            start()
        }
    }

    private fun successEnvelope(
        state: String,
        detail: String,
        nodeMode: NodeMode = mode,
        port: Int? = null,
    ): String {
        val data = JSONObject()
            .put("state", state)
            .put("detail", detail)
            .put("completedStartCycles", 1)
            .put("mode", nodeMode.name)
            .put("peerCount", 0)
            .put("connectionAttempts", 0)
            .put("successfulConnections", 0)
            .put("bytesSent", 0)
            .put("bytesReceived", 0)
            .put("currentNetworkType", "Unavailable")
            .put("connectivityAvailable", true)
            .put("networkMetered", false)
            .put("vpnActive", false)
        if (port != null) {
            data.put("udpPort", port)
        }
        return JSONObject().put("ok", true).put("data", data).toString()
    }

    private fun errorEnvelope(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("data", JSONObject.NULL)
            .put(
                "error",
                JSONObject()
                    .put("code", "PROCESS_NOT_RUNNING")
                    .put("message", message),
            )
            .toString()

    private data class Snapshot(
        val alive: Boolean,
        val exitCode: Int?,
        val startedAt: Long,
        val mode: NodeMode,
        val udpPort: Int?,
        val detail: String,
    )
}
