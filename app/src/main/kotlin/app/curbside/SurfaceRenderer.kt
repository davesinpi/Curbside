package app.curbside

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
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
    private var pendingUrl = HOME_URL
    private var contentInsets = Rect()
    private var surfaceW = 0
    private var surfaceH = 0

    // Media audio focus only while a page is actually playing (design section 4), so opening the
    // browser doesn't stop the driver's music. Losing focus (another app, nav prompt) pauses the page.
    private val audioManager = carContext.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                root.post { webView?.evaluateJavascript(PAUSE_ALL_JS, null) }
            }
        }
        .build()

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

    /** Called from page JS on media play/pause. Only exposes focus/playback state: harmless to any page. */
    private inner class AudioBridge {
        @JavascriptInterface fun play() {
            root.post { audioManager.requestAudioFocus(focusRequest); setPlaying(true) }
        }
        @JavascriptInterface fun pause() {
            root.post { audioManager.abandonAudioFocusRequest(focusRequest); setPlaying(false) }
        }
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
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            // Privacy defaults (design section 6): no third-party cookies, no form/password saving.
            @Suppress("DEPRECATION")
            settings.saveFormData = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            if (isDesktop(carContext)) settings.userAgentString = desktopUserAgent(settings.userAgentString)
            addJavascriptInterface(AudioBridge(), "CurbsideAudio")
            webChromeClient = fullscreenVideoClient()
            // Without a WebViewClient, tapped links are handed to the phone's browser instead of
            // loading here. Non-web schemes (intent:, market:, tel:) are dropped: no phone UI in a car.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.scheme !in setOf("http", "https")

                // ponytail: listens on the top document only; media inside cross-origin iframes
                // (embedded players) won't take audio focus. Inject per-frame if that matters.
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(MEDIA_HOOK_JS, null)
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
        applyInsets()
        wv.onResume()
        wv.resumeTimers()
    }

    /** Pad content so the page isn't hidden under Android Auto's own chrome (design section 4). */
    fun onVisibleAreaChanged(area: Rect) {
        if (!area.isEmpty) { contentInsets = area; applyInsets() }
    }

    private fun applyInsets() {
        if (presentation == null) return
        val a = contentInsets
        root.setPadding(a.left, a.top, (surfaceW - a.right).coerceAtLeast(0), (surfaceH - a.bottom).coerceAtLeast(0))
    }

    fun onSurfaceDestroyed() {
        webView?.apply { onPause(); pauseTimers() }
        presentation?.dismiss(); presentation = null
        (root.parent as? ViewGroup)?.removeView(root)
        virtualDisplay?.release(); virtualDisplay = null
    }

    fun destroy() {
        onSurfaceDestroyed()
        audioManager.abandonAudioFocusRequest(focusRequest)
        mediaSession.release()
        webView?.destroy(); webView = null
    }

    fun onTrimMemory() {
        webView?.apply { clearCache(false); clearHistory() }
    }

    // ---- actions / input (design section 4) ----

    fun load(input: String) { pendingUrl = toUrl(input); webView?.loadUrl(pendingUrl) ?: ensureWebView() }
    fun home() = load(HOME_URL)
    fun back(): Boolean = webView?.takeIf { it.canGoBack() }?.also { it.goBack() } != null
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
    fun pageDown() { webView?.let { it.scrollBy(0, (it.height * 0.85f).toInt()) } }

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
    fun onScale(factor: Float) { if (factor > 0f) webView?.zoomBy(factor.coerceIn(0.01f, 100f)) }

    private fun fullscreenVideoClient() = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView = view
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }; customView = null
        }
    }

    private companion object {
        // Media events don't bubble, so listen in the capture phase. Guarded: onPageFinished can repeat.
        const val MEDIA_HOOK_JS = """(function(){if(window.__curbside)return;window.__curbside=1;
            document.addEventListener('play',function(e){window.__curbsideLast=e.target;CurbsideAudio.play()},true);
            document.addEventListener('pause',function(){CurbsideAudio.pause()},true);
            document.addEventListener('ended',function(){CurbsideAudio.pause()},true);})()"""
        const val PAUSE_ALL_JS = "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})"
        const val PLAY_LAST_JS = "window.__curbsideLast&&window.__curbsideLast.play()"
    }
}
