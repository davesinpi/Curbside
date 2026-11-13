package app.curbside

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template

/**
 * Page actions + bookmarks (design section 6). The nav action strip holds only 4 buttons,
 * so home/forward/reload/desktop live here instead.
 */
class MenuScreen(carContext: CarContext, private val renderer: SurfaceRenderer) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val desktop = isDesktop(carContext)
        val page = listOf(
            Triple("Home", R.drawable.ic_home, renderer::home),
            Triple("Reload", R.drawable.ic_reload, renderer::reload),
            Triple("Bookmark this page", R.drawable.ic_bookmark) {
                CarToast.makeText(carContext, renderer.bookmarkPage(), CarToast.LENGTH_SHORT).show()
            },
            Triple("Text size: ${textSize(carContext)}%", R.drawable.ic_text) { renderer.nextTextSize() },
            Triple(if (desktop) "Switch to mobile site" else "Switch to desktop site", R.drawable.ic_desktop, renderer::toggleDesktop),
        )
        // Hosts cap list rows (often lower while driving); bookmarks get whatever is left.
        val limit = if (carContext.carAppApiLevel >= 2)
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        else 6
        val rows = page.map { (title, icon, act) -> row(title, null, icon) { act() } }.toMutableList()
        // Most head units are single-touch, so pinch never arrives: zoom by tap instead.
        // One row: tap = zoom in, its trailing button = zoom out. Hosts allow only one row action,
        // and only from Car API 6; older hosts get a separate zoom-out row.
        val zoomIn = row("Zoom in (${pageZoom(carContext)}%)", null, R.drawable.ic_zoom_in) { renderer.zoom(1) }
        if (carContext.carAppApiLevel >= 6) rows.add(2, Row.Builder()
            .setTitle(zoomIn.title!!)
            .setImage(carIcon(R.drawable.ic_zoom_in))
            .setOnClickListener { renderer.zoom(1); screenManager.pop() }
            .addAction(zoomAction(R.drawable.ic_zoom_out, -1))
            .build())
        else rows.addAll(2, listOf(zoomIn, row("Zoom out", null, R.drawable.ic_zoom_out) { renderer.zoom(-1) }))
        val pageList = ItemList.Builder()
        rows.take(limit).forEach { pageList.addItem(it) }
        val marks = Bookmarks.list(carContext).take((limit - rows.size).coerceAtLeast(0))

        val template = ListTemplate.Builder()
            .setTitle(renderer.title?.take(60) ?: "Menu")
            .setHeaderAction(Action.BACK)
            .addSectionedList(SectionedItemList.create(pageList.build(), "Page"))
        if (marks.isNotEmpty()) {
            val markList = ItemList.Builder()
            marks.forEach { b -> markList.addItem(row(b.title, b.url, R.drawable.ic_bookmark) { renderer.load(b.url) }) }
            template.addSectionedList(SectionedItemList.create(markList.build(), "Bookmarks"))
        }
        return template.build()
    }

    private fun zoomAction(icon: Int, step: Int) = Action.Builder()
        .setIcon(carIcon(icon))
        .setOnClickListener { renderer.zoom(step); screenManager.pop() }
        .build()

    private fun row(title: String, text: String?, icon: Int, onClick: () -> Unit) = Row.Builder()
        .setTitle(title)
        .setImage(carIcon(icon))
        .apply { text?.let { addText(it) } }
        .setOnClickListener { onClick(); screenManager.pop() }
        .build()
}
