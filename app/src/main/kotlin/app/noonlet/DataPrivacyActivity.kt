package app.noonlet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.CompletableFuture

internal class DataPrivacyActivity : AppScreenActivity() {
    override val titleResource = R.string.data_privacy
    private var operation: ClearOperation? = null
    private var resumed = false

    override fun onCreate(state: Bundle?) {
        operation = lastNonConfigurationInstance as? ClearOperation
        super.onCreate(state)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        observeOperation()
    }

    override fun onPause() {
        resumed = false
        operation?.removeObserver(this)
        super.onPause()
    }

    override fun onRetainNonConfigurationInstance(): Any? = operation

    override fun buildContent(parent: LinearLayout) {
        val privacy = card(parent).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        privacy.addView(label(tr(R.string.privacy_weather), 14f, palette.muted))
        privacy.addView(label(tr(R.string.privacy_storage), 14f, palette.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val sources = SpannableStringBuilder()
        for ((name, url) in listOf("Open-Meteo" to "https://open-meteo.com",
            "GeoNames" to "https://www.geonames.org",
            "CC BY 4.0" to "https://creativecommons.org/licenses/by/4.0")) {
            if (sources.isNotEmpty()) sources.append(" · ")
            sources.append(name, URLSpan(url), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        privacy.addView(label("", 13f, palette.muted).apply {
            tag = "weather-data-sources"
            text = sources
            setLinkTextColor(palette.accent)
            movementMethod = LinkMovementMethod.getInstance()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val controls = card(parent)
        row(controls, tr(R.string.clear_forecast_cache), tagValue = "clear-weather-cache") {
            confirmClear(false)
        }
        divider(controls)
        row(controls, tr(R.string.forget_device_location), tagValue = "forget-device-location") {
            confirmClear(true)
        }
        divider(controls)
        row(controls, tr(R.string.app_permissions), tagValue = "app-permissions") {
            SettingsNavigation.open(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
        updateControls()
    }

    private fun confirmClear(deviceLocation: Boolean) {
        if (operation != null) return
        AppDialogBuilder(this)
            .setTitle(tr(if (deviceLocation) R.string.forget_device_location else R.string.clear_forecast_cache))
            .setMessage(tr(if (deviceLocation) R.string.forget_device_confirmation else R.string.clear_cache_confirmation))
            .setNegativeButton(tr(R.string.cancel), null)
            .setPositiveButton(tr(if (deviceLocation) R.string.forget else R.string.clear_data_action)) { _, _ -> clear(deviceLocation) }
            .show()
    }

    private fun clear(deviceLocation: Boolean) {
        if (operation != null) return
        val future =
            if (deviceLocation) WeatherRefreshScheduler.clearDeviceLocation(applicationContext)
            else WeatherRefreshScheduler.clearForecastCache(applicationContext)
        operation = ClearOperation(future)
        updateControls()
        observeOperation()
    }

    private fun observeOperation() {
        if (!resumed) return
        val current = operation ?: return
        current.observe(this) { failure ->
            if (operation !== current || isDestroyed || isFinishing) return@observe
            operation = null
            updateControls()
            Toast.makeText(this, tr(if (failure == null) R.string.data_cleared
                else R.string.data_clear_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateControls() {
        val clearing = operation != null
        for (tag in listOf("clear-weather-cache", "forget-device-location")) {
            body.findViewWithTag<View>(tag)?.apply {
                isEnabled = !clearing
                alpha = if (clearing) .5f else 1f
            }
        }
    }

    private class ClearOperation(val future: CompletableFuture<Void>) {
        private var owner: Any? = null
        private var observer: ((Throwable?) -> Unit)? = null
        private var complete = false
        private var consumed = false
        private var failure: Throwable? = null

        init {
            val main = Handler(Looper.getMainLooper())
            future.whenComplete { _, error ->
                main.post {
                    complete = true
                    failure = error
                    deliver()
                }
            }
        }

        fun observe(owner: Any, callback: (Throwable?) -> Unit) {
            this.owner = owner
            observer = callback
            deliver()
        }

        fun removeObserver(owner: Any) {
            if (this.owner !== owner) return
            this.owner = null
            observer = null
        }

        private fun deliver() {
            if (!complete || consumed) return
            val callback = observer ?: return
            consumed = true
            owner = null
            observer = null
            callback(failure)
        }
    }
}
