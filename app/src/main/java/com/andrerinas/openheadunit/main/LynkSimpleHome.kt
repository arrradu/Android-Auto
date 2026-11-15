package com.andrerinas.openheadunit.main

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * The one screen the driver sees: what the connection is doing, and one button.
 *
 * It covers the original home instead of replacing it. The original home, with every setting,
 * is still underneath and still runs its own logic; a long press on the bottom line uncovers it
 * until the app is next opened.
 */
class LynkSimpleHome private constructor(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val root = LinearLayout(activity)
    private val status = TextView(activity)
    private val button = Button(activity)
    private var uncovered = false
    private var connected = false

    private val refresh = object : Runnable {
        override fun run() {
            // Stop with the activity, or this loop would keep a destroyed screen alive.
            if (activity.isFinishing) return
            if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed) return
            update()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private fun build() {
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.BLACK)
        // Taps must stop here, or they would press the original home's buttons underneath.
        root.isClickable = true
        root.isFocusable = true

        val title = TextView(activity)
        title.text = "Android Auto"
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        title.gravity = Gravity.CENTER
        root.addView(title, wrap())

        status.setTextColor(Color.LTGRAY)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(24), 0, dp(32))
        root.addView(status, wrap())

        button.isAllCaps = false
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        button.setPadding(dp(48), dp(20), dp(48), dp(20))
        button.setOnClickListener { onButton() }
        root.addView(button, wrap())

        val advanced = TextView(activity)
        advanced.text = "Ține apăsat aici pentru setările avansate"
        advanced.setTextColor(Color.DKGRAY)
        advanced.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        advanced.gravity = Gravity.CENTER
        advanced.setPadding(dp(24), dp(64), dp(24), dp(24))
        advanced.setOnLongClickListener {
            AppLog.i("LynkSimpleHome: uncovering the original home")
            uncovered = true
            update()
            true
        }
        root.addView(advanced, wrap())

        activity.addContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun update() {
        // The setup wizard and the original home own the screen until setup is finished.
        val setupDone = try {
            Settings(activity).onboardingVersion >= OnboardingActivity.CURRENT_ONBOARDING_VERSION
        } catch (e: Exception) {
            true
        }
        root.visibility = if (setupDone && !uncovered) View.VISIBLE else View.GONE
        if (root.visibility != View.VISIBLE) return

        val comm = App.provide(activity).commManager
        connected = comm.isConnected
        // Matched by name so this screen does not depend on the shape of each state.
        val state = comm.connectionState.value.javaClass.simpleName
        when {
            connected -> {
                status.text = "Conectat la telefon"
                button.text = "Deschide Android Auto"
            }
            state == "Disconnected" || state == "Error" -> {
                status.text = "Aștept telefonul. Bluetooth pornit pe telefon și gata."
                button.text = "Reconectează"
            }
            else -> {
                status.text = "Se conectează la telefon ..."
                button.text = "Reconectează"
            }
        }
    }

    private fun onButton() {
        try {
            if (connected) {
                activity.startActivity(AapProjectionActivity.intent(activity))
            } else {
                AppLog.i("LynkSimpleHome: reconnect pressed")
                ContextCompat.startForegroundService(
                    activity,
                    Intent(activity, AapService::class.java).apply {
                        action = AapService.ACTION_START_WIRELESS_SCAN
                    }
                )
                status.text = "Caut telefonul ..."
            }
        } catch (e: Exception) {
            AppLog.e("LynkSimpleHome: button action failed: ${e.message}")
        }
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REFRESH_MS = 1000L

        /** Call once, after the activity has set its own content view. */
        fun attach(activity: Activity) {
            val home = LynkSimpleHome(activity)
            home.build()
            home.refresh.run()
        }
    }
}
