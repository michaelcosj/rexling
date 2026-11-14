package dev.shulhi.rex

/** Consumes each remote write once, including writes discarded while not viewing its pane. */
internal class TerminalClipboardDelivery(private val write: (String)->Unit) {
    private var serial=0L
    fun deliver(state: AppState,active: Boolean) {
        val request=state.clipboardWrite ?: return
        if(request.serial<=serial) return
        serial=request.serial
        if(active && (state.connected || request.local) && request.block==state.selectedBlock &&
            request.connection==state.connectionEpoch && request.navigation==state.navigationId &&
            request.text.toByteArray(Charsets.UTF_8).size<=262144) {
            runCatching {write(request.text)}
        }
    }
}
