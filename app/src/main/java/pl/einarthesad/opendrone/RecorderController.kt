package pl.einarthesad.opendrone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
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
    private var mp4Writer: Mp4VideoWriter? = null
    private var tempFile: File? = null
    private var currentFormat = VideoFormat.AVI
    private var useBitmapFrames = false

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Synchronized
    fun start(
        firstJpeg: ByteArray?,
        firstBitmap: Bitmap?,
        videoFormat: VideoFormat
    ) {
        if (isRecording) return

        val needsBitmap = videoFormat == VideoFormat.MP4

        if (!needsBitmap && firstJpeg == null) {
            onStatus("No frame yet")
            return
        }

        if (needsBitmap && firstBitmap == null) {
            onStatus("No frame yet")
            return
        }

        currentFormat = videoFormat
        useBitmapFrames = needsBitmap

        try {
            if (needsBitmap) {
                startWithBitmap(firstBitmap ?: return, videoFormat)
            } else {
                startWithJpeg(firstJpeg ?: return)
            }
        } catch (e: Exception) {
            writer = null
            mp4Writer = null
            tempFile?.delete()
            tempFile = null
            isRecording = false
            onStatus("REC error: ${e.message}")
        }
    }

    private fun startWithJpeg(firstJpeg: ByteArray) {
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

    private fun startWithBitmap(firstBitmap: Bitmap, videoFormat: VideoFormat) {
        val extension = if (videoFormat == VideoFormat.MP4) "mp4" else "avi"
        val name = "drone_temp_${stamp()}.$extension"
        val file = File(context.cacheDir, name)
        val width = makeEven(firstBitmap.width)
        val height = makeEven(firstBitmap.height)

        tempFile = file

        if (videoFormat == VideoFormat.MP4) {
            mp4Writer = Mp4VideoWriter(file, width, height, fps = 20)
            mp4Writer?.addFrame(firstBitmap)
        } else {
            writer = AviMjpegWriter(file, width, height, fps = 20)
            writer?.addFrame(bitmapToJpeg(firstBitmap))
        }

        isRecording = true
        onStatus("REC ${width}x${height}")
    }

    @Synchronized
    fun addFrame(jpeg: ByteArray?, bitmap: Bitmap?) {
        if (!isRecording) return

        try {
            if (useBitmapFrames) {
                val frame = bitmap ?: return

                if (currentFormat == VideoFormat.MP4) {
                    mp4Writer?.addFrame(frame)
                } else {
                    writer?.addFrame(bitmapToJpeg(frame))
                }
            } else if (jpeg != null) {
                writer?.addFrame(jpeg)
            }
        } catch (e: Exception) {
            onStatus("REC error: ${e.message}")
        }
    }

    @Synchronized
    fun stopAndSave() {
        if (!isRecording) return

        isRecording = false

        val finishedWriter = writer
        val finishedMp4Writer = mp4Writer
        val finishedFile = tempFile
        val finishedFormat = currentFormat

        writer = null
        mp4Writer = null
        tempFile = null

        if ((finishedWriter == null && finishedMp4Writer == null) || finishedFile == null) {
            onStatus("REC stopped")
            return
        }

        onStatus("Saving video...")

        thread(name = "SaveVideo") {
            try {
                val video = if (finishedFormat == VideoFormat.MP4) {
                    finishedMp4Writer?.finish()
                } else {
                    finishedWriter?.finish()
                } ?: finishedFile

                val savedName = MediaStoreSaver.saveVideo(context, video, finishedFormat)
                video.delete()

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
            mp4Writer?.finish()
            tempFile?.delete()
        } catch (_: Exception) {
        }

        writer = null
        mp4Writer = null
        tempFile = null
    }

    fun wantsJpegFrames(): Boolean {
        return isRecording && !useBitmapFrames
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)

        return out.toByteArray()
    }

    private fun makeEven(value: Int): Int {
        return if (value % 2 == 0) value else value + 1
    }

    private fun stamp(): String {
        return stampFormat.format(Date())
    }
}
