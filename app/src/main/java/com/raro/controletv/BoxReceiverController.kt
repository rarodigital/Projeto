package com.raro.controletv

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fala com o app receptor instalado NO TV Box (porta 8787), via HTTP.
 * É o caminho "sem ADB": o receptor executa os comandos lá dentro.
 * Todas as chamadas devem rodar fora da main thread.
 */
class BoxReceiverController {

    companion object { const val PORT = 8787 }

    var host: String = ""
        private set

    @Volatile
    var connected: Boolean = false
        private set

    var screenW: Int = 0
        private set
    var screenH: Int = 0
        private set

    private fun get(path: String): String {
        val conn = (URL("http://$host:$PORT$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Testa se o receptor responde nesse IP. Lança exceção se não. */
    fun connect(ip: String) {
        host = ip.trim()
        val r = get("/ping")
        if (!r.startsWith("controletv-receiver")) throw RuntimeException("Não é o receptor (resposta: $r)")
        connected = true
    }

    /** True se o /ping disser que a acessibilidade está ligada (acc=1). */
    fun accessibilityReady(): Boolean = try { get("/ping").contains("acc=1") } catch (_: Exception) { false }

    fun send(action: RemoteAction) {
        val path = when (action) {
            RemoteAction.UP -> "/key/UP"
            RemoteAction.DOWN -> "/key/DOWN"
            RemoteAction.LEFT -> "/key/LEFT"
            RemoteAction.RIGHT -> "/key/RIGHT"
            RemoteAction.OK -> "/key/OK"
            RemoteAction.BACK -> "/key/BACK"
            RemoteAction.HOME -> "/key/HOME"
            RemoteAction.MENU -> "/key/MENU"
            RemoteAction.VOL_UP -> "/vol/up"
            RemoteAction.VOL_DOWN -> "/vol/down"
            RemoteAction.MUTE -> "/vol/mute"
            RemoteAction.POWER -> "/power"
            RemoteAction.PLAY_PAUSE -> "/media/playpause"
        }
        get(path)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun openUrl(url: String): String = get("/open?url=${enc(url)}")
    fun youtubeSearch(query: String): String =
        get("/open?url=${enc("https://www.youtube.com/results?search_query=$query")}")
    fun text(t: String): String = get("/text?t=${enc(t)}")
    fun launchApp(pkg: String): String = get("/launch?pkg=${enc(pkg)}")

    // ---- Mouse (gestos de toque na tela da TV) ----
    /** Busca a resolução da tela do Box (pra mapear o touchpad). */
    fun fetchSize() {
        try {
            val r = get("/size").trim()        // "1920x1080"
            val p = r.split("x")
            if (p.size == 2) { screenW = p[0].trim().toInt(); screenH = p[1].trim().toInt() }
        } catch (_: Exception) {}
    }

    fun tap(x: Int, y: Int) { get("/tap?x=$x&y=$y") }
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 120) {
        get("/swipe?x1=$x1&y1=$y1&x2=$x2&y2=$y2&dur=$dur")
    }

    /** Lista apps do Box: pares (nome, pacote). */
    fun listApps(): List<Pair<String, String>> =
        get("/apps").lineSequence()
            .mapNotNull { val p = it.split("\t"); if (p.size >= 2) p[0] to p[1] else null }
            .toList()

    fun disconnect() { connected = false }
}
