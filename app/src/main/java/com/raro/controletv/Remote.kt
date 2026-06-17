package com.raro.controletv

/**
 * Estado compartilhado do controle. A tela principal (MainActivity) e o controle
 * flutuante (FloatingService) usam a MESMA conexão — assim a bolha controla o
 * aparelho que já foi conectado no app.
 */
object Remote {
    val box = TvBoxController()
    val lg = LgTvController()

    /** Aparelho ativo: "box" ou "lg". */
    @Volatile
    var device: String = "box"

    /** Roteia a ação pro aparelho ativo. Chamar fora da main thread. */
    fun send(action: RemoteAction) {
        if (device == "box") box.send(action) else lg.send(action)
    }
}
