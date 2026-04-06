package com.nettarion.hyperborea.ui.license

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.MainDispatcherRule
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.core.PairingSession
import com.nettarion.hyperborea.core.PairingStatus
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.system.SystemSnapshot
import com.nettarion.hyperborea.core.system.SystemStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LicenseViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @get:Rule
    val timeout: Timeout = Timeout(45, TimeUnit.SECONDS)

    private val licenseState = MutableStateFlow<LicenseState>(LicenseState.Checking)
    private var checkCallCount = 0
    private var lastCheckSilent = false
    private var unlinkCalled = false
    private var pairingResult: PairingSession = PairingSession.Error("stub")
    private var pollResult: PairingStatus = PairingStatus.Pending

    private val systemSnapshot = MutableStateFlow(
        SystemSnapshot(
            status = SystemStatus(
                isBluetoothLeEnabled = false,
                isBluetoothLeAdvertisingSupported = false,
                isNetworkConnected = false,
                isWifiEnabled = false,
                wifiIpAddress = null,
                isUsbHostAvailable = false,
                isAdbEnabled = false,
                isRootAvailable = false,
                isSeLinuxEnforcing = false,
                isImmersiveModeEnabled = false,
                isUserSetupComplete = false,
            ),
            packages = emptyList(),
            components = emptyList(),
            usbDevices = emptyList(),
            timestamp = 0L,
        )
    )

    private val fakeSystemMonitor = object : SystemMonitor {
        override val snapshot: StateFlow<SystemSnapshot> = systemSnapshot
        override suspend fun refresh() {}
    }

    private val fakeLicenseChecker = object : LicenseChecker {
        override val state: StateFlow<LicenseState> = licenseState
        override val authToken: String? = null
        override val deviceUuid: String? = null
        override suspend fun check(silent: Boolean) {
            checkCallCount++
            lastCheckSilent = silent
        }
        override suspend fun requestPairing(): PairingSession = pairingResult
        override suspend fun pollPairing(pairingToken: String): PairingStatus = pollResult
        override suspend fun unlink() { unlinkCalled = true }
    }

    private lateinit var viewModel: LicenseViewModel

    private fun createViewModel() {
        viewModel = LicenseViewModel(
            licenseChecker = fakeLicenseChecker,
            systemMonitor = fakeSystemMonitor,
            context = ContextWrapper(null),
        )
    }

    /**
     * Runs a test block then cancels the ViewModel's scope so runTest doesn't
     * hang on the infinite periodic re-check loop.
     */
    private fun runViewModelTest(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            block()
        } finally {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun `init calls check non-silently`() = runViewModelTest {
        createViewModel()
        runCurrent()

        assertThat(checkCallCount).isEqualTo(1)
        assertThat(lastCheckSilent).isFalse()
    }

    @Test
    fun `licenseState exposes license checker state`() = runViewModelTest {
        createViewModel()
        runCurrent()

        viewModel.licenseState.test {
            assertThat(awaitItem()).isEqualTo(LicenseState.Checking)

            licenseState.value = LicenseState.Unlicensed
            assertThat(awaitItem()).isEqualTo(LicenseState.Unlicensed)
        }
    }

    @Test
    fun `periodic re-check uses silent`() = runViewModelTest {
        createViewModel()
        runCurrent()
        checkCallCount = 0

        advanceTimeBy(4 * 60 * 60 * 1000L + 1)
        runCurrent()

        assertThat(checkCallCount).isGreaterThan(0)
        assertThat(lastCheckSilent).isTrue()
    }

    @Test
    fun `requestPairing starts polling on success`() = runViewModelTest {
        val expiry = System.currentTimeMillis() + 60_000
        pairingResult = PairingSession.Created("tok-1", "123456", expiry)
        pollResult = PairingStatus.Linked("auth-token")
        createViewModel()
        runCurrent()

        viewModel.requestPairing()
        runCurrent()
        // Advance past the 3s polling delay
        advanceTimeBy(3001)
        runCurrent()

        // Poll returned Linked, so the loop should have broken — no crash
    }

    @Test
    fun `requestPairing exposes error on failure`() = runViewModelTest {
        pairingResult = PairingSession.Error("TLS handshake failed")
        createViewModel()
        runCurrent()

        viewModel.requestPairing()
        runCurrent()

        assertThat(viewModel.pairingError.value).isEqualTo("TLS handshake failed")
    }

    @Test
    fun `cancelPairing calls check`() = runViewModelTest {
        createViewModel()
        runCurrent()
        checkCallCount = 0
        pairingResult = PairingSession.Error("stub")
        viewModel.requestPairing()
        runCurrent()

        viewModel.cancelPairing()
        runCurrent()

        assertThat(checkCallCount).isEqualTo(1)
        assertThat(lastCheckSilent).isFalse()
        assertThat(viewModel.pairingError.value).isNull()
    }

    @Test
    fun `retries check silently when network appears while unlicensed`() = runViewModelTest {
        createViewModel()
        runCurrent()
        licenseState.value = LicenseState.Unlicensed
        checkCallCount = 0

        // Simulate network becoming available
        systemSnapshot.value = systemSnapshot.value.copy(
            status = systemSnapshot.value.status.copy(isNetworkConnected = true)
        )
        runCurrent()

        assertThat(checkCallCount).isEqualTo(1)
        assertThat(lastCheckSilent).isTrue()
    }

    @Test
    fun `does not retry when network appears while licensed`() = runViewModelTest {
        createViewModel()
        runCurrent()
        licenseState.value = LicenseState.Licensed
        checkCallCount = 0

        systemSnapshot.value = systemSnapshot.value.copy(
            status = systemSnapshot.value.status.copy(isNetworkConnected = true)
        )
        runCurrent()

        assertThat(checkCallCount).isEqualTo(0)
    }

    @Test
    fun `hasNetwork reflects network connectivity state`() = runViewModelTest {
        createViewModel()
        runCurrent()

        viewModel.hasNetwork.test {
            assertThat(awaitItem()).isFalse()

            systemSnapshot.value = systemSnapshot.value.copy(
                status = systemSnapshot.value.status.copy(isNetworkConnected = true)
            )
            assertThat(awaitItem()).isTrue()
        }
    }

    @Test
    fun `unlinkDevice calls unlink`() = runViewModelTest {
        createViewModel()
        runCurrent()

        viewModel.unlinkDevice()
        runCurrent()

        assertThat(unlinkCalled).isTrue()
    }
}
