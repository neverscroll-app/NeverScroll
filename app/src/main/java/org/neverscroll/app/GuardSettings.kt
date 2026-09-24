package org.neverscroll.app

import android.content.Context

internal object GuardSettings {
    private const val FILE = "guard_settings"
    private const val MASTER = "master_enabled"
    private const val CONSENT = "accessibility_consent"

    fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context) = preferences(context).getBoolean(MASTER, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(MASTER, enabled).apply()
    }

    fun appEnabled(context: Context, app: ProtectedApp): Boolean =
        preferences(context).getBoolean(app.preferenceKey, true)

    fun setAppEnabled(context: Context, app: ProtectedApp, enabled: Boolean) {
        preferences(context).edit().putBoolean(app.preferenceKey, enabled).apply()
    }

    fun compatibilityEnabled(context: Context, app: ProtectedApp): Boolean =
        preferences(context).getBoolean("${app.preferenceKey}_compatibility", false)

    fun setCompatibilityEnabled(context: Context, app: ProtectedApp, enabled: Boolean) {
        preferences(context).edit()
            .putBoolean("${app.preferenceKey}_compatibility", enabled).apply()
    }

    fun consentGiven(context: Context) = preferences(context).getBoolean(CONSENT, false)

    fun setConsentGiven(context: Context) {
        preferences(context).edit().putBoolean(CONSENT, true).apply()
    }
}
