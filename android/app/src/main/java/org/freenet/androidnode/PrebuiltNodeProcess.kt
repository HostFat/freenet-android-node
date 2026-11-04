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

    private val lock = Any()
    private var process: Process? = null
    private var logFile: File? = null
    private var mode = NodeMode.Local
    private var udpPort: Int? = null
    private var startedAtElapsed = 0L
    private var lastExitCode: Int? = null
    private var lastDetail = "Prebuilt Freenet process is not running"

    fun start(context: Context, configJson: String, network: Boolean): Result<String> = runCatching {
        stop()
        val config = JSONObject(configJson)
        val binary = File(context.applicationInfo.nativeLibraryDir, "libfreenet.so")
        require(binary.isFile) {
            "Prebuilt freenet binary is missing from ${binary.parent}"
        }
        binary.setExecutable(true)

        val configDir = File(config.getString("configurationDirectory")).apply { mkdirs() }
        val logDir = File(config.getString("logDirectory")).apply { mkdirs() }
        val websocketPort = config.optInt("websocketPort", 7509)
        val customPort = config.optString("udpPortMode") == "custom"

        // Connection limits are config.toml keys, not CLI flags in 0.2.155.
        // The process starts with the node defaults until a complete config is written.
        val arguments = mutableListOf(
            binary.absolutePath,
            if (network) "network" else "local",
            "--config-dir", configDir.absolutePath,
            "--ws-api-address", "127.0.0.1",
            "--ws-api-port", websocketPort.toString(),
            "--disable-auto-update",
        )
        if (network && customPort) {
            arguments += listOf("--network-port", config.optInt("udpPort", UdpPorts.Default).toString())
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
        Log.i(TAG, "started ${if (network) "network" else "local"} process")
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

    fun updateConnectivity(@Suppress("UNUSED_PARAMETER") json: String): Result<String> =
        Result.success("""{"ok":true,"data":"connectivity is owned by the OS process"}""")

    fun appendInfoLog(detail: String) {
        Log.i(TAG, detail)
        val file = synchronized(lock) { logFile } ?: return
        runCatching { file.appendText("$detail\n") }
    }

    private fun readerThread(started: Process, nodeLog: File) {
        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(lock) { lastDetail = line.take(400) }
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
                lastDetail = "Prebuilt Freenet process exited with code $code"
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
