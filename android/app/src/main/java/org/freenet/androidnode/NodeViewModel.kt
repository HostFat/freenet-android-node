package org.freenet.androidnode

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class UpdateInstallUiState(
    val installing: Boolean = false,
    val progress: UpdateInstaller.Progress? = null,
    val installStarted: Boolean = false,
    val error: String? = null,
)

internal data class CrashUploadUiState(
    val sending: Boolean = false,
    val sentId: String? = null,
    val error: String? = null,
)

class NodeViewModel(application: Application) : AndroidViewModel(application) {
    init {
        NodePolicyRepository.initialize(application)
        UpdateCheckRepository.initialize(application)
        CrashReportOffer.initialize(application)
    }

    val state = NodeRepository.state
    val policies = NodeRepository.policies

    private val mutableUpdateInstall = MutableStateFlow(UpdateInstallUiState())
    internal val updateInstall: StateFlow<UpdateInstallUiState> = mutableUpdateInstall.asStateFlow()

    private val mutableCrashUpload = MutableStateFlow(CrashUploadUiState())
    internal val crashUpload: StateFlow<CrashUploadUiState> = mutableCrashUpload.asStateFlow()

    private val mutableResetMessage = MutableStateFlow<String?>(null)
    val resetMessage: StateFlow<String?> = mutableResetMessage.asStateFlow()

    private val mutableRestoreMessage = MutableStateFlow<String?>(null)
    val restoreMessage: StateFlow<String?> = mutableRestoreMessage.asStateFlow()

    fun startNetworkNode() {
        NodeRepository.startNetwork(getApplication())
    }

    fun stopNode() {
        NodeRepository.stop(getApplication())
    }

    fun resetNode() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                NodeReset.resetRuntimeData(getApplication())
            }
            mutableResetMessage.value = result
        }
    }

    fun restoreBackup() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                NodeBackup.restore(getApplication())
            }
            mutableRestoreMessage.value = result.getOrElse {
                "Restore failed: ${it.message}"
            }
        }
    }

    internal fun downloadAndInstallUpdate(version: SemVer) {
        if (mutableUpdateInstall.value.installing) return
        val app = getApplication<Application>()
        mutableUpdateInstall.value = UpdateInstallUiState(installing = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val file = UpdateInstaller.download(app, version) { progress ->
                        mutableUpdateInstall.value = mutableUpdateInstall.value.copy(
                            progress = progress,
                        )
                    }
                    if (!UpdateInstaller.verifySignature(app, file)) {
                        file.delete()
                        error("Downloaded APK is signed with a different key")
                    }
                    UpdateInstaller.install(app, file)
                }
            }
            mutableUpdateInstall.value = outcome.fold(
                onSuccess = {
                    UpdateInstallUiState(installing = false, installStarted = true)
                },
                onFailure = { error ->
                    UpdateInstallUiState(
                        installing = false,
                        error = error.message ?: "Update failed",
                    )
                },
            )
        }
    }

    fun clearCrashUploadState() {
        mutableCrashUpload.value = CrashUploadUiState()
    }

    fun uploadCrashReport(comment: String) {
        if (mutableCrashUpload.value.sending) return
        val app = getApplication<Application>()
        mutableCrashUpload.value = CrashUploadUiState(sending = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                CrashInbox.upload(app, comment)
            }
            mutableCrashUpload.value = result.fold(
                onSuccess = { id ->
                    CrashReportOffer.clear(app)
                    CrashUploadUiState(sending = false, sentId = id)
                },
                onFailure = { error ->
                    CrashUploadUiState(
                        sending = false,
                        error = error.message ?: error.toString(),
                    )
                },
            )
        }
    }

    fun exportIdentity(uri: Uri, passphrase: String, onResult: (String) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                IdentityBackup.export(app, uri, passphrase)
            }
            onResult(message)
        }
    }

    fun importIdentity(uri: Uri, passphrase: String, onResult: (String) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                IdentityBackup.importFrom(app, uri, passphrase)
            }
            onResult(message)
        }
    }

    fun exportLogs(uri: Uri, text: String, onResult: (String) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                writeTextToUri(app, uri, text)
            }
            onResult(message)
        }
    }

    fun reportNotificationPermissionRequired() {
        NodeRepository.reportNotificationPermissionRequired()
    }

    fun setPowerPolicy(policy: NodePowerPolicy) {
        NodeRepository.setPowerPolicy(getApplication(), policy)
    }

    fun setNetworkDataPolicy(policy: NetworkDataPolicy) {
        NodeRepository.setNetworkDataPolicy(getApplication(), policy)
    }

    fun setScheduleEveryHours(hours: Int) {
        NodeRepository.setScheduleEveryHours(getApplication(), hours)
    }

    fun setScheduleOnMinutes(minutes: Int) {
        NodeRepository.setScheduleOnMinutes(getApplication(), minutes)
    }

    fun setMinConnections(minConnections: Int) {
        NodeRepository.setMinConnections(getApplication(), minConnections)
    }

    fun setMaxConnections(maxConnections: Int) {
        NodeRepository.setMaxConnections(getApplication(), maxConnections)
    }

    fun setConnectionLimits(minConnections: Int, maxConnections: Int) {
        NodeRepository.setConnectionLimits(getApplication(), minConnections, maxConnections)
    }

    fun setUdpPortSettings(mode: UdpPortMode, port: Int) {
        NodePolicyRepository.setUdpPortSettings(getApplication(), mode, port)
    }

    fun setStartOnBoot(enabled: Boolean) {
        NodePolicyRepository.setStartOnBoot(getApplication(), enabled)
    }

    fun setAutoRestartOnCrash(enabled: Boolean) {
        NodePolicyRepository.setAutoRestartOnCrash(getApplication(), enabled)
    }

    fun setNotifyConnected(enabled: Boolean) {
        NodePolicyRepository.setNotifyConnected(getApplication(), enabled)
    }

    fun setNotifyStopped(enabled: Boolean) {
        NodePolicyRepository.setNotifyStopped(getApplication(), enabled)
    }

    fun setNotifyUdpBusy(enabled: Boolean) {
        NodePolicyRepository.setNotifyUdpBusy(getApplication(), enabled)
    }

    fun setNotifyUpdate(enabled: Boolean) {
        NodePolicyRepository.setNotifyUpdate(getApplication(), enabled)
    }

    fun restartNetworkNode() {
        NodeRepository.restartNetworkNode(getApplication())
    }
}
