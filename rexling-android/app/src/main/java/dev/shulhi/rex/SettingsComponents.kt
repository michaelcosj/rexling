package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object SettingsType {
    val title=TextStyle(fontFamily=MonoUi,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Bold)
    val row=TextStyle(fontFamily=MonoUi,fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium)
    val body=TextStyle(fontFamily=MonoUi,fontSize=13.sp,lineHeight=19.sp)
    val detail=TextStyle(fontFamily=MonoUi,fontSize=11.sp,lineHeight=16.sp)
    val section=detail.copy(fontWeight=FontWeight.Medium,letterSpacing=.6.sp)
}

@Composable internal fun SettingsHeader(title: String,closeDescription: String,onClose: ()->Unit,subtitle: String?=null) {
    Row(Modifier.fillMaxWidth().padding(bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("~/",style=SettingsType.title,color=MaterialTheme.colorScheme.primary)
                Text(title,style=SettingsType.title,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            subtitle?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        IconButton(onClick=onClose,modifier=Modifier.semantics {contentDescription=closeDescription}) {ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(18.dp),MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable internal fun SettingsSection(title: String,content: @Composable ColumnScope.()->Unit) {
    // A labelled rule instead of a card, like a section in a terminal listing.
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=SettingsType.section,color=MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f))
        }
        Column(Modifier.fillMaxWidth(),content=content)
    }
}

@Composable internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start=32.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
}

internal enum class SettingsGlyph { APPEARANCE,FONT,ALERTS,UPLOAD,INFO,SERVER,COMMAND }

@Composable internal fun SettingsRow(title: String,subtitle: String,glyph: SettingsGlyph,onClick: ()->Unit,badge: String?=null) {
    val chevronColor=MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onClick).padding(end=4.dp,top=11.dp,bottom=11.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        SettingsIcon(glyph)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(title,style=SettingsType.row)
            Text(subtitle,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
        }
        badge?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.primary)}
        // A chevron indicates navigation without implying a terminal key action.
        Canvas(Modifier.size(16.dp)) {
            val u=size.minDimension/24f
            drawLine(chevronColor,Offset(9f*u,5f*u),Offset(16f*u,12f*u),1.8f*u,StrokeCap.Round)
            drawLine(chevronColor,Offset(16f*u,12f*u),Offset(9f*u,19f*u),1.8f*u,StrokeCap.Round)
        }
    }
}

@Composable private fun SettingsIcon(glyph: SettingsGlyph) {
    val color=MaterialTheme.colorScheme.primary
    // A bare glyph column, like a prompt marker, instead of a tinted tile.
    Box(Modifier.size(20.dp),contentAlignment=Alignment.Center) {
            if(glyph==SettingsGlyph.FONT) Text("Aa",style=SettingsType.row.copy(fontWeight=FontWeight.Bold),color=color)
            else Canvas(Modifier.size(18.dp)) {
                val u=size.minDimension/24f
                val stroke=Stroke(1.7f*u,cap=StrokeCap.Round)
                fun line(x1: Float,y1: Float,x2: Float,y2: Float)=drawLine(color,Offset(x1*u,y1*u),Offset(x2*u,y2*u),1.7f*u,StrokeCap.Round)
                when(glyph) {
                    SettingsGlyph.APPEARANCE->{
                        drawCircle(color,8f*u,style=stroke)
                        drawArc(color,-90f,180f,true,Offset(4f*u,4f*u),Size(16f*u,16f*u))
                    }
                    SettingsGlyph.ALERTS->{
                        val path=androidx.compose.ui.graphics.Path().apply {
                            moveTo(5f*u,17f*u);lineTo(7f*u,14f*u);lineTo(7f*u,10f*u)
                            cubicTo(7f*u,3f*u,17f*u,3f*u,17f*u,10f*u)
                            lineTo(17f*u,14f*u);lineTo(19f*u,17f*u);close()
                        }
                        drawPath(path,color,style=stroke);line(10f,21f,14f,21f);line(12f,3f,12f,4f)
                    }
                    SettingsGlyph.UPLOAD->{line(12f,16f,12f,4f);line(7f,9f,12f,4f);line(12f,4f,17f,9f);line(4f,16f,4f,21f);line(4f,21f,20f,21f);line(20f,21f,20f,16f)}
                    SettingsGlyph.INFO->{drawCircle(color,9f*u,style=stroke);drawCircle(color,1f*u,Offset(12f*u,7f*u));line(12f,11f,12f,17f)}
                    SettingsGlyph.SERVER->{
                        for(y in listOf(4f,14f)) {
                            drawRoundRect(color,Offset(3f*u,y*u),Size(18f*u,6f*u),CornerRadius(1.5f*u),style=stroke)
                            drawCircle(color,.8f*u,Offset(7f*u,(y+3)*u))
                        }
                    }
                    SettingsGlyph.FONT->Unit
                    SettingsGlyph.COMMAND->{line(9f,6f,3f,12f);line(3f,12f,9f,18f);line(15f,6f,21f,12f);line(21f,12f,15f,18f)}
                }
            }
    }
}

/** A labelled toggle row; the whole row toggles. */
@Composable internal fun SettingsToggleRow(title: String,checked: Boolean,onChange: (Boolean)->Unit,subtitle: String?=null) {
    Row(Modifier.fillMaxWidth().toggleable(checked,role=Role.Switch,onValueChange=onChange).padding(end=4.dp,top=11.dp,bottom=11.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(title,style=SettingsType.row)
            subtitle?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        RexToggle(checked)
    }
}

/** A labelled single choice among a few options. */
@Composable internal fun <T> SettingsChoiceRow(title: String,options: List<T>,selected: T,label: (T)->String,onSelect: (T)->Unit,subtitle: String?=null) {
    Column(Modifier.fillMaxWidth().padding(end=4.dp,top=11.dp,bottom=11.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(title,style=SettingsType.row)
        RexSegmented(options,selected,label,onSelect)
        subtitle?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

/** A labelled text field; [mono] is kept for callers, as every field now uses the menu's monospace type. */
@Composable internal fun SettingsTextField(label: String,value: String,onChange: (String)->Unit,modifier: Modifier=Modifier,
    mono: Boolean=false,singleLine: Boolean=true,minLines: Int=1,maxLines: Int=if(singleLine) 1 else 10,isError: Boolean=false,supporting: String?=null) {
    val scheme=MaterialTheme.colorScheme
    OutlinedTextField(value,onChange,modifier.fillMaxWidth(),label={Text(label,style=SettingsType.detail)},singleLine=singleLine,minLines=minLines,maxLines=maxLines,
        textStyle=SettingsType.body,isError=isError,shape=RoundedCornerShape(8.dp),
        colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=scheme.outlineVariant,focusedContainerColor=scheme.background.copy(alpha=.5f),
            unfocusedContainerColor=scheme.background.copy(alpha=.5f)),
        supportingText=supporting?.let {{Text(it,style=SettingsType.detail)}})
}

/** Two choices: a confirming action and Cancel. */
@Composable internal fun SettingsConfirmDialog(title: String,body: String,confirm: String,onConfirm: ()->Unit,onDismiss: ()->Unit,detail: String?=null) {
    RexDialog(onDismiss,title,confirmButton={RexButton(confirm,onConfirm,primary=true)},dismissButton={RexButton("Cancel",onDismiss)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(body,style=SettingsType.body)
            detail?.let {Text(it,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background,RoundedCornerShape(6.dp))
                .border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(6.dp)).padding(horizontal=10.dp,vertical=8.dp),
                style=SettingsType.body,color=MaterialTheme.colorScheme.tertiary)}
        }
    })
}
