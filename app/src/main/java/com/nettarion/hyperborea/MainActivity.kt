package com.nettarion.hyperborea

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.platform.update.TrackState
import com.nettarion.hyperborea.ui.AppScreen
import com.nettarion.hyperborea.ui.admin.AdminViewModel
import com.nettarion.hyperborea.ui.admin.UpdateDialog
import com.nettarion.hyperborea.ui.dashboard.DashboardScreen
import com.nettarion.hyperborea.ui.license.LicenseViewModel
import com.nettarion.hyperborea.ui.license.PairingScreen
import com.nettarion.hyperborea.ui.license.UnlicensedScreen
import com.nettarion.hyperborea.ui.profile.ProfileEditScreen
import com.nettarion.hyperborea.ui.profile.ProfilePickerScreen
import com.nettarion.hyperborea.ui.profile.ProfileStatsScreen
import com.nettarion.hyperborea.ui.device.DeviceConfigScreen
import com.nettarion.hyperborea.ui.ride.RideDetailScreen
import com.nettarion.hyperborea.ui.settings.SettingsScreen
import com.nettarion.hyperborea.ui.theme.HyperboreaTheme
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val pendingStopDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStopDialogIntent(intent)
        enableEdgeToEdge()
        setContent {
            HyperboreaTheme {
                val licenseVm: LicenseViewModel = hiltViewModel()
                val licenseState by licenseVm.licenseState.collectAsStateWithLifecycle()

                when (val state = licenseState) {
                    is LicenseState.Checking -> {
                        val colors = LocalHyperboreaColors.current
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Checking license...",
                                    color = colors.textMedium,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    is LicenseState.Unlicensed -> {
                        val hasNetwork by licenseVm.hasNetwork.collectAsStateWithLifecycle()
                        val pairingError by licenseVm.pairingError.collectAsStateWithLifecycle()
                        UnlicensedScreen(
                            licenseState = state,
                            hasNetwork = hasNetwork,
                            pairingError = pairingError,
                            onLinkDevice = licenseVm::requestPairing,
                        )
                    }
                    is LicenseState.Pairing -> {
                        PairingScreen(
                            pairingToken = state.pairingToken,
                            pairingCode = state.pairingCode,
                            expiresAt = state.expiresAt,
                            onCancel = licenseVm::cancelPairing,
                        )
                    }
                    is LicenseState.Licensed -> {
                        MainApp(
                            onUnlinkDevice = licenseVm::unlinkDevice,
                            pendingStopDialog = pendingStopDialog,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStopDialogIntent(intent)
    }

    private fun handleStopDialogIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("show_stop_dialog", false) == true) {
            pendingStopDialog.value = true
            intent.removeExtra("show_stop_dialog")
        }
    }
}

@Composable
private fun MainApp(
    onUnlinkDevice: () -> Unit,
    pendingStopDialog: MutableState<Boolean>,
) {
    val adminViewModel: AdminViewModel = hiltViewModel()
    val appTrackState by adminViewModel.appTrackState.collectAsStateWithLifecycle()

    if (appTrackState != TrackState.Idle) {
        UpdateDialog(
            trackState = appTrackState,
            onUpdateNow = adminViewModel::applyUpdate,
            onLater = adminViewModel::dismissUpdate,
            onDismissError = adminViewModel::dismissUpdate,
        )
    }

    var backStack by remember { mutableStateOf(listOf<AppScreen>(AppScreen.ProfilePicker())) }
    val currentScreen = backStack.last()

    fun navigateTo(screen: AppScreen) { backStack = backStack + screen }
    fun navigateBack() { if (backStack.size > 1) backStack = backStack.dropLast(1) }
    fun navigateReplace(screen: AppScreen) { backStack = listOf(screen) }

    BackHandler(enabled = backStack.size > 1) { navigateBack() }

    when (val screen = currentScreen) {
        is AppScreen.ProfilePicker -> ProfilePickerScreen(
            onProfileSelected = { navigateReplace(AppScreen.Dashboard) },
            onCreateProfile = { navigateTo(AppScreen.ProfileEdit(null)) },
            onGuest = { navigateReplace(AppScreen.Dashboard) },
            autoSelect = screen.autoSelect,
        )
        is AppScreen.Dashboard -> DashboardScreen(
            onProfileClick = { navigateTo(AppScreen.ProfileStats(it)) },
            onSwitchProfile = { navigateReplace(AppScreen.ProfilePicker(autoSelect = false)) },
            onViewRide = { rideId -> navigateTo(AppScreen.RideDetail(rideId)) },
            onOpenSettings = { navigateTo(AppScreen.Settings) },
            pendingStopDialog = pendingStopDialog,
        )
        is AppScreen.ProfileEdit -> ProfileEditScreen(
            profileId = screen.profileId,
            onSaved = {
                if (screen.profileId != null) navigateBack()
                else navigateReplace(AppScreen.Dashboard)
            },
            onBack = { navigateBack() },
            onDeleted = { navigateReplace(AppScreen.ProfilePicker()) },
        )
        is AppScreen.ProfileStats -> ProfileStatsScreen(
            profileId = screen.profileId,
            onBack = { navigateBack() },
            onEditProfile = { navigateTo(AppScreen.ProfileEdit(screen.profileId)) },
            onSwitchProfile = { navigateReplace(AppScreen.ProfilePicker(autoSelect = false)) },
            onRideClick = { rideId -> navigateTo(AppScreen.RideDetail(rideId)) },
        )
        is AppScreen.RideDetail -> RideDetailScreen(
            rideId = screen.rideId,
            onBack = { navigateBack() },
        )
        is AppScreen.Settings -> SettingsScreen(
            onBack = { navigateBack() },
            onUnlinkDevice = onUnlinkDevice,
            onConfigureDevice = { modelNumber -> navigateTo(AppScreen.DeviceConfig(modelNumber)) },
        )
        is AppScreen.DeviceConfig -> DeviceConfigScreen(
            modelNumber = screen.modelNumber,
            onSaved = { navigateBack() },
            onBack = { navigateBack() },
        )
    }
}
