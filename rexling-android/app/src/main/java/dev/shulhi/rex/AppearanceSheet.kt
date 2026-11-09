package dev.shulhi.rex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable internal fun QuickThemeMenuItem(dark: Boolean,onDark: (Boolean)->Unit) {
    TerminalMenuItem(if(dark) "Toggle Light theme" else "Toggle Dark theme",if(dark) ChromeIcon.SUN else ChromeIcon.MOON,onClick={onDark(!dark)})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AppearanceSheet(appearance: Appearance,themes: List<AppTheme>,light: AppTheme,dark: AppTheme,
    onAppearance: (Appearance)->Unit,onTheme: (String)->Unit,onClose: ()->Unit) {
    var editing by remember {mutableStateOf<Boolean?>(null)}
    BackHandler(editing!=null) {editing=null}
    RexSheet(onDismissRequest=onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start=if(editing!=null) 8.dp else 20.dp,end=20.dp),verticalAlignment=Alignment.CenterVertically) {
                if(editing!=null) ChromeButton(ChromeIcon.LEFT,"Back to appearance") {editing=null}
                Box(Modifier.weight(1f)) {
                    SettingsHeader(if(editing==null) "Appearance" else if(editing==true) "Dark theme" else "Light theme","Close appearance",onClose)
                }
            }
            if(editing==null) {
                Column(Modifier.padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    SettingsSection("Mode") {
                        SettingsChoiceRow("Follow",Appearance.entries,appearance,{when(it){Appearance.SYSTEM->"System";Appearance.DARK->"Dark";Appearance.LIGHT->"Light"}},onAppearance)
                    }
                    SettingsSection("Themes") {
                        for((label,preset) in listOf("Light theme" to light,"Dark theme" to dark)) {
                            Row(Modifier.fillMaxWidth().clickable {editing=preset.terminal.dark}.padding(vertical=10.dp),
                                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                ThemeSwatch(preset)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Text(label,style=SettingsType.row)
                                    Text(preset.name,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ChromeSymbol(ChromeIcon.RIGHT,Modifier.size(16.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                val selected=if(editing==true) dark else light
                val options=remember(themes,editing) {themes.filter {it.terminal.dark==editing}}
                LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(options,key={it.id}) {theme->
                        val active=theme.id==selected.id
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .border(1.dp,if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f),RoundedCornerShape(10.dp))
                            .semantics(mergeDescendants=true) {contentDescription="Theme ${theme.name}";this.selected=active;role=Role.RadioButton}
                            .clickable {onTheme(theme.id);editing=null}.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            ThemeSwatch(theme)
                            Text(theme.name,Modifier.weight(1f),style=SettingsType.row.copy(fontWeight=if(active) FontWeight.Bold else FontWeight.Normal))
                            if(active) Text("✓",color=MaterialTheme.colorScheme.primary,style=SettingsType.title)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ThemeSwatch(theme: AppTheme) {
    val palette=theme.terminal
    Column(Modifier.size(64.dp,42.dp).clip(RoundedCornerShape(9.dp)).background(Color(palette.background))
        .border(1.dp,Color(palette.foreground).copy(alpha=.15f),RoundedCornerShape(9.dp)).padding(7.dp),verticalArrangement=Arrangement.SpaceBetween) {
        Text("❯ Aa",fontFamily=MonoUi,fontSize=11.sp,color=Color(palette.foreground),lineHeight=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {for(i in listOf(1,2,3,4,5)) Box(Modifier.size(7.dp,3.dp).background(Color(palette.ansi[i]),RoundedCornerShape(1.dp)))}
    }
}

@Composable internal fun FontSizeDialog(size: Int,onSize: (Int)->Unit,onClose: ()->Unit) {
    RexDialog(onClose,"Font size",confirmButton={RexButton("Done",onClose,primary=true)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton(onClick={onSize(size-1)},enabled=size>8,modifier=Modifier.semantics {contentDescription="Decrease font size"}) {Text("−",style=SettingsType.title.copy(fontSize=22.sp))}
                Text("$size sp",style=SettingsType.title)
                IconButton(onClick={onSize(size+1)},enabled=size<20,modifier=Modifier.semantics {contentDescription="Increase font size"}) {Text("+",style=SettingsType.title.copy(fontSize=22.sp))}
            }
            Slider(value=size.toFloat(),onValueChange={onSize(it.roundToInt())},valueRange=8f..20f,steps=11,modifier=Modifier.semantics {contentDescription="Font size"})
            Text("Aa 0123 / ~",fontFamily=MonoUi,fontSize=size.sp)
        }
    })
}
