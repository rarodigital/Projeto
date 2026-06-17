package com.raro.controletv

import java.net.HttpURLConnection
import java.net.URL

/**
 * Controla TVs LG antigas (NetCast / UDAP 2.0, modelos 2012-2013 como a 50PH4700).
 * HTTP + XML na porta 8080. Pareamento por PIN exibido na tela da TV.
 * NÃO é webOS (esse usa WebSocket SSAP). Todas as chamadas devem rodar fora da main thread.
 */
class LgTvController {

    var host: String = ""
        private set

    @Volatile
    var paired: Boolean = false
        private set

    private fun post(path: String, xml: String): Int {
        val conn = (URL("http://$host:8080$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/atom+xml; charset=utf-8")
            setRequestProperty("Connection", "Close")
            setRequestProperty("User-Agent", "UDAP/2.0 ControleTV")
        }
        try {
            conn.outputStream.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            try { conn.inputStream.close() } catch (_: Exception) { conn.errorStream?.close() }
            return code
        } finally {
            conn.disconnect()
        }
    }

    /** Pede pra TV EXIBIR o PIN de pareamento na tela. */
    fun requestPairingKey(ip: String) {
        host = ip.trim()
        post(
            "/udap/api/pairing",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><envelope><api type=\"pairing\">" +
                "<name>showKey</name></api></envelope>"
        )
    }

    /** Autentica usando o PIN que apareceu na TV. */
    fun pair(ip: String, pin: String) {
        host = ip.trim()
        val code = post(
            "/udap/api/pairing",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><envelope><api type=\"pairing\">" +
                "<name>hello</name><value>${pin.trim()}</value><port>8080</port></api></envelope>"
        )
        if (code !in 200..299) {
            throw RuntimeException("Pareamento falhou (HTTP $code). Confira o PIN e o IP.")
        }
        paired = true
    }

    /** Envia um código de tecla UDAP cru (HandleKeyInput). */
    fun sendKey(code: Int) {
        post(
            "/udap/api/command",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><envelope><api type=\"command\">" +
                "<name>HandleKeyInput</name><value>$code</value></api></envelope>"
        )
    }

    /** Traduz a ação genérica para o código UDAP e envia. */
    fun send(action: RemoteAction) = sendKey(lgCode(action))

    /** Troca a entrada/fonte da TV (botão INPUT do controle). Código UDAP 47 (a confirmar por modelo). */
    fun switchInput() = sendKey(47)

    // Códigos UDAP (NetCast). Alguns podem variar por modelo — ajustar se necessário.
    private fun lgCode(a: RemoteAction): Int = when (a) {
        RemoteAction.UP -> 12
        RemoteAction.DOWN -> 13
        RemoteAction.LEFT -> 14
        RemoteAction.RIGHT -> 15
        RemoteAction.OK -> 20
        RemoteAction.BACK -> 23
        RemoteAction.HOME -> 21
        RemoteAction.MENU -> 22
        RemoteAction.VOL_UP -> 24
        RemoteAction.VOL_DOWN -> 25
        RemoteAction.MUTE -> 26
        RemoteAction.POWER -> 1
        RemoteAction.PLAY_PAUSE -> 33
    }
}
