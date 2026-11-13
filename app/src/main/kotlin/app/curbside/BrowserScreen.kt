package app.curbside

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** The car screen: a NavigationTemplate whose surface is driven by [SurfaceRenderer]. */
class BrowserScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = SurfaceRenderer(carContext)

    init {
        lifecycle.addObserver(this)
        renderer.onEditText = { current ->
            screenManager.push(AddressScreen(carContext, "Type here", current) { renderer.typeText(it) })
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
    }

    override fun onDestroy(owner: LifecycleOwner) = renderer.destroy()

    override fun onGetTemplate(): Template {
        // Icon-only buttons except Address: a smaller strip hides less of the page.
        val strip = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_back) { renderer.back() })
            .addAction(action(R.drawable.ic_search, "Address") {
                screenManager.push(AddressScreen(carContext) { renderer.load(it) })
            })
            .addAction(action(R.drawable.ic_more) { screenManager.push(MenuScreen(carContext, renderer)) })
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            // Pan mode makes the host deliver onScroll/onFling to the surface (design section 4).
            // The map strip sits by the page edge, where page-up/down buttons belong.
            .setMapActionStrip(ActionStrip.Builder()
                .addAction(Action.PAN)
                .addAction(action(R.drawable.ic_up) { renderer.pageUp() })
                .addAction(action(R.drawable.ic_down) { renderer.pageDown() })
                .build())
            .setPanModeListener { /* no distinct pan UI; scroll handlers do the work */ }
            .build()
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

/** Car action with a white icon and optional label; shared by the browser and menu screens. */
fun Screen.action(icon: Int, title: String? = null, onClick: () -> Unit): Action =
    Action.Builder().setIcon(carIcon(icon)).apply { title?.let { setTitle(it) } }.setOnClickListener(onClick).build()

fun Screen.carIcon(icon: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build()
