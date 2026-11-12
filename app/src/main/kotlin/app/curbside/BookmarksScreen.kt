package app.curbside

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/** Bookmarks edited on the phone, shown as a tappable list (design section 6). */
class BookmarksScreen(carContext: CarContext, private val onPick: (String) -> Unit) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        Bookmarks.list(carContext).forEach { b ->
            items.addItem(
                Row.Builder()
                    .setTitle(b.title)
                    .addText(b.url)
                    .setOnClickListener { onPick(b.url); screenManager.pop() }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Bookmarks")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
