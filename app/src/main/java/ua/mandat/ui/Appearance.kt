package ua.mandat.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Light / dark / automatic theme and Material You colours. Kept in plain app preferences, not
 * in the encrypted database, because the lock screen needs them before anything is unlocked.
 * These choices say nothing about the owner's data.
 */
object Appearance {
    private const val PREFS = "appearance"
    const val AUTO = 0
    const val LIGHT = 1
    const val DARK = 2

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context) = prefs(context).getInt("mode", AUTO)

    fun dynamic(context: Context) = prefs(context).getBoolean("dynamic", false)

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt("mode", mode).apply()
        apply(mode)
    }

    fun setDynamic(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("dynamic", on).apply()
    }

    /** Whether the small summary strip on a screen is open (only a layout choice). */
    fun stripOpen(context: Context, key: String) = prefs(context).getBoolean("strip_$key", true)

    fun setStripOpen(context: Context, key: String, open: Boolean) {
        prefs(context).edit().putBoolean("strip_$key", open).apply()
    }

    /** Sets the theme for the whole app, including the system bars. */
    fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }
}
