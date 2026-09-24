package org.neverscroll.app

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

internal object AppLanguage {
    private const val PREFERENCE = "ui_language"

    fun current(context: Context): String {
        val chosen = selected(context)
        if (chosen != null) return chosen
        return if (context.resources.configuration.locales[0].language == "ru") "ru" else "en"
    }

    fun set(context: Context, language: String) {
        require(language == "en" || language == "ru")
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language)
        } else {
            GuardSettings.preferences(context).edit().putString(PREFERENCE, language).apply()
        }
    }

    fun localizedContext(context: Context): Context {
        val language = selected(context) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        return context.createConfigurationContext(configuration)
    }

    fun isLanguagePreference(key: String?) = key == PREFERENCE

    private fun selected(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) null else locales[0].language
        }
        return GuardSettings.preferences(context).getString(PREFERENCE, null)
            ?.takeIf { it == "en" || it == "ru" }
    }
}
