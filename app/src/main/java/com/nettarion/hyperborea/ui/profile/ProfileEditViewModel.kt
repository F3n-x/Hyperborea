package com.nettarion.hyperborea.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.nettarion.hyperborea.ui.util.UnitFormatter
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _weight = MutableStateFlow("")
    val weight: StateFlow<String> = _weight.asStateFlow()

    // Metric: single height field (cm). Imperial: feet and inches.
    private val _height = MutableStateFlow("")
    val height: StateFlow<String> = _height.asStateFlow()

    private val _heightInches = MutableStateFlow("")
    val heightInches: StateFlow<String> = _heightInches.asStateFlow()

    private val _age = MutableStateFlow("")
    val age: StateFlow<String> = _age.asStateFlow()

    private val _ftpWatts = MutableStateFlow("")
    val ftpWatts: StateFlow<String> = _ftpWatts.asStateFlow()

    private val _maxHeartRate = MutableStateFlow("")
    val maxHeartRate: StateFlow<String> = _maxHeartRate.asStateFlow()

    /** Global units pref. The on-screen toggle writes through to [UserPreferences]. */
    val useImperial: StateFlow<Boolean> = userPreferences.useImperial

    private var editingProfile: Profile? = null

    fun loadProfile(profileId: Long?) {
        if (profileId == null || editingProfile != null) return
        viewModelScope.launch {
            val profiles = profileRepository.profiles.first()
            val profile = profiles.find { it.id == profileId } ?: return@launch
            editingProfile = profile
            _name.value = profile.name
            _age.value = profile.age?.toString() ?: ""
            _ftpWatts.value = profile.ftpWatts?.toString() ?: ""
            _maxHeartRate.value = profile.maxHeartRate?.toString() ?: ""
            loadBodyFields(profile)
        }
    }

    private fun loadBodyFields(profile: Profile) {
        val imperial = useImperial.value
        _weight.value = profile.weightKg?.let { UnitFormatter.weightEditDisplay(it, imperial) } ?: ""
        if (profile.heightCm != null) {
            val (h, hIn) = UnitFormatter.heightEditFields(profile.heightCm!!, imperial)
            _height.value = h
            _heightInches.value = hIn
        } else {
            _height.value = ""
            _heightInches.value = ""
        }
    }

    fun setName(value: String) { _name.value = value }
    fun setWeight(value: String) { _weight.value = value }
    fun setHeight(value: String) { _height.value = value }
    fun setHeightInches(value: String) { _heightInches.value = value }
    fun setAge(value: String) { _age.value = value }
    fun setFtpWatts(value: String) { _ftpWatts.value = value }
    fun setMaxHeartRate(value: String) { _maxHeartRate.value = value }

    fun toggleUnits() {
        // Capture current displayed values in metric *before* flipping the pref,
        // then reload the display in the new units.
        val weightKg = parseWeightToKg()
        val heightCm = parseHeightToCm()
        userPreferences.setUseImperial(!useImperial.value)
        val imperial = useImperial.value

        _weight.value = weightKg?.let { UnitFormatter.weightEditDisplay(it, imperial) } ?: ""
        if (heightCm != null) {
            val (h, hIn) = UnitFormatter.heightEditFields(heightCm, imperial)
            _height.value = h
            _heightInches.value = hIn
        } else {
            _height.value = ""
            _heightInches.value = ""
        }
    }

    private fun parseWeightToKg(): Float? {
        val v = _weight.value.toFloatOrNull() ?: return null
        return UnitFormatter.parseWeightToKg(v, useImperial.value)
    }

    private fun parseHeightToCm(): Int? {
        return if (useImperial.value) {
            val feet = _height.value.toIntOrNull() ?: 0
            val inches = _heightInches.value.toIntOrNull() ?: 0
            if (feet == 0 && inches == 0) null
            else UnitFormatter.parseHeightToCm(feet, inches)
        } else {
            _height.value.toIntOrNull()
        }
    }

    fun deleteProfile(onDeleted: () -> Unit) {
        val profile = editingProfile ?: return
        viewModelScope.launch {
            profileRepository.deleteProfile(profile.id)
            onDeleted()
        }
    }

    fun save(onSaved: () -> Unit) {
        val nameValue = _name.value.trim()
        if (nameValue.isBlank()) return

        val weightKg = parseWeightToKg()
        val heightCm = parseHeightToCm()

        viewModelScope.launch {
            val existing = editingProfile
            if (existing != null) {
                profileRepository.updateProfile(
                    existing.copy(
                        name = nameValue,
                        weightKg = weightKg,
                        heightCm = heightCm,
                        age = _age.value.toIntOrNull(),
                        ftpWatts = _ftpWatts.value.toIntOrNull(),
                        maxHeartRate = _maxHeartRate.value.toIntOrNull(),
                    ),
                )
            } else {
                val profile = profileRepository.createProfile(nameValue)
                profileRepository.updateProfile(
                    profile.copy(
                        weightKg = weightKg,
                        heightCm = heightCm,
                        age = _age.value.toIntOrNull(),
                        ftpWatts = _ftpWatts.value.toIntOrNull(),
                        maxHeartRate = _maxHeartRate.value.toIntOrNull(),
                    ),
                )
                profileRepository.setActiveProfile(profile.id)
            }
            onSaved()
        }
    }
}
