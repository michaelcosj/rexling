package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** When a shortcut is followed by Enter. */
internal enum class ShortcutEnter(val label: String,val detail: String) {
    NEVER("never","Keys are sent exactly as written."),
    TEXT("always","Plain text gets Enter; key sequences such as <C-c> do not."),
    EX_COMMANDS(": commands","Only plain : commands get Enter, as in Neovim.");
}

/**
 * A set of shortcuts shown while a matching program is in the foreground.
 * [match] holds executable names, such as lazygit, or path globs using * and ?;
 * [prefix] is key notation sent before every shortcut, such as Neovim's `<C-\><C-n>`.
 */
internal data class ShortcutProfile(val id: String,val name: String,val match: List<String>,val prefix: String="",
    val enter: ShortcutEnter=ShortcutEnter.TEXT,val enabled: Boolean=true,val items: List<NeovimShortcut> = emptyList()) {
    val matchText: String get()=match.joinToString(", ")
}

internal object ShortcutProfiles {
    const val NEOVIM="neovim"
    const val SHELL="shell"
    private const val MAX_PROFILES=32

    fun neovim(items: List<NeovimShortcut> = NeovimShortcuts.defaults,enabled: Boolean=true)=
        ShortcutProfile(NEOVIM,"Neovim",listOf("nvim","neovim"),"<C-\\><C-n>",ShortcutEnter.EX_COMMANDS,enabled,items)

    val shellDefaults: List<NeovimShortcut> = NeovimShortcuts.parse("""
        gs = git status
        gd = git diff
        log = git log --oneline -20
        ls = ls -la
        !push = git push
        clear
    """.trimIndent())

    /** Matches a shell only while it is the foreground program, which means it is at its prompt. */
    fun shell(items: List<NeovimShortcut> = shellDefaults,enabled: Boolean=true)=
        ShortcutProfile(SHELL,"Shell",listOf("fish","zsh","bash","sh"),"",ShortcutEnter.TEXT,enabled,items)

    fun defaults(legacyItems: List<NeovimShortcut> = NeovimShortcuts.defaults,legacyEnabled: Boolean=true)=
        listOf(neovim(legacyItems,legacyEnabled),shell())

    fun newId()="p-"+UUID.randomUUID().toString().take(8)

    private fun baseName(value: String)=value.replace('\\','/').lowercase(Locale.ROOT).substringAfterLast('/').trimStart('-')
        .removeSuffix(".exe").removeSuffix(".cmd")

    fun matches(pattern: String,program: ProgramIdentity): Boolean {
        val wanted=pattern.trim().lowercase(Locale.ROOT)
        if(wanted.isEmpty() || program.pending) return false
        val names=program.matchNames
        if(wanted.contains('*') || wanted.contains('/') || wanted.contains('?')) {
            val glob=Regex(wanted.split('*').joinToString(".*") {part->part.split('?').joinToString(".") {Regex.escape(it)}})
            return names.any {glob.matches(it.replace('\\','/').lowercase(Locale.ROOT))}
        }
        return names.any {baseName(it)==wanted}
    }

    fun matches(profile: ShortcutProfile,program: ProgramIdentity)=profile.match.any {matches(it,program)}

    /** 0 for an exact program name, 1 for a path glob, 2 for a bare `*`; null when nothing matches. */
    private fun rank(profile: ShortcutProfile,program: ProgramIdentity): Int? = profile.match.filter {matches(it,program)}.minOfOrNull {p->
        val wanted=p.trim()
        when {wanted.all {it=='*'}->2;wanted.contains('*') || wanted.contains('/') || wanted.contains('?')->1;else->0}
    }

    /** The most specific enabled, non-empty profile that matches; list order breaks ties. */
    fun active(profiles: List<ShortcutProfile>,program: ProgramIdentity?): ShortcutProfile? {
        if(program==null || program.pending) return null
        return profiles.filter {it.enabled && it.items.isNotEmpty()}.mapNotNull {p->rank(p,program)?.let {p to it}}.minByOrNull {it.second}?.first
    }

    fun parseMatch(text: String): List<String> {
        val patterns=text.split(',','\n').map {it.trim()}.filter {it.isNotEmpty()}.distinct()
        require(patterns.isNotEmpty()) {"Add at least one program name, such as lazygit."}
        require(patterns.size<=16) {"Use at most 16 program names."}
        require(patterns.all {p->p.length<=128 && p.none {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}}) {"Use program names or paths of at most 128 characters."}
        return patterns
    }

    /** Validates a profile the way the editor builds it; returns the profile, or throws with a message for the user. */
    fun build(id: String,name: String,match: String,prefix: String,enter: ShortcutEnter,enabled: Boolean,shortcuts: String,others: List<ShortcutProfile>): ShortcutProfile {
        val cleanName=name.trim()
        require(cleanName.isNotEmpty() && cleanName.length<=40 && cleanName.none {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}) {"Use a profile name of 1–40 characters."}
        require(others.none {it.id!=id && it.name.equals(cleanName,ignoreCase=true)}) {"Another profile is already called $cleanName."}
        val cleanPrefix=prefix.trim()
        require(cleanPrefix.length<=64) {"Use at most 64 characters for keys sent first."}
        NeovimShortcuts.decodeKeys(cleanPrefix)
        return ShortcutProfile(id,cleanName,parseMatch(match),cleanPrefix,enter,enabled,NeovimShortcuts.parse(shortcuts))
    }

    fun encode(profiles: List<ShortcutProfile>)=JSONArray().apply {profiles.forEach {p->
        put(JSONObject().put("id",p.id).put("name",p.name).put("match",JSONArray(p.match)).put("prefix",p.prefix)
            .put("enter",p.enter.name).put("enabled",p.enabled).put("items",JSONArray(NeovimShortcuts.encode(p.items))))
    }}.toString()

    /** Invalid profiles are dropped one by one, so a single bad entry cannot erase the rest. */
    fun decode(text: String?,fallback: ()->List<ShortcutProfile>): List<ShortcutProfile> {
        if(text==null) return fallback()
        val array=runCatching {JSONArray(text)}.getOrNull() ?: return fallback()
        val profiles=(0 until array.length()).mapNotNull {i-> runCatching {
            val o=array.getJSONObject(i)
            val stored=o.getJSONArray("items")
            val items=(0 until stored.length()).map {j->stored.getJSONObject(j).let {NeovimShortcut(it.getString("label"),it.getString("keys"),it.optString("group"),it.optBoolean("confirm"))}}
            val enter=runCatching {ShortcutEnter.valueOf(o.getString("enter"))}.getOrDefault(ShortcutEnter.TEXT)
            val match=o.getJSONArray("match").let {m->(0 until m.length()).map {m.getString(it)}}
            build(o.getString("id"),o.getString("name"),match.joinToString(","),o.optString("prefix"),enter,o.optBoolean("enabled",true),NeovimShortcuts.editorText(items),emptyList())
        }.getOrNull()}.distinctBy {it.id}.distinctBy {it.name.lowercase(Locale.ROOT)}.take(MAX_PROFILES)
        return profiles.ifEmpty(fallback)
    }

    fun canAdd(profiles: List<ShortcutProfile>)=profiles.size<MAX_PROFILES

    /** Use live identity and a captured input target, never a cached icon or a replacement pane. */
    fun input(state: AppState,profile: ShortcutProfile,item: NeovimShortcut,target: String,currentTarget: String,active: Boolean,pending: Boolean): String? {
        if(!active || pending || target!=currentTarget || !state.connected || !state.controlled || state.creating || state.screen==null ||
            state.selectedBlock.isBlank() || state.selectedBlock in state.cachedPrograms || item !in profile.items) return null
        val program=state.programs[state.selectedBlock] ?: return null
        if(program.pending || !matches(profile,program)) return null
        return runCatching {
            require(NeovimShortcuts.parse(NeovimShortcuts.editorText(listOf(item))).single()==item)
            val keys=NeovimShortcuts.decodeKeys(item.keys)
            val body=keys.trimStart('\u001b')
            val enter=when(profile.enter) {
                ShortcutEnter.NEVER->false
                ShortcutEnter.TEXT->keys.isNotEmpty() && keys.none {it.code<0x20 || it=='\u007f'}
                // Submit plain Ex commands, while preserving explicitly encoded key sequences.
                ShortcutEnter.EX_COMMANDS->body.startsWith(":") && body.none {it=='\r' || it=='\n' || it=='\u001b'}
            }
            NeovimShortcuts.decodeKeys(profile.prefix)+keys+if(enter) "\r" else ""
        }.getOrNull()
    }
}
