package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Image identity belongs to one decoded frame stream, never just an image ID. */
internal class TerminalImages {
    private val cache=java.util.LinkedHashMap<TerminalImage,Bitmap>(8,.75f,true)
    private var bytes=0L
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun clear() {cache.clear();bytes=0}
    private fun bitmap(image: TerminalImage): Bitmap {
        cache[image]?.let {return it}
        val pixels=IntArray(image.width*image.height)
        for(i in pixels.indices) {
            val p=i*4;val rgba=image.rgba
            pixels[i]=((rgba[p+3].toInt() and 255) shl 24) or ((rgba[p].toInt() and 255) shl 16) or
                ((rgba[p+1].toInt() and 255) shl 8) or (rgba[p+2].toInt() and 255)
        }
        val bitmap=Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888)
        cache[image]=bitmap;bytes+=bitmap.allocationByteCount
        val entries=cache.entries.iterator()
        while(bytes>32L*1024*1024 && cache.size>1 && entries.hasNext()) {
            val old=entries.next();bytes-=old.value.allocationByteCount;entries.remove()
            // Display lists may still hold older bitmaps; let Android release them safely.
        }
        return bitmap
    }
    fun draw(canvas: Canvas,screen: TerminalScreen,layer: Int,left: Float,top: Float,right: Float,bottom: Float,cw: Float,ch: Float) {
        val sx=cw/screen.imageCellWidth.coerceAtLeast(1);val sy=ch/screen.imageCellHeight.coerceAtLeast(1)
        val save=canvas.save();canvas.clipRect(left,top,right,bottom)
        for(p in screen.images) {
            if(p.layer!=layer) continue
            val x=left+p.col*cw+p.offsetX*sx;val y=top+p.row*ch+p.offsetY*sy
            val target=RectF(x,y,x+p.width*sx,y+p.height*sy)
            if(!RectF.intersects(target,RectF(left,top,right,bottom))) continue
            canvas.drawBitmap(bitmap(p.image),Rect(p.sourceX,p.sourceY,p.sourceX+p.sourceWidth,p.sourceY+p.sourceHeight),target,paint)
        }
        canvas.restoreToCount(save)
    }
}
