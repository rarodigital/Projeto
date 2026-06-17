package com.raro.controletv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/** Descobre TV Boxes na rede local procurando quem tem a porta do ADB (5555) aberta. */
object NetworkScanner {

    /** IP IPv4 local do celular (ex: 192.168.0.23). */
    fun localIpv4(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Varre a sub-rede /24 e retorna os IPs com a porta [port] aberta. */
    suspend fun scan(port: Int = 5555, timeoutMs: Int = 350): List<String> = withContext(Dispatchers.IO) {
        val ip = localIpv4() ?: return@withContext emptyList()
        val prefix = ip.substringBeforeLast(".") + "."
        val found = Collections.synchronizedList(mutableListOf<String>())
        (1..254).map { host ->
            async {
                val target = prefix + host
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(target, port), timeoutMs)
                        found.add(target)
                    }
                } catch (_: Exception) {
                }
            }
        }.awaitAll()
        found.sortedBy { it.substringAfterLast(".").toIntOrNull() ?: 0 }
    }
}
