package app.curbside

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/** Car keyboard + voice input (design section 4): the address bar, and text fields on the page. */
class AddressScreen(
    carContext: CarContext,
    private val hint: String = "Address or search",
    private val initial: String = "",
    private val onSubmit: (String) -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank()) onSubmit(searchText)
                screenManager.pop()
            }
        })
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint(hint)
            .setInitialSearchText(initial)
            .build()
}
