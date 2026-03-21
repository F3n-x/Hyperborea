package com.nettarion.hyperborea.platform.update

import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class UpdateTrack internal constructor(
    private val name: String,
    private val downloadDir: String,
    private val downloadFilename: String,
    private val installer: UpdateInstaller,
    private val httpClient: UpdateHttpClient,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val urlResolver: (suspend (UpdateInfo) -> String)? = null,
    private val allowedUrlPrefixes: List<String> = buildList {
        add(BuildConfig.SERVER_URL)
        val r2 = BuildConfig.R2_BASE_URL
        if (r2.isNotEmpty()) add(r2)
    },
) {
    private val _state = MutableStateFlow<TrackState>(TrackState.Idle)
    val state: StateFlow<TrackState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun setAvailable(info: UpdateInfo) {
        cancelActiveJob()
        logger.i(TAG, "$name: Update available: ${info.version}")
        _state.value = TrackState.Available(info)
    }

    fun download() {
        val current = _state.value
        if (current !is TrackState.Available) {
            logger.w(TAG, "$name: download() called in invalid state: ${current::class.simpleName}")
            return
        }
        val info = current.info
        _state.value = TrackState.Downloading(info, DownloadProgress(0, 0))
        activeJob = scope.launch {
            try {
                val resolvedUrl = resolveUrl(info)
                if (!isAllowedDownloadUrl(resolvedUrl)) {
                    val msg = "Download URL not from allowed domain: $resolvedUrl"
                    logger.e(TAG, "$name: $msg")
                    _state.value = TrackState.Error(msg)
                    return@launch
                }
                logger.i(TAG, "$name: Starting download from $resolvedUrl")

                val dir = File(downloadDir)
                dir.mkdirs()
                val file = File(dir, downloadFilename)

                val downloadStream = httpClient.openDownload(resolvedUrl)
                val totalBytes = downloadStream.contentLength
                val digest = MessageDigest.getInstance("SHA-256")
                var bytesDownloaded = 0L

                downloadStream.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            _state.value = TrackState.Downloading(
                                info,
                                DownloadProgress(bytesDownloaded, totalBytes),
                            )
                        }
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(info.sha256, ignoreCase = true)) {
                    file.delete()
                    val msg = "SHA-256 mismatch: expected ${info.sha256}, got $actualSha256"
                    logger.e(TAG, "$name: $msg")
                    _state.value = TrackState.Error(msg)
                    return@launch
                }

                logger.i(TAG, "$name: Download complete, SHA-256 verified ($bytesDownloaded bytes)")
                _state.value = TrackState.ReadyToInstall(info, file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "$name: Download failed", e)
                _state.value = TrackState.Error("Download failed: ${e.message}", e)
            }
        }
    }

    fun install() {
        val current = _state.value
        if (current !is TrackState.ReadyToInstall) {
            logger.w(TAG, "$name: install() called in invalid state: ${current::class.simpleName}")
            return
        }
        val info = current.info
        val path = current.path
        _state.value = TrackState.Installing(info)
        activeJob = scope.launch {
            try {
                logger.i(TAG, "$name: Installing from $path")

                when (val result = installer.install(path)) {
                    is InstallResult.Success -> {
                        logger.i(TAG, "$name: Install succeeded")
                        _state.value = TrackState.Installed(info)
                    }
                    is InstallResult.Failed -> {
                        logger.e(TAG, "$name: Install failed: ${result.reason}")
                        _state.value = TrackState.Error(result.reason, result.cause)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "$name: Install exception", e)
                _state.value = TrackState.Error("Install failed: ${e.message}", e)
            }
        }
    }

    fun finalizeInstall() {
        val current = _state.value
        if (current !is TrackState.Installed) {
            logger.w(TAG, "$name: finalizeInstall() called in invalid state: ${current::class.simpleName}")
            return
        }
        val path = File(downloadDir, downloadFilename).absolutePath
        logger.i(TAG, "$name: Finalizing install")
        scope.launch { installer.finalize(path) }
    }

    fun dismiss() {
        cancelActiveJob()
        logger.i(TAG, "$name: Dismissed")
        _state.value = TrackState.Idle
    }

    private fun cancelActiveJob() {
        if (activeJob != null) {
            logger.d(TAG, "$name: Cancelling active job")
            activeJob?.cancel()
            activeJob = null
        }
    }

    private suspend fun resolveUrl(info: UpdateInfo): String {
        val resolver = urlResolver ?: return info.url
        return try {
            resolver(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "$name: URL resolver failed, falling back to cached URL: ${e.message}")
            info.url
        }
    }

    private fun isAllowedDownloadUrl(url: String): Boolean =
        allowedUrlPrefixes.any { url.startsWith(it) }

    companion object {
        private const val TAG = "Update"
        private const val BUFFER_SIZE = 8192
    }
}
