package dev.shulhi.rex

// Space around the terminal grid, in dp. Panes in a multi-pane tab keep extra
// room at the bottom for the pager dots.
internal enum class TerminalPadding(val label: String,val horizontal: Int,val top: Int,val bottom: Int,val pagedBottom: Int) {
    NONE("None",0,0,0,10),
    SMALL("Small",6,4,3,12),
    NORMAL("Normal",12,8,6,14),
}
