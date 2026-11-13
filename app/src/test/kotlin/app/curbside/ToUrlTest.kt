package app.curbside

import org.junit.Assert.assertEquals
import org.junit.Test

class ToUrlTest {
    @Test fun addressOrSearch() {
        assertEquals("https://example.com", toUrl("example.com"))
        assertEquals("https://news.site/a?b=1", toUrl("  news.site/a?b=1 "))
        assertEquals("http://plain.org", toUrl("http://plain.org"))
        assertEquals("https://192.168.1.10:8080/", toUrl("192.168.1.10:8080/"))
        assertEquals("https://localhost:3000", toUrl("localhost:3000"))
        assertEquals("about:blank", toUrl("about:blank"))
        assertEquals("https://duckduckgo.com/?q=weather+today", toUrl("weather today"))
        assertEquals("https://duckduckgo.com/?q=kotlin", toUrl("kotlin"))
        assertEquals("https://duckduckgo.com/?q=javascript%3Aalert%281%29", toUrl("javascript:alert(1)"))
    }

    @Test fun desktopUaKeepsChromeVersion() {
        val wv = "Mozilla/5.0 (Linux; Android 14; SM-A528B Build/UP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.39 Mobile Safari/537.36"
        val ua = desktopUserAgent(wv)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.39 Safari/537.36", ua)
        assertEquals(false, "Mobile" in ua || "Android" in ua || "wv" in ua)
        assert("Chrome/120.0.0.0" in desktopUserAgent("garbage"))
    }
}
