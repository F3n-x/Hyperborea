package com.nettarion.hyperborea.ecosystem.ifit

import com.nettarion.hyperborea.core.system.ComponentState
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.orchestration.EcosystemManager
import com.nettarion.hyperborea.core.orchestration.FulfillResult
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IfitEcosystemManager @Inject constructor() : EcosystemManager {

    override val prerequisites = listOf(
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

    private companion object {
        const val GLASSOS_SERVICE_PACKAGE = "com.ifit.glassos_service"
        const val IFIT_STANDALONE_PACKAGE = "com.ifit.standalone"
        const val ERU_PACKAGE = "com.ifit.eru"
        const val ERU_USB_RECEIVER = "com.ifit.eru.receivers.UsbDeviceAttachedReceiver"
    }
}
