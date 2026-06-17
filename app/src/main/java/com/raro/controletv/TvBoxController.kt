package com.raro.controletv

import dadb.AdbKeyPair
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

    /** Conecta no TV Box. Passe um [keyPair] persistente pra a TV lembrar a autorização
     * e NÃO pedir "Permitir depuração USB?" toda vez. Lança exceção se falhar. */
    fun connect(ip: String, port: Int = 5555, keyPair: AdbKeyPair? = null) {
        disconnect()
        val d = if (keyPair != null) Dadb.create(ip, port, keyPair) else Dadb.create(ip, port)
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

    /** Traduz a ação genérica para o keyevent do Android e envia. */
    fun send(action: RemoteAction) {
        val code = when (action) {
            RemoteAction.UP -> 19
            RemoteAction.DOWN -> 20
            RemoteAction.LEFT -> 21
            RemoteAction.RIGHT -> 22
            RemoteAction.OK -> 23
            RemoteAction.BACK -> 4
            RemoteAction.HOME -> 3
            RemoteAction.MENU -> 82
            RemoteAction.VOL_UP -> 24
            RemoteAction.VOL_DOWN -> 25
            RemoteAction.MUTE -> 164
            RemoteAction.POWER -> 26
            RemoteAction.PLAY_PAUSE -> 85
        }
        keyevent(code)
    }

    /** Digita texto no campo focado da TV. */
    fun text(t: String) {
        val d = dadb ?: throw IllegalStateException("Não conectado ao TV Box.")
        val safe = t.replace("\"", "").replace(" ", "%s")
        d.shell("input text \"$safe\"")
    }

    /** Apaga 1 caractere (backspace). */
    fun backspace() = keyevent(67)
    /** Confirma / Enter. */
    fun enter() = keyevent(66)

    /** Toque (clique do mouse) numa coordenada absoluta da tela. */
    fun tap(x: Int, y: Int) {
        val d = dadb ?: throw IllegalStateException("Não conectado ao TV Box.")
        d.shell("input tap $x $y")
    }

    /** Deslize (arrastar / rolar) entre duas coordenadas, em [ms] milissegundos. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 80) {
        val d = dadb ?: throw IllegalStateException("Não conectado ao TV Box.")
        d.shell("input swipe $x1 $y1 $x2 $y2 $ms")
    }

    /** Resolução da tela (largura, altura). Faz cache pra não consultar toda hora. */
    private var cachedSize: Pair<Int, Int>? = null
    fun screenSize(): Pair<Int, Int> {
        cachedSize?.let { return it }
        // "Physical size: 1920x1080" (pode ter "Override size:" também — pega o último número)
        val out = sh("wm size")
        val m = Regex("(\\d+)x(\\d+)").findAll(out).lastOrNull()
        val size = if (m != null) m.groupValues[1].toInt() to m.groupValues[2].toInt() else 1920 to 1080
        cachedSize = size
        return size
    }

    private fun sh(cmd: String): String {
        val d = dadb ?: throw IllegalStateException("Não conectado ao TV Box.")
        return d.shell(cmd).allOutput
    }

    /** Abre um app pelo nome do pacote (ex: com.google.android.youtube). */
    fun launchApp(pkg: String) {
        sh("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    }

    /** YouTube: tenta a versão de TV; se não tiver, a versão comum. */
    fun launchYoutube() {
        val out = sh("monkey -p com.google.android.youtube.tv -c android.intent.category.LAUNCHER 1")
        if (out.contains("No activities found", true) || out.contains("Error", true)) {
            sh("monkey -p com.google.android.youtube -c android.intent.category.LAUNCHER 1")
        }
    }

    fun openSettings() = run { sh("am start -a android.settings.SETTINGS"); Unit }
    fun openAppsSettings() = run { sh("am start -a android.settings.APPLICATION_SETTINGS"); Unit }
    /** Libera cache de apps (sem root). */
    fun clearCache() = run { sh("pm trim-caches 9999999999"); Unit }
    /** Fecha apps em segundo plano. */
    fun closeApps() = run { sh("am kill-all"); Unit }

    /** Lista os apps instalados pelo usuário (pacotes). */
    fun listUserApps(): List<String> =
        sh("pm list packages -3").lineSequence()
            .map { it.trim().removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .sorted()
            .toList()

    fun disconnect() {
        try {
            dadb?.close()
        } catch (_: Exception) {
        }
        dadb = null
        cachedSize = null
    }
}
