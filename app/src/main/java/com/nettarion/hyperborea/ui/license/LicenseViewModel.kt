package com.nettarion.hyperborea.ui.license

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.HyperboreaService
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.core.PairingSession
import com.nettarion.hyperborea.core.PairingStatus
import com.nettarion.hyperborea.core.system.SystemMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LicenseViewModel @Inject constructor(
    private val licenseChecker: LicenseChecker,
    private val systemMonitor: SystemMonitor,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val licenseState: StateFlow<LicenseState> = licenseChecker.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LicenseState.Checking)

    val hasNetwork: StateFlow<Boolean> = systemMonitor.snapshot
        .map { it.status.wifiIpAddress != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var pollingJob: Job? = null

    init {
        // Initial license check
        viewModelScope.launch {
            licenseChecker.check()
        }
        // Periodic re-check every 4 hours (silent to avoid flashing Checking state)
        viewModelScope.launch {
            while (true) {
                delay(LICENSE_RECHECK_INTERVAL_MS)
                licenseChecker.check(silent = true)
            }
        }
        // Auto-retry when WiFi becomes available while unlicensed
        viewModelScope.launch {
            systemMonitor.snapshot
                .map { it.status.wifiIpAddress }
                .distinctUntilChanged()
                .collect { ip ->
                    if (ip != null && licenseChecker.state.value is LicenseState.Unlicensed) {
                        licenseChecker.check(silent = true)
                    }
                }
        }
    }

    fun requestPairing() {
        viewModelScope.launch {
            val result = licenseChecker.requestPairing()
            if (result is PairingSession.Created) {
                startPolling(result.pairingToken, result.expiresAt)
            }
        }
    }

    private fun startPolling(token: String, expiresAt: Long) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (System.currentTimeMillis() < expiresAt) {
                delay(3000)
                when (licenseChecker.pollPairing(token)) {
                    is PairingStatus.Linked -> break
                    is PairingStatus.Expired -> break
                    is PairingStatus.Error -> break
                    is PairingStatus.Pending -> continue
                }
            }
        }
    }

    fun cancelPairing() {
        pollingJob?.cancel()
        pollingJob = null
        viewModelScope.launch {
            licenseChecker.check()
        }
    }

    fun unlinkDevice() {
        viewModelScope.launch {
            // Stop broadcasting before unlinking
            val intent = Intent(context, HyperboreaService::class.java).apply {
                action = HyperboreaService.ACTION_DEACTIVATE_DISCARD
            }
            context.startService(intent)
            licenseChecker.unlink()
        }
    }

    companion object {
        private const val LICENSE_RECHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours
    }
}
