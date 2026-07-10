package com.example.clock

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 极简 SNTP 客户端：向 NTP 服务器（UDP 123）请求时间，
 * 返回「服务器真实时间 - 本机时间」的偏移（毫秒），用于校正时钟。
 */
object SntpClient {

    private const val NTP_PORT = 123
    private const val NTP_MODE_CLIENT = 3
    private const val NTP_VERSION = 3
    private const val NTP_PACKET_SIZE = 48
    private const val NTP_RECEIVE_OFFSET = 32
    private const val NTP_TRANSMIT_OFFSET = 40
    private const val SECONDS_1900_TO_1970 = 2208988800L
    private const val TIMEOUT_MS = 8000

    /**
     * @return 本机时间需要加上的偏移量（毫秒）即为真实时间；失败抛出异常。
     */
    @Throws(Exception::class)
    fun requestTime(host: String): Long {
        val address = InetAddress.getByName(host)
        val socket = DatagramSocket()
        socket.soTimeout = TIMEOUT_MS
        try {
            val buffer = ByteArray(NTP_PACKET_SIZE)
            buffer[0] = (NTP_MODE_CLIENT or (NTP_VERSION shl 3)).toByte()

            val requestTime = System.currentTimeMillis()
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))

            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val responseTime = System.currentTimeMillis()

            val receiveTime = readTimeStamp(buffer, NTP_RECEIVE_OFFSET)
            val transmitTime = readTimeStamp(buffer, NTP_TRANSMIT_OFFSET)

            // 经典 SNTP 偏移计算
            return ((receiveTime - requestTime) + (transmitTime - responseTime)) / 2
        } finally {
            socket.close()
        }
    }

    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        return (seconds - SECONDS_1900_TO_1970) * 1000 + ((fraction * 1000) shr 32)
    }

    private fun read32(buffer: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..3) {
            result = (result shl 8) or (buffer[offset + i].toLong() and 0xff)
        }
        return result
    }
}
