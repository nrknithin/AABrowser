package com.nrknithin.aayt.data

import android.content.Context
import android.content.res.Configuration
import com.nrknithin.aayt.model.AppThemeMode

object Preferences {
    private const val PREFS_NAME = "aayt_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_GLOBAL_SCALE_PERCENT = "global_scale_percent"

    const val MIN_GLOBAL_SCALE_PERCENT = 40
    const val MAX_GLOBAL_SCALE_PERCENT = 200
    const val DEFAULT_GLOBAL_SCALE_PERCENT = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(context: Context): AppThemeMode =
        AppThemeMode.fromKey(prefs(context).getString(KEY_THEME_MODE, null))

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.storageKey).apply()
    }

    fun getGlobalScalePercent(context: Context): Int =
        sanitizeGlobalScalePercent(
            prefs(context).getInt(KEY_GLOBAL_SCALE_PERCENT, DEFAULT_GLOBAL_SCALE_PERCENT)
        )

    fun sanitizeGlobalScalePercent(percent: Int): Int =
        percent.coerceIn(MIN_GLOBAL_SCALE_PERCENT, MAX_GLOBAL_SCALE_PERCENT)

    fun setGlobalScalePercent(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(KEY_GLOBAL_SCALE_PERCENT, sanitizeGlobalScalePercent(percent))
            .apply()
    }

    fun createScaledContext(base: Context): Context {
        val percent = getGlobalScalePercent(base)
        if (percent == DEFAULT_GLOBAL_SCALE_PERCENT) return base
        val configuration = Configuration(base.resources.configuration)
        configuration.densityDpi = (base.resources.configuration.densityDpi * (percent / 100f)).toInt()
        return base.createConfigurationContext(configuration)
    }
}
