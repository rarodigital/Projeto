package com.raro.controletv

import dadb.Dadb

/** Códigos de tecla do Android (input keyevent). */
object Key {
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23          // DPAD_CENTER
    const val BACK = 4
    const val HOME = 3
    const val MENU = 82
    const val VOL_UP = 24
    const val VOL_DOWN = 25
    const val MUTE = 164
    const val POWER = 26
    const val PLAY_PAUSE = 85
}

/**
 * Controla um TV Box Android via ADB sobre a rede (porta 5555).
 * Na 1ª conexão o TV mostra o aviso "Permitir depuração USB?" — aceitar (marcar "sempre permitir").
 * Todas as chamadas de rede devem rodar fora da main thread.
 */
class TvBoxController {

    @Volatile
    private var dadb: Dadb? = null

    var host: String = ""
        private set

    /** Conecta no TV Box. Lança exceção se falhar (sem rede / ADB desligado / não pareado). */
    fun connect(ip: String, port: Int = 5555) {
        disconnect()
        val d = Dadb.create(ip, port)
        // teste rápido pra confirmar que o shell responde
        d.shell("echo ok")
        dadb = d
        host = ip
    }

    fun isConnected(): Boolean = dadb != null

    /** Envia uma tecla (input keyevent). */
    fun keyevent(code: Int) {
        val d = dadb ?: throw IllegalStateException("Não conectado ao TV Box.")
        d.shell("input keyevent $code")
    }

    /** Digita texto no campo focado da TV. */
    fun text(t: String) {
        val d = dadb ?: throw IllegalStateException("Não conectado ao TV Box.")
        val safe = t.replace("\"", "").replace(" ", "%s")
        d.shell("input text \"$safe\"")
    }

    fun disconnect() {
        try {
            dadb?.close()
        } catch (_: Exception) {
        }
        dadb = null
    }
}
