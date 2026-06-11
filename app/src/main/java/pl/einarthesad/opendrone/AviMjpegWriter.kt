package pl.einarthesad.opendrone

import java.io.File
import java.io.RandomAccessFile

class AviMjpegWriter(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 20
) {
    private data class IndexEntry(
        val offset: Int,
        val size: Int
    )

    private var raf: RandomAccessFile = RandomAccessFile(file, "rw")
    private val index = ArrayList<IndexEntry>()

    private var riffSizePos = 0L
    private var hdrlSizePos = 0L
    private var moviSizePos = 0L
    private var moviDataStart = 0L

    private var avihFramesPos = 0L
    private var avihSuggestedBufferSizePos = 0L
    private var strhLengthPos = 0L
    private var strhSuggestedBufferSizePos = 0L
    private var strfImageSizePos = 0L

    private var frameCount = 0
    private var maxFrameSize = 0
    private var finished = false

    init {
        raf.setLength(0)
        writeHeader()
    }

    @Synchronized
    fun addFrame(jpeg: ByteArray) {
        if (finished) return

        val chunkStart = raf.filePointer
        val offset = (chunkStart - moviDataStart).toInt()

        writeFourCc("00dc")
        writeIntLe(jpeg.size)
        raf.write(jpeg)

        if ((jpeg.size and 1) != 0) {
            raf.write(0)
        }

        index.add(IndexEntry(offset, jpeg.size))

        frameCount += 1

        if (jpeg.size > maxFrameSize) {
            maxFrameSize = jpeg.size
        }
    }

    @Synchronized
    fun finish(): File {
        if (finished) return file

        finished = true

        val idxStart = raf.filePointer

        writeFourCc("idx1")
        writeIntLe(index.size * 16)

        for (entry in index) {
            writeFourCc("00dc")
            writeIntLe(0x10)
            writeIntLe(entry.offset)
            writeIntLe(entry.size)
        }

        val fileEnd = raf.filePointer

        patchIntLe(riffSizePos, (fileEnd - 8).toInt())
        patchIntLe(hdrlSizePos, (moviSizePos - hdrlSizePos - 4).toInt())
        patchIntLe(moviSizePos, (idxStart - moviSizePos - 4).toInt())

        patchIntLe(avihFramesPos, frameCount)
        patchIntLe(avihSuggestedBufferSizePos, maxFrameSize)
        patchIntLe(strhLengthPos, frameCount)
        patchIntLe(strhSuggestedBufferSizePos, maxFrameSize)
        patchIntLe(strfImageSizePos, maxFrameSize)

        raf.close()

        return file
    }

    private fun writeHeader() {
        writeFourCc("RIFF")
        riffSizePos = raf.filePointer
        writeIntLe(0)
        writeFourCc("AVI ")

        writeFourCc("LIST")
        hdrlSizePos = raf.filePointer
        writeIntLe(0)
        writeFourCc("hdrl")

        writeAviHeader()
        writeStreamList()

        writeFourCc("LIST")
        moviSizePos = raf.filePointer
        writeIntLe(0)
        writeFourCc("movi")
        moviDataStart = raf.filePointer
    }

    private fun writeAviHeader() {
        writeFourCc("avih")
        writeIntLe(56)

        writeIntLe(1_000_000 / fps)
        writeIntLe(0)
        writeIntLe(0)
        writeIntLe(0x10)

        avihFramesPos = raf.filePointer
        writeIntLe(0)

        writeIntLe(0)
        writeIntLe(1)

        avihSuggestedBufferSizePos = raf.filePointer
        writeIntLe(0)

        writeIntLe(width)
        writeIntLe(height)

        writeIntLe(0)
        writeIntLe(0)
        writeIntLe(0)
        writeIntLe(0)
    }

    private fun writeStreamList() {
        writeFourCc("LIST")
        val strlSizePos = raf.filePointer
        writeIntLe(0)
        writeFourCc("strl")

        writeStreamHeader()
        writeBitmapInfoHeader()

        val after = raf.filePointer
        patchIntLe(strlSizePos, (after - strlSizePos - 4).toInt())
    }

    private fun writeStreamHeader() {
        writeFourCc("strh")
        writeIntLe(56)

        writeFourCc("vids")
        writeFourCc("MJPG")
        writeIntLe(0)
        writeIntLe(0)
        writeIntLe(0)
        writeIntLe(1)
        writeIntLe(fps)
        writeIntLe(0)

        strhLengthPos = raf.filePointer
        writeIntLe(0)

        strhSuggestedBufferSizePos = raf.filePointer
        writeIntLe(0)

        writeIntLe(-1)
        writeIntLe(0)

        writeShortLe(0)
        writeShortLe(0)
        writeShortLe(width)
        writeShortLe(height)
    }

    private fun writeBitmapInfoHeader() {
        writeFourCc("strf")
        writeIntLe(40)

        writeIntLe(40)
        writeIntLe(width)
        writeIntLe(height)
        writeShortLe(1)
        writeShortLe(24)
        writeFourCc("MJPG")

        strfImageSizePos = raf.filePointer
        writeIntLe(0)

        writeIntLe(0)
        writeIntLe(0)
        writeIntLe(0)
        writeIntLe(0)
    }

    private fun patchIntLe(pos: Long, value: Int) {
        val old = raf.filePointer
        raf.seek(pos)
        writeIntLe(value)
        raf.seek(old)
    }

    private fun writeFourCc(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        if (bytes.size != 4) {
            throw IllegalArgumentException("FourCC must be 4 bytes")
        }

        raf.write(bytes)
    }

    private fun writeIntLe(value: Int) {
        raf.write(value and 0xff)
        raf.write((value shr 8) and 0xff)
        raf.write((value shr 16) and 0xff)
        raf.write((value shr 24) and 0xff)
    }

    private fun writeShortLe(value: Int) {
        raf.write(value and 0xff)
        raf.write((value shr 8) and 0xff)
    }
}