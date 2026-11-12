package app.curbside

import android.content.Context

const val HOME_URL = "https://duckduckgo.com"
const val MAX_BOOKMARKS = 12

/** Copied from PrivaCar (design section 11): address bar input -> URL to load. */
fun toUrl(input: String): String {
    val s = input.trim()
    val host = Regex("""^(localhost|[^\s/:]+\.[a-z]{2,}|\d{1,3}(\.\d{1,3}){3})(:\d+)?(/\S*)?$""", RegexOption.IGNORE_CASE)
    return when {
        Regex("^(https?://|about:)", RegexOption.IGNORE_CASE).containsMatchIn(s) -> s
        host.matches(s) -> "https://$s"
        else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(s, "UTF-8")
    }
}

/** Bookmarks: "Title|url" lines in SharedPreferences, edited on the phone (SetupActivity). */
object Bookmarks {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("curbside", Context.MODE_PRIVATE)

    data class Entry(val title: String, val url: String)

    fun list(ctx: Context): List<Entry> =
        prefs(ctx).getStringSet("bookmarks", null)?.sorted().orEmpty()
            .mapNotNull { line -> line.split("|", limit = 2).takeIf { it.size == 2 }?.let { Entry(it[0], it[1]) } }
            .ifEmpty { DEFAULTS }

    fun save(ctx: Context, entries: List<Entry>) {
        val set = entries.take(MAX_BOOKMARKS)
            .map { "${it.title.replace("|", " ")}|${it.url}" }.toSet()
        prefs(ctx).edit().putStringSet("bookmarks", set).apply()
    }

    private val DEFAULTS = listOf(
        Entry("YouTube", "https://m.youtube.com"),
        Entry("Wikipedia", "https://wikipedia.org"),
        Entry("Hacker News", "https://news.ycombinator.com"),
    )
}
