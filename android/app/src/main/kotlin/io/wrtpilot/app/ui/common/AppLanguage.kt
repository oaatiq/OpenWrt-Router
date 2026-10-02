package io.wrtpilot.app.ui.common

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * In-app language (English default, French, Arabic). Uses AppCompat's
 * per-app locales: the system picker on Android 13+, stored by AppCompat
 * on older versions.
 */
object AppLanguage {
    /** "" = follow the system. */
    val options = listOf("", "en", "fr", "ar")

    /** Language names are shown in their own language. */
    fun nativeName(tag: String): String = when (tag) {
        "en" -> "English"
        "fr" -> "Français"
        "ar" -> "العربية"
        else -> tag
    }

    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ""
        return locales.get(0)?.language ?: ""
    }

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }
}
