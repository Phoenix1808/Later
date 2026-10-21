package com.example.later

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.math.max

// Finding screenshots in the phone's shared storage, and preparing them for the AI.
object Screenshots {

    private const val MAX_SIDE = 1600

    // Android 13+ has a photos-only permission; older versions use general storage access
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Screenshots added after [sinceSec], newest first. Every phone brand stores them in a
    // folder or file name containing "Screenshot" (DCIM/Screenshots, Pictures/Screenshots...).
    fun findSince(context: Context, sinceSec: Long, limit: Int): List<Shot> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folderColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH
        else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND " +
            "($folderColumn LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
        val args = arrayOf(sinceSec.toString(), "%Screenshot%", "Screenshot%")
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val shots = mutableListOf<Shot>()
        context.contentResolver.query(collection, projection, selection, args, order)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext() && shots.size < limit) {
                val id = cursor.getLong(idCol)
                shots += Shot(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    takenAtSec = cursor.getLong(dateCol)
                )
            }
        }
        return shots
    }

    // Phone screenshots are ~1080x2400 PNGs. Shrink to 1600px and send as JPEG:
    // text stays readable, the upload is ~10x smaller.
    fun loadForUpload(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw java.io.FileNotFoundException("Screenshot is gone")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw java.io.FileNotFoundException("Screenshot is gone")

        val longest = max(decoded.width, decoded.height)
        val bitmap = if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx * 2), null)
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        null   // screenshot deleted, or access revoked
    }
}
