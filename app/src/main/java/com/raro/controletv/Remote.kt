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

    // ---- Trackpad com cursor na tela (só no modo receiver) ----
    fun mouseShow(on: Boolean) { if (useReceiver) boxReceiver.mouse(on) }
    fun mouseMove(dx: Int, dy: Int) { if (useReceiver) boxReceiver.move(dx, dy) }
    fun mouseClick() { if (useReceiver) boxReceiver.click() }
    fun mouseScroll(d: Int) { if (useReceiver) boxReceiver.scroll(d) }

    val boxHost: String get() = if (useReceiver) boxReceiver.host else box.host
}
