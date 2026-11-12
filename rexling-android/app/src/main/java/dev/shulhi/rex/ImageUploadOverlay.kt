package dev.shulhi.rex

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val UploadPillText=SettingsType.row.copy(fontSize=12.sp,lineHeight=18.sp,letterSpacing=0.sp,fontWeight=FontWeight.SemiBold)

@Composable internal fun ImageUploadOverlay(status: ImageUploadStatus,onRetry: ()->Unit,onCancel: ()->Unit,modifier: Modifier=Modifier,retryable: Boolean=true,hazeState: dev.chrisbanes.haze.HazeState?=null) {
    val localHaze=remember {dev.chrisbanes.haze.HazeState()}
    val scheme=MaterialTheme.colorScheme
    val failed=status.phase==ImagePhase.FAILED
    val success=status.phase==ImagePhase.SUCCESS
    val uploading=status.phase==ImagePhase.UPLOADING && status.progress>0f
    val percent=(status.progress.coerceIn(0f,1f)*100).roundToInt()
    val green=Color(0xff27884f)
    val ink=Color(0xff202a3c)
    val container by animateColorAsState(if(success) Color(0xffdef2e5) else Color(0xffe5edff),tween(200),label="Upload success fill")
    val accent by animateColorAsState(if(success) green else scheme.primary,tween(200),label="Upload success ring")
    val titleColor by animateColorAsState(if(success) Color(0xff1e7143) else ink,tween(200),label="Upload success text")
    Surface(modifier.widthIn(max=280.dp).paneFrost(hazeState ?: localHaze,RoundedCornerShape(20.dp),edge=false,tint=container).semantics {paneTitle="Image upload"},
        shape=RoundedCornerShape(20.dp),color=Color.Transparent,contentColor=ink) {
        Column(Modifier.animateContentSize(spring(dampingRatio=.9f,stiffness=500f),alignment=Alignment.BottomEnd)) {
            Row(Modifier.then(if(failed) Modifier.fillMaxWidth() else Modifier)
                .padding(start=8.dp,end=if(success) 8.dp else 2.dp).heightIn(min=32.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                if(failed) ChromeSymbol(ChromeIcon.IMAGE,Modifier.size(14.dp),Color(0xffb3261e))
                else UploadRing(status.progress,indeterminate=!uploading && !success,color=accent,success=success)
                AnimatedContent(when(status.phase) {
                    ImagePhase.PREPARING->"Preparing image"
                    ImagePhase.UPLOADING->"Uploading image"
                    ImagePhase.FINISHING->"Finishing upload"
                    ImagePhase.SUCCESS->"Image uploaded"
                    ImagePhase.FAILED->"Upload failed"
                },Modifier.weight(1f,fill=failed),transitionSpec={uploadTextTransition()},label="Upload label") {label->
                    val softness by transition.animateFloat(transitionSpec={tween(200)},label="Upload label softness") {
                        if(it==EnterExitState.Visible) 0f else 2.5f
                    }
                    Text(label,Modifier.blur(softness.dp,BlurredEdgeTreatment.Unbounded),style=UploadPillText,
                        color=titleColor,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                if(uploading) UploadPercentage(percent)
                if(!success) IconButton(onClick=onCancel,modifier=Modifier.size(32.dp).semantics {
                    contentDescription=if(failed) "Dismiss upload error" else "Cancel image upload"
                }) {ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(13.dp),ink.copy(alpha=.6f))}
            }
            if(failed) {
                Text(status.error,Modifier.padding(start=8.dp,end=8.dp,bottom=if(retryable) 0.dp else 8.dp),
                    style=SettingsType.detail,color=ink.copy(alpha=.75f))
                if(retryable) TextButton(onClick=onRetry,modifier=Modifier.align(Alignment.End).padding(end=4.dp,bottom=2.dp),
                    colors=ButtonDefaults.textButtonColors(contentColor=scheme.primary)) {Text("Retry",style=SettingsType.section)}
            }
        }
    }
}

private fun uploadTextTransition() =
    (slideInVertically(tween(220,easing=LinearOutSlowInEasing)) {it/5}+fadeIn(tween(180))) togetherWith
        (slideOutVertically(tween(180)) {-it/5}+fadeOut(tween(140)))

@Composable private fun UploadRing(progress: Float,indeterminate: Boolean,color: Color,success: Boolean) {
    val sweep by animateFloatAsState(progress.coerceIn(0f,1f)*360f,tween(240),label="Upload ring progress")
    val ringWidth by animateFloatAsState(if(success) 1.1f else 2f,tween(180),label="Upload ring weight")
    val check by animateFloatAsState(if(success) 1f else 0f,tween(180),label="Upload check")
    val rotation=if(indeterminate) rememberInfiniteTransition(label="Upload activity").animateFloat(
        0f,360f,infiniteRepeatable(tween(1100,easing=LinearEasing)),label="Upload ring rotation") else rememberUpdatedState(0f)
    Canvas(Modifier.size(14.dp).semantics {
        progressBarRangeInfo=if(indeterminate) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(progress.coerceIn(0f,1f),0f..1f)
    }) {
        val stroke=ringWidth.dp.toPx();val inset=stroke/2
        val origin=androidx.compose.ui.geometry.Offset(inset,inset)
        val extent=androidx.compose.ui.geometry.Size(size.width-stroke,size.height-stroke)
        drawArc(color.copy(alpha=.14f),0f,360f,false,origin,extent,style=Stroke(stroke,cap=StrokeCap.Round))
        drawArc(color,-90f+rotation.value,if(indeterminate) 260f else sweep.coerceAtLeast(2f),false,origin,extent,
            style=Stroke(stroke,cap=StrokeCap.Round))
        if(check>0f) {
            val u=size.minDimension/18f
            val mark=androidx.compose.ui.graphics.Path().apply {moveTo(5f*u,9f*u);lineTo(8f*u,12f*u);lineTo(13f*u,6.5f*u)}
            drawPath(mark,color.copy(alpha=check),style=Stroke(1.2f*u,cap=StrokeCap.Round))
        }
    }
}

@Composable private fun UploadPercentage(percent: Int) {
    val digitWidth=with(LocalDensity.current) {7.5.sp.toDp()}
    Row(Modifier.clearAndSetSemantics {contentDescription="$percent%"},verticalAlignment=Alignment.CenterVertically) {
        val digits=percent.toString()
        digits.forEachIndexed {index,digit->
            key(digits.length-index) {
                AnimatedContent(digit,Modifier.width(digitWidth).alignByBaseline().clipToBounds(),
                    transitionSpec={uploadTextTransition()},contentAlignment=Alignment.Center,label="Upload digit") {value->
                    val softness by transition.animateFloat(transitionSpec={tween(200)},label="Upload digit softness") {
                        if(it==EnterExitState.Visible) 0f else 2.5f
                    }
                    Text(value.toString(),Modifier.blur(softness.dp,BlurredEdgeTreatment.Unbounded),
                        style=UploadPillText.copy(fontFeatureSettings="tnum"),maxLines=1)
                }
            }
        }
        Text("%",Modifier.alignByBaseline(),style=UploadPillText)
    }
}
