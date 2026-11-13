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
        val pageList = ItemList.Builder()
        page.forEach { (title, icon, act) -> pageList.addItem(row(title, null, icon) { act() }) }

        // Hosts cap list rows (often lower while driving); bookmarks get whatever is left.
        val limit = if (carContext.carAppApiLevel >= 2)
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        else 6
        val marks = Bookmarks.list(carContext).take((limit - page.size).coerceAtLeast(0))

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

    private fun row(title: String, text: String?, icon: Int, onClick: () -> Unit) = Row.Builder()
        .setTitle(title)
        .setImage(carIcon(icon))
        .apply { text?.let { addText(it) } }
        .setOnClickListener { onClick(); screenManager.pop() }
        .build()
}
