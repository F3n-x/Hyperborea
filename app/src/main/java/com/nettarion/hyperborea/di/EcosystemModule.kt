package com.nettarion.hyperborea.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.nettarion.hyperborea.core.orchestration.EcosystemManager
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.ecosystem.ifit.IfitEcosystemManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EcosystemModule {

    @Provides
    @Singleton
    fun provideEcosystemManager(
        @ApplicationContext context: Context,
        userPreferences: UserPreferences,
    ): EcosystemManager = IfitEcosystemManager(
        userPreferences = userPreferences,
        hasSecureSettingsAccess = context.checkCallingOrSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED,
    )
}
