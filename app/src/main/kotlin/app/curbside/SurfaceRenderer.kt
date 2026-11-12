package app.curbside

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
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
            settings.saveFormData = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = fullscreenVideoClient()
        }
        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        wv.loadUrl(pendingUrl)
        return wv
    }

    fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        if (container.width <= 0 || container.height <= 0) return
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val dpi = (container.dpi * dpiScale).toInt().coerceAtLeast(120)
        virtualDisplay = dm.createVirtualDisplay(
            "Curbside", container.width, container.height, dpi, surface,
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
        val d = presentation?.display ?: return
        val w = d.width; val h = d.height
        val a = contentInsets
        root.setPadding(a.left, a.top, (w - a.right).coerceAtLeast(0), (h - a.bottom).coerceAtLeast(0))
    }

    fun onSurfaceDestroyed() {
        webView?.apply { onPause(); pauseTimers() }
        presentation?.dismiss(); presentation = null
        (root.parent as? ViewGroup)?.removeView(root)
        virtualDisplay?.release(); virtualDisplay = null
    }

    fun destroy() {
        onSurfaceDestroyed()
        webView?.destroy(); webView = null
    }

    fun onTrimMemory() {
        webView?.apply { clearCache(false); clearHistory() }
    }

    // ---- actions / input (design section 4) ----

    fun load(input: String) { pendingUrl = toUrl(input); webView?.loadUrl(pendingUrl) ?: ensureWebView() }
    fun home() = load(HOME_URL)
    fun back(): Boolean = webView?.takeIf { it.canGoBack() }?.also { it.goBack() } != null
    fun pageDown() { webView?.let { it.scrollBy(0, (it.height * 0.85f).toInt()) } }

    fun onClick(x: Float, y: Float) {
        val wv = webView ?: return
        val t = SystemClock.uptimeMillis()
        wv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        wv.dispatchTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, x, y, 0))
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
}
