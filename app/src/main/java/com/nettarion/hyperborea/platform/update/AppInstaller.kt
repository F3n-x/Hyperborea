package com.nettarion.hyperborea.platform.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.nettarion.hyperborea.core.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a downloaded APK to the platform [PackageInstaller] to install as an app update.
 *
 * We stream the staged APK into a [PackageInstaller.Session] and `commit()` it with an
 * [IntentSender]; the system then drives its standard "Install update?" confirmation and
 * reports the outcome back to our broadcast receiver. This is the documented replacement
 * for the legacy `ACTION_INSTALL_PACKAGE` / `ACTION_VIEW` install intents (deprecated since
 * API 29), and — crucially — it tells us whether the install actually *succeeded*: a
 * fire-and-forget `ACTION_VIEW` can't, so the previous implementation reported success the
 * instant the intent was sent, even when the system installer rejected the package.
 *
 *   https://developer.android.com/reference/android/content/pm/PackageInstaller
 *
 * The package replacement still triggers `ACTION_MY_PACKAGE_REPLACED`, which
 * `UpdateRestartReceiver` handles to relaunch the activity in the fresh process.
 *
 * On API 26+ the system requires the user to grant "Install unknown apps" once per source
 * app. If they haven't, the confirmation step is a dead-end "Install blocked" screen — so we
 * pre-flight `canRequestPackageInstalls()` and route the user to the right Settings screen the
 * first time. Once granted, the preference persists per package.
 *   https://developer.android.com/about/versions/oreo/android-8.0-changes#unknown-sources
 *
 * Only `REQUEST_INSTALL_PACKAGES` (a normal permission, auto-granted) is required — *not*
 * `INSTALL_PACKAGES`; that `signature|privileged` permission only buys *silent* installs,
 * which `PackageInstaller.Session.commit()` never does for a regular app anyway.
 */
@Singleton
class AppInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: AppLogger,
) : UpdateInstaller {

    private val statusAction = "${context.packageName}.action.INSTALL_STATUS"

    override suspend fun install(path: String): InstallResult = withContext(Dispatchers.IO) {
        logger.i(TAG, "Installing APK: $path")
        val file = File(path)
        if (!file.exists()) {
            return@withContext InstallResult.Failed("Update APK not found at $path")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return@withContext launchUnknownSourcesSettings()
        }

        val packageInstaller = context.packageManager.packageInstaller
        val outcome = CompletableDeferred<InstallResult>()
        val receiver = statusReceiver(outcome)
        registerStatusReceiver(receiver)

        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite(WRITE_NAME, 0, file.length()).use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(buildStatusSender(sessionId))
            }
            logger.i(TAG, "PackageInstaller session $sessionId committed; awaiting result")
            outcome.await()
        } catch (e: CancellationException) {
            if (sessionId != -1) runCatching { packageInstaller.abandonSession(sessionId) }
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "PackageInstaller session failed", e)
            if (sessionId != -1) runCatching { packageInstaller.abandonSession(sessionId) }
            InstallResult.Failed("Failed to stage update: ${e.message}", e)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun statusReceiver(outcome: CompletableDeferred<InstallResult>) = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    logger.i(TAG, "Install needs user confirmation; launching system dialog")
                    @Suppress("DEPRECATION")
                    val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmIntent == null) {
                        outcome.complete(
                            InstallResult.Failed("System installer did not provide a confirmation intent"),
                        )
                        return
                    }
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(confirmIntent)
                        // Wait for the follow-up STATUS_SUCCESS / STATUS_FAILURE_ABORTED broadcast.
                    } catch (e: Exception) {
                        logger.e(TAG, "Failed to launch install confirmation dialog", e)
                        outcome.complete(
                            InstallResult.Failed("Failed to show the install dialog: ${e.message}", e),
                        )
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    logger.i(TAG, "Update installed")
                    outcome.complete(InstallResult.Success)
                }
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    logger.e(TAG, "Update install failed: status=$status message=$message")
                    outcome.complete(InstallResult.Failed(installFailureMessage(status, message)))
                }
            }
        }
    }

    // InlinedApi: RECEIVER_NOT_EXPORTED (API 33) is only referenced inside the SDK_INT >= 33
    //   branch; inlining the int constant on older builds is harmless and never reached there.
    // UnspecifiedRegisterReceiverFlag: the flag is only required on API 33+, which the guard
    //   already routes to the 3-arg overload; lint can't see that the 2-arg call is API <33 only.
    @SuppressLint("InlinedApi", "UnspecifiedRegisterReceiverFlag")
    private fun registerStatusReceiver(receiver: BroadcastReceiver) {
        val filter = IntentFilter(statusAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun buildStatusSender(sessionId: Int): IntentSender {
        val intent = Intent(statusAction).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system fills the result extras into this PendingIntent, so it must be mutable.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private fun installFailureMessage(status: Int, message: String?): String {
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked by the device"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflicts with an installed package"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "the package could not be parsed"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
            else -> "unknown error (status $status)"
        }
        return if (message.isNullOrBlank()) {
            "Update install failed: $reason"
        } else {
            "Update install failed: $reason ($message)"
        }
    }

    // InlinedApi: only ever invoked when SDK_INT >= O (the canRequestPackageInstalls() gate in
    // install()); the inlined ACTION_MANAGE_UNKNOWN_APP_SOURCES string is harmless on older builds.
    @SuppressLint("InlinedApi")
    private fun launchUnknownSourcesSettings(): InstallResult {
        logger.w(TAG, "Install Unknown Apps not granted; routing user to Settings")
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            InstallResult.Failed(
                "Allow installing unknown apps for Hyperborea, then re-run the update.",
            )
        } catch (e: Exception) {
            logger.e(TAG, "Failed to open Install Unknown Apps settings", e)
            InstallResult.Failed(
                "Allow installing unknown apps for Hyperborea in Settings, then re-run the update.",
                e,
            )
        }
    }

    override suspend fun finalize(path: String) = withContext(Dispatchers.IO) {
        // Reached when the user cancels the system installer or after install completes
        // without process restart. Drop the staged APK either way.
        logger.i(TAG, "Finalizing app update")
        File(path).delete()
        Unit
    }

    companion object {
        private const val TAG = "AppInstaller"

        // Logical name of the APK within the install session — not a filesystem path.
        private const val WRITE_NAME = "hyperborea"
    }
}
