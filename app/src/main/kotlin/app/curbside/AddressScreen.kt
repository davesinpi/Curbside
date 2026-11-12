package app.curbside

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/** Address / search using the car's own keyboard and voice input (design section 4). */
class AddressScreen(carContext: CarContext, private val onSubmit: (String) -> Unit) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank()) onSubmit(searchText)
                screenManager.pop()
            }
        })
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint("Address or search")
            .build()
}
