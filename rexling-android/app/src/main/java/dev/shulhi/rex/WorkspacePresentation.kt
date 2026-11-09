package dev.shulhi.rex

import org.json.JSONObject

/** Last-known chrome only. Never supplies input permission, activity or terminal content. */
internal class WorkspacePresentation {
    private data class Pane(val program: ProgramIdentity?,val title: String,val directory: String)
    private val sessions=linkedMapOf<String,Map<String,Pane>>()
    var dirty=false
        private set

    /** Returns true for an identity change, so new icons can be saved without saving every animated title. */
    fun observe(state: AppState): Boolean {
        if(!state.connected || state.creating || state.selectedSession.isBlank() || state.windows.isEmpty()) return false
        val previous=sessions[state.selectedSession].orEmpty()
        val ids=buildList {
            if(state.selectedBlock.isNotBlank()) add(state.selectedBlock)
            state.windows.forEach {w->w.blocks.forEach {add(it.id)}}
        }.distinct().filter {id->state.windows.any {w->w.blocks.any {it.id==id}}}.take(MAX_PANES)
        var identityChanged=false
        val panes=ids.associateWith {id->
            val old=previous[id]
            // The cache keeps presentation only; raw process names never reach saved preferences.
            val live=state.programs[id]?.takeIf {it.known}?.copy(candidates=emptyList())
            val changed=live!=null && live!=old?.program
            if(changed) identityChanged=true
            Pane(live ?: old?.program,
                state.titles[id]?.take(256) ?: if(changed) "" else old?.title.orEmpty(),
                state.directories[id]?.take(1024) ?: old?.directory.orEmpty())
        }
        if(previous!=panes || state.selectedSession !in sessions) {
            sessions.remove(state.selectedSession)
            sessions[state.selectedSession]=panes
            while(sessions.size>MAX_SESSIONS) sessions.remove(sessions.keys.first())
            dirty=true
        }
        return identityChanged
    }

    fun present(state: AppState): AppState {
        val saved=sessions[state.selectedSession].orEmpty()
        val ids=state.windows.flatMap {it.blocks}.map {it.id}
        val cached=mutableSetOf<String>()
        val programs=ids.associateWith {id->
            state.programs[id]?.takeIf {it.known} ?: saved[id]?.program?.also {cached.add(id)} ?: ProgramIdentity.Loading
        }
        val titles=ids.mapNotNull {id->saved[id]?.title?.let {id to it}}.toMap()+state.titles
        val directories=ids.mapNotNull {id->saved[id]?.directory?.let {id to it}}.toMap()+state.directories
        // The freshness marker also emits when a live identity confirms an identical cached icon.
        return state.copy(programs=state.programs+programs,titles=titles,directories=directories,cachedPrograms=cached)
    }

    fun markSaved() {dirty=false}
    fun encode(): String = JSONObject().apply {
        sessions.forEach {(session,panes)->put(session,JSONObject().apply {
            panes.forEach {(id,pane)->put(id,JSONObject().apply {
                pane.program?.let {p->put("kind",p.kind.name);put("label",p.label.take(80));put("executable",p.executable.take(80))}
                put("title",pane.title);put("directory",pane.directory)
            })}
        })}
    }.toString()

    companion object {
        private const val MAX_SESSIONS=8
        private const val MAX_PANES=64
        private val ProgramIdentity.known: Boolean get()=!pending && this!=ProgramIdentity.Terminal
        fun decode(text: String?): WorkspacePresentation {
            val cache=WorkspacePresentation()
            if(text==null || text.length>1_000_000) return cache
            runCatching {
                val root=JSONObject(text)
                root.keys().asSequence().take(MAX_SESSIONS).forEach {session->
                    val panes=root.optJSONObject(session) ?: return@forEach
                    cache.sessions[session]=panes.keys().asSequence().take(MAX_PANES).mapNotNull {id->
                        val value=panes.optJSONObject(id) ?: return@mapNotNull null
                        val program=runCatching {
                            ProgramIdentity(ProgramKind.valueOf(value.getString("kind")),value.getString("label").take(80),value.optString("executable").take(80)).takeIf {it.known}
                        }.getOrNull()
                        id to Pane(program,value.optString("title").take(256),value.optString("directory").take(1024))
                    }.toMap()
                }
            }
            return cache
        }
    }
}
