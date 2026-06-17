package com.raro.controletv

/**
 * Estado compartilhado do controle. A tela principal (MainActivity) e o controle
 * flutuante (FloatingService) usam a MESMA conexão.
 *
 * O TV Box tem 2 transportes:
 *  - "receiver": app instalado no Box (porta 8787) — caminho sem ADB (recomendado).
 *  - "adb": conexão ADB direta (porta 5555) — avançado, exige ADB de rede no Box.
 */
object Remote {
    val box = TvBoxController()           // ADB
    val boxReceiver = BoxReceiverController()  // app no Box
    val lg = LgTvController()

    /** Aparelho ativo: "box" ou "lg". */
    @Volatile
    var device: String = "box"

    /** Transporte do Box: "receiver" ou "adb". */
    @Volatile
    var boxMode: String = "receiver"

    private val useReceiver: Boolean get() = boxMode == "receiver"

    /** Roteia a ação pro aparelho/transporte ativo. Chamar fora da main thread. */
    fun send(action: RemoteAction) {
        when {
            device == "lg" -> lg.send(action)
            useReceiver -> boxReceiver.send(action)
            else -> box.send(action)
        }
    }

    // ---- Operações só do Box (roteadas pelo transporte). Retornam "ok" ou o erro. ----
    fun openUrl(url: String): String =
        if (useReceiver) boxReceiver.openUrl(url) else { box.openUrl(url); "ok" }
    fun youtubeSearch(q: String): String =
        if (useReceiver) boxReceiver.youtubeSearch(q) else { box.youtubeSearch(q); "ok" }
    fun boxText(t: String): String =
        if (useReceiver) boxReceiver.text(t) else { box.text(t); "ok" }
    fun launchApp(pkg: String): String =
        if (useReceiver) boxReceiver.launchApp(pkg) else { box.launchApp(pkg); "ok" }

    /** Lista de apps do Box como pares (nome, pacote). */
    fun listApps(): List<Pair<String, String>> =
        if (useReceiver) boxReceiver.listApps()
        else box.listUserApps().map { it to it }

    // ---- Mouse / touchpad (gestos na tela do Box) ----
    fun boxTap(x: Int, y: Int) = if (useReceiver) boxReceiver.tap(x, y) else box.tap(x, y)
    fun boxSwipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int) =
        if (useReceiver) boxReceiver.swipe(x1, y1, x2, y2, dur) else box.swipe(x1, y1, x2, y2, dur)
    fun boxScreenSize(): Pair<Int, Int> =
        if (useReceiver) boxReceiver.screenW to boxReceiver.screenH else box.screenSize()

    val boxHost: String get() = if (useReceiver) boxReceiver.host else box.host
}
