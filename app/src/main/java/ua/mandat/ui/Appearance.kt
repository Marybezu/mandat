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

    /**
     * Screenshots are forbidden unless the owner explicitly allows them (for example to show the
     * app to testers). Kept here because the lock screen must be protected before unlocking.
     */
    fun screenshotsAllowed(context: Context): Boolean =
        try {
            prefs(context).getBoolean("screenshots", false)
        } catch (e: Exception) {
            false // anything unreadable means "forbidden"
        }

    /**
     * Saves the choice and applies it at once. Returns false if it could not be saved; then
     * screenshots stay (or become) forbidden, never allowed by accident after a restart.
     */
    fun setScreenshotsAllowed(activity: android.app.Activity, allowed: Boolean): Boolean {
        val saved = prefs(activity).edit().putBoolean("screenshots", allowed).commit()
        if (!saved && allowed) prefs(activity).edit().putBoolean("screenshots", false).commit()
        applyScreenGuard(activity)
        return saved
    }

    /**
     * Android 12 takes the recent-apps preview while leaving, before the app locks, and has no
     * other way to hide it: so the screen is protected again whenever the app goes to the background.
     */
    fun guardWhileLeaving(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** No screenshots, no screen recording, no preview in recent apps, unless allowed. */
    fun applyScreenGuard(activity: android.app.Activity) {
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (screenshotsAllowed(activity)) activity.window.clearFlags(secure) else activity.window.addFlags(secure)
        // The preview in the recent apps list stays hidden either way (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
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
