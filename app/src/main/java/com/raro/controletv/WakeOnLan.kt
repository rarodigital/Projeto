package com.raro.controletv

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Liga aparelhos que suportam Wake-on-LAN (magic packet UDP pro MAC). */
object WakeOnLan {
    fun send(mac: String) {
        val clean = mac.replace(Regex("[^0-9a-fA-F]"), "")
        require(clean.length == 12) { "MAC inválido (use formato AA:BB:CC:DD:EE:FF)" }
        val macBytes = ByteArray(6) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val payload = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) payload[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(macBytes, 0, payload, 6 + i * 6, 6)
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 9))
        }
    }
}
