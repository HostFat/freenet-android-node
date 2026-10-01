package org.freenet.androidnode

import org.json.JSONObject

object NativeBridge {
    private val loadResult = runCatching {
        System.loadLibrary("freenet_android")
    }

    val isLoaded: Boolean
        get() = loadResult.isSuccess

    val loadError: String?
        get() = loadResult.exceptionOrNull()?.message

    external fun nativePing(): String

    external fun nativeBuildInfo(): String

    external fun nativeFreenetBuildInfo(): String

    external fun nativeStartLocalNode(configJson: String): String

    external fun nativeStartNetworkNode(configJson: String): String

    external fun nativeUpdateConnectivity(connectivityJson: String): String

    external fun nativeStopNode(): String

    external fun nativeGetNodeStatus(): String

    external fun nativeGetRecentLogs(maxEntries: Int): String

    external fun nativeGetStorageStatus(configJson: String): String

    external fun nativeRunContractProof(): String

    external fun nativeVerifyContractPersistence(): String

    external fun nativeGetContractProofStatus(): String

    external fun nativeAppendInfoLog(message: String): String

    external fun nativeQueryNodeDiagnostics(websocketPort: Int): String

    external fun nativeNearbyExportContract(
        websocketPort: Int,
        contractKeyHex: String,
        outputDirectory: String,
        allowSendOwned: Boolean,
        allowFetchMissing: Boolean,
    ): String

    external fun nativeNearbyImportContract(
        websocketPort: Int,
        contractFilePath: String,
    ): String

    fun ping(): Result<String> = withLoadedLibrary(::nativePing)

    fun buildInfo(): Result<String> = withLoadedLibrary(::nativeBuildInfo)

    fun freenetBuildInfo(): Result<String> = withLoadedLibrary(::nativeFreenetBuildInfo)

    fun startLocalNode(configJson: String): Result<String> =
        withLoadedLibrary { nativeStartLocalNode(configJson) }

    fun startNetworkNode(configJson: String): Result<String> =
        withLoadedLibrary { nativeStartNetworkNode(configJson) }

    fun updateConnectivity(connectivityJson: String): Result<String> =
        withLoadedLibrary { nativeUpdateConnectivity(connectivityJson) }

    fun stopNode(): Result<String> = withLoadedLibrary(::nativeStopNode)

    fun nodeStatus(): Result<String> = withLoadedLibrary(::nativeGetNodeStatus)

    fun recentLogs(maxEntries: Int): Result<String> =
        withLoadedLibrary { nativeGetRecentLogs(maxEntries) }

    fun storageStatus(configJson: String): Result<String> =
        withLoadedLibrary { nativeGetStorageStatus(configJson) }

    fun runContractProof(): Result<String> = withLoadedLibrary(::nativeRunContractProof)

    fun verifyContractPersistence(): Result<String> =
        withLoadedLibrary(::nativeVerifyContractPersistence)

    fun contractProofStatus(): Result<String> =
        withLoadedLibrary(::nativeGetContractProofStatus)

    fun queryNodeDiagnostics(websocketPort: Int): Result<String> =
        withLoadedLibrary { nativeQueryNodeDiagnostics(websocketPort) }

    fun appendInfoLog(message: String): Result<String> =
        withLoadedLibrary { nativeAppendInfoLog(message) }

    fun nearbyExportContract(
        websocketPort: Int,
        contractKeyHex: String,
        outputDirectory: String,
        allowSendOwned: Boolean,
        allowFetchMissing: Boolean,
    ): String {
        if (!isLoaded) return nearbyExportError(loadError ?: "The node library is not loaded.")
        return runCatching {
            nativeNearbyExportContract(
                websocketPort,
                contractKeyHex,
                outputDirectory,
                allowSendOwned,
                allowFetchMissing,
            )
        }.getOrElse { error ->
            nearbyExportError(error.message ?: "The nearby request failed.")
        }
    }

    fun nearbyImportContract(websocketPort: Int, contractFilePath: String): String {
        if (!isLoaded) return nearbyImportError(loadError ?: "The node library is not loaded.")
        return runCatching {
            nativeNearbyImportContract(websocketPort, contractFilePath)
        }.getOrElse { error ->
            nearbyImportError(error.message ?: "The nearby import failed.")
        }
    }

    private fun nearbyExportError(message: String): String = JSONObject()
        .put("status", "error")
        .put("bytes", 0)
        .put("path", "")
        .put("message", message)
        .put("fetched", false)
        .toString()

    private fun nearbyImportError(message: String): String = JSONObject()
        .put("status", "error")
        .put("key", "")
        .put("message", message)
        .toString()

    private inline fun <T> withLoadedLibrary(block: () -> T): Result<T> {
        return loadResult.fold(
            onSuccess = { runCatching(block) },
            onFailure = { Result.failure(it) },
        )
    }
}
