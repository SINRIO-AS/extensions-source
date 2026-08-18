package eu.kanade.tachiyomi.extension.en.ehentai

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

private const val RATE_PROFILE_PREF = "request_rate_profile"
private const val RETRIES_PREF = "request_retries"
private const val RICH_DETAILS_PREF = "rich_details"

internal fun setupEhentaiPreferenceScreen(screen: PreferenceScreen) {
    ListPreference(screen.context).apply {
        key = RATE_PROFILE_PREF
        title = "Request profile"
        summary = "%s"
        entries = arrayOf("Polite", "Balanced", "Fast (may trigger limits)")
        entryValues = arrayOf("polite", "balanced", "fast")
        setDefaultValue("balanced")
    }.also(screen::addPreference)

    ListPreference(screen.context).apply {
        key = RETRIES_PREF
        title = "Retry failed requests"
        summary = "%s"
        entries = arrayOf("1 attempt", "2 attempts", "3 attempts", "5 attempts")
        entryValues = arrayOf("1", "2", "3", "5")
        setDefaultValue("3")
    }.also(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = RICH_DETAILS_PREF
        title = "Load extended gallery details"
        summary = "Includes the full tag list, uploader data, and gallery description."
        setDefaultValue(true)
    }.also(screen::addPreference)
}

internal val SharedPreferences.requestRateProfile: String
    get() = getString(RATE_PROFILE_PREF, "balanced") ?: "balanced"

internal val SharedPreferences.requestRetries: Int
    get() = getString(RETRIES_PREF, "3")?.toIntOrNull()?.coerceIn(1, 5) ?: 3

internal val SharedPreferences.richDetails: Boolean
    get() = getBoolean(RICH_DETAILS_PREF, true)

