package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Rex-styled menus: JetBrains Mono throughout, flat surfaces separated by hairlines,
 * square toggles and segmented choices. Colors always come from the active theme,
 * so light mode and theme presets keep working.
 */

private fun mono(size: Int,line: Int,weight: FontWeight=FontWeight.Normal)=TextStyle(fontFamily=MonoUi,fontSize=size.sp,lineHeight=line.sp,fontWeight=weight)

/** Material typography in JetBrains Mono, so stock components inside menus match. */
internal val RexTypography=Typography(
    displayLarge=mono(32,40,FontWeight.Bold),displayMedium=mono(28,36,FontWeight.Bold),displaySmall=mono(24,32,FontWeight.Bold),
    headlineLarge=mono(22,28,FontWeight.Bold),headlineMedium=mono(18,24,FontWeight.Bold),headlineSmall=mono(16,22,FontWeight.Bold),
    titleLarge=mono(16,22,FontWeight.Bold),titleMedium=mono(14,20,FontWeight.Medium),titleSmall=mono(13,18,FontWeight.Medium),
    bodyLarge=mono(14,20),bodyMedium=mono(13,19),bodySmall=mono(11,16),
    labelLarge=mono(13,18,FontWeight.Medium),labelMedium=mono(12,16,FontWeight.Medium),labelSmall=mono(11,14,FontWeight.Medium))

private val RexShapes=Shapes(extraSmall=RoundedCornerShape(4.dp),small=RoundedCornerShape(6.dp),medium=RoundedCornerShape(8.dp),
    large=RoundedCornerShape(12.dp),extraLarge=RoundedCornerShape(14.dp))

/** Applies Rex typography and tighter shapes to everything inside a menu. */
@Composable internal fun RexMenuTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=MaterialTheme.colorScheme,typography=RexTypography,shapes=RexShapes,content=content)
}

@Composable internal fun RexDragHandle() {
    Box(Modifier.fillMaxWidth().height(18.dp),contentAlignment=Alignment.Center) {
        Box(Modifier.width(28.dp).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(3.dp)))
    }
}

/** A flat bottom sheet with the same top corners as the tab dock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RexSheet(onDismissRequest: ()->Unit,sheetState: SheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
    containerColor: Color=MaterialTheme.colorScheme.surface,dragHandle: (@Composable ()->Unit)?={RexDragHandle()},content: @Composable ColumnScope.()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismissRequest,sheetState=sheetState,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),
        containerColor=containerColor,tonalElevation=0.dp,scrimColor=Color.Black.copy(alpha=.55f),dragHandle=dragHandle) {
        RexMenuTheme {Column(Modifier.fillMaxWidth(),content=content)}
    }
}

/** AlertDialog with Rex type, shapes and a hairline border; same slots as Material's. */
@Composable internal fun RexAlertDialog(onDismissRequest: ()->Unit,confirmButton: @Composable ()->Unit,modifier: Modifier=Modifier,
    dismissButton: (@Composable ()->Unit)?=null,title: (@Composable ()->Unit)?=null,text: (@Composable ()->Unit)?=null) {
    RexMenuTheme {
        AlertDialog(onDismissRequest=onDismissRequest,confirmButton=confirmButton,dismissButton=dismissButton,
            title=title?.let {t->{ProvideTextStyle(SettingsType.title,t)}},text=text,shape=RoundedCornerShape(14.dp),
            containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp,
            modifier=modifier.border(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f),RoundedCornerShape(14.dp)))
    }
}

/** A compact dialog in Rex type; buttons are usually [RexButton]s. */
@Composable internal fun RexDialog(onDismissRequest: ()->Unit,title: String,confirmButton: @Composable ()->Unit,dismissButton: (@Composable ()->Unit)?=null,
    text: (@Composable ()->Unit)?=null) {
    RexAlertDialog(onDismissRequest,confirmButton,dismissButton=dismissButton,title={Text(title)},text=text)
}

/** A small rectangular button; [primary] fills it with the accent. */
@Composable internal fun RexButton(text: String,onClick: ()->Unit,modifier: Modifier=Modifier,primary: Boolean=false,destructive: Boolean=false,enabled: Boolean=true) {
    val scheme=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(7.dp)
    val content=when {!enabled->scheme.onSurfaceVariant.copy(alpha=.5f);primary->scheme.onPrimary;destructive->scheme.error;else->scheme.onSurface}
    Box(modifier.heightIn(min=36.dp).clip(shape)
        .then(if(primary && enabled) Modifier.background(scheme.primary) else Modifier.border(1.dp,if(destructive) scheme.error.copy(alpha=.5f) else scheme.outlineVariant,shape))
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=14.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
        Text(text,style=SettingsType.row.copy(fontSize=12.sp),color=content,maxLines=1)
    }
}

/** The visual part of a toggle; put toggleable semantics on the row that owns it. */
@Composable internal fun RexToggle(checked: Boolean,modifier: Modifier=Modifier,enabled: Boolean=true) {
    val scheme=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(5.dp)
    Box(modifier.size(36.dp,20.dp).clip(shape).background(if(checked) scheme.primary else scheme.surfaceVariant)
        .border(1.dp,if(checked) scheme.primary else scheme.outlineVariant,shape).padding(3.dp),
        contentAlignment=if(checked) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
            .background(if(checked) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha=if(enabled) 1f else .5f)))
    }
}

/** Joined choices, like the keys of the keyboard dock. Each segment is selectable for accessibility. */
@Composable internal fun <T> RexSegmented(options: List<T>,selected: T,label: (T)->String,onSelect: (T)->Unit,modifier: Modifier=Modifier) {
    val scheme=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(7.dp)
    Row(modifier.height(IntrinsicSize.Min).clip(shape).border(1.dp,scheme.outlineVariant,shape)) {
        options.forEachIndexed {index,option->
            if(index>0) Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
            val on=option==selected
            Box(Modifier.background(if(on) scheme.primary else Color.Transparent)
                .selectable(on,role=Role.RadioButton) {onSelect(option)}.padding(horizontal=11.dp,vertical=7.dp),contentAlignment=Alignment.Center) {
                Text(label(option),style=SettingsType.row.copy(fontSize=12.sp),color=if(on) scheme.onPrimary else scheme.onSurfaceVariant,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}
