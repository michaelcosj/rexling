package dev.shulhi.rex

/** Phone-only presentation: the server's layout and floating state stay intact. */
internal class FloatingPaneNavigation {
    private val lastTiled = mutableMapOf<Pair<String,String>,String>()
    fun remember(session: String,window: WindowItem,block: String) {
        if(window.tiledPanes().any {it.id==block}) lastTiled[session to window.id]=block
    }
    fun returnPane(session: String,window: WindowItem): String? {
        val tiled=window.tiledPanes()
        return tiled.firstOrNull {it.id==lastTiled[session to window.id]}?.id ?: tiled.firstOrNull()?.id
    }
}

internal fun WindowItem.floatingPanes(): List<BlockItem> {
    val ids=layout.filter {it.floating}.mapTo(hashSetOf()) {it.id}
    return blocks.filter {it.id in ids}
}
internal fun WindowItem.tiledPanes(): List<BlockItem> {
    val floating=floatingPanes().mapTo(hashSetOf()) {it.id}
    return blocks.filterNot {it.id in floating}
}
internal fun WindowItem.carouselPanes(minimize: Boolean,selected: String): List<BlockItem> =
    if(!minimize) blocks else if(floatingPanes().any {it.id==selected}) blocks.filter {it.id==selected} else tiledPanes()
