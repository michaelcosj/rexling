package dev.shulhi.rex

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class RexModel @JvmOverloads constructor(application: Application, preferenceName: String="rex") : AndroidViewModel(application) {
    internal var activityVisible by mutableStateOf(false)
        private set
    internal var activitySources by mutableStateOf<List<ActivitySource>>(emptyList())
        private set
    internal fun showActivity(open: Boolean) {
        if(open) activeClient.cancelKeyRepeats()
        activityVisible=open;connections.values.forEach {it.activity(open)};updateActivity()
    }
    internal fun refreshActivity() {connections.values.forEach {it.activity(activityVisible)}}
    internal var attentionCount by mutableIntStateOf(0)
        private set
    internal var networkAvailable by mutableStateOf<Boolean?>(null)
        private set
    private var paneObscured=true
    internal fun setPaneObscured(value: Boolean) {paneObscured=value;updateActivity()}
    private fun watchedPane(): WatchedPane? {
        val client=connections[activeServerId] ?: return null
        val state=client.state.value
        val app=getApplication<Application>()
        return ActivityFocusPolicy.watched(activeServer,state,foreground && windowFocused,paneObscured || activityVisible,client.hasScreen && state.screen!=null,client.navigationRequest.pending,
            app.getSystemService(android.os.PowerManager::class.java).isInteractive,app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
    }
    private fun updateActivity() {
        val watched=watchedPane()
        if(watched!=null) connections[watched.server]?.let {client->
            val profile=servers.firstOrNull {it.id==watched.server}
            if(profile!=null) ActivityFocusPolicy.acknowledge(profile,client.state.value.activity,watched,client::isActivitySeen).takeIf {it.isNotEmpty()}?.let(client::markActivitySeen)
        }
        attentionCount=connections.values.sumOf {client->if(client.state.value.connected) ActivityFocusPolicy.unread(client.state.value.activity,client::isActivitySeen) else 0}
        activitySources=servers.mapNotNull {server->connections[server.id]?.state?.value?.let {state->
            if(state.phase in listOf("disconnected","preview")) null else ActivitySource(server,state.connected,state.activityLoading,
                if(state.connected) state.activityError else "Connection interrupted",state.activityPartial,state.activity,state.activity.filter {connections[server.id]?.isActivitySeen(it)==true}.map {it.key}.toSet())
        }}
    }
    internal fun markActivitySeen(target: ActivityTarget) {
        val client=connections[target.server.id] ?: return
        if(client.savedEndpoint!=target.server.endpoint) return
        val current=client.state.value.activity.firstOrNull {it.key==target.record.key && it.attentionVersion==target.record.attentionVersion} ?: return
        client.markActivitySeen(listOf(current));updateActivity()
    }
    internal fun markAllActivitySeen() {
        activitySources.filter {it.connected}.forEach {source->connections[source.server.id]?.markActivitySeen(source.items)};updateActivity()
    }
    internal fun openActivity(target: ActivityTarget) {
        val profile=servers.firstOrNull {it.id==target.server.id && it.endpoint==target.server.endpoint} ?: return
        val source=activitySources.firstOrNull {it.server.id==profile.id && it.connected} ?: return
        val record=source.items.firstOrNull {it.key==target.record.key} ?: return
        markActivitySeen(target);rememberLastSession();connectServer(profile.id);activeClient.openActivity(record.session,record.block);showActivity(false)
    }
    private val preferences=application.getSharedPreferences(preferenceName,0)
    private var serverSwitchSerial=0L
    internal var pendingImageTarget: ImageTarget?=null
    internal val images=ImageUploads(application,viewModelScope)
    internal fun imageTarget(): ImageTarget? {
        val client=activeClient;val state=client.state.value
        if(!foreground || !state.connected || !state.controlled || state.creating || client.navigationRequest.pending || state.selectedBlock.isBlank()) return null
        val program=state.programs[state.selectedBlock] ?: ProgramIdentity.Terminal
        return ImageTarget(activeServerId,activeServer.endpoint,state.selectedSession,state.selectedBlock,client.selectionVersion,serverSwitchSerial,program.kind,"${program.title} · ${activeServer.label}")
    }
    internal fun attachImage(uri: android.net.Uri,target: ImageTarget?,onCopied: ()->Unit={}): Boolean {
        if(target==null) {onCopied();images.notice="Take control of a pane before attaching an image.";return false}
        val profile=servers.firstOrNull {it.id==target.server && it.endpoint==target.endpoint}
        if(profile==null) {onCopied();images.notice="The original Rex server is no longer available.";return false}
        return images.start(uri,target,onCopied)
    }
    internal var predictiveEcho by mutableStateOf(PredictiveEcho.fromPreference(preferences.getString("predictive_echo",null)))
        private set
    internal fun changePredictiveEcho(value: PredictiveEcho) {
        predictiveEcho=value;preferences.edit {putString("predictive_echo",value.name)}
        connections.values.forEach {it.updatePredictiveEcho(value)}
    }
    internal var experimentalCodexAttention by mutableStateOf(preferences.getBoolean(CodexAttentionPolicy.ENABLED,false))
        private set
    internal fun changeCodexAttention(enabled: Boolean) {
        experimentalCodexAttention=enabled;preferences.edit {putBoolean(CodexAttentionPolicy.ENABLED,enabled)}
        connections.values.forEach {it.refreshActivityPolicy()};updateActivity()
    }
    internal var backgroundAlerts by mutableStateOf(preferences.getBoolean(BackgroundAlerts.ENABLED,false))
        private set
    internal var notificationNotice by mutableStateOf("")
    private var pendingAlert: AlertDestination?=null
    private val alertsListener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==BackgroundAlerts.ENABLED) {
        viewModelScope.launch {backgroundAlerts=preferences.getBoolean(BackgroundAlerts.ENABLED,false)}
    }}
    internal fun setBackgroundAlerts(enabled: Boolean) {
        backgroundAlerts=enabled;preferences.edit {putBoolean(BackgroundAlerts.ENABLED,enabled)}
        BackgroundAlerts.reconcile(getApplication())
    }
    internal fun openAlert(target: AlertDestination) {
        val profile=servers.firstOrNull {it.id==target.serverId && it.endpoint==target.endpoint}
        if(profile==null) {notificationNotice="That server is no longer saved.";return}
        pendingAlert=target;connectServer(profile.id);tryOpenAlert()
    }
    private fun tryOpenAlert() {
        val target=pendingAlert ?: return
        val client=connections[target.serverId] ?: return
        val state=client.state.value
        if(!state.connected || !foreground) return
        pendingAlert=null
        if(state.sessions.none {it.id==target.session}) {notificationNotice="That session is no longer available.";return}
        client.markAttentionSeen(target.attentionKey,target.attentionVersion)
        client.openActivity(target.session,target.block);showActivity(false)
    }
    internal val themes by lazy {ThemeCatalog.load(application)}
    private var darkTheme by mutableStateOf(preferences.getString("dark_theme","rexlink-default-dark").orEmpty())
    private var lightTheme by mutableStateOf(preferences.getString("light_theme","rexlink-default-light").orEmpty())
    var fontSize by mutableIntStateOf(preferences.getInt("font_size",10).coerceIn(8,20))
        private set
    internal fun theme(dark: Boolean): AppTheme {
        val id=if(dark) darkTheme else lightTheme
        return ThemeCatalog.defaults.firstOrNull {it.id==id && it.terminal.dark==dark} ?: ThemeCatalog.resolve(themes,dark,id)
    }
    internal fun chooseTheme(id: String) {
        val theme=themes.firstOrNull {it.id==id} ?: return
        if(theme.terminal.dark) darkTheme=id else lightTheme=id
        preferences.edit {putString(if(theme.terminal.dark) "dark_theme" else "light_theme",id)}
    }
    fun changeFontSize(size: Int) {fontSize=size.coerceIn(8,20);preferences.edit {putInt("font_size",fontSize)}}
    var appearance by mutableStateOf(runCatching {Appearance.valueOf(preferences.getString("appearance","SYSTEM")!!)}.getOrDefault(Appearance.SYSTEM))
        private set
    internal var minimizeFloatingPanes by mutableStateOf(preferences.getBoolean("minimize_floating_panes",false))
        private set
    internal fun changeMinimizeFloatingPanes(value: Boolean) {
        minimizeFloatingPanes=value
        preferences.edit {putBoolean("minimize_floating_panes",value)}
    }
    internal var arrowJoystickEnabled by mutableStateOf(preferences.getBoolean("arrow_joystick_enabled",true))
        private set
    internal fun changeArrowJoystick(value: Boolean) {arrowJoystickEnabled=value;preferences.edit {putBoolean("arrow_joystick_enabled",value)}}
    internal var terminalPadding by mutableStateOf(runCatching {TerminalPadding.valueOf(preferences.getString("terminal_padding","NORMAL")!!)}.getOrDefault(TerminalPadding.NORMAL))
        private set
    internal fun changeTerminalPadding(value: TerminalPadding) {terminalPadding=value;preferences.edit {putString("terminal_padding",value.name)}}
    internal var extendPadding by mutableStateOf(preferences.getBoolean("terminal_padding_extend",true))
        private set
    internal fun changeExtendPadding(value: Boolean) {extendPadding=value;preferences.edit {putBoolean("terminal_padding_extend",value)}}
    internal var shortcutGesture by mutableStateOf(runCatching {ControlShortcutGesture.valueOf(preferences.getString("ctrl_shortcut_gesture","HOLD")!!)}.getOrDefault(ControlShortcutGesture.HOLD))
        private set
    internal fun changeShortcutGesture(value: ControlShortcutGesture) {shortcutGesture=value;preferences.edit {putString("ctrl_shortcut_gesture",value.name)}}
    internal var neovimShortcutLayout by mutableStateOf(runCatching {NeovimShortcutLayout.valueOf(preferences.getString("neovim_shortcut_layout","CIRCULAR")!!)}.getOrDefault(NeovimShortcutLayout.CIRCULAR))
        private set
    internal fun changeNeovimShortcutLayout(value: NeovimShortcutLayout) {neovimShortcutLayout=value;preferences.edit {putString("neovim_shortcut_layout",value.name)}}
    internal var neovimShortcutEffect by mutableStateOf(NeovimShortcutEffect.fromPreference(preferences.getString("neovim_shortcut_effect","FAN_OUT")))
        private set
    internal fun changeNeovimShortcutEffect(value: NeovimShortcutEffect) {neovimShortcutEffect=value;preferences.edit {putString("neovim_shortcut_effect",value.name)}}
    internal var neovimShortcutHaptics by mutableStateOf(preferences.getBoolean("neovim_shortcut_haptics",true))
        private set
    internal fun changeNeovimShortcutHaptics(value: Boolean) {neovimShortcutHaptics=value;preferences.edit {putBoolean("neovim_shortcut_haptics",value)}}
    /** Saved profiles; the first launch moves the earlier Neovim-only list and switch into the Neovim profile. */
    internal var shortcutProfiles by mutableStateOf(ShortcutProfiles.decode(preferences.getString("shortcut_profiles",null)) {
        ShortcutProfiles.defaults(NeovimShortcuts.decode(preferences.getString("neovim_shortcuts",null)),preferences.getBoolean("neovim_shortcuts_enabled",true))
    })
        private set
    private fun storeProfiles(value: List<ShortcutProfile>) {shortcutProfiles=value;preferences.edit {putString("shortcut_profiles",ShortcutProfiles.encode(value))}}
    internal fun enableShortcutProfile(id: String,value: Boolean) {storeProfiles(shortcutProfiles.map {if(it.id==id) it.copy(enabled=value) else it})}
    internal fun deleteShortcutProfile(id: String) {storeProfiles(shortcutProfiles.filterNot {it.id==id})}
    /** Saves a new or edited profile; returns a message for the user when it is invalid. */
    internal fun saveShortcutProfile(id: String,name: String,match: String,prefix: String,enter: ShortcutEnter,shortcuts: String): String? = runCatching {
        val existing=shortcutProfiles.firstOrNull {it.id==id}
        require(existing!=null || ShortcutProfiles.canAdd(shortcutProfiles)) {"You can keep at most 32 profiles."}
        val profile=ShortcutProfiles.build(id,name,match,prefix,enter,existing?.enabled ?: true,shortcuts,shortcutProfiles)
        storeProfiles(if(existing==null) shortcutProfiles+profile else shortcutProfiles.map {if(it.id==id) profile else it})
    }.exceptionOrNull()?.message
    internal var neovimShortcutPosition by mutableStateOf(androidx.compose.ui.geometry.Offset(
        preferences.getFloat("neovim_shortcut_x",0f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 0f,
        preferences.getFloat("neovim_shortcut_y",1f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 1f))
        private set
    internal fun moveNeovimShortcut(value: androidx.compose.ui.geometry.Offset) {
        if(!value.x.isFinite() || !value.y.isFinite()) return
        neovimShortcutPosition=androidx.compose.ui.geometry.Offset(value.x.coerceIn(0f,1f),value.y.coerceIn(0f,1f))
        preferences.edit {putFloat("neovim_shortcut_x",neovimShortcutPosition.x);putFloat("neovim_shortcut_y",neovimShortcutPosition.y)}
    }
    var servers by mutableStateOf(SavedServers.decode(preferences.getString("servers",null),preferences.getString("endpoint","").orEmpty()))
        private set
    var activeServerId by mutableStateOf(preferences.getString("active_server",null)?.takeIf {id->servers.any {it.id==id}} ?: servers.firstOrNull()?.id.orEmpty())
        private set
    var serverStatuses by mutableStateOf<Map<String,ServerStatus>>(emptyMap())
        private set
    internal var sessionTargets by mutableStateOf<List<SessionTarget>>(emptyList())
        private set
    internal var lastSessionKey by mutableStateOf(preferences.getString("last_global_session","").orEmpty())
        private set
    var closingSessionKey by mutableStateOf("")
        private set
    var sessionActionError by mutableStateOf("")
        private set
    internal var renamingSessionKey by mutableStateOf("")
        private set
    private var renameServerId=""
    private var renameSessionId=""
    private var renameLabel=""
    private var closingServerId=""
    private var closingSessionId=""
    private val connections=mutableMapOf<String,RexConnection>()
    private val observers=mutableMapOf<String,Job>()
    private var desired=preferences.getStringSet("connected_servers",emptySet()).orEmpty().filter {id->servers.any {it.id==id}}.toSet()
    private var palette: TerminalPalette?=null
    private var windowFocused=false
    internal fun windowFocusChanged(focused: Boolean) {windowFocused=focused;updateActivity()}
    private var foreground=true
    private var networkJob: Job?=null
    private val networkMonitor=NetworkMonitor(application) {change->viewModelScope.launch {
        val wasOffline=networkAvailable==false;networkAvailable=change.available
        if(!change.available || change.retry || wasOffline) {
            networkJob?.cancel();networkJob=viewModelScope.launch {
                if(change.available) delay(300)
                if(foreground) connections.filterKeys {it in desired}.values.forEach {it.networkChanged(change)}
            }
        }
    }}
    internal var activeClient by mutableStateOf(connection(activeServer))
        private set
    val activeServer: ServerProfile get()=servers.firstOrNull {it.id==activeServerId} ?: ServerProfile.Empty
    val connectedCount: Int get()=serverStatuses.values.count {it.connected}

    init {preferences.registerOnSharedPreferenceChangeListener(alertsListener);desired.toList().forEach {id->connectServer(id,select=false)}}

    private fun connection(profile: ServerProfile): RexConnection = connections.getOrPut(profile.id) {
        RexConnection(getApplication(),viewModelScope,profile.endpoint,preferences).also {client->
            client.visible=profile.id==activeServerId
            if(activityVisible) client.activity(true)
            palette?.let(client::updateTerminalTheme)
            client.updatePredictiveEcho(predictiveEcho)
            if(!foreground) client.pause()
            observers[profile.id]=viewModelScope.launch {
                client.chromeState.collect {state->
                    val next=ServerStatus(state.phase,state.error)
                    if(serverStatuses[profile.id]!=next) serverStatuses=serverStatuses+(profile.id to next)

                    updateSessionTargets()
                    if(profile.id==pendingAlert?.serverId) tryOpenAlert()
                    updateActivity()
                    if(renamingSessionKey.isNotBlank() && profile.id==renameServerId) {
                        if(!state.creating && state.sessions.any {it.id==renameSessionId && it.label==renameLabel}) renamingSessionKey=""
                        else if(state.error.isNotBlank() || !state.connected || state.sessions.none {it.id==renameSessionId}) {
                            renamingSessionKey="";sessionActionError=state.error.ifBlank {if(state.connected) "That session is no longer available" else "Connection interrupted. Check the session name before retrying."}
                        }
                    }
                    if(closingSessionKey.isNotBlank() && profile.id==closingServerId) {
                        if(state.sessions.none {it.id==closingSessionId}) closingSessionKey=""
                        else if(state.error.isNotBlank() || !state.connected) {
                            sessionActionError=state.error.ifBlank {"Connection interrupted. Refresh the session list before trying again."}
                            closingSessionKey=""
                        }
                    }
                }
            }
        }
    }
    private fun updateSessionTargets() {
        val next=SessionCatalog.collect(servers,connections.mapValues {it.value.state.value})
        if(next!=sessionTargets) sessionTargets=next
    }
    private fun rememberLastSession() {
        val session=activeClient.navigationRequest.session ?: activeClient.state.value.selectedSession
        if(session.isNotBlank()) {
            lastSessionKey=sessionKey(activeServerId,session)
            preferences.edit {putString("last_global_session",lastSessionKey)}
        }
    }
    internal fun selectSessionTarget(key: String) {
        val target=sessionTargets.firstOrNull {it.key==key} ?: return
        if(activeServerId==target.server.id) {
            val current=activeClient.navigationRequest.session ?: activeClient.state.value.selectedSession
            if(current==target.session.id) return
            rememberLastSession()
        }
        connectServer(target.server.id)
        activeClient.selectSession(target.session.id)
    }
    internal fun renameSession(target: SessionTarget,label: String): Boolean {
        if(closingSessionKey.isNotBlank() || renamingSessionKey.isNotBlank()) return false
        sessionActionError=""
        sessionNameError(label)?.let {sessionActionError=it;return false}
        val current=sessionTargets.firstOrNull {it.key==target.key && it.server.endpoint==target.server.endpoint}
        if(current==null) {sessionActionError="That session is no longer available";return false}
        renameServerId=target.server.id;renameSessionId=target.session.id;renameLabel=label.trim();renamingSessionKey=target.key
        if(connections[target.server.id]?.renameSession(target.session.id,renameLabel)!=true) {
            renamingSessionKey="";sessionActionError="Wait for the server to finish its current operation, then try again.";return false
        }
        return true
    }
    internal fun closeSession(target: SessionTarget) {
        if(closingSessionKey.isNotBlank() || renamingSessionKey.isNotBlank()) return
        sessionActionError=""
        val current=sessionTargets.firstOrNull {it.key==target.key && it.server.endpoint==target.server.endpoint}
        if(current==null) {sessionActionError="That session is no longer available";return}
        closingSessionKey=target.key;closingServerId=target.server.id;closingSessionId=target.session.id
        if(connections[target.server.id]?.closeSession(target.session.id)!=true) {
            closingSessionKey="";sessionActionError="Wait for the server to finish its current operation, then try again."
        }
    }
    internal fun jumpLastSession() {
        if(sessionTargets.any {it.key==lastSessionKey}) selectSessionTarget(lastSessionKey)
        else activeClient.jumpLastSession()
    }
    internal fun refreshSessionDetails() {connections.values.filter {it.state.value.connected}.forEach {it.refreshSessionDetails()}}
    private fun persist() {updateSessionTargets();preferences.edit {
        putString("servers",SavedServers.encode(servers));putString("active_server",activeServerId)
        putStringSet("connected_servers",desired)
    };if(foreground && BackgroundAlerts.foreground.value) BackgroundAlerts.reconcile(getApplication())}
    fun connectServer(id: String,select: Boolean=true) {
        val profile=servers.firstOrNull {it.id==id} ?: return
        val client=connection(profile)
        if(select && activeServerId!=id) {
            rememberLastSession();serverSwitchSerial++
            activeClient.releaseControl();activeClient.visible=false
            client.visible=true;activeServerId=id;activeClient=client
        }
        desired=desired+id;persist()
        if(client.state.value.phase in listOf("disconnected","error","preview")) client.connect(profile.endpoint)
        else if(foreground && client.state.value.phase=="paused") client.resume()
    }
    /** The legacy URL form also adds a new host without disconnecting existing ones. */
    fun connect(endpoint: String): String? = runCatching {
        val normalized=ServerProfile.normalizeEndpoint(endpoint)
        val existing=servers.firstOrNull {it.endpoint==normalized}
        if(existing!=null) connectServer(existing.id)
        else saveServer(null,"",normalized)?.let {throw IllegalArgumentException(it)}
    }.exceptionOrNull()?.message

    fun saveServer(id: String?,label: String,endpoint: String,icon: ServerIcon?=null): String? = runCatching {
        val profile=ServerProfile.create(label,endpoint,id ?: java.util.UUID.randomUUID().toString(),icon ?: servers.firstOrNull {it.id==id}?.icon ?: ServerIcon.GLOBE)
        require(servers.none {it.id!=profile.id && it.endpoint==profile.endpoint}) {"That server is already saved"}
        val old=servers.firstOrNull {it.id==profile.id}
        if(old!=null && old.endpoint!=profile.endpoint) {
            images.forget(old.id)
            connections.remove(old.id)?.let {it.releaseControl();it.disconnect();it.close()}
            observers.remove(old.id)?.cancel();serverStatuses=serverStatuses-old.id
        }
        servers=if(old==null) servers+profile else servers.map {if(it.id==profile.id) profile else it}
        persist()
        val client=connection(profile)
        if(activeServerId==profile.id) {activeClient=client;client.visible=true}
        connectServer(profile.id)
        updateActivity()
    }.exceptionOrNull()?.message

    fun disconnectServer(id: String) {
        desired=desired-id;persist()
        connections[id]?.let {it.releaseControl();it.disconnect()}
    }
    fun forgetServer(id: String) {
        images.forget(id)
        if(activeServerId==id) serverSwitchSerial++
        disconnectServer(id)
        observers.remove(id)?.cancel();connections.remove(id)?.close()
        servers=servers.filterNot {it.id==id};serverStatuses=serverStatuses-id
        if(activeServerId==id) {
            activeServerId=servers.firstOrNull()?.id.orEmpty()
            activeClient=connection(activeServer);activeClient.visible=true
        }
        persist()
    }
    fun previewServer(id: String) {
        if(id!=activeServerId) return
        desired=desired-id;persist();activeClient.preview()
    }
    fun updateTerminalTheme(value: TerminalPalette) {palette=value;connections.values.forEach {it.updateTerminalTheme(value)}}
    fun changeAppearance(value: Appearance) {appearance=value;preferences.edit {putString("appearance",value.name)}}
    fun pause() {foreground=false;updateActivity();networkJob?.cancel();networkMonitor.stop();connections.values.forEach {it.pause()}}
    fun resume() {foreground=true;networkMonitor.start();connections.values.forEach {it.resume()};tryOpenAlert();BackgroundAlerts.reconcile(getApplication())}
    override fun onCleared() {images.close();preferences.unregisterOnSharedPreferenceChangeListener(alertsListener);networkJob?.cancel();networkMonitor.stop();observers.values.forEach {it.cancel()};connections.values.forEach {it.close()}}
}
