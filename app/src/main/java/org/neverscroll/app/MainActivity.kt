package org.neverscroll.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val ink = Color.rgb(27, 43, 34)
    private val muted = Color.rgb(86, 101, 91)
    private val canvas = Color.rgb(246, 244, 238)
    private val white = Color.WHITE
    private val accent = Color.rgb(48, 87, 64)
    private lateinit var status: TextView
    private lateinit var accessButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = canvas
        window.navigationBarColor = canvas
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0

        val scroll = ScrollView(this).apply {
            setBackgroundColor(canvas)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        content.addView(label(getString(R.string.brand), 12f, accent, true).apply { letterSpacing = 0.18f })
        content.addView(label(getString(R.string.hero_title), 30f, ink, true).apply {
            setPadding(0, dp(12), 0, dp(10))
        })
        content.addView(label(
            getString(R.string.hero_description),
            16f, muted, false,
        ).apply { setPadding(0, 0, 0, dp(24)) })

        val setup = card()
        content.addView(setup)
        setup.addView(label(getString(R.string.protection_section), 20f, ink, true))
        status = label(getString(R.string.access_checking), 14f, muted, false).apply {
            setPadding(0, dp(8), 0, dp(14))
        }
        setup.addView(status)
        accessButton = Button(this).apply {
            text = getString(R.string.access_settings)
            isAllCaps = false
            setTextColor(white)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnClickListener { explainAndOpenSettings() }
        }
        setup.addView(accessButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        setup.addView(toggle(getString(R.string.protection_enabled), getString(R.string.protection_description),
            GuardSettings.enabled(this)) { GuardSettings.setEnabled(this, it) })

        content.addView(label(getString(R.string.feeds_section), 20f, ink, true).apply {
            setPadding(0, dp(28), 0, dp(12))
        })
        val apps = card()
        content.addView(apps)
        for (app in ProtectedApp.entries) {
            val detail = when (app) {
                ProtectedApp.YOUTUBE -> getString(R.string.shorts_screen)
                ProtectedApp.YOUTUBE_REVANCED -> getString(R.string.shorts_link_screen)
                ProtectedApp.INSTAGRAM -> getString(R.string.reels_screen)
                ProtectedApp.TIKTOK -> getString(R.string.for_you_feed)
            }
            apps.addView(toggle(app.label, detail, GuardSettings.appEnabled(this, app)) {
                GuardSettings.setAppEnabled(this, app, it)
            })
        }

        content.addView(label(getString(R.string.compatibility_section), 20f, ink, true).apply {
            setPadding(0, dp(28), 0, dp(12))
        })
        val compatibility = card()
        content.addView(compatibility)
        compatibility.addView(label(
            getString(R.string.compatibility_explanation),
            14f, muted, false,
        ).apply { setPadding(0, 0, 0, dp(12)) })
        for (app in ProtectedApp.entries) {
            compatibility.addView(toggle(app.label, getString(R.string.compatibility_toggle_description),
                GuardSettings.compatibilityEnabled(this, app)) {
                GuardSettings.setCompatibilityEnabled(this, app, it)
            })
        }

        content.addView(label(getString(R.string.how_section), 20f, ink, true).apply {
            setPadding(0, dp(28), 0, dp(12))
        })
        val explanation = card()
        content.addView(explanation)
        explanation.addView(label(
            getString(R.string.how_explanation),
            14f, ink, false,
        ))
        explanation.addView(label(
            getString(R.string.privacy_explanation),
            14f, muted, false,
        ).apply { setPadding(0, dp(12), 0, 0) })
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            val enabled = serviceEnabled()
            status.text = if (enabled)
                getString(R.string.access_enabled)
            else getString(R.string.access_required)
            status.setTextColor(if (enabled) accent else muted)
            accessButton.text = if (enabled) getString(R.string.access_settings)
            else getString(R.string.enable_in_settings)
        }
    }

    private fun serviceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == ScrollGuardService::class.java.name }
    }

    private fun explainAndOpenSettings() {
        if (GuardSettings.consentGiven(this)) {
            openSettings()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.disclosure_continue) { _, _ ->
                GuardSettings.setConsentGiven(this)
                openSettings()
            }
            .show()
    }

    private fun openSettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(white)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.rgb(229, 231, 223))
        }
    }

    private fun toggle(title: String, description: String, checked: Boolean,
                       onChange: (Boolean) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        val words = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(label(title, 16f, ink, true))
        words.addView(label(description, 13f, muted, false))
        addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(this@MainActivity).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
