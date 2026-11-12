package dev.shulhi.rex

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** State is decoder-local: row deltas never cross a connection or an unknown base. */
internal class TerminalUpdates {
    var revision: Long=0;private set
    private var state=AppState()
    fun decode(bytes: ByteArray): AppState {
        require(bytes.size in 17..134217728) {"Invalid terminal packet size"}
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version=data.int
        require(version==0x31545852 || version==0x32545852 || version==0x33545852 || version==0x34545852) {"Unsupported terminal packet"}
        val nextRevision=data.long;require(nextRevision>revision)
        fun text(): String {val n=data.int;require(n>=0 && n<=data.remaining());val raw=ByteArray(n);data.get(raw);return raw.toString(Charsets.UTF_8)}
        val metadata=text()
        var next=if(metadata.isNotEmpty()) AppState.parse(metadata,state.activity) else state.copy(previewFrames=emptyMap())
        val frame=when(val flag=data.get().toInt()) {
            0->state.screen
            1->null
            2,3->{
                val columns=data.short.toInt() and 0xffff;val rows=data.short.toInt() and 0xffff
                require(columns>0 && rows>0 && columns.toLong()*rows<=2_000_000)
                val x=data.int;val y=data.int;val fg=data.int;val bg=data.int
                val scroll=TerminalScroll(data.long,data.long,data.long)
                val inputFlags=if(version>=0x32545852) data.get().toInt() and 0xff else 0
                val previous=state.screen
                if(flag==2) require(previous!=null && previous.columns==columns && previous.rows==rows) {"Missing terminal delta base"}
                val lines=if(flag==2) previous!!.lines.toMutableList() else MutableList<List<TerminalCell>>(rows){emptyList()}
                val changed=data.short.toInt() and 0xffff;require(changed<=rows)
                val seen=HashSet<Int>()
                repeat(changed) {
                    val row=data.short.toInt() and 0xffff;require(row<rows && seen.add(row))
                    val count=data.short.toInt() and 0xffff;require(count<=columns)
                    val cells=List(count) {
                        val col=data.short.toInt() and 0xffff;val width=data.get().toInt() and 0xff
                        val attrs=data.int;val foreground=data.int;val background=data.int
                        require(col<columns && width<=2)
                        TerminalCell(col,row,text(),foreground,background,attrs,width)
                    }
                    lines[row]=if(version>=0x33545852) {
                        val n=data.short.toInt() and 0xffff;require(n<=columns)
                        var end=0
                        val links=List(n) {
                            val start=data.short.toInt() and 0xffff;val stop=data.short.toInt() and 0xffff
                            require(start>=end && stop>start && stop<=columns);end=stop
                            val uri=text();require(uri.isNotEmpty() && uri.toByteArray(Charsets.UTF_8).size<=65536)
                            Triple(start,stop,uri)
                        }
                        var index=0
                        cells.map {cell->
                            while(index<links.size && links[index].second<=cell.x) index++
                            val link=links.getOrNull(index)
                            if(link!=null && cell.x>=link.first && cell.x<link.second) cell.copy(hyperlink=link.third) else cell
                        }
                    } else cells
                }
                if(flag==3) require(changed==rows)
                TerminalScreen(columns,rows,x.takeIf {it>=0},y.takeIf {it>=0},fg,bg,lines.flatten(),scroll,lines,inputFlags,state.screen?.images ?: TerminalImages.None)
            }
            else->error("Invalid terminal frame flag $flag")
        }
        val imaged=if(version>=0x34545852) images(data,frame) else frame
        require(!data.hasRemaining()) {"Trailing terminal packet data"}
        next=next.copy(revision=nextRevision,screen=imaged)
        state=next;revision=nextRevision
        return next
    }
    /** RXT4 kitty section. Pixels arrive once per (id, generation); bitmaps not
     *  referenced by the latest placement list are dropped. */
    private fun images(data: ByteBuffer,frame: TerminalScreen?): TerminalScreen? {
        if(data.get().toInt()==0) return frame
        val cellWidth=data.int;val cellHeight=data.int
        require(cellWidth in 0..4096 && cellHeight in 0..4096)
        val count=data.short.toInt() and 0xffff;require(count<=256) {"Too many image placements"}
        val placements=List(count) {
            TerminalImagePlacement(data.int,data.long,data.get().toInt() and 0xff,data.int,data.int,data.int,
                data.int,data.int,data.int,data.int,data.int,data.int,data.int,data.int,data.int,data.int)
        }
        val keys=placements.mapTo(HashSet()) {it.imageId to it.generation}
        val pixels=(frame?.images?.pixels ?: emptyMap()).filterKeys {it in keys}.toMutableMap()
        repeat(data.short.toInt() and 0xffff) {
            val id=data.int;val generation=data.long;val width=data.int;val height=data.int;val length=data.int
            require(width in 1..16384 && height in 1..16384 && length.toLong()==width.toLong()*height*4 && length<=data.remaining()) {"Invalid image pixels"}
            val rgba=ByteArray(length);data.get(rgba)
            if((id to generation) in keys) pixels[id to generation]=TerminalImagePixels(width,height,TerminalImagePixels.premultiply(rgba))
        }
        return frame?.copy(images=if(placements.isEmpty()) TerminalImages.None else TerminalImages(cellWidth,cellHeight,placements,pixels))
    }
}
