package com.nettarion.hyperborea.ecosystem.ifit

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.system.ComponentState
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.system.DeclaredComponent
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.FanMode
import com.nettarion.hyperborea.core.orchestration.FulfillResult
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class IfitEcosystemManagerTest {

    private lateinit var manager: IfitEcosystemManager
    private lateinit var privilegedManager: IfitEcosystemManager
    private lateinit var fakePrefs: FakeUserPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeUserPreferences()
        manager = IfitEcosystemManager(fakePrefs, hasSecureSettingsAccess = false)
        privilegedManager = IfitEcosystemManager(fakePrefs, hasSecureSettingsAccess = true)
    }

    // --- Prerequisites ---

    @Test
    fun `non privileged install skips secure settings prerequisites`() {
        assertThat(manager.prerequisites.map { it.id }).containsExactly(
            "ifit-standalone-stopped",
            "glassos-service-stopped",
            "eru-usb-receiver-disabled",
        )
    }

    @Test
    fun `privileged install includes secure settings prerequisites`() {
        assertThat(privilegedManager.prerequisites.map { it.id }).containsExactly(
            "ifit-standalone-stopped",
            "glassos-service-stopped",
            "eru-usb-receiver-disabled",
            "immersive-mode-enforced",
            "adb-enabled",
            "user-setup-complete",
        )
    }

    // --- glassos-service-stopped ---

    private fun glassosPrereq() = manager.prerequisites.first { it.id == "glassos-service-stopped" }

    @Test
    fun `glassos prerequisite is met when no glassos components`() {
        val snapshot = buildSystemSnapshot(components = emptyList())
        assertThat(glassosPrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `glassos prerequisite is NOT met when glassos is RUNNING`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildGlassosComponent(ComponentState.RUNNING)),
        )
        assertThat(glassosPrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `glassos prerequisite is met when glassos is DISABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildGlassosComponent(ComponentState.DISABLED)),
        )
        assertThat(glassosPrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `glassos fulfill calls forceStopPackage with correct package`() = runTest {
        var capturedPackage: String? = null
        val controller = stubController(onForceStop = { pkg -> capturedPackage = pkg; true })
        glassosPrereq().fulfill!!.invoke(controller)
        assertThat(capturedPackage).isEqualTo("com.ifit.glassos_service")
    }

    @Test
    fun `glassos fulfill returns Success when forceStopPackage succeeds`() = runTest {
        val controller = stubController(onForceStop = { true })
        val result = glassosPrereq().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `glassos fulfill returns Failed when forceStopPackage fails`() = runTest {
        val controller = stubController(onForceStop = { false })
        val result = glassosPrereq().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
        assertThat((result as FulfillResult.Failed).reason).contains("com.ifit.glassos_service")
    }

    // --- ifit-standalone-stopped ---

    private fun standalonePrereq() = manager.prerequisites.first { it.id == "ifit-standalone-stopped" }

    @Test
    fun `standalone prerequisite is met when no ifit components`() {
        val snapshot = buildSystemSnapshot(components = emptyList())
        assertThat(standalonePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `standalone prerequisite is met when ifit service is ENABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.ENABLED)),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `standalone prerequisite is met when ifit service is DISABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.DISABLED)),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `standalone prerequisite is NOT met when ifit service is RUNNING`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.RUNNING)),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `standalone prerequisite is NOT met when ifit service is RUNNING_FOREGROUND`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.RUNNING_FOREGROUND)),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `standalone prerequisite is met when other package is running`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(
                DeclaredComponent(
                    packageName = "com.other.app",
                    className = "com.other.app.SomeService",
                    type = ComponentType.SERVICE,
                    state = ComponentState.RUNNING,
                ),
            ),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `standalone fulfill calls forceStopPackage with correct package`() = runTest {
        var capturedPackage: String? = null
        val controller = stubController(onForceStop = { pkg -> capturedPackage = pkg; true })
        standalonePrereq().fulfill!!.invoke(controller)
        assertThat(capturedPackage).isEqualTo("com.ifit.standalone")
    }

    @Test
    fun `standalone fulfill returns Success when forceStopPackage succeeds`() = runTest {
        val controller = stubController(onForceStop = { true })
        val result = standalonePrereq().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `standalone fulfill returns Failed when forceStopPackage fails`() = runTest {
        val controller = stubController(onForceStop = { false })
        val result = standalonePrereq().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
        assertThat((result as FulfillResult.Failed).reason).contains("com.ifit.standalone")
    }

    // --- eru-usb-receiver-disabled ---

    private fun eruReceiverPrereq() = manager.prerequisites.first { it.id == "eru-usb-receiver-disabled" }

    @Test
    fun `eru receiver prerequisite is met when receiver is absent`() {
        val snapshot = buildSystemSnapshot(components = emptyList())
        assertThat(eruReceiverPrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `eru receiver prerequisite is met when receiver is DISABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildEruReceiver(ComponentState.DISABLED)),
        )
        assertThat(eruReceiverPrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `eru receiver prerequisite is NOT met when receiver is ENABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildEruReceiver(ComponentState.ENABLED)),
        )
        assertThat(eruReceiverPrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `eru receiver fulfill calls disableComponent with correct arguments`() = runTest {
        var capturedPkg: String? = null
        var capturedClass: String? = null
        val controller = stubController(
            onDisableComponent = { pkg, cls -> capturedPkg = pkg; capturedClass = cls; true },
        )
        eruReceiverPrereq().fulfill!!.invoke(controller)
        assertThat(capturedPkg).isEqualTo("com.ifit.eru")
        assertThat(capturedClass).isEqualTo("com.ifit.eru.receivers.UsbDeviceAttachedReceiver")
    }

    @Test
    fun `eru receiver fulfill returns Success when disableComponent succeeds`() = runTest {
        val controller = stubController(onDisableComponent = { _, _ -> true })
        val result = eruReceiverPrereq().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `eru receiver fulfill returns Failed when disableComponent fails`() = runTest {
        val controller = stubController(onDisableComponent = { _, _ -> false })
        val result = eruReceiverPrereq().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
    }

    // --- Helpers ---

    private fun buildGlassosComponent(state: ComponentState) = DeclaredComponent(
        packageName = "com.ifit.glassos_service",
        className = "com.ifit.glassos_service.GlassOSService",
        type = ComponentType.SERVICE,
        state = state,
    )

    private fun buildIfitComponent(state: ComponentState) = DeclaredComponent(
        packageName = "com.ifit.standalone",
        className = "com.ifit.standalone.ConnectionService",
        type = ComponentType.SERVICE,
        state = state,
    )

    private fun buildEruReceiver(state: ComponentState) = DeclaredComponent(
        packageName = "com.ifit.eru",
        className = "com.ifit.eru.receivers.UsbDeviceAttachedReceiver",
        type = ComponentType.BROADCAST_RECEIVER,
        state = state,
    )

    // --- immersive-mode-enforced ---

    private fun immersivePrereq() = privilegedManager.prerequisites.first { it.id == "immersive-mode-enforced" }

    @Test
    fun `immersive prerequisite is met when system matches preference (both true)`() {
        fakePrefs.setImmersiveModePref(true)
        val snapshot = buildSystemSnapshot(isImmersiveModeEnabled = true)
        assertThat(immersivePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `immersive prerequisite is met when system matches preference (both false)`() {
        fakePrefs.setImmersiveModePref(false)
        val snapshot = buildSystemSnapshot(isImmersiveModeEnabled = false)
        assertThat(immersivePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `immersive prerequisite is NOT met when system on but preference off`() {
        fakePrefs.setImmersiveModePref(false)
        val snapshot = buildSystemSnapshot(isImmersiveModeEnabled = true)
        assertThat(immersivePrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `immersive prerequisite is NOT met when system off but preference on`() {
        fakePrefs.setImmersiveModePref(true)
        val snapshot = buildSystemSnapshot(isImmersiveModeEnabled = false)
        assertThat(immersivePrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `immersive fulfill reads current preference value`() = runTest {
        fakePrefs.setImmersiveModePref(false)
        var capturedEnabled: Boolean? = null
        val controller = stubController(onSetImmersiveMode = { capturedEnabled = it; true })
        immersivePrereq().fulfill!!.invoke(controller)
        assertThat(capturedEnabled).isFalse()
    }

    @Test
    fun `immersive fulfill returns Success when setImmersiveMode succeeds`() = runTest {
        val controller = stubController(onSetImmersiveMode = { true })
        val result = immersivePrereq().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `immersive fulfill returns Failed when setImmersiveMode fails`() = runTest {
        val controller = stubController(onSetImmersiveMode = { false })
        val result = immersivePrereq().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
    }

    // --- adb-enabled ---

    private fun adbPrereq() = privilegedManager.prerequisites.first { it.id == "adb-enabled" }

    @Test
    fun `adb prerequisite is met when ADB enabled`() {
        val snapshot = buildSystemSnapshot(isAdbEnabled = true)
        assertThat(adbPrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `adb prerequisite is NOT met when ADB disabled`() {
        val snapshot = buildSystemSnapshot(isAdbEnabled = false)
        assertThat(adbPrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `adb fulfill calls setAdbEnabled with true`() = runTest {
        var capturedEnabled: Boolean? = null
        val controller = stubController(onSetAdbEnabled = { capturedEnabled = it; true })
        adbPrereq().fulfill!!.invoke(controller)
        assertThat(capturedEnabled).isTrue()
    }

    @Test
    fun `adb fulfill returns Success when setAdbEnabled succeeds`() = runTest {
        val controller = stubController(onSetAdbEnabled = { true })
        val result = adbPrereq().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `adb fulfill returns Failed when setAdbEnabled fails`() = runTest {
        val controller = stubController(onSetAdbEnabled = { false })
        val result = adbPrereq().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
    }

    // --- user-setup-complete ---

    private fun setupCompletePrereq() = privilegedManager.prerequisites.first { it.id == "user-setup-complete" }

    @Test
    fun `setup complete prerequisite is met when user_setup_complete is 1`() {
        val snapshot = buildSystemSnapshot(isUserSetupComplete = true)
        assertThat(setupCompletePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `setup complete prerequisite is NOT met when user_setup_complete is 0`() {
        val snapshot = buildSystemSnapshot(isUserSetupComplete = false)
        assertThat(setupCompletePrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `setup complete fulfill calls setUserSetupComplete with true`() = runTest {
        var capturedComplete: Boolean? = null
        val controller = stubController(onSetUserSetupComplete = { capturedComplete = it; true })
        setupCompletePrereq().fulfill!!.invoke(controller)
        assertThat(capturedComplete).isTrue()
    }

    @Test
    fun `setup complete fulfill returns Success when setUserSetupComplete succeeds`() = runTest {
        val controller = stubController(onSetUserSetupComplete = { true })
        val result = setupCompletePrereq().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `setup complete fulfill returns Failed when setUserSetupComplete fails`() = runTest {
        val controller = stubController(onSetUserSetupComplete = { false })
        val result = setupCompletePrereq().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
    }

    // --- Helpers ---

    private fun stubController(
        onForceStop: suspend (String) -> Boolean = { false },
        onDisableComponent: suspend (String, String) -> Boolean = { _, _ -> false },
        onSetImmersiveMode: suspend (Boolean) -> Boolean = { false },
        onSetAdbEnabled: suspend (Boolean) -> Boolean = { false },
        onSetUserSetupComplete: suspend (Boolean) -> Boolean = { false },
    ) = object : SystemController {
        override suspend fun stopService(packageName: String, className: String) = false
        override suspend fun forceStopPackage(packageName: String) = onForceStop(packageName)
        override suspend fun disablePackage(packageName: String) = false
        override suspend fun enablePackage(packageName: String) = false
        override suspend fun uninstallPackage(packageName: String) = false
        override suspend fun disableComponent(packageName: String, className: String) = onDisableComponent(packageName, className)
        override suspend fun enableComponent(packageName: String, className: String) = false
        override suspend fun grantUsbPermission(packageName: String) = false
        override suspend fun revokeUsbPermissions(packageName: String) = false
        override suspend fun setImmersiveMode(enabled: Boolean) = onSetImmersiveMode(enabled)
        override suspend fun setAdbEnabled(enabled: Boolean) = onSetAdbEnabled(enabled)
        override suspend fun setUserSetupComplete(complete: Boolean) = onSetUserSetupComplete(complete)
    }
}

private class FakeUserPreferences : UserPreferences {
    private val _immersiveModeEnabled = MutableStateFlow(true)
    override val immersiveModeEnabled: StateFlow<Boolean> = _immersiveModeEnabled
    override val enabledBroadcasts: StateFlow<Set<BroadcastId>> = MutableStateFlow(emptySet())
    override val overlayEnabled: StateFlow<Boolean> = MutableStateFlow(false)
    override val savedSensorAddress: StateFlow<String?> = MutableStateFlow(null)
    override val fanMode: StateFlow<FanMode> = MutableStateFlow(FanMode.OFF)
    override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {}
    override fun setOverlayEnabled(enabled: Boolean) {}
    override fun setSavedSensorAddress(address: String?) {}
    override fun setFanMode(mode: FanMode) {}
    override fun setImmersiveModeEnabled(enabled: Boolean) { _immersiveModeEnabled.value = enabled }
    fun setImmersiveModePref(enabled: Boolean) { _immersiveModeEnabled.value = enabled }
}
