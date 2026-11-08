package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.LocalContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val model: RexModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeAlert(intent)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val dark=when(model.appearance) {Appearance.SYSTEM->isSystemInDarkTheme();Appearance.DARK->true;Appearance.LIGHT->false}
            SideEffect {WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }}
            RexTheme(dark,model.theme(dark)) {RexApp(model)}
        }
    }
    private fun consumeAlert(intent: Intent?) {
        AlertDestinations(BackgroundAlerts.preferences(this)).consume(intent?.data)?.let(model::openAlert)
        intent?.data=null
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);consumeAlert(intent)}
    override fun onWindowFocusChanged(hasFocus: Boolean) {super.onWindowFocusChanged(hasFocus);model.windowFocusChanged(hasFocus)}
    override fun onStart(){super.onStart();BackgroundAlerts.foreground.value=true;model.resume()}
    override fun onStop(){if(!isChangingConfigurations) BackgroundAlerts.foreground.value=false;model.pause();super.onStop()}
}

@Composable internal fun RexTheme(dark: Boolean,theme: AppTheme,content: @Composable ()->Unit) {
    val base=if(dark) darkColorScheme(primary=Color(0xff315cf4),onPrimary=Color.White,
        background=Color(0xff090c11),surface=Color(0xff141922),onSurface=Color(0xffe5e9f2),
        surfaceVariant=Color(0xff232934),onSurfaceVariant=Color(0xff969fb1),outline=Color(0xff2b3341))
    else lightColorScheme(primary=Color(0xff3156f4),onPrimary=Color.White,
        background=Color(0xfff3f4f9),surface=Color(0xfffdfdff),onSurface=Color(0xff171b26),
        surfaceVariant=Color(0xffe9ebf2),onSurfaceVariant=Color(0xff62697c),outline=Color(0xffd8dce8))
    val container = if(dark) Color(0xff1b212c) else Color(0xffeceef5)
    val primaryContainer = if(dark) Color(0xff17254d) else Color(0xffe4eaff)
    val onPrimaryContainer = if(dark) Color(0xffbed0ff) else Color(0xff193dba)
    val scheme = base.copy(surfaceTint=Color.Transparent,
        surfaceDim=base.background,surfaceBright=base.surfaceVariant,
        surfaceContainerLowest=base.background,surfaceContainerLow=base.surface,
        surfaceContainer=container,surfaceContainerHigh=container,surfaceContainerHighest=base.surfaceVariant,
        primaryContainer=primaryContainer,onPrimaryContainer=onPrimaryContainer,
        secondary=base.primary,onSecondary=base.onPrimary,secondaryContainer=primaryContainer,onSecondaryContainer=onPrimaryContainer,
        tertiary=base.primary,onTertiary=base.onPrimary,tertiaryContainer=primaryContainer,onTertiaryContainer=onPrimaryContainer)
    CompositionLocalProvider(LocalTerminalPalette provides theme.terminal) {
        MaterialTheme(colorScheme=scheme.withPreset(theme),content=content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexApp(model: RexModel) {
    val client=model.activeClient
    key(model.activeServerId) {RexWorkspace(model,client)}
    if(model.notificationNotice.isNotBlank()) AlertDialog(onDismissRequest={model.notificationNotice=""},title={Text("Notification")},text={Text(model.notificationNotice)},confirmButton={TextButton(onClick={model.notificationNotice=""}) {Text("OK")}})
}

private data class TerminalCloseTarget(val session: String,val id: String,val label: String,val tab: Boolean,val blocks: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexWorkspace(model: RexModel,client: RexConnection) {
    val state by client.chromeState.collectAsState()
    val navigation=client.navigationRequest
    val displayedSession=navigation.session ?: state.selectedSession
    val currentSessionKey=sessionKey(model.activeServerId,displayedSession)
    val sessionStrip=SessionCatalog.strip(model.sessionTargets,SessionTarget(model.activeServer,
        SessionItem(displayedSession,state.sessions.firstOrNull {it.id==displayedSession}?.label ?: if(state.connected) "No session" else state.phase)))
    val displayedBlock=navigation.block ?: state.selectedBlock
    val changingSession=navigation.session!=null && navigation.session!=state.selectedSession
    val windows=if(changingSession) emptyList() else client.orderedWindows(state)
    val window=windows.firstOrNull {w->w.blocks.any {it.id==displayedBlock}} ?: windows.firstOrNull {it.blocks.isNotEmpty()}
    val ready=state.connected && !navigation.pending && !state.creating
    val controlled=state.controlled && ready && client.hasScreen
    val keyTarget=client.keyTarget()
    val palette=LocalTerminalPalette.current
    var endpoint by rememberSaveable {mutableStateOf(client.savedEndpoint)}
    var showServer by rememberSaveable {mutableStateOf(true)}
    var servers by remember {mutableStateOf(false)}
    var connectionError by remember {mutableStateOf<String?>(null)}
    var picker by remember {mutableStateOf("")}
    var newSession by remember {mutableStateOf(false)}
    var newName by rememberSaveable {mutableStateOf("")}
    var menu by remember {mutableStateOf(false)}
    var appearance by remember {mutableStateOf(false)}
    var about by remember {mutableStateOf(false)}
    var preferencesOpen by remember {mutableStateOf(false)}
    var neovimSettings by remember {mutableStateOf(false)}
    var alerts by remember {mutableStateOf(false)}
    var closeTarget by remember {mutableStateOf<TerminalCloseTarget?>(null)}
    val fontSize=model.fontSize
    var fontPicker by remember {mutableStateOf(false)}
    var terminalView by remember {mutableStateOf<TerminalView?>(null)}
    val context=LocalContext.current
    var networkAction by remember {mutableStateOf<(() ->Unit)?>(null)}
    var networkDenied by remember {mutableStateOf<(() ->Unit)?>(null)}
    val localNetwork=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        val action=networkAction;val denied=networkDenied;networkAction=null;networkDenied=null
        if(granted) action?.invoke() else {denied?.invoke();model.images.notice="Allow local network access to upload images to Rex."}
    }
    DisposableEffect(Unit) {onDispose {networkDenied?.invoke();networkAction=null;networkDenied=null}}
    fun withUploadNetwork(action: ()->Unit,onDenied: ()->Unit={}) {
        if(Build.VERSION.SDK_INT>=37 && context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if(networkAction!=null) {onDenied();return}
            networkAction=action;networkDenied=onDenied;localNetwork.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else action()
    }
    val imageCommit: (android.net.Uri,()->Unit)->Boolean={uri,release->
        val target=model.imageTarget()
        if(target==null) model.attachImage(uri,target,release)
        else {withUploadNetwork({model.attachImage(uri,target,release)},release);true}
    }
    fun pasteClipboard(): Boolean = runCatching {
        pasteTerminalClipboard(context,{uri->imageCommit(uri,{})},client::paste)
    }.getOrElse {model.images.notice="Could not read the clipboard image. Try Attach image instead.";false}
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri->
        val target=model.pendingImageTarget;model.pendingImageTarget=null
        if(uri!=null) withUploadNetwork({model.attachImage(uri,target)})
    }
    fun chooseImage() {
        val target=model.imageTarget()
        if(target==null) {model.images.notice="Take control of a pane before attaching an image.";return}
        model.pendingImageTarget=target
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    SideEffect {model.setPaneObscured(model.activityVisible || picker.isNotEmpty() || preferencesOpen || servers || newSession || appearance || about || neovimSettings || alerts || fontPicker || closeTarget!=null)}
    DisposableEffect(client) {onDispose {model.setPaneObscured(true)}}
    val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val blocks=window?.blocks ?: if(state.phase=="preview") listOf(BlockItem("preview","Terminal")) else emptyList()
    val chosen=displayedBlock.ifBlank {blocks.firstOrNull()?.id.orEmpty()}
    val liveBlock=state.selectedBlock.ifBlank {if(state.phase=="preview") "preview" else ""}
    fun closePaneTarget(block: BlockItem)=TerminalCloseTarget(state.selectedSession,block.id,
        (state.programs[block.id] ?: ProgramIdentity.Loading).paneTitle(block.label,state.titles[block.id].orEmpty()),false,listOf(block.id))
    fun closeTabTarget(tab: WindowItem): TerminalCloseTarget {
        val pane=client.tabPaneIndex(tab);val block=tab.blocks.getOrNull(pane)
        val program=state.programs[block?.id] ?: ProgramIdentity.Loading
        return TerminalCloseTarget(state.selectedSession,tab.id,program.tabTitle(tab.label,block?.label.orEmpty(),1,state.titles[block?.id].orEmpty()),true,tab.blocks.map {it.id})
    }
    LaunchedEffect(state.selectedSession) {closeTarget=null}
    LaunchedEffect(palette) { model.updateTerminalTheme(palette) }
    LaunchedEffect(state.phase) {if(state.connected || state.phase=="preview") showServer=false}
    LaunchedEffect(state.connected,state.selectedSession,state.selectedBlock,state.windows,navigation.pending) {
        client.openPreferredTerminal();client.rememberCurrentTerminal()
    }
    LaunchedEffect(state.connected,state.selectedSession,window?.id,state.selectedBlock,blocks) {
        val index=blocks.indexOfFirst {it.id==state.selectedBlock}
        if(index>=0 && blocks.size>1) client.prefetchPanes(listOf(blocks[Math.floorMod(index-1,blocks.size)].id,blocks[(index+1)%blocks.size].id).distinct())
    }
    LaunchedEffect(ready,chosen,client.hasScreen) {if(ready) client.claimActivePane()}
    LaunchedEffect(controlled,client.keyboardRequest,terminalView) {
        if(controlled) {
            terminalView?.requestFocus()
            client.applySize()
            if(client.keyboardRequest==displayedBlock && terminalView!=null) {terminalView?.keyboard();client.keyboardShown()}
        }
    }
    LaunchedEffect(picker) {if(picker=="sessions") model.refreshSessionDetails()}
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            SessionHeader(sessionStrip,currentSessionKey,state.connected,model.sessionTargets.isNotEmpty(),
                blocks.map {state.programs[it.id] ?: ProgramIdentity.Loading},blocks.indexOfFirst {it.id==chosen}.coerceAtLeast(0),state.directories[displayedBlock].orEmpty(),{picker="sessions"},model::selectSessionTarget,model::jumpLastSession,
                working=chosen in state.workingBlocks,title=(state.programs[chosen] ?: ProgramIdentity.Loading).paneTitle(blocks.firstOrNull {it.id==chosen}?.label.orEmpty(),state.titles[chosen].orEmpty()),menu={
                    Box {
                        ChromeButton(ChromeIcon.MORE,"Terminal menu"){menu=true}
                        DropdownMenu(menu,{menu=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                            Text(model.activeServer.label,Modifier.padding(horizontal=16.dp,vertical=6.dp),fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(state.connected) if(controlled) "In control" else "Viewing terminal" else state.phase,
                                Modifier.padding(horizontal=16.dp,vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            client.screenDimensions?.let {Text("${it.first} × ${it.second}",Modifier.padding(horizontal=16.dp),fontFamily=MonoUi,fontSize=11.sp)}
                            HorizontalDivider(Modifier.padding(vertical=6.dp))
                            OwnershipMenuItem(controlled,ready&&client.hasScreen) {menu=false;if(controlled) client.releaseControl() else client.takeControl()}
                            QuickThemeMenuItem(palette.dark) {dark->menu=false;model.changeAppearance(if(dark) Appearance.DARK else Appearance.LIGHT)}
                            TerminalMenuItem("Preferences",ChromeIcon.SETTINGS,onClick={menu=false;preferencesOpen=true})
                            TerminalMenuItem("Copy viewport",ChromeIcon.COPY,enabled=client.hasScreen,onClick={
                                menu=false;client.terminal.value?.let {screen->(getClipboard(model.getApplication()) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rex terminal",screen.plainText()))}
                            })
                            TerminalMenuItem("Paste",ChromeIcon.PASTE,enabled=controlled,onClick={
                                menu=false;pasteClipboard()
                            })
                            TerminalMenuItem("Attach image",ChromeIcon.IMAGE,enabled=controlled && model.images.state==null,onClick={menu=false;chooseImage()})
                            TerminalMenuItem("Servers · ${model.connectedCount} connected",ChromeIcon.SERVER,onClick={menu=false;servers=true})
                            TerminalMenuItem("Refresh sessions",ChromeIcon.REFRESH,enabled=state.connected,onClick={menu=false;client.refresh()})
                        }
                    }
                })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    if(showServer || (state.phase=="disconnected"&&!client.hasScreen)) {
                        Surface(Modifier.padding(12.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {
                            Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.app_name),fontWeight=FontWeight.Bold,fontSize=20.sp)
                                Text(stringResource(R.string.app_subtitle),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Connect over Tailscale",fontWeight=FontWeight.SemiBold)
                                OutlinedTextField(endpoint,{endpoint=it},Modifier.fillMaxWidth(),label={Text("Rex server URL")},singleLine=true,
                                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),shape=RoundedCornerShape(12.dp))
                                connectionError?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=11.sp)}
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={connectionError=model.connect(endpoint)},enabled=state.phase!="connecting" && endpoint.isNotBlank()){Text("Connect")}
                                    TextButton(onClick={servers=true}) {Text("Servers")}
                                    if(state.connected || state.phase=="reconnecting") TextButton(onClick={model.disconnectServer(model.activeServerId)}){Text("Disconnect")}
                                    else TextButton(onClick={model.previewServer(model.activeServerId)}){Text("Try preview")}
                                    if(client.hasScreen) TextButton(onClick={showServer=false}){Text("Hide")}
                                }
                            }
                        }
                    }
                    if(blocks.isEmpty()) ConnectionNotice(state.phase,model.networkAvailable,client::retryNow)
                    if(state.error.isNotBlank() && state.phase !in listOf("connecting","reconnecting")) Text(state.error,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
                        color=MaterialTheme.colorScheme.onErrorContainer,fontSize=11.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(blocks.isNotEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(top=3.dp,bottom=7.dp)) {
                        LiveTerminal(client,blocks,chosen,liveBlock,state.programs,controlled,fontSize.toFloat(),
                            client::type,client::scroll,client::wheel,client::takeControl,client::scrollToBottom,client::resize,client::selectBlock,{picker="panes"},{terminalView=it},
                            Modifier.fillMaxSize(),onImage=imageCommit,onPaste=::pasteClipboard,padding=model.terminalPadding,extendPadding=model.extendPadding)
                        ConnectionNotice(state.phase,model.networkAvailable,client::retryNow,Modifier.align(Alignment.TopCenter).padding(8.dp))
                        ReadyNotice(state.connectionEpoch,controlled,ready,Modifier.align(Alignment.TopCenter).padding(8.dp))
                        OtherOwnerNotice(chosen,state.otherOwner && !controlled,ready,client.screenDimensions,client::takeControl,Modifier.align(Alignment.TopEnd).padding(10.dp))
                        if(model.neovimShortcutsEnabled && ready && chosen==liveBlock && chosen !in state.cachedPrograms && state.programs[chosen]?.kind==ProgramKind.NEOVIM && model.neovimShortcuts.isNotEmpty()) {
                            NeovimShortcutButton(model.neovimShortcuts,keyTarget,controlled,
                                onChoose={item,target->client.neovimShortcut(item,target)},onCustomize={neovimSettings=true},layout=model.neovimShortcutLayout,effect=model.neovimShortcutEffect,hapticsEnabled=model.neovimShortcutHaptics,
                                modifier=Modifier.fillMaxSize(),position=model.neovimShortcutPosition,onPosition=model::moveNeovimShortcut,bottomInset=if(blocks.size>1) 64.dp else 10.dp)
                        }
                        }
                    } else Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center) {
                        Text(if(state.connected) "Choose a session, or start a new one." else "Your terminals, anywhere.",
                            fontFamily=MonoUi,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(state.connected || state.phase=="preview") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart=24.dp,topEnd=24.dp)).background(MaterialTheme.colorScheme.surface)) {
                            TerminalTabs(windows,window?.id.orEmpty(),state.connected&&!state.creating&&!changingSession,client::selectWindow,panePrograms=client::tabPrograms,selectedPane=client::tabPaneIndex,paneTitle={w,pane->state.titles[w.blocks.getOrNull(pane)?.id].orEmpty()},paneWorking={w->w.blocks.any {it.id in state.workingBlocks}},onClose={closeTarget=closeTabTarget(it)},onMove=client::moveTab,reorderKey=displayedSession,animateWorking=!model.activityVisible,
                                contentReady=client.hasScreen && !state.creating && !changingSession && windows.isNotEmpty() && windows.all {w->
                                    val block=w.blocks.getOrNull(client.tabPaneIndex(w))
                                    block!=null && state.programs[block.id]?.pending==false
                                },actions={
                                TerminalActions(state.connected && state.selectedSession.isNotBlank(),model.connectedCount>0,
                                    {model.showActivity(true)},{picker="tabs"},attentionCount=model.attentionCount)
                            })
                            KeyboardBar(controlled,ready,client.controlModifier,client.altModifier,client.shiftModifier,keyboardVisible,
                                client::toggleControl,client::toggleAlt,client::toggleShift,client::pressShift,client::releaseShift,{client.keyForTarget(it,keyTarget)},readable=state.connected,onControlShortcut=client::controlShortcut,inputKey=keyTarget,shortcutGesture=model.shortcutGesture,arrowJoystickEnabled=model.arrowJoystickEnabled) {
                                if(!controlled) client.takeControl() else if(keyboardVisible) terminalView?.hideKeyboard() else terminalView?.keyboard()
                            }
                        }
                    }
                }
                model.images.state?.let {status->ImageUploadOverlay(status,model.images::retry,model.images::cancel,
                    modifier=Modifier.align(Alignment.BottomEnd).padding(horizontal=16.dp).padding(bottom=if(state.connected) 112.dp else 16.dp),retryable=model.images.retryable)}
                // Loading feedback must not resize the terminal grid during navigation.
                if(state.creating || navigation.pending || state.phase=="connecting" || state.phase=="reconnecting")
                    LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))

            }
        }
    }
if(picker=="sessions") SessionSwitcher(model.sessionTargets,currentSessionKey,model.lastSessionKey,state.connected&&!state.creating,model.closingSessionKey.ifBlank {model.renamingSessionKey},model.sessionActionError,model::closeSession,model::renameSession,
                    onSelect={picker="";model.selectSessionTarget(it)},onClose={picker=""},onNew={picker="";newSession=true})
    if(model.activityVisible) ActivitySheet(model.activitySources,model::refreshActivity,model::openActivity,onClose={model.showActivity(false)},onSeen=model::markActivitySeen,onMarkAll=model::markAllActivitySeen)
    if(preferencesOpen) PreferencesSheet({preferencesOpen=false;appearance=true},{preferencesOpen=false;fontPicker=true},{preferencesOpen=false;alerts=true},{preferencesOpen=false;about=true},
        onClose={preferencesOpen=false},shortcutGesture=model.shortcutGesture,onShortcutGesture=model::changeShortcutGesture,
        onNeovim={preferencesOpen=false;neovimSettings=true},arrowJoystickEnabled=model.arrowJoystickEnabled,onArrowJoystick=model::changeArrowJoystick,
        codexTitleAttention=model.experimentalCodexAttention,onCodexTitleAttention=model::changeCodexAttention,
        terminalPadding=model.terminalPadding,onTerminalPadding=model::changeTerminalPadding,extendPadding=model.extendPadding,onExtendPadding=model::changeExtendPadding)
    if(neovimSettings) NeovimShortcutsSheet(model.neovimShortcuts,model::saveNeovimShortcuts,onClose={neovimSettings=false},
        enabled=model.neovimShortcutsEnabled,onEnabled=model::enableNeovimShortcuts,
        layout=model.neovimShortcutLayout,onLayout=model::changeNeovimShortcutLayout,effect=model.neovimShortcutEffect,onEffect=model::changeNeovimShortcutEffect,
        hapticsEnabled=model.neovimShortcutHaptics,onHaptics=model::changeNeovimShortcutHaptics)
    if(model.images.notice.isNotEmpty()) AlertDialog(onDismissRequest={model.images.notice=""},title={Text("Image upload")},text={Text(model.images.notice)},confirmButton={TextButton(onClick={model.images.notice=""}) {Text("OK")}})
    if(about) AboutSheet {about=false}
    if(alerts) BackgroundAlertsDialog(model) {alerts=false}
    if(servers) ServersSheet(model){servers=false}
    if(picker=="tabs" || picker=="panes") TabPaneSwitcher(windows,window?.id.orEmpty(),chosen,
        state.programs,state.titles,state.directories,client::tabPaneIndex,ready,
        canCreateTab=ready && state.selectedSession.isNotBlank(),canCreatePane=ready && state.selectedSession.isNotBlank(),
        onTab={picker="";client.selectWindow(it)},onPane={picker="";client.selectBlock(it)},
        onNewTab={picker="";client.createTab()},onNewPane={anchor,split->picker="";client.createPane(split,anchor)},
        onCloseTab={picker="";closeTarget=closeTabTarget(it)},onClosePane={picker="";closeTarget=closePaneTarget(it)},
        onClose={picker=""},initialPage=if(picker=="panes") 1 else 0,onMoveTab=client::moveTab,onMovePane=client::movePane,reorderKey=currentSessionKey)
    closeTarget?.let {target->
        AlertDialog(onDismissRequest={closeTarget=null},title={Text(if(target.tab) "Close tab?" else "Close pane?")},
            text={Text(if(target.tab) "Close “${target.label}”? This ends every program in its ${target.blocks.size} pane${if(target.blocks.size==1) "" else "s"}."
                else "Close “${target.label}”? This ends the program running in this pane.")},
            confirmButton={TextButton(enabled=ready,onClick={closeTarget=null
                if(target.tab) client.closeTab(target.session,target.id,target.blocks) else client.closePane(target.session,target.id)
            }) {Text("Close",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={closeTarget=null}) {Text("Cancel")}})
    }
    if(appearance) AppearanceSheet(model.appearance,model.themes,model.theme(false),model.theme(true),model::changeAppearance,model::chooseTheme) {appearance=false}
    if(fontPicker) FontSizeDialog(fontSize,model::changeFontSize) {fontPicker=false}
    if(newSession) AlertDialog(onDismissRequest={newSession=false},title={Text("New session")},text={
        OutlinedTextField(newName,{newName=it},singleLine=true,label={Text("Name (optional)")},supportingText={Text("Leave blank for a name from Rex.")})
    },confirmButton={Button(onClick={newSession=false;client.createSession(newName);newName=""},enabled=state.connected&&!state.creating){Text("Create")}},
        dismissButton={TextButton(onClick={newSession=false}){Text("Cancel")}})
}
private fun getClipboard(context: Context)=context.getSystemService(Context.CLIPBOARD_SERVICE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SessionSwitcher(targets: List<SessionTarget>,current: String,last: String,canCreate: Boolean,closing: String,error: String,onCloseSession: (SessionTarget)->Unit,onRenameSession: (SessionTarget,String)->Boolean,onSelect: (String)->Unit,onClose: ()->Unit,onNew: ()->Unit) {
    var search by rememberSaveable {mutableStateOf("")}
    var closeTarget by remember {mutableStateOf<SessionTarget?>(null)}
    var renameTarget by remember {mutableStateOf<SessionTarget?>(null)}
    val groups=remember(targets,current,search) {SessionCatalog.groups(targets,current,search)}
    // Filtering does not resize the drawer or move the search field under the finger.
    val panelHeight=(128+targets.size.coerceIn(1,7)*64+targets.map {it.server.id}.distinct().size.coerceAtMost(5)*24).dp.coerceIn(248.dp,600.dp)
    val scheme=MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=scheme.surface,
        dragHandle={Box(Modifier.fillMaxWidth().height(16.dp),contentAlignment=Alignment.Center) {
            Box(Modifier.width(28.dp).height(3.dp).background(scheme.outlineVariant,RoundedCornerShape(3.dp)))
        }}) {
        Column(Modifier.fillMaxWidth().height(panelHeight).padding(bottom=4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Sessions",Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold)
                TextButton(onClick=onNew,enabled=canCreate,contentPadding=PaddingValues(horizontal=8.dp)) {
                    ChromeSymbol(ChromeIcon.ADD,Modifier.size(15.dp),scheme.primary)
                    Spacer(Modifier.width(4.dp));Text("New",style=SettingsType.detail)
                }
                ChromeButton(ChromeIcon.CLOSE,"Close sessions",onClick=onClose)
            }
            BasicTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=40.dp)
                .clip(RoundedCornerShape(12.dp)).background(scheme.surfaceVariant.copy(alpha=.45f)).padding(horizontal=12.dp,vertical=10.dp),
                textStyle=SettingsType.row.copy(color=scheme.onSurface),singleLine=true,
                cursorBrush=androidx.compose.ui.graphics.SolidColor(scheme.primary),
                decorationBox={field->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    ChromeSymbol(ChromeIcon.SEARCH,Modifier.size(17.dp),scheme.onSurfaceVariant)
                    Box(Modifier.weight(1f)) {if(search.isEmpty()) Text("Find session…",style=SettingsType.row,color=scheme.onSurfaceVariant);field()}
                }})
            LazyColumn(Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(groups.isEmpty()) item {Text("No matching sessions",Modifier.padding(vertical=16.dp),style=SettingsType.row,color=scheme.onSurfaceVariant)}
                groups.forEach {group->
                    item(key="server:${group.server.id}") {
                        Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(group.server.label,Modifier.weight(1f),style=SettingsType.section,color=scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(group.sessions.size.toString(),style=SettingsType.detail,color=scheme.onSurfaceVariant)
                        }
                    }
                    items(group.sessions,key={it.key}) {target->
                        SessionRow(target,current==target.key,last==target.key,closing==target.key,
                            onSelect={onSelect(target.key)},onClose={closeTarget=target},onRename={renameTarget=target})
                    }
                }
            }
            if(error.isNotBlank()) Text(error,Modifier.padding(horizontal=16.dp,vertical=4.dp),color=scheme.error,style=SettingsType.detail)
        }
    }
    renameTarget?.let {target->
        SessionRenameDialog(target,closing.isBlank(),onSave={label->if(onRenameSession(target,label)) renameTarget=null},onDismiss={renameTarget=null})
    }
    closeTarget?.let {target->
        AlertDialog(onDismissRequest={closeTarget=null},title={Text("Close session?")},
            text={Text("Close “${target.session.label}” on ${target.server.label}? All its tabs and panes will close, ending the programs running in them.")},
            confirmButton={TextButton(onClick={closeTarget=null;onCloseSession(target)},enabled=closing.isBlank()) {
                Text("Close session",color=MaterialTheme.colorScheme.error)
            }},dismissButton={TextButton(onClick={closeTarget=null}){Text("Cancel")}})
    }

}

@Composable private fun LiveTerminal(client: RexConnection,blocks: List<BlockItem>,chosen: String,liveBlock: String,
    programs: Map<String,ProgramIdentity>,controlled: Boolean,fontSize: Float,
    onInput: (String)->Unit,onScroll: (Int)->Unit,onWheel: (Int,Int,Int,Int,Int)->Unit,onActivate: ()->Unit,onBottom: ()->Unit,onDimensions: (Int,Int)->Unit,
    onSelect: (String)->Unit,onPanes: ()->Unit,onView: (TerminalView)->Unit,modifier: Modifier=Modifier,
    onImage: (android.net.Uri,()->Unit)->Boolean={_,release->release();false},onPaste: ()->Boolean={false},
    padding: TerminalPadding=TerminalPadding.NORMAL,extendPadding: Boolean=true) {
    val layout by client.displayLayout.collectAsState()
    val screen=client.screenForLayout(layout)
    TerminalCarousel(blocks,chosen,liveBlock,screen,client.paneFrames,programs,controlled,fontSize,
        onInput,onScroll,onWheel,onActivate,onBottom,onDimensions,onSelect,onPanes,onView,modifier,client::bindTerminalView,onMouseClick=client::click,mouseTarget=client::keyTarget,onImage=onImage,onPaste=onPaste,imageTarget=client::imageInputTarget,padding=padding,extendPadding=extendPadding)
}

@Composable private fun BackgroundAlertsDialog(model: RexModel,onClose: ()->Unit) {
    val context=LocalContext.current
    var denied by remember {mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) {model.setBackgroundAlerts(true);denied=false} else denied=true
    }
    AlertDialog(onDismissRequest=onClose,title={Text("Background alerts",style=SettingsType.title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Get notified when a task finishes, needs input, approval, sign-in, or fails while Rexling is in the background or your phone is locked.",style=SettingsType.body)
            Text("A quiet monitoring notification stays visible. Stop it there, or turn this off. Closing Rexling stops monitoring.",style=SettingsType.body)
            Text("Your phone may delay alerts while sleeping.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Notify about task updates",Modifier.weight(1f),style=SettingsType.row)
                Switch(checked=model.backgroundAlerts,onCheckedChange={enabled->
                    if(!enabled) model.setBackgroundAlerts(false)
                    else if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if(BackgroundAlerts.allowed(context)) {model.setBackgroundAlerts(true);denied=false} else denied=true
                },enabled=model.servers.any {model.serverStatuses[it.id]?.maintained==true})
            }
            if(model.servers.none {model.serverStatuses[it.id]?.maintained==true}) Text("Connect a server to enable alerts.",style=SettingsType.detail)
            if(denied || (model.backgroundAlerts && !BackgroundAlerts.allowed(context))) {
                Text("Allow notifications in Android settings to receive alerts.",style=SettingsType.detail)
                TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) {Text("Notification settings")}
            }
        }
    },confirmButton={TextButton(onClick=onClose) {Text("Done")}})
}
