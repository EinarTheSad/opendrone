package pl.einarthesad.opendrone

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class DroneVideoReceiver(
    private val config: ConnectionConfig,
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile
    private var running = false

    private var socket: DatagramSocket? = null

    private val startVideo = byteArrayOf(0x42, 0x76)
    private val stopVideo = byteArrayOf(0x42, 0x77)

    fun start() {
        if (running) return

        running = true

        thread(name = "DroneVideoReceiver") {
            runReceiver()
        }
    }

    fun stop() {
        running = false

        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun runReceiver() {
        var currentFrameId: Int? = null
        var parts = ArrayList<ByteArray>()

        try {
            val droneAddress = InetAddress.getByName(config.droneIp)

            val s = DatagramSocket(config.localVideoPort)
            socket = s

            s.soTimeout = 1000
            s.receiveBufferSize = 262144

            onStatus("UDP ${config.droneIp}:${config.droneVideoPort} -> ${config.localVideoPort}")

            repeat(3) {
                sendPacket(s, droneAddress, startVideo)
                Thread.sleep(100)
            }

            onStatus("Video started")

            val recvBuffer = ByteArray(4096)
            val packet = DatagramPacket(recvBuffer, recvBuffer.size)

            while (running) {
                try {
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val len = packet.length
                val data = packet.data

                if (len < 8) continue
                if (data[4] != 'T'.code.toByte()) continue
                if (data[5] != 'Z'.code.toByte()) continue
                if (data[6] != 'H'.code.toByte()) continue

                val frameId = data[0].toInt() and 0xff
                val lastFlag = data[1].toInt() and 0xff
                val expectedPackets = data[2].toInt() and 0xff

                if (currentFrameId == null) {
                    currentFrameId = frameId
                    parts = ArrayList()
                } else if (frameId != currentFrameId) {
                    currentFrameId = frameId
                    parts = ArrayList()
                }

                val payload = data.copyOfRange(8, len)
                parts.add(payload)

                if (lastFlag == 1) {
                    if (parts.size == expectedPackets) {
                        val raw = concat(parts)
                        val jpg = extractJpeg(raw)

                        if (jpg != null) {
                            onFrame(jpg)
                        }
                    }

                    currentFrameId = null
                    parts = ArrayList()
                }
            }

            repeat(3) {
                sendPacket(s, droneAddress, stopVideo)
                Thread.sleep(50)
            }

            s.close()
            onStatus("Stopped")
        } catch (e: Exception) {
            onStatus("Error: ${e.message}")
        }
    }

    private fun sendPacket(socket: DatagramSocket, address: InetAddress, data: ByteArray) {
        val packet = DatagramPacket(data, data.size, address, config.droneVideoPort)
        socket.send(packet)
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()

        for (p in parts) {
            out.write(p)
        }

        return out.toByteArray()
    }

    private fun extractJpeg(raw: ByteArray): ByteArray? {
        var soi = -1
        var eoi = -1

        for (i in 0 until raw.size - 1) {
            if (soi == -1 &&
                raw[i] == 0xff.toByte() &&
                raw[i + 1] == 0xd8.toByte()
            ) {
                soi = i
            }

            if (soi != -1 &&
                raw[i] == 0xff.toByte() &&
                raw[i + 1] == 0xd9.toByte()
            ) {
                eoi = i + 2
                break
            }
        }

        if (soi == -1 || eoi == -1 || eoi <= soi) return null

        val jpg = raw.copyOfRange(soi, eoi)

        if (jpg.size < 3000) return null
        if (jpg.size > 500000) return null

        return jpg
    }
}
