package app.curbside

import android.content.Intent
import android.webkit.WebView
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Car entry point. Warms the WebView provider so first frame is fast (design section 5). */
class CurbsideCarAppService : CarAppService() {

    override fun onCreate() {
        super.onCreate()
        // Warm the system WebView provider without creating a WebView yet; the WebView itself
        // waits for a car surface. Cheap no-op if already started by SetupActivity.
        runCatching { WebView.getCurrentWebViewPackage() }
    }

    // Sideload-only, unofficial route (design section 8): accept any host. There is no Play
    // distribution to protect, and locking to Google's signatures would just break on OEM hosts.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CurbsideSession()
}

/** Session owns one BrowserScreen for its lifetime. */
class CurbsideSession : Session() {
    override fun onCreateScreen(intent: Intent) = BrowserScreen(carContext)
}
