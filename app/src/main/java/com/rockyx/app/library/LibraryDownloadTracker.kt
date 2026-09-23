package com.rockyx.app.library

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import com.rockyx.app.media.MediaDescriptor
import com.rockyx.app.media.MediaDownloadRepository
import com.rockyx.app.media.RockyXDownloadService

@UnstableApi
class LibraryDownloadTracker(
    context: Context,
    private val library: LibraryService
) : DownloadManager.Listener, AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = MediaDownloadRepository.downloadManager(appContext)

    init {
        manager.addListener(this)
    }

    fun enqueue(descriptor: MediaDescriptor) {
        require(descriptor.downloadable) { "Media is not marked downloadable" }
        library.setDownloadStatus(
            LibraryDownloadStatus(
                mediaId = descriptor.id,
                state = LibraryDownloadState.QUEUED,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(
            descriptor.id,
            android.net.Uri.parse(descriptor.uri)
        ).build()
        DownloadService.sendAddDownload(
            appContext,
            RockyXDownloadService::class.java,
            request,
            true
        )
    }

    fun remove(mediaId: String) {
        DownloadService.sendRemoveDownload(
            appContext,
            RockyXDownloadService::class.java,
            mediaId,
            true
        )
        library.setDownloadStatus(
            LibraryDownloadStatus(
                mediaId = mediaId,
                state = LibraryDownloadState.REMOVED,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    override fun onInitialized(downloadManager: DownloadManager) {
        reconcileKnownDownloads()
    }

    override fun onDownloadChanged(
        downloadManager: DownloadManager,
        download: Download,
        finalException: Exception?
    ) {
        library.setDownloadStatus(
            LibraryDownloadStatus(
                mediaId = download.request.id,
                state = stateOf(download.state),
                updatedAtEpochMs = System.currentTimeMillis(),
                errorCode = finalException?.javaClass?.simpleName
            )
        )
    }

    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
        library.setDownloadStatus(
            LibraryDownloadStatus(
                mediaId = download.request.id,
                state = LibraryDownloadState.REMOVED,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    private fun stateOf(state: Int): LibraryDownloadState = when (state) {
        Download.STATE_QUEUED,
        Download.STATE_RESTARTING -> LibraryDownloadState.QUEUED
        Download.STATE_DOWNLOADING -> LibraryDownloadState.DOWNLOADING
        Download.STATE_COMPLETED -> LibraryDownloadState.COMPLETED
        Download.STATE_FAILED -> LibraryDownloadState.FAILED
        Download.STATE_REMOVING -> LibraryDownloadState.REMOVED
        else -> LibraryDownloadState.QUEUED
    }

    private fun reconcileKnownDownloads() {
        val index = manager.downloadIndex
        library.mediaAssets().forEach { asset ->
            runCatching { index.getDownload(asset.id) }
                .getOrNull()
                ?.let { download ->
                    library.setDownloadStatus(
                        LibraryDownloadStatus(
                            mediaId = download.request.id,
                            state = stateOf(download.state),
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
        }
    }

    override fun close() {
        manager.removeListener(this)
    }
}
