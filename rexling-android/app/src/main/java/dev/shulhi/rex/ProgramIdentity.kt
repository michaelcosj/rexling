package dev.shulhi.rex

import java.util.Locale

enum class ProgramKind { TERMINAL, CLAUDE, CODEX, NEOVIM, VIM, GIT, PYTHON, NODE, SSH, TMUX, MONITOR, CLOUDFLARE }
data class ProgramIdentity(val kind: ProgramKind, val label: String, val executable: String="",val pending: Boolean=false,val candidates: List<String> = emptyList()) {
    /** Names and paths Rex reported for the foreground program; falls back to the resolved name. */
    internal val matchNames: List<String> get()=candidates.ifEmpty {listOf(executable,label).filter {it.isNotBlank()}.distinct()}
    val title: String get() = when(kind) {
        ProgramKind.CLAUDE -> "claude"; ProgramKind.CODEX -> "codex"; ProgramKind.NEOVIM -> "nvim"
        ProgramKind.VIM -> "vim"; ProgramKind.GIT -> "git"; ProgramKind.PYTHON -> "python"
        ProgramKind.NODE -> "node"; ProgramKind.SSH -> "ssh"; ProgramKind.TMUX -> "tmux"
        ProgramKind.MONITOR -> "monitor"; ProgramKind.CLOUDFLARE -> "cloudflared"; ProgramKind.TERMINAL -> label
    }
    /** Shell OSC titles usually describe cwd; keep the executable name above the directory. */
    private val isShell: Boolean get() = kind == ProgramKind.TERMINAL &&
        label.lowercase(Locale.ROOT).removeSuffix(".exe") in shellNames

    /** A task title is presentation only; executable identity still chooses the icon. */
    fun paneTitle(paneLabel: String, reportedTitle: String = ""): String =
        paneLabel.takeUnless {genericTitle(it) || it.equals(title,true) || it.equals(label,true)} ?: if (isShell) title else reportedTitle.takeUnless(::genericTitle) ?: title

    /** Explicit tab names stay put; defaults follow the remembered focused pane. */
    fun tabTitle(windowLabel: String, paneLabel: String, number: Int, reportedTitle: String = ""): String {
        if (!genericTitle(windowLabel)) return windowLabel
        val focused=paneTitle(paneLabel,reportedTitle)
        return if (focused != Terminal.title) focused else windowLabel.takeIf { it.isNotBlank() && !defaultTitle.matches(it) } ?: "Terminal $number"
    }

    companion object {
        internal val shellNames = setOf("fish", "zsh", "bash", "sh", "dash", "ash", "ksh", "mksh", "tcsh", "csh", "nu", "nushell", "pwsh", "powershell", "cmd")
        private val defaultTitle = Regex("(?:(?:Window|Tab|Block)[ _:-]+[0-9a-f]+(?:-[0-9a-f]+)*|Terminal \\d+|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})", RegexOption.IGNORE_CASE)
        private fun genericTitle(value: String): Boolean = value.isBlank() ||
            value.lowercase(Locale.ROOT) in setOf("shell", "terminal", "window") || defaultTitle.matches(value)
        val Terminal = ProgramIdentity(ProgramKind.TERMINAL, "Terminal")
        val Loading = ProgramIdentity(ProgramKind.TERMINAL,"Loading…",pending=true)
        fun resolve(candidates: List<String>): ProgramIdentity {
            var runtime: ProgramIdentity?=null
            for (candidate in candidates) {
                val path = candidate.replace('\\', '/').lowercase(Locale.ROOT)
                val name = path.substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
                val kind = when {
                    name in listOf("claude", "claude-code", "claude-code.js") || path.contains("/@anthropic-ai/claude-code/") -> ProgramKind.CLAUDE
                    name in listOf("codex", "codex.js", "@openai/codex") || path.contains("/@openai/codex/") || Regex("codex-(aarch64|x86_64)-.*").matches(name) -> ProgramKind.CODEX
                    name in listOf("nvim", "neovim") -> ProgramKind.NEOVIM
                    name in listOf("vim", "vi", "gvim") -> ProgramKind.VIM
                    name in listOf("git", "lazygit", "tig") -> ProgramKind.GIT
                    name == "python" || Regex("python[23](\\.\\d+)?").matches(name) || name == "ipython" -> ProgramKind.PYTHON
                    name in listOf("node", "nodejs", "node.js", "npm", "pnpm", "yarn", "bun", "deno") -> ProgramKind.NODE
                    name in listOf("ssh", "mosh", "mosh-client") -> ProgramKind.SSH
                    name in listOf("tmux", "zellij", "screen") -> ProgramKind.TMUX
                    name in listOf("top", "htop", "btop") -> ProgramKind.MONITOR
                    name in listOf("cloudflared", "cloudflare") -> ProgramKind.CLOUDFLARE
                    else -> null
                }
                if (kind == null) {
                    if(name !in shellNames && name !in setOf("shell","terminal","placeholder","minimal") && ProgramCatalog.tile(name)!=null) return ProgramIdentity(ProgramKind.TERMINAL,name,name,candidates=candidates)
                    continue
                }
                val resolved=ProgramIdentity(kind, when(kind) {
                    ProgramKind.CLAUDE -> "Claude Code"; ProgramKind.CODEX -> "Codex"
                    ProgramKind.NEOVIM -> "Neovim"; ProgramKind.VIM -> "Vim"; ProgramKind.GIT -> "Git"
                    ProgramKind.PYTHON -> "Python"; ProgramKind.NODE -> "JavaScript runtime"
                    ProgramKind.SSH -> "Remote shell"; ProgramKind.TMUX -> "Terminal multiplexer"
                    ProgramKind.MONITOR -> "System monitor"; ProgramKind.CLOUDFLARE -> "Cloudflare"; ProgramKind.TERMINAL -> "Terminal"
                },name,candidates=candidates)
                if(kind in listOf(ProgramKind.NODE,ProgramKind.PYTHON)) {if(runtime==null) runtime=resolved} else return resolved
            }
            runtime?.let {return it}
            val fallback=candidates.firstOrNull {candidate->candidate.replace('\\','/').substringAfterLast('/').trimStart('-').lowercase(Locale.ROOT) !in shellNames} ?: candidates.firstOrNull()
            val name = fallback?.replace('\\', '/')?.substringAfterLast('/')?.trimStart('-')
                ?.filterNot { it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() }?.take(40).orEmpty()
            return ProgramIdentity(ProgramKind.TERMINAL, name.ifBlank { "Terminal" },name,candidates=candidates)
        }
    }
}
