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

    // ---- Operações só do Box (roteadas pelo transporte) ----
    fun openUrl(url: String) = if (useReceiver) boxReceiver.openUrl(url) else box.openUrl(url)
    fun youtubeSearch(q: String) = if (useReceiver) boxReceiver.youtubeSearch(q) else box.youtubeSearch(q)
    fun boxText(t: String) = if (useReceiver) boxReceiver.text(t) else box.text(t)
    fun launchApp(pkg: String) = if (useReceiver) boxReceiver.launchApp(pkg) else box.launchApp(pkg)

    /** Lista de apps do Box como pares (nome, pacote). */
    fun listApps(): List<Pair<String, String>> =
        if (useReceiver) boxReceiver.listApps()
        else box.listUserApps().map { it to it }

    val boxHost: String get() = if (useReceiver) boxReceiver.host else box.host
}
