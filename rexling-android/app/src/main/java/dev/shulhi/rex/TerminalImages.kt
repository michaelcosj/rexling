package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer

/**
 * One kitty graphics placement, positioned by libghostty. Sizes are in the
 * replica's cell pixels ([TerminalImages.cellWidth]/[TerminalImages.cellHeight]).
 */
internal data class TerminalImagePlacement(
    val imageId: Int,val generation: Long,val layer: Int,val z: Int,
    val col: Int,val row: Int,val cols: Int,val rows: Int,val offsetX: Int,val offsetY: Int,val width: Int,val height: Int,
    val sourceX: Int,val sourceY: Int,val sourceWidth: Int,val sourceHeight: Int,
) {
    companion object {
        const val BELOW_BACKGROUND=0;const val BELOW_TEXT=1;const val ABOVE_TEXT=2
    }
}

/**
 * Premultiplied RGBA pixels for one image generation. The Bitmap is made on
 * first draw (main thread) and the byte array is released after that.
 */
internal class TerminalImagePixels(val width: Int,val height: Int,premultipliedRgba: ByteArray) {
    private var pixels: ByteArray?=premultipliedRgba
    private var bitmap: Bitmap?=null
    fun bitmap(): Bitmap? {
        bitmap?.let {return it}
        val data=pixels ?: return null
        return runCatching {
            Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {it.copyPixelsFromBuffer(ByteBuffer.wrap(data))}
        }.getOrNull()?.also {bitmap=it;pixels=null}
    }
    companion object {
        /** Straight RGBA8 to the premultiplied RGBA byte order ARGB_8888 buffers use. Modifies [rgba]. */
        fun premultiply(rgba: ByteArray): ByteArray {
            for(i in 0 until rgba.size-3 step 4) {
                val a=rgba[i+3].toInt() and 255
                if(a!=255) for(c in 0..2) rgba[i+c]=(((rgba[i+c].toInt() and 255)*a+127)/255).toByte()
            }
            return rgba
        }
    }
}

/** Placements in draw order plus the pixels they reference. Immutable per frame. */
internal class TerminalImages(val cellWidth: Int,val cellHeight: Int,val placements: List<TerminalImagePlacement>,val pixels: Map<Pair<Int,Long>,TerminalImagePixels>) {
    val isEmpty: Boolean get()=placements.isEmpty()
    /**
     * Draw one layer. The canvas is already translated to the grid origin and
     * clipped; replica cell pixels are scaled to the view's cell size.
     */
    fun draw(canvas: Canvas,layer: Int,viewCellWidth: Float,viewCellHeight: Float,paint: Paint) {
        if(placements.isEmpty()) return
        val scaleX=if(cellWidth>0) viewCellWidth/cellWidth else 1f
        val scaleY=if(cellHeight>0) viewCellHeight/cellHeight else 1f
        for(p in placements) {
            if(p.layer!=layer) continue
            val bitmap=pixels[p.imageId to p.generation]?.bitmap() ?: continue
            val target=target(p,viewCellWidth,viewCellHeight,scaleX,scaleY) ?: continue
            source.set(p.sourceX,p.sourceY,(p.sourceX+p.sourceWidth).coerceAtMost(bitmap.width),(p.sourceY+p.sourceHeight).coerceAtMost(bitmap.height))
            if(source.isEmpty) continue
            canvas.drawBitmap(bitmap,source,target,paint)
        }
    }
    private val source=Rect();private val destination=RectF()
    private fun target(p: TerminalImagePlacement,cw: Float,ch: Float,sx: Float,sy: Float): RectF? {
        if(p.sourceWidth<=0 || p.sourceHeight<=0 || p.width<=0 || p.height<=0) return null
        val left=p.col*cw+p.offsetX*sx;val top=p.row*ch+p.offsetY*sy
        destination.set(left,top,left+p.width*sx,top+p.height*sy)
        return destination
    }
    companion object {val None=TerminalImages(0,0,emptyList(),emptyMap())}
}
