package pl.einarthesad.opendrone

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaStoreSaver {
    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun saveSnapshot(context: Context, jpeg: ByteArray): String {
        val name = "drone_snapshot_${stamp()}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Opendrone")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create image")

        resolver.openOutputStream(uri)?.use { out ->
            out.write(jpeg)
            out.flush()
        } ?: throw IllegalStateException("Cannot open image output")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return name
    }

    fun saveAviVideo(context: Context, sourceFile: File): String {
        val name = "drone_recording_${stamp()}.avi"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/x-msvideo")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Opendrone")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create video")

        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(out)
            }

            out.flush()
        } ?: throw IllegalStateException("Cannot open video output")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return name
    }

    private fun stamp(): String {
        return stampFormat.format(Date())
    }
}