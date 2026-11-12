package dev.shulhi.rex

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TerminalUpdatesTest {
    private fun packet(revision: Long,flag: Int,changed: Map<Int,String>,metadata: String="",version: Int=1,links: Map<Int,List<Triple<Int,Int,String>>> = emptyMap(),images: ByteArray?=null): ByteArray {
        val out=ByteArrayOutputStream()
        fun number(value: Long,size: Int) {repeat(size){out.write((value ushr (8*it)).toInt() and 255)}}
        fun text(value: String) {val bytes=value.toByteArray();number(bytes.size.toLong(),4);out.write(bytes)}
        out.write("RXT$version".toByteArray());number(revision,8);text(metadata);number(flag.toLong(),1)
        if(flag>=2) {
            number(2,2);number(2,2);number(-1,4);number(-1,4);number(-1,4);number(0xff000000,4)
            repeat(3){number(2,8)};if(version>=2) number(3,1);number(changed.size.toLong(),2)
            changed.forEach {(row,value)->
                number(row.toLong(),2);number(1,2);number(0,2);number(1,1);number(0,4);number(-1,4);number(0xff000000,4);text(value)
                if(version>=3) {val ranges=links[row].orEmpty();number(ranges.size.toLong(),2);for((start,end,url) in ranges) {number(start.toLong(),2);number(end.toLong(),2);text(url)}}
            }
        }
        if(version>=4) out.write(images ?: byteArrayOf(0))
        return out.toByteArray()
    }
    @Test fun deltasReuseUntouchedRowsAndPreserveUnicode() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,3,mapOf(0 to "λ",1 to "🦀"),"""{"phase":"connected","selectedBlock":"a"}"""))
        val second=decoder.decode(packet(4,2,mapOf(1 to "\uDB80\uDF1D")))
        assertSame(first.screen!!.lines[0],second.screen!!.lines[0])
        assertEquals("\uDB80\uDF1D",second.screen.lines[1][0].text)
        assertEquals("a",second.selectedBlock);assertEquals(4L,decoder.revision)
        val unchanged=decoder.decode(packet(5,0,emptyMap(),"""{"phase":"paused"}"""))
        assertSame(second.screen,unchanged.screen)
        assertNull(decoder.decode(packet(6,1,emptyMap())).screen)
    }
    @Test fun unknownBaseTruncationAndDuplicateRevisionAreRejectedWithoutAdvancing() {
        val decoder=TerminalUpdates()
        assertTrue(runCatching {decoder.decode(packet(1,2,mapOf(0 to "x")))}.isFailure)
        val full=packet(1,3,mapOf(0 to "x",1 to "y"))
        assertTrue(runCatching {decoder.decode(full.copyOf(full.size-1))}.isFailure)
        assertEquals(0L,decoder.revision)
        decoder.decode(full)
        assertTrue(runCatching {decoder.decode(full)}.isFailure)
        assertEquals(1L,decoder.revision)
    }
    @Test fun versionTwoCarriesApplicationInputModes() {
        val legacy=packet(1,3,mapOf(0 to "x",1 to "y"))
        val at=17+44 // frame flag/header, then input-mode byte before changed-row count
        val current=legacy.copyOfRange(0,at)+byteArrayOf(3)+legacy.copyOfRange(at,legacy.size)
        current[3]='2'.code.toByte()
        val frame=TerminalUpdates().decode(current).screen!!
        assertEquals(3,frame.inputFlags);assertTrue(frame.applicationScroll)
    }

    @Test fun versionThreeKeepsAndReplacesSparseHyperlinksWithoutChangingLabelText() {
        val decoder=TerminalUpdates()
        fun ranges(url: String)=mapOf(0 to listOf(Triple(0,1,url)))
        val first=decoder.decode(packet(1,3,mapOf(0 to "Docs",1 to "x"),version=3,links=ranges("https://example.com/one")))
        val second=decoder.decode(packet(2,2,mapOf(0 to "Docs"),version=3,links=ranges("https://example.com/two")))
        assertEquals("https://example.com/one",first.screen!!.lines[0][0].hyperlink)
        assertEquals("Docs",second.screen!!.lines[0][0].text)
        assertEquals("https://example.com/two",second.screen.lines[0][0].hyperlink)
        assertSame(first.screen.lines[1],second.screen.lines[1])
        assertNull(decoder.decode(packet(3,2,mapOf(0 to "Docs"),version=3)).screen!!.lines[0][0].hyperlink)
    }
    @Test fun invalidHyperlinkRangesDoNotAdvanceTheDecoder() {
        for(ranges in listOf(listOf(Triple(1,1,"https://example.com")),listOf(Triple(0,3,"https://example.com")),
            listOf(Triple(0,1,"https://a.example"),Triple(0,1,"https://b.example")))) {
            val decoder=TerminalUpdates()
            assertTrue(runCatching {decoder.decode(packet(1,3,mapOf(0 to "x",1 to "y"),version=3,links=mapOf(0 to ranges)))}.isFailure)
            assertEquals(0L,decoder.revision)
        }
    }

    @Test fun metadataOnlyTitleUpdatesReuseTheActivityCatalogUntilExplicitReplacement() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,1,emptyMap(),"""{"activity":[{"session":"s","block":"b","state":"blocked","title":"Task"}]}"""))
        val next=decoder.decode(packet(2,1,emptyMap(),"""{"titles":{"b":"New title"}}"""))
        assertSame(first.activity,next.activity)
        assertTrue(decoder.decode(packet(3,1,emptyMap(),"""{"activity":null}""")).activity.isEmpty())
    }


    /** RXT4 image section: one placement of image 7 and, optionally, its 1×2 pixels. */
    private fun images(generation: Long=3,row: Int=1,pixels: Boolean=true): ByteArray {
        val out=ByteArrayOutputStream()
        fun number(value: Long,size: Int) {repeat(size){out.write((value ushr (8*it)).toInt() and 255)}}
        out.write(1);number(10,4);number(20,4);number(1,2)
        number(7,4);number(generation,8);out.write(2);number(0,4);number(0,4);number(row.toLong(),4)
        for(v in listOf(1,1,0,0,10,20,0,0,1,2)) number(v.toLong(),4)
        if(pixels) {number(1,2);number(7,4);number(generation,8);number(1,4);number(2,4);number(8,4);out.write(byteArrayOf(10,20,30,-1,100,100,100,0))}
        else number(0,2)
        return out.toByteArray()
    }
    @Test fun kittyPlacementsAndPixelsDecodeAndPixelsPersistAcrossMoves() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,3,mapOf(0 to "a",1 to "b"),version=4,images=images())).screen!!
        val placement=first.images.placements.single()
        assertEquals(7,placement.imageId);assertEquals(1,placement.row);assertEquals(TerminalImagePlacement.ABOVE_TEXT,placement.layer)
        assertEquals(10,first.images.cellWidth);assertEquals(20,first.images.cellHeight)
        val pixels=first.images.pixels[7 to 3L]!!
        assertEquals(1,pixels.width);assertEquals(2,pixels.height)
        // A moved placement without pixels keeps the cached pixels; an unchanged flag keeps everything.
        val moved=decoder.decode(packet(2,2,mapOf(0 to "c"),version=4,images=images(row=0,pixels=false))).screen!!
        assertSame(pixels,moved.images.pixels[7 to 3L]);assertEquals(0,moved.images.placements.single().row)
        val same=decoder.decode(packet(3,2,mapOf(1 to "d"),version=4)).screen!!
        assertSame(moved.images,same.images)
        // A new generation drops the old pixels.
        val replaced=decoder.decode(packet(4,2,mapOf(0 to "e"),version=4,images=images(generation=4))).screen!!
        assertNull(replaced.images.pixels[7 to 3L]);assertNotNull(replaced.images.pixels[7 to 4L])
    }
    @Test fun kittyPixelsArePremultipliedAndBadSizesRejected() {
        assertArrayEquals(byteArrayOf(10,20,30,-1,50,50,50,-128),TerminalImagePixels.premultiply(byteArrayOf(10,20,30,-1,100,100,100,-128)))
        val bad=images().also {it[it.size-9]=99}  // claimed byte length no longer matches 1×2×4
        assertTrue(runCatching {TerminalUpdates().decode(packet(1,3,mapOf(0 to "a",1 to "b"),version=4,images=bad))}.isFailure)
    }
}
