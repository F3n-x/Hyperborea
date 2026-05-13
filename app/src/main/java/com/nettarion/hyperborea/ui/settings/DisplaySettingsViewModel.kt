package com.nettarion.hyperborea.ui.settings

import androidx.lifecycle.ViewModel
import com.nettarion.hyperborea.core.profile.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Backs the "Display" tab in [SettingsScreen]. Exposes and mutates the global
 * units preference — read by the dashboard, profile screens, and ride summaries.
 */
@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    val useImperial: StateFlow<Boolean> = userPreferences.useImperial
    val overlayEnabled: StateFlow<Boolean> = userPreferences.overlayEnabled
    val immersiveModeEnabled: StateFlow<Boolean> = userPreferences.immersiveModeEnabled

    fun setUseImperial(enabled: Boolean) {
        userPreferences.setUseImperial(enabled)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        userPreferences.setOverlayEnabled(enabled)
    }

    fun setImmersiveModeEnabled(enabled: Boolean) {
        userPreferences.setImmersiveModeEnabled(enabled)
    }
}
