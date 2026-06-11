package pl.einarthesad.opendrone

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class RecorderController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    @Volatile
    var isRecording: Boolean = false
        private set

    private var writer: AviMjpegWriter? = null
    private var tempFile: File? = null

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Synchronized
    fun start(firstJpeg: ByteArray?) {
        if (isRecording) return

        if (firstJpeg == null) {
            onStatus("No frame yet")
            return
        }

        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(firstJpeg, 0, firstJpeg.size, opts)

        val width = opts.outWidth
        val height = opts.outHeight

        if (width <= 0 || height <= 0) {
            onStatus("Cannot read frame size")
            return
        }

        val name = "drone_temp_${stamp()}.avi"
        val file = File(context.cacheDir, name)

        tempFile = file
        writer = AviMjpegWriter(file, width, height, fps = 20)
        writer?.addFrame(firstJpeg)

        isRecording = true
        onStatus("REC ${width}x${height}")
    }

    @Synchronized
    fun addFrame(jpeg: ByteArray) {
        if (!isRecording) return

        try {
            writer?.addFrame(jpeg)
        } catch (e: Exception) {
            onStatus("REC error: ${e.message}")
        }
    }

    @Synchronized
    fun stopAndSave() {
        if (!isRecording) return

        isRecording = false

        val finishedWriter = writer
        val finishedFile = tempFile

        writer = null
        tempFile = null

        if (finishedWriter == null || finishedFile == null) {
            onStatus("REC stopped")
            return
        }

        onStatus("Saving video...")

        thread(name = "SaveVideo") {
            try {
                val avi = finishedWriter.finish()
                val savedName = MediaStoreSaver.saveAviVideo(context, avi)
                avi.delete()

                onStatus("Saved $savedName")
            } catch (e: Exception) {
                onStatus("Save error: ${e.message}")
            }
        }
    }

    fun discard() {
        try {
            isRecording = false
            writer?.finish()
            tempFile?.delete()
        } catch (_: Exception) {
        }

        writer = null
        tempFile = null
    }

    private fun stamp(): String {
        return stampFormat.format(Date())
    }
}