package com.metrowin.keyboard

import com.metrowin.keyboard.gif.GifRating
import com.metrowin.keyboard.gif.GifSourceType
import com.metrowin.keyboard.gif.GifSources
import org.junit.Assert.*
import org.junit.Test

class GifSourcesTest {
    @Test fun theRatingReachesEveryService() {
        assertTrue(GifSources.giphyUrl("K", "ciao", 5, "it", GifRating.G).contains("rating=g"))
        assertTrue(GifSources.giphyUrl("K", null, 5, "it", GifRating.R).contains("rating=r"))
        assertTrue(GifSources.tenorUrl("K", "x", 5, "it", GifRating.PG).contains("contentfilter=medium"))
        assertTrue(GifSources.tenorUrl("K", "x", 5, "it", GifRating.R).contains("contentfilter=off"))
        assertTrue(GifSources.klipyUrl("K", "x", 5, GifRating.PG13).contains("content_filter=low"))
        assertTrue(GifSources.customUrl("https://e.org/s?q={query}&r={rating}", "", "a b", 5, "it", GifRating.PG).endsWith("q=a+b&r=pg"))
        assertEquals(GifRating.PG13, GifRating.from(null))
    }

    @Test fun klipyAddressesAndNoUserIdIsSent() {
        val u = GifSources.klipyUrl("my key", "ciao mondo", 20)
        assertTrue(u.startsWith("https://api.klipy.com/api/v1/my+key/gifs/search?q=ciao+mondo&per_page=20"))
        assertFalse(u.contains("customer_id"))
        assertTrue(GifSources.klipyUrl("K", null, 20).contains("/gifs/trending?"))
    }

    @Test fun klipyAnswerIsRead() {
        val json = """{"result":true,"data":{"data":[
            {"id":1,"slug":"hello","title":"Hello","file":{"hd":{"gif":{"url":"https://static.klipy.com/hd.gif"}},"md":{"gif":{"url":"https://static.klipy.com/md.gif"}},"sm":{"gif":{"url":"https://static.klipy.com/sm.gif"}}}},
            {"id":2,"title":"no gif","file":{"hd":{"mp4":{"url":"https://static.klipy.com/x.mp4"}}}}]}}"""
        val items = GifSources.parseKlipy(json)
        assertEquals(1, items.size)
        assertEquals("https://static.klipy.com/sm.gif", items[0].previewUrl)
        assertEquals("https://static.klipy.com/md.gif", items[0].fullUrl)
        assertTrue(GifSources.parseKlipy("""{"result":false,"errors":{"message":["bad key"]}}""").isEmpty())
    }

    @Test fun imgurNeedsTheKeyAsAHeaderAndHonoursTheMatureFlag() {
        assertEquals("Client-ID abc", GifSources.imgurHeaders("abc")["Authorization"])
        assertTrue(GifSources.imgurUrl("gatti").contains("q=gatti&q_type=anigif"))
        val json = """{"data":[
            {"title":"single","link":"https://i.imgur.com/a.gif","type":"image/gif","size":1000},
            {"title":"album","images":[{"link":"https://i.imgur.com/b.jpg","type":"image/jpeg"},{"link":"https://i.imgur.com/c.gif","type":"image/gif","size":2000}]},
            {"title":"mature","nsfw":true,"link":"https://i.imgur.com/d.gif","type":"image/gif"},
            {"title":"huge","link":"https://i.imgur.com/e.gif","type":"image/gif","size":99000000},
            {"title":"still","link":"https://i.imgur.com/f.png","type":"image/png"}]}"""
        assertEquals(listOf("https://i.imgur.com/a.gif", "https://i.imgur.com/c.gif"), GifSources.parseImgur(json, allowMature = false).map { it.fullUrl })
        assertEquals(3, GifSources.parseImgur(json, allowMature = true).size)
    }

    @Test fun everyKeyedServiceHasAPageToGetTheKey() {
        for (t in GifSourceType.entries) {
            if (t.needsKey) assertTrue("${t.code} page", t.keyPage!!.startsWith("https://"))
            else assertNull(t.keyPage)
        }
        assertFalse(GifSourceType.FOLDER.hasRating)
    }
}
