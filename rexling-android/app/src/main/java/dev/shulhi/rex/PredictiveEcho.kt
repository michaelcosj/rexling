package dev.shulhi.rex

/** Local echo of typed text before the server answers; the engine draws and checks the guesses. */
internal enum class PredictiveEcho(val wire: String,val label: String,val detail: String) {
    OFF("off","Off","Typed text appears when the server echoes it."),
    SLOW("slow","When slow","Shows typing immediately once the connection is slow, in shells and pi."),
    ALWAYS("always","Always","Always shows typing immediately in shells and pi.");
    companion object {
        /** Cells the engine predicted and the server has not confirmed yet; drawn underlined. */
        const val PREDICTED_CELL=1 shl 30
        fun fromPreference(value: String?)=entries.firstOrNull {it.name==value} ?: SLOW
    }
}
