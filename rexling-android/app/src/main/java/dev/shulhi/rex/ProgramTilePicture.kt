package dev.shulhi.rex

import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import org.json.JSONObject

/** Cached vector drawing commands, replayed at the final pixel size and angle. */
internal class ProgramTilePicture private constructor(private val picture: Picture) {
    fun draw(canvas: Canvas,width: Float,height: Float,rotation: Float=0f) {
        val save=canvas.save()
        canvas.rotate(rotation,width/2,height/2)
        canvas.scale(width/61f,height/48f)
        canvas.drawPicture(picture)
        canvas.restoreToCount(save)
    }
    companion object {
        private var catalog: JSONObject?=null
        private val cache=mutableMapOf<String,ProgramTilePicture>()
        @Synchronized fun load(assets: AssetManager,name: String): ProgramTilePicture = cache.getOrPut(name) {
            val icons=catalog ?: assets.open("program_tiles.json").bufferedReader().use {JSONObject(it.readText()).getJSONObject("icons")}.also {catalog=it}
            val picture=Picture();val canvas=picture.beginRecording(61,48)
            val commands=icons.getJSONArray(name)
            for(i in 0 until commands.length()) {
                val command=commands.getJSONObject(i)
                when(command.getString("op")) {
                    "save"->canvas.save()
                    "restore"->canvas.restore()
                    "translate"->canvas.translate(command.float("x"),command.float("y"))
                    "scale"->canvas.scale(command.float("x"),command.float("y"))
                    "transform"->canvas.concat(transform(command.getString("value")))
                    "clip"->canvas.clipPath(path(command))
                    "path"->{val path=path(command);val bounds=RectF();path.computeBounds(bounds,true);canvas.drawPath(path,paint(command.getJSONObject("paint"),bounds))}
                    else->error("Unsupported vector command")
                }
            }
            picture.endRecording();ProgramTilePicture(picture)
        }
        private fun JSONObject.float(key: String)=getDouble(key).toFloat()
        private fun path(command: JSONObject)=requireNotNull(PathParser.createPathFromPathData(command.getString("path"))).apply {
            fillType=if(command.optBoolean("evenOdd")) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        }
        private val numbers=Regex("[-+]?(?:\\d*\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?")
        private val transforms=Regex("(matrix|translate|scale|rotate)\\s*\\(([^)]*)\\)")
        private fun transform(value: String): Matrix {
            val matrix=Matrix()
            for(match in transforms.findAll(value)) {
                val v=numbers.findAll(match.groupValues[2]).map {it.value.toFloat()}.toList()
                val next=Matrix()
                when(match.groupValues[1]) {
                    "translate"->next.setTranslate(v[0],v.getOrElse(1) {0f})
                    "scale"->next.setScale(v[0],v.getOrElse(1) {v[0]})
                    "rotate"->next.setRotate(v[0],v.getOrElse(1) {0f},v.getOrElse(2) {0f})
                    "matrix"->next.setValues(floatArrayOf(v[0],v[2],v[4],v[1],v[3],v[5],0f,0f,1f))
                }
                matrix.preConcat(next)
            }
            return matrix
        }
        private fun color(value: String,opacity: Float=1f): Int {
            val values=numbers.findAll(if(value.startsWith("color(display-p3")) value.substringAfter("display-p3") else value).map {it.value.toFloat()}.toList()
            val color=when {
                value.startsWith("color(display-p3")->Color.valueOf(values[0],values[1],values[2],1f,ColorSpace.get(ColorSpace.Named.DISPLAY_P3)).toArgb()
                value.startsWith("rgba(")->Color.argb((values[3]*255).toInt(),values[0].toInt(),values[1].toInt(),values[2].toInt())
                value=="currentColor"->Color.BLACK
                else->Color.parseColor(value)
            }
            return Color.argb((Color.alpha(color)*opacity).toInt().coerceIn(0,255),Color.red(color),Color.green(color),Color.blue(color))
        }
        private fun coordinate(value: String)=if(value.endsWith('%')) value.dropLast(1).toFloat()/100f else value.toFloat()
        private fun paint(value: JSONObject,bounds: RectF): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if(value.optBoolean("stroke")) {
                style=Paint.Style.STROKE;strokeWidth=value.optDouble("strokeWidth",1.0).toFloat()
                strokeCap=when(value.optString("strokeCap")) {"round"->Paint.Cap.ROUND;"square"->Paint.Cap.SQUARE;else->Paint.Cap.BUTT}
                strokeJoin=when(value.optString("strokeJoin")) {"round"->Paint.Join.ROUND;"bevel"->Paint.Join.BEVEL;else->Paint.Join.MITER}
            }
            if(!value.has("gradient")) {color=color(value.getString("color"),value.float("alpha"));return@apply}
            val attributes=value.getJSONObject("attributes");val stops=value.getJSONArray("stops")
            val positions=FloatArray(stops.length());val colors=IntArray(stops.length())
            for(i in positions.indices) {
                val stop=stops.getJSONObject(i);positions[i]=coordinate(stop.getString("offset"))
                colors[i]=color(stop.getString("stop-color"),stop.optDouble("stop-opacity",1.0).toFloat())
            }
            val local=Matrix()
            if(attributes.optString("gradientUnits")!="userSpaceOnUse") {
                local.setValues(floatArrayOf(bounds.width(),0f,bounds.left,0f,bounds.height(),bounds.top,0f,0f,1f))
            }
            val gradientTransform=attributes.optString("gradientTransform")
            if(gradientTransform.isNotEmpty()) local.preConcat(transform(gradientTransform))
            fun coord(name: String,default: String)=coordinate(attributes.optString(name,default))
            shader=if(value.getString("gradient")=="radialGradient") {
                RadialGradient(coord("cx","50%"),coord("cy","50%"),coord("r","50%"),colors,positions,Shader.TileMode.CLAMP)
            } else LinearGradient(coord("x1","0"),coord("y1","0"),coord("x2","1"),coord("y2","0"),colors,positions,Shader.TileMode.CLAMP)
            shader!!.setLocalMatrix(local)
            alpha=(value.float("alpha")*255).toInt().coerceIn(0,255)
        }
    }
}
