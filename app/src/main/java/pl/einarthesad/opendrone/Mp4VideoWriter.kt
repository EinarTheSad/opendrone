package pl.einarthesad.opendrone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

class Mp4VideoWriter(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 20
) {
    private val bufferInfo = MediaCodec.BufferInfo()
    private val codec: MediaCodec
    private val inputSurface: Surface
    private val muxer: MediaMuxer
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var trackIndex = -1
    private var muxerStarted = false
    private var finished = false

    init {
        val format = MediaFormat.createVideoFormat("video/avc", width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(width, height, fps))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        codec.start()
    }

    @Synchronized
    fun addFrame(bitmap: Bitmap) {
        if (finished) return

        drawFrame(bitmap)
        drainEncoder(false)
    }

    @Synchronized
    fun finish(): File {
        if (finished) return file

        finished = true

        codec.signalEndOfInputStream()
        drainEncoder(true)

        try {
            codec.stop()
        } finally {
            codec.release()
            inputSurface.release()

            if (muxerStarted) {
                muxer.stop()
            }

            muxer.release()
        }

        return file
    }

    private fun drawFrame(bitmap: Bitmap) {
        val canvas = inputSurface.lockCanvas(null)

        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
        } finally {
            inputSurface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        while (true) {
            val encoderStatus = codec.dequeueOutputBuffer(bufferInfo, 0)

            when {
                encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }

                encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        throw IllegalStateException("Video format changed twice")
                    }

                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                encoderStatus >= 0 -> {
                    val encodedData = codec.getOutputBuffer(encoderStatus)
                        ?: throw IllegalStateException("Missing encoder output")

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) {
                            throw IllegalStateException("Muxer has not started")
                        }

                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }

                    val done = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(encoderStatus, false)

                    if (done) return
                }
            }
        }
    }

    private fun bitRateFor(width: Int, height: Int, fps: Int): Int {
        return (width * height * fps / 3).coerceAtLeast(1_500_000)
    }
}
