package com.nettarion.hyperborea.ecosystem.ifit

import android.Manifest
import android.content.pm.PackageManager
import com.nettarion.hyperborea.core.system.ComponentState
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.orchestration.EcosystemManager
import com.nettarion.hyperborea.core.orchestration.FulfillResult
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.profile.UserPreferences
import javax.inject.Singleton

@Singleton
class IfitEcosystemManager(
    private val userPreferences: UserPreferences,
    private val hasSecureSettingsAccess: Boolean,
) : EcosystemManager {

    override val prerequisites = buildList {
        add(
            Prerequisite(
                id = "ifit-standalone-stopped",
                description = "iFit standalone must be stopped to release USB",
                isMet = { snapshot ->
                    snapshot.components.none {
                        it.packageName == IFIT_STANDALONE_PACKAGE &&
                            (it.state == ComponentState.RUNNING ||
                                it.state == ComponentState.RUNNING_FOREGROUND)
                    }
                },
                fulfill = { controller ->
                    if (controller.forceStopPackage(IFIT_STANDALONE_PACKAGE)) FulfillResult.Success
                    else FulfillResult.Failed("Failed to force-stop $IFIT_STANDALONE_PACKAGE")
                },
            ),
        )
        add(
            Prerequisite(
                id = "glassos-service-stopped",
                description = "GlassOS service must be stopped to release USB",
                isMet = { snapshot ->
                    snapshot.components.none {
                        it.packageName == GLASSOS_SERVICE_PACKAGE &&
                            (it.state == ComponentState.RUNNING ||
                                it.state == ComponentState.RUNNING_FOREGROUND)
                    }
                },
                fulfill = { controller ->
                    if (controller.forceStopPackage(GLASSOS_SERVICE_PACKAGE)) FulfillResult.Success
                    else FulfillResult.Failed("Failed to force-stop $GLASSOS_SERVICE_PACKAGE")
                },
            ),
        )
        add(
            Prerequisite(
                id = "eru-usb-receiver-disabled",
                description = "ERU USB receiver must be disabled to prevent USB cycling",
                isMet = { snapshot ->
                    val receiver = snapshot.components.find {
                        it.packageName == ERU_PACKAGE &&
                            it.className == ERU_USB_RECEIVER &&
                            it.type == ComponentType.BROADCAST_RECEIVER
                    }
                    receiver == null || receiver.state == ComponentState.DISABLED
                },
                fulfill = { controller ->
                    if (controller.disableComponent(ERU_PACKAGE, ERU_USB_RECEIVER)) FulfillResult.Success
                    else FulfillResult.Failed("Failed to disable $ERU_USB_RECEIVER")
                },
            ),
        )

        // These toggles require privileged firmware access. Skip them on
        // sideloaded/debug installs so the app can still operate normally.
        if (hasSecureSettingsAccess) {
            add(
                Prerequisite(
                    id = "immersive-mode-enforced",
                    description = "Immersive mode must match user preference",
                    isMet = { snapshot ->
                        snapshot.status.isImmersiveModeEnabled ==
                            userPreferences.immersiveModeEnabled.value
                    },
                    fulfill = { controller ->
                        val desired = userPreferences.immersiveModeEnabled.value
                        if (controller.setImmersiveMode(desired)) FulfillResult.Success
                        else FulfillResult.Failed("Failed to set immersive mode")
                    },
                ),
            )
            add(
                Prerequisite(
                    id = "adb-enabled",
                    description = "ADB must be enabled",
                    isMet = { snapshot -> snapshot.status.isAdbEnabled },
                    fulfill = { controller ->
                        if (controller.setAdbEnabled(true)) FulfillResult.Success
                        else FulfillResult.Failed("Failed to enable ADB")
                    },
                ),
            )
            add(
                Prerequisite(
                    id = "user-setup-complete",
                    description = "User setup must be complete for Settings access",
                    isMet = { snapshot -> snapshot.status.isUserSetupComplete },
                    fulfill = { controller ->
                        if (controller.setUserSetupComplete(true)) FulfillResult.Success
                        else FulfillResult.Failed("Failed to set user_setup_complete")
                    },
                ),
            )
        }
    }

    private companion object {
        @Suppress("unused")
        const val SECURE_SETTINGS_PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS
        @Suppress("unused")
        const val PERMISSION_GRANTED = PackageManager.PERMISSION_GRANTED
        const val GLASSOS_SERVICE_PACKAGE = "com.ifit.glassos_service"
        const val IFIT_STANDALONE_PACKAGE = "com.ifit.standalone"
        const val ERU_PACKAGE = "com.ifit.eru"
        const val ERU_USB_RECEIVER = "com.ifit.eru.receivers.UsbDeviceAttachedReceiver"
    }
}
