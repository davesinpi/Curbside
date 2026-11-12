package app.curbside

import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** The car screen: a NavigationTemplate whose surface is driven by [SurfaceRenderer]. */
class BrowserScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = SurfaceRenderer(carContext)
    private var audioFocus: AudioFocusRequest? = null

    init { lifecycle.addObserver(this) }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        requestMediaAudioFocus()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        renderer.destroy()
        audioFocus?.let { carContext.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(action("Back") { renderer.back() })
            .addAction(action("Address") {
                screenManager.push(AddressScreen(carContext) { renderer.load(it) })
            })
            .addAction(action("Bookmarks") {
                screenManager.push(BookmarksScreen(carContext) { renderer.load(it) })
            })
            .addAction(action("Scroll") { renderer.pageDown() })
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            // Pan mode makes the host deliver onScroll/onFling to the surface (design section 4).
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { /* no distinct pan UI; scroll handlers do the work */ }
            .build()
    }

    private fun action(title: String, onClick: () -> Unit) =
        Action.Builder().setTitle(title).setOnClickListener(onClick).build()

    private fun requestMediaAudioFocus() {
        // So the car routes WebView video/audio like music (design section 4). Minimal, no ducking logic.
        val am = carContext.getSystemService(AudioManager::class.java)
        audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            ).build().also { am.requestAudioFocus(it) }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(c: SurfaceContainer) = renderer.onSurfaceAvailable(c)
        override fun onSurfaceDestroyed(c: SurfaceContainer) = renderer.onSurfaceDestroyed()
        override fun onVisibleAreaChanged(area: Rect) = renderer.onVisibleAreaChanged(area)
        override fun onStableAreaChanged(area: Rect) = renderer.onVisibleAreaChanged(area)
        override fun onClick(x: Float, y: Float) = renderer.onClick(x, y)
        override fun onScroll(dx: Float, dy: Float) = renderer.onScroll(dx, dy)
        override fun onFling(vx: Float, vy: Float) = renderer.onFling(vx, vy)
        override fun onScale(fx: Float, fy: Float, factor: Float) = renderer.onScale(factor)
    }
}
