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

fun prefs(ctx: Context) = ctx.getSharedPreferences("curbside", Context.MODE_PRIVATE)

/** Home page, set in the phone app. */
fun homeUrl(ctx: Context) = prefs(ctx).getString("home", null) ?: HOME_URL
fun setHomeUrl(ctx: Context, input: String) =
    prefs(ctx).edit().apply { if (input.isBlank()) remove("home") else putString("home", toUrl(input)) }.apply()

/** Page text size (WebView textZoom %), cycled from the car menu. */
val TEXT_SIZES = listOf(100, 125, 150)
fun textSize(ctx: Context) = prefs(ctx).getInt("textZoom", 100)
fun setTextSize(ctx: Context, zoom: Int) = prefs(ctx).edit().putInt("textZoom", zoom).apply()

/** Page zoom % (CSS zoom), stepped with the car menu's -/+ buttons. */
val ZOOMS = listOf(50, 67, 75, 90, 100, 110, 125, 150, 175, 200)
fun pageZoom(ctx: Context) = prefs(ctx).getInt("pageZoom", 100)
fun setPageZoom(ctx: Context, zoom: Int) = prefs(ctx).edit().putInt("pageZoom", zoom).apply()

/** Desktop site toggle (design section 6). Default desktop: car screens are wide. */
fun isDesktop(ctx: Context) = prefs(ctx).getBoolean("desktop", true)
fun setDesktop(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("desktop", on).apply()

/** Desktop Chrome UA that keeps the WebView's real Chrome version, so sites don't serve "outdated browser" pages. */
fun desktopUserAgent(defaultUa: String): String {
    val chrome = Regex("""Chrome/[\d.]+""").find(defaultUa)?.value ?: "Chrome/120.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
}

/** Bookmarks: "Title|url" lines in SharedPreferences, edited on the phone (SetupActivity). */
object Bookmarks {
    data class Entry(val title: String, val url: String)

    fun list(ctx: Context): List<Entry> =
        prefs(ctx).getStringSet("bookmarks", null)?.sorted().orEmpty()
            .mapNotNull { line -> line.split("|", limit = 2).takeIf { it.size == 2 }?.let { Entry(it[0], it[1]) } }
            .ifEmpty { DEFAULTS }

    /** Adds a page from the car menu. Returns a short message for the driver. */
    fun add(ctx: Context, title: String, url: String): String {
        val now = list(ctx)
        return when {
            now.any { it.url == url } -> "Already bookmarked"
            now.size >= MAX_BOOKMARKS -> "Bookmarks full ($MAX_BOOKMARKS)"
            else -> { save(ctx, now + Entry(title.ifBlank { url }.take(40), url)); "Bookmarked" }
        }
    }

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
