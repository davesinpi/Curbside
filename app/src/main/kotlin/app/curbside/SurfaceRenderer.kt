package app.curbside

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceContainer
import org.json.JSONObject

/**
 * Binds one long-lived WebView to whatever car surface Android Auto hands us.
 *
 * The WebView lives in a reusable [root] FrameLayout. On each surface we build a VirtualDisplay
 * straight onto the car surface (zero-copy) and show a Presentation that hosts [root]; when the
 * surface goes away we tear those down and pause the WebView so it costs ~0 CPU (design section 5).
 */
class SurfaceRenderer(private val carContext: CarContext) {

    // Quality knob (design section 5): <1.0 renders at lower dpi -> bigger text, less layout work,
    // cooler phone. The car's video encoder is the real heat source, so we trade sharpness for heat.
    // ponytail: fixed default; expose via settings only if a phone actually overheats.
    private val dpiScale = 1.0

    private var webView: WebView? = null
    private val root = FrameLayout(carContext)
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var customView: View? = null
    // Reopen where the driver left off, not the home page.
    private var pendingUrl = prefs(carContext).getString("last", null) ?: homeUrl(carContext)
    private var surfaceW = 0
    private var surfaceH = 0

    // Steering-wheel play/pause (design section 4): media buttons resume or pause the page's media.
    private val mediaSession = MediaSession(carContext, "Curbside").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { root.post { webView?.evaluateJavascript(PLAY_LAST_JS, null) } }
            override fun onPause() { root.post { webView?.evaluateJavascript(PAUSE_ALL_JS, null) } }
        })
    }

    private fun setPlaying(playing: Boolean) {
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        mediaSession.isActive = true
    }

    /** Car keyboard for page text fields: set by BrowserScreen, called with the field's current text. */
    var onEditText: ((String) -> Unit)? = null

    /**
     * Called from page JS. Only reports playback state and text-field taps: harmless to any page.
     * Audio focus is left to WebView itself; requesting our own focus too made the two fight and
     * the loser paused every video right after play.
     */
    private inner class Bridge {
        @JavascriptInterface fun play() { root.post { setPlaying(true) } }
        @JavascriptInterface fun pause() { root.post { setPlaying(false) } }
        @JavascriptInterface fun edit(value: String) { root.post { onEditText?.invoke(value) } }
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(carContext).apply {
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // WebView's own zoom off: it double-tap zoomed on every quick pair of taps. Zoom is CSS
            // zoom instead (menu row, or pinch via onScale), which also keeps pages fitting the width.
            settings.setSupportZoom(false)
            settings.textZoom = textSize(carContext)
            // Privacy defaults (design section 6): no third-party cookies, no form/password saving.
            @Suppress("DEPRECATION")
            settings.saveFormData = false
            // New-tab links (target=_blank, window.open) reach onCreateWindow and open in this view.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            if (isDesktop(carContext)) settings.userAgentString = desktopUserAgent(settings.userAgentString)
            addJavascriptInterface(Bridge(), "Curbside")
            webChromeClient = fullscreenVideoClient()
            // Without a WebViewClient, tapped links are handed to the phone's browser instead of
            // loading here. Non-web schemes (intent:, market:, tel:) are dropped: no phone UI in a car.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.scheme !in setOf("http", "https")

                // ponytail: listens on the top document only; media inside cross-origin iframes
                // (embedded players) won't take audio focus. Inject per-frame if that matters.
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith("http")) prefs(carContext).edit().putString("last", url).apply()
                    view.evaluateJavascript(MEDIA_HOOK_JS, null)
                    applyZoom(view)
                }
            }
        }
        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        wv.loadUrl(pendingUrl)
        return wv
    }

    fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        if (container.width <= 0 || container.height <= 0) return
        surfaceW = container.width
        surfaceH = container.height
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val dpi = (container.dpi * dpiScale).toInt().coerceAtLeast(120)
        virtualDisplay = dm.createVirtualDisplay(
            "Curbside", surfaceW, surfaceH, dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        )
        virtualDisplay?.display?.let { showPresentation(it) }
    }

    private fun showPresentation(display: Display) {
        val wv = ensureWebView()
        (root.parent as? ViewGroup)?.removeView(root)
        presentation = Presentation(carContext, display).apply {
            setContentView(root)
            window?.clearFlags(2 /* FLAG_DIM_BEHIND */)
            show()
        }
        wv.onResume()
        wv.resumeTimers()
    }

    fun onSurfaceDestroyed() {
        webView?.apply { onPause(); pauseTimers() }
        presentation?.dismiss(); presentation = null
        (root.parent as? ViewGroup)?.removeView(root)
        virtualDisplay?.release(); virtualDisplay = null
    }

    fun destroy() {
        onSurfaceDestroyed()
        mediaSession.release()
        webView?.destroy(); webView = null
    }

    fun onTrimMemory() {
        webView?.apply { clearCache(false); clearHistory() }
    }

    // ---- actions / input (design section 4) ----

    fun load(input: String) { pendingUrl = toUrl(input); webView?.loadUrl(pendingUrl) ?: ensureWebView() }
    fun home() = load(homeUrl(carContext))
    val title: String? get() = webView?.title?.takeIf { it.isNotBlank() }

    fun bookmarkPage(): String {
        val url = webView?.url?.takeIf { it.startsWith("http") } ?: return "Nothing to bookmark"
        return Bookmarks.add(carContext, title ?: url, url)
    }

    /** Steps text size 100 -> 125 -> 150 -> 100 %. Bigger text is easier to read at arm's length. */
    fun nextTextSize() {
        val next = TEXT_SIZES[(TEXT_SIZES.indexOf(textSize(carContext)) + 1) % TEXT_SIZES.size]
        setTextSize(carContext, next)
        webView?.settings?.textZoom = next
    }
    /** Leaves fullscreen video first (WebView then calls onHideCustomView), else goes back a page. */
    fun back(): Boolean {
        if (customView != null) { webView?.evaluateJavascript("document.exitFullscreen()", null); return true }
        return webView?.takeIf { it.canGoBack() }?.also { it.goBack() } != null
    }
    fun forward() { webView?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { webView?.reload() }

    /** Switches desktop/mobile site, remembers the choice, and reloads. */
    fun toggleDesktop() {
        val on = !isDesktop(carContext)
        setDesktop(carContext, on)
        webView?.apply {
            settings.userAgentString = if (on) desktopUserAgent(WebSettings.getDefaultUserAgent(carContext)) else null
            reload()
        }
    }
    /** Replaces the tapped field's text (execCommand fires the page's input events) and presses Enter. */
    fun typeText(text: String) {
        val wv = webView ?: return
        wv.evaluateJavascript("document.execCommand('selectAll');document.execCommand('insertText',false,${JSONObject.quote(text)})") {
            val t = SystemClock.uptimeMillis()
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        }
    }

    fun pageDown() = page(0.85f)
    fun pageUp() = page(-0.85f)
    private fun page(f: Float) { webView?.let { it.scrollBy(0, (it.height * f).toInt()) } }

    /**
     * Car taps arrive in surface coordinates. [root] fills the surface at (0,0), so dispatching
     * there lets the view tree subtract the inset padding (and hit a fullscreen video view too).
     */
    fun onClick(x: Float, y: Float) {
        if (presentation == null) return
        val t = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 10)) {
            val ev = MotionEvent.obtain(t, time, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            root.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    fun onScroll(dx: Float, dy: Float) { webView?.scrollBy(dx.toInt(), dy.toInt()) }
    fun onFling(vx: Float, vy: Float) { webView?.flingScroll(-vx.toInt(), -vy.toInt()) }
    /**
     * Steps page zoom through [ZOOMS]. CSS zoom, not WebView.zoomIn/Out: WebView can't zoom out past
     * its fit-to-width start scale, and CSS zoom reflows the page so it still fits the screen width.
     */
    fun zoom(step: Int) {
        val i = ZOOMS.indexOf(pageZoom(carContext)).takeIf { it >= 0 } ?: ZOOMS.indexOf(100)
        setPageZoom(carContext, ZOOMS[(i + step).coerceIn(ZOOMS.indices)])
        webView?.let(::applyZoom)
    }
    // Fullscreen video gets 100%: CSS zoom would shrink it too and leave the rest of the screen white.
    private fun applyZoom(wv: WebView) {
        val z = if (customView != null) 100 else pageZoom(carContext)
        wv.evaluateJavascript("document.documentElement.style.zoom='$z%'", null)
    }
    // Pinch (multi-touch head units) steps the same CSS zoom as the menu, once per ~25% of pinch.
    // The host also reports a double tap as a single onScale(2.0) (map zoom-in); quick taps on a
    // page are just taps, so that one is dropped. Real pinches arrive as many small factors.
    private var pinch = 1f
    fun onScale(factor: Float) {
        if (factor <= 0f || factor >= 2f) return
        pinch *= factor
        if (pinch > 1.25f || pinch < 0.8f) { zoom(if (pinch > 1f) 1 else -1); pinch = 1f }
    }

    private fun fullscreenVideoClient() = object : WebChromeClient() {
        // One car screen, no tabs: a new-tab link loads here instead. Only on a tap, so pop-up ads stay blocked.
        // A throwaway WebView receives the new window's first navigation and hands the URL over.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            if (!isUserGesture) return false
            val catcher = WebView(view.context)
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme in setOf("http", "https")) view.loadUrl(request.url.toString())
                    v.destroy()
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView = view
            webView?.let(::applyZoom)
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }; customView = null
            webView?.let(::applyZoom)
        }
    }

    private companion object {
        // Media events don't bubble, so listen in the capture phase. Guarded: onPageFinished can repeat.
        // A tap on a text field hands it to the car keyboard (the phone IME can't reach a virtual display).
        const val MEDIA_HOOK_JS = """(function(){if(window.__curbside)return;window.__curbside=1;
            document.addEventListener('play',function(e){window.__curbsideLast=e.target;Curbside.play()},true);
            document.addEventListener('pause',function(){Curbside.pause()},true);
            document.addEventListener('ended',function(){Curbside.pause()},true);
            document.addEventListener('click',function(e){var t=e.target;
              if(t.isContentEditable||t.tagName=='TEXTAREA'||(t.tagName=='INPUT'&&/^(text|search|email|url|tel|number|password)$/.test(t.type))){
                t.focus();Curbside.edit(t.value||t.innerText||'')}},true);})()"""
        const val PAUSE_ALL_JS = "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})"
        const val PLAY_LAST_JS = "window.__curbsideLast&&window.__curbsideLast.play()"
    }
}
