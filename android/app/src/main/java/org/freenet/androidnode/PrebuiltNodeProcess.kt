package org.freenet.androidnode

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Runs the official `freenet` binary, shipped as `libfreenet.so` in jniLibs,
 * as a child process in network mode. It speaks the same JSON envelope as the
 * JNI bridge so NodeService and NodeRepository stay uniform.
 */
internal object PrebuiltNodeProcess {
    private const val TAG = "PrebuiltNode"
    private const val STARTUP_GRACE_MS = 8_000L
    private const val STOP_TIMEOUT_SECONDS = 4L
    private const val RECENT_LINES = 200
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024
    private const val MAX_TAIL_BYTES = 64 * 1024
    private const val MAX_HTTP_CHARS = 1024 * 1024
    private const val GATEWAYS_INDEX = "https://freenet.org/keys/gateways.toml"
    private val GATEWAY_KEY = Regex("""public[_-]key\s*=\s*"[^"]+"""")

    private val lock = Any()
    private val logLock = Any()
    private var process: Process? = null
    private var logFile: File? = null
    private var logWriter: BufferedWriter? = null
    private var logBytesWritten: Long = 0L
    private var udpPort: Int? = null
    private var startedAtElapsed = 0L
    private var completedStartCycles = 0L
    private var cycleCountedForCurrent = false
    private var lastExitCode: Int? = null
    private var lastDetail = "Prebuilt Freenet process is not running"
    private var pidFile: File? = null
    private val recentLines = ArrayDeque<String>()
    @Volatile
    private var stopRequested = false

    fun start(context: Context, configJson: String): Result<String> = runCatching {
        appendInfoLog("start requested (network)")
        stop()
        val pidFile = File(context.filesDir, "freenet/prebuilt/node.pid")
        killOrphan(pidFile)
        stopRequested = false
        val config = JSONObject(configJson)
        val binary = File(context.applicationInfo.nativeLibraryDir, "libfreenet.so")
        require(binary.isFile) {
            "Prebuilt freenet binary is missing from ${binary.parent}"
        }
        binary.setExecutable(true)
        appendInfoLog("binary=${binary.absolutePath} exists=${binary.isFile}")

        val root = File(context.filesDir, "freenet/prebuilt").apply { mkdirs() }
        synchronized(lock) { this.pidFile = pidFile }
        val configDir = File(root, "config").apply { mkdirs() }
        val dataDir = File(root, "data").apply { mkdirs() }
        val logDir = File(root, "logs").apply { mkdirs() }
        val nodeLog = File(logDir, "node-process.log")
        synchronized(logLock) {
            closeLogWriterLocked()
            logFile = nodeLog
            if (nodeLog.length() > MAX_LOG_BYTES * 2) {
                nodeLog.delete()
            }
            logBytesWritten = if (nodeLog.isFile) nodeLog.length() else 0L
        }
        appendInfoLog(
            "dirs config=${configDir.isDirectory} data=${dataDir.isDirectory} " +
                "logs=${logDir.isDirectory}",
        )
        val websocketPort = config.optInt("websocketPort", 7509)
        if (portIsListening(websocketPort)) {
            val detail = "Port $websocketPort is already in use. Another Freenet node, " +
                "possibly another app, is running. Close it and retry."
            synchronized(lock) { lastDetail = detail }
            ServiceEventLog.record("start blocked: port $websocketPort in use")
            return@runCatching errorEnvelope(detail, code = "PORT_IN_USE")
        }
        val udpPortMode = UdpPortMode.entries
            .find { it.name.equals(config.optString("udpPortMode"), ignoreCase = true) }
            ?: UdpPortMode.Random
        val customPort = udpPortMode == UdpPortMode.Custom
        val coercedCustomPort = UdpPorts.coerce(config.optInt("udpPort", UdpPorts.Default))

        val contractsDir = File(dataDir, "contracts").apply { mkdirs() }
        val delegatesDir = File(dataDir, "delegates").apply { mkdirs() }
        val secretsDir = File(dataDir, "secrets").apply { mkdirs() }
        val dbDir = File(dataDir, "db").apply { mkdirs() }
        val eventLogDir = File(dataDir, "_EVENT_LOG").apply { mkdirs() }
        val wasmtimeDir = File(context.cacheDir, "wasmtime").apply { mkdirs() }
        listOf(
            File(contractsDir, "network"),
            File(delegatesDir, "network"),
            File(dbDir, "network"),
        ).forEach { it.mkdirs() }
        val minConnections = config.optInt("minConnections", ConnectionLimits.DefaultMin)
        val maxConnections = config.optInt("maxConnections", ConnectionLimits.DefaultMax)
        val configFile = File(configDir, "config.toml")
        val runtimePrefs = context.getSharedPreferences(
            NodeBackup.PREFS,
            Context.MODE_PRIVATE,
        )
        val previousCore = runtimePrefs.getString(NodeBackup.CORE_VERSION_KEY, "").orEmpty()
        if (previousCore != BuildConfig.CORE_VERSION) {
            appendInfoLog(
                "core changed from '$previousCore' to '${BuildConfig.CORE_VERSION}'; " +
                    "regenerating config and gateways",
            )
            configFile.delete()
            File(configDir, "gateways.toml").delete()
            File(configDir, "keys").deleteRecursively()
            runtimePrefs.edit()
                .putString(NodeBackup.CORE_VERSION_KEY, BuildConfig.CORE_VERSION)
                .apply()
        }
        if (!configFile.isFile || !configFile.readText().contains("log_level")) {
            configFile.writeText(
                completeConfig(
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
                    networkPort = if (customPort) coercedCustomPort else null,
                    minConnections = minConnections,
                    maxConnections = maxConnections,
                ),
            )
            appendInfoLog("config.toml written (${configFile.length()} bytes)")
        } else {
            ensureNetworkConfigFields(configFile, secretsDir, minConnections, maxConnections)
            appendInfoLog(
                "connection limits set min=$minConnections max=$maxConnections",
            )
        }
        applyNetworkPortMode(configFile, udpPortMode, coercedCustomPort)

        val effectiveUdpPort = when (udpPortMode) {
            UdpPortMode.Custom -> coercedCustomPort
            UdpPortMode.Saved -> ConfigToml.parseInt(configFile.readText(), ConfigToml.NETWORK_PORT_KEY)
            UdpPortMode.Random -> null
        }

        // The musl binary cannot use Android's DNS resolver, so download the
        // gateway index with the app's TLS stack and resolve hostnames here.
        val gatewaysReady = java.util.concurrent.atomic.AtomicBoolean(false)
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
        if (!gatewaysReady.get()) {
            appendInfoLog("gateways.toml is not ready; refusing to start the node")
            return Result.failure(
                IllegalStateException("Gateway file is not ready; check the network and retry"),
            )
        }
        Log.i(
            TAG,
            "config dir=${configDir.absolutePath} exists=${configDir.isDirectory} " +
                "file=${configFile.absolutePath} exists=${configFile.isFile} " +
                "data=${dataDir.isDirectory} logs=${logDir.isDirectory}",
        )

        val arguments = mutableListOf(
            binary.absolutePath,
            "network",
            "--config-dir", configDir.absolutePath,
            "--data-dir", dataDir.absolutePath,
            "--log-dir", logDir.absolutePath,
            "--ws-api-address", "127.0.0.1",
            "--ws-api-port", websocketPort.toString(),
            "--disable-auto-update",
            "--skip-load-from-network",
        )
        if (customPort) {
            arguments += listOf("--network-port", coercedCustomPort.toString())
        }

        val command = mutableListOf(
            "/system/bin/sh",
            "-c",
            "echo $$ > \"$1\"; shift; exec \"$@\"",
            "sh",
            pidFile.absolutePath,
        )
        command += arguments
        val builder = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("XDG_CONFIG_HOME", File(context.filesDir, "freenet/config").absolutePath)
            put("XDG_DATA_HOME", File(context.filesDir, "freenet").absolutePath)
            put("XDG_CACHE_HOME", context.cacheDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }

        appendInfoLog("starting process: ${arguments.joinToString(" ")}")
        val started = builder.start()
        synchronized(lock) {
            process = started
            udpPort = effectiveUdpPort
            startedAtElapsed = SystemClock.elapsedRealtime()
            cycleCountedForCurrent = false
            lastExitCode = null
            lastDetail = "Prebuilt Freenet process started"
        }
        readerThread(started)
        exitThread(started)
        appendInfoLog("process started")
        successEnvelope("Starting", "Prebuilt Freenet process started")
    }

    fun stop(): Result<String> = runCatching {
        stopRequested = true
        val current = synchronized(lock) { process }
        if (current == null || !current.isAlive) {
            synchronized(lock) {
                process = null
                lastExitCode = null
                lastDetail = "Prebuilt Freenet process is not running"
                pidFile?.delete()
            }
            synchronized(logLock) { flushLogWriterLocked() }
            return@runCatching successEnvelope("Stopped", "Prebuilt Freenet process was already stopped")
        }
        current.destroy()
        if (!current.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            current.destroyForcibly()
            current.waitFor(2, TimeUnit.SECONDS)
        }
        synchronized(lock) {
            if (process === current) {
                process = null
            }
            lastExitCode = null
            lastDetail = "Prebuilt Freenet process stopped"
            pidFile?.delete()
        }
        synchronized(logLock) { flushLogWriterLocked() }
        successEnvelope("Stopped", "Prebuilt Freenet process stopped")
    }

    fun status(): Result<String> = runCatching {
        val snapshot = synchronized(lock) {
            val alive = process?.isAlive == true
            val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
            if (alive && elapsed >= STARTUP_GRACE_MS && !cycleCountedForCurrent) {
                completedStartCycles += 1
                cycleCountedForCurrent = true
            }
            Snapshot(
                alive = alive,
                exitCode = lastExitCode,
                startedAt = startedAtElapsed,
                udpPort = udpPort,
                completedStartCycles = completedStartCycles,
                detail = lastDetail,
            )
        }
        when {
            !snapshot.alive && stopRequested ->
                successEnvelope("Stopped", "Prebuilt Freenet process stopped", snapshot.completedStartCycles)
            !snapshot.alive && snapshot.exitCode != null ->
                errorEnvelope("Prebuilt Freenet process exited with code ${snapshot.exitCode}")
            !snapshot.alive ->
                errorEnvelope("Prebuilt Freenet process is not running")
            else -> {
                val elapsed = SystemClock.elapsedRealtime() - snapshot.startedAt
                val starting = elapsed < STARTUP_GRACE_MS
                val state = if (starting) "Starting" else "RunningNetwork"
                val detail = if (starting) {
                    "Prebuilt Freenet process is starting"
                } else {
                    "Prebuilt Freenet process is running"
                }
                successEnvelope(state, detail, snapshot.completedStartCycles, snapshot.udpPort)
            }
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { process?.isAlive == true }

    fun lastExitDetail(): String? = synchronized(lock) {
        lastDetail.takeIf {
            it != "Prebuilt Freenet process is not running" &&
                it != "Prebuilt Freenet process started" &&
                it != "Prebuilt Freenet process stopped"
        }
    }

    fun recentLogs(limit: Int): String {
        val buffered = synchronized(lock) { recentLines.toList() }
        if (buffered.isNotEmpty()) {
            return buffered.takeLast(limit).joinToString("\n")
        }
        val file = synchronized(logLock) {
            flushLogWriterLocked()
            logFile
        }
        if (file == null || !file.isFile) {
            return "No process output yet."
        }
        return filterLogNoise(readLogTail(file, MAX_TAIL_BYTES * 4))
            .lines()
            .takeLast(limit)
            .joinToString("\n")
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
        writeLogLine(detail, flush = true)
    }

    private fun writeLogLine(line: String, flush: Boolean) {
        synchronized(logLock) {
            val file = logFile ?: return
            runCatching {
                val payload = "$line\n"
                val payloadBytes = payload.toByteArray(Charsets.UTF_8).size.toLong()
                if (logBytesWritten + payloadBytes > MAX_LOG_BYTES) {
                    closeLogWriterLocked()
                    val previous = File(file.parentFile, "node-process.log.1")
                    previous.delete()
                    file.renameTo(previous)
                    logBytesWritten = 0L
                }
                val writer = logWriter ?: BufferedWriter(
                    OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8),
                    16 * 1024,
                ).also { logWriter = it }
                writer.write(payload)
                logBytesWritten += payloadBytes
                if (flush) {
                    writer.flush()
                }
            }.onFailure {
                closeLogWriterLocked()
            }
        }
    }

    private fun flushLogWriterLocked() {
        runCatching { logWriter?.flush() }
    }

    private fun closeLogWriterLocked() {
        runCatching { logWriter?.flush() }
        runCatching { logWriter?.close() }
        logWriter = null
    }

    private fun ensureGateways(configDir: File, workingDir: File): Boolean {
        configDir.mkdirs()
        val gatewaysFile = File(configDir, "gateways.toml")
        appendInfoLog(
            "gateway write target: dir=${configDir.absolutePath} " +
                "exists=${configDir.exists()} isDirectory=${configDir.isDirectory} " +
                "canWrite=${configDir.canWrite()}",
        )
        val savedIndex = if (gatewaysFile.isFile && gatewaysFile.length() > 0L) {
            gatewaysFile.readText()
        } else {
            ""
        }
        appendInfoLog("fetching $GATEWAYS_INDEX")
        val fetchedIndex = runCatching { httpGet(GATEWAYS_INDEX, 5_000) }
            .onFailure { appendInfoLog("gateway index download failed: ${it.message}") }
            .getOrDefault("")
        val rawIndex = listOf(fetchedIndex, savedIndex)
            .firstOrNull { it.isNotBlank() && GATEWAY_KEY.containsMatchIn(it) }
        require(rawIndex != null) { "gateway index is unavailable and no saved copy exists" }
        val index = resolveGatewayHosts(rawIndex)
        val current = if (gatewaysFile.isFile) gatewaysFile.readText() else ""
        if (index != current) {
            writeGatewayFile(configDir, gatewaysFile, index)
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

    private fun completeConfig(
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
        networkPort: Int?,
        minConnections: Int,
        maxConnections: Int,
    ): String = buildString {
        appendLine("mode = \"network\"")
        appendLine("network-address = \"0.0.0.0\"")
        if (networkPort != null) {
            appendLine("network-port = $networkPort")
        }
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

    fun killOrphan(context: Context) {
        killOrphan(File(context.filesDir, "freenet/prebuilt/node.pid"))
    }

    private fun killOrphan(pidFile: File) {
        if (!pidFile.isFile) return
        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
        pidFile.delete()
        if (pid == null || pid <= 0 || pid == android.os.Process.myPid()) return
        val entry = File("/proc/$pid")
        if (!entry.exists()) return
        val cmdline = runCatching {
            File(entry, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
        }.getOrDefault("")
        if (!isFreenetProcessCmdline(cmdline)) {
            appendInfoLog("ignoring stale pid=$pid (not freenet: ${cmdline.take(80)})")
            return
        }
        appendInfoLog("terminating orphan node pid=$pid")
        runCatching { android.os.Process.sendSignal(pid, 15) }
        Thread.sleep(2_000)
        if (entry.exists()) {
            val stillFreenet = runCatching {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            }.getOrDefault("")
            if (isFreenetProcessCmdline(stillFreenet)) {
                runCatching { android.os.Process.sendSignal(pid, 9) }
                Thread.sleep(500)
            }
        }
    }

    internal fun isFreenetProcessCmdline(cmdline: String): Boolean =
        cmdline.contains("libfreenet.so")

    private fun writeGatewayFile(configDir: File, gatewaysFile: File, index: String) {
        runCatching {
            gatewaysFile.writeText(index)
        }.onFailure { first ->
            appendInfoLog(
                "gateways.toml write failed: ${first.message}; retrying after mkdirs",
            )
            configDir.mkdirs()
            gatewaysFile.parentFile?.mkdirs()
            gatewaysFile.writeText(index)
        }
        appendInfoLog("gateways.toml written (${index.length} bytes)")
    }

    internal fun formatResolvedHostPort(ip: String, port: String): String =
        if (ip.contains(':')) "[$ip]:$port" else "$ip:$port"

    private fun resolveGatewayHosts(toml: String): String =
        Regex("""hostname\s*=\s*"([^":]+):(\d+)"""").replace(toml) { match ->
            val host = match.groupValues[1]
            val port = match.groupValues[2]
            val ip = runCatching {
                val addresses = InetAddress.getAllByName(host)
                val preferred = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
                preferred?.hostAddress
            }.getOrNull()
            if (ip != null && ip != host) {
                val formatted = formatResolvedHostPort(ip, port)
                appendInfoLog("resolved $host -> $formatted")
                "hostname = \"$formatted\""
            } else {
                match.value
            }
        }

    private fun applyNetworkPortMode(configFile: File, mode: UdpPortMode, port: Int) {
        if (!configFile.isFile) return
        val current = configFile.readText()
        val updated = ConfigToml.applyNetworkPort(current, mode, port)
        if (updated != current) {
            configFile.writeText(updated)
            appendInfoLog("network port mode ${mode.name.lowercase()} applied to config.toml")
        }
    }

    /**
     * Ensures network-mode essentials and UI-owned connection limits stay valid
     * (including healing configs left over from earlier builds that had local mode).
     */
    private fun ensureNetworkConfigFields(
        configFile: File,
        secretsDir: File,
        min: Int,
        max: Int,
    ) {
        var text = configFile.readText()
        text = ConfigToml.upsertInt(text, ConfigToml.MIN_KEY, min)
        text = ConfigToml.upsertInt(text, ConfigToml.MAX_KEY, max)
        text = Regex("""(?m)^[ \t]*mode[ \t]*=[ \t]*"local"[ \t]*$""")
            .replace(text, "mode = \"network\"")
        text = Regex("""(?m)^[ \t]*network-address[ \t]*=[ \t]*"127\.0\.0\.1"[ \t]*$""")
            .replace(text, "network-address = \"0.0.0.0\"")
        text = Regex("""(?m)^[ \t]*secrets_dir[ \t]*=[ \t]*"[^"]*secrets_local"[ \t]*$""")
            .replace(text, "secrets_dir = \"${secretsDir.absolutePath}\"")
        configFile.writeText(text)
    }

    private val ansiPattern = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    private fun stripAnsi(value: String): String = value.replace(ansiPattern, "")

    private fun httpGet(url: String, timeoutMs: Int): String {
        val connection = (java.net.URL(url).openConnection()
            as java.net.HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "freenet-android-node")
        }
        try {
            require(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode} from $url"
            }
            return connection.inputStream.bufferedReader().use { reader ->
                readBoundedText(reader, MAX_HTTP_CHARS)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readerThread(started: Process) {
        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    var written = 0
                    lines.forEach { rawLine ->
                        val line = stripAnsi(rawLine)
                        if (!isRateLimitLogLine(line)) {
                            synchronized(lock) {
                                recentLines.addLast(line)
                                if (recentLines.size > RECENT_LINES) {
                                    recentLines.removeFirst()
                                }
                            }
                        }
                        written += 1
                        writeLogLine(line, flush = written % 20 == 0)
                        if (written % 500 == 0) {
                            Log.i(TAG, line)
                        }
                    }
                }
            }
            synchronized(logLock) { flushLogWriterLocked() }
        }, "freenet-process-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun exitThread(started: Process) {
        Thread({
            val code = runCatching { started.waitFor() }.getOrDefault(-1)
            synchronized(lock) {
                if (process !== started) {
                    return@Thread
                }
                if (stopRequested) {
                    lastExitCode = null
                    lastDetail = "Prebuilt Freenet process stopped"
                } else {
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
        cycles: Long = 0L,
        port: Int? = null,
    ): String {
        val data = JSONObject()
            .put("state", state)
            .put("detail", detail)
            .put("completedStartCycles", cycles)
            .put("mode", NodeMode.Network.name)
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

    private fun portIsListening(port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
        }
        true
    }.getOrDefault(false)

    private fun errorEnvelope(
        message: String,
        code: String = "PROCESS_NOT_RUNNING",
    ): String =
        JSONObject()
            .put("ok", false)
            .put("data", JSONObject.NULL)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message),
            )
            .toString()

    private data class Snapshot(
        val alive: Boolean,
        val exitCode: Int?,
        val startedAt: Long,
        val udpPort: Int?,
        val completedStartCycles: Long,
        val detail: String,
    )
}
