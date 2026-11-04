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

        // The node generates a complete config.toml on first boot. Do not write
        // a partial file: it would fail the required-field parser. Remove the
        // partial file left by the earlier exploratory build.
        val configFile = File(configDir, "config.toml")
        if (configFile.isFile && !configFile.readText().contains("log_level")) {
            configFile.delete()
        }
        if (network && customPort) {
            // A custom port still needs the file; the node fills the rest on boot.
            // Passing it as a flag is not supported in v0.2.155.
            Log.i(TAG, "custom network port is ignored until the node writes its config")
        }

        // The musl binary cannot use Android's DNS resolver, so it fails to
        // fetch the gateway index. Download it with the app's TLS stack and
        // hand the node a local file instead.
        val gatewaysFile = File(configDir, "gateways.toml")
        if (network && (!gatewaysFile.isFile || gatewaysFile.length() == 0L)) {
            appendInfoLog("fetching $GATEWAYS_INDEX")
            val downloader = Thread({
                runCatching {
                    val index = httpGet(GATEWAYS_INDEX, 5_000)
                    require(index.isNotBlank()) { "empty gateway index" }
                    gatewaysFile.writeText(index)
                    val keyPaths = Regex("""public[_-]key\s*=\s*"([^"]+)"""")
                        .findAll(index)
                        .map { it.groupValues[1] }
                        .toList()
                    for (path in keyPaths) {
                        val target = File(configDir, path).canonicalFile
                        val root = configDir.canonicalPath + File.separator
                        require(target.path.startsWith(root)) { "unsafe key path $path" }
                        target.parentFile?.mkdirs()
                        val pem = httpGet("https://freenet.org/$path", 5_000)
                        require(pem.isNotBlank()) { "empty key $path" }
                        target.writeText(pem)
                        appendInfoLog("gateway key $path (${pem.length} bytes)")
                    }
                    appendInfoLog("gateways.toml and ${keyPaths.size} keys downloaded")
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
        val hasLocalGateways = network && gatewaysFile.isFile && gatewaysFile.length() > 0L
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
