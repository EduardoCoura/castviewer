package com.castviewer.sender.model

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CastMediaItem(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val isVideo: Boolean,
    val dateAddedMs: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val folderLabel: String
)

/**
 * Loads recent CastViewer clips (`Movies/CastViewer/`) and 1-tap screenshots
 * (`Pictures/CastViewer/`) via Scoped Storage MediaStore (API 29+) with FileProvider fallback.
 */
object ClipGalleryRepository {

    suspend fun loadRecentMedia(context: Context, limit: Int = 20): List<CastMediaItem> =
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val items = mutableListOf<CastMediaItem>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                items += queryMediaStoreVideos(appCtx, limit)
                items += queryMediaStoreImages(appCtx, limit)
            } else {
                items += scanLegacyDir(
                    appCtx,
                    appCtx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { File(it, "CastViewer") },
                    isVideo = true,
                    folderLabel = "Movies/CastViewer"
                )
                items += scanLegacyDir(
                    appCtx,
                    appCtx.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "CastViewer") },
                    isVideo = false,
                    folderLabel = "Pictures/CastViewer"
                )
                items += scanLegacyDir(
                    appCtx,
                    File(appCtx.filesDir, "CastViewer"),
                    isVideo = null,
                    folderLabel = "CastViewer"
                )
            }

            items.distinctBy { it.uri.toString() }
                .sortedByDescending { it.dateAddedMs }
                .take(limit)
        }

    private fun queryMediaStoreVideos(context: Context, limit: Int): List<CastMediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val list = mutableListOf<CastMediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_MOVIES}/CastViewer%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext() && list.size < limit) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val name = cursor.getString(nameCol) ?: "CastViewer_Clip.mp4"
                    val dateSec = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    val dur = cursor.getLong(durCol)
                    if (size <= 0L) continue
                    list.add(
                        CastMediaItem(
                            id = "vid_$id",
                            uri = uri,
                            displayName = name,
                            isVideo = true,
                            dateAddedMs = dateSec * 1000L,
                            sizeBytes = size,
                            durationMs = dur,
                            folderLabel = "Movies/CastViewer"
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun queryMediaStoreImages(context: Context, limit: Int): List<CastMediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val list = mutableListOf<CastMediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_PICTURES}/CastViewer%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext() && list.size < limit) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val name = cursor.getString(nameCol) ?: "CastViewer_Shot.png"
                    val dateSec = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0L) continue
                    list.add(
                        CastMediaItem(
                            id = "img_$id",
                            uri = uri,
                            displayName = name,
                            isVideo = false,
                            dateAddedMs = dateSec * 1000L,
                            sizeBytes = size,
                            durationMs = 0L,
                            folderLabel = "Pictures/CastViewer"
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun scanLegacyDir(
        context: Context,
        dir: File?,
        isVideo: Boolean?,
        folderLabel: String
    ): List<CastMediaItem> {
        if (dir == null || !dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: return emptyList()
        return files.mapNotNull { file ->
            val lower = file.name.lowercase()
            val detectedVideo = isVideo ?: lower.endsWith(".mp4")
            if (!lower.endsWith(".mp4") && !lower.endsWith(".png") && !lower.endsWith(".jpg")) {
                return@mapNotNull null
            }
            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (_: Exception) {
                Uri.fromFile(file)
            }
            CastMediaItem(
                id = "file_${file.absolutePath.hashCode()}",
                uri = uri,
                displayName = file.name,
                isVideo = detectedVideo,
                dateAddedMs = file.lastModified(),
                sizeBytes = file.length(),
                durationMs = 0L,
                folderLabel = folderLabel
            )
        }
    }

    suspend fun loadThumbnail(context: Context, item: CastMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return@withContext context.contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                }
            } catch (_: Exception) {
            }

            if (item.isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.uri)
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } catch (_: Exception) {
                    null
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            } else {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    context.contentResolver.openInputStream(item.uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

    fun openMedia(context: Context, item: CastMediaItem): Boolean {
        val mime = if (item.isVideo) "video/mp4" else "image/png"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun shareMedia(context: Context, item: CastMediaItem): Boolean {
        val mime = if (item.isVideo) "video/mp4" else "image/png"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            putExtra(Intent.EXTRA_SUBJECT, item.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Compartilhar ${item.displayName}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }
}
