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
}
