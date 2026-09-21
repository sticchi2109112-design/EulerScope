package it.rs.eulerscope.core

/**
 * Le modalità differiscono solo per banda temporale, guadagno e per cosa
 * viene mostrato. La pipeline sottostante è la stessa.
 */
enum class Mode(
    val label: String,
    val fLow: Float,
    val fHigh: Float,
    val alpha: Float,
    val hint: String
) {
    VITALS(
        "Vitali", 0.7f, 3.5f, 12f,
        "Inquadra il volto da fermo, luce stabile, telefono su supporto."
    ),
    MAGNIFY(
        "Magnifica", 0.5f, 5.0f, 25f,
        "Amplifica i movimenti nella banda scelta. Regola banda e guadagno."
    ),
    STRUCTURES(
        "Strutture", 1.0f, 30.0f, 20f,
        "Macchine, motori, strutture. Lo spettro mostra le frequenze presenti."
    ),
    THERMAL_FLOW(
        "Flussi d'aria", 0.1f, 1.5f, 40f,
        "Spifferi e moti convettivi. Serve contrasto termico e sfondo texturizzato."
    ),
    LIE_GAME(
        "Gioco", 0.7f, 3.5f, 12f,
        "Gioco da festa. Non rileva le bugie: mostra scostamenti fisiologici reali."
    );

    val isGame: Boolean get() = this == LIE_GAME
}

/**
 * Banda personalizzabile per le modalità che la espongono.
 * I limiti dipendono dall'fps reale: oltre fps/2 non c'è informazione.
 */
data class BandSetting(val fLow: Float, val fHigh: Float, val alpha: Float) {
    fun clampedTo(fps: Float): BandSetting {
        val nyq = fps / 2f
        val hi = fHigh.coerceAtMost(nyq * 0.9f)
        val lo = fLow.coerceIn(0.05f, hi - 0.05f)
        return BandSetting(lo, hi, alpha)
    }
}
