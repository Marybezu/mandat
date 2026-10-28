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

    /** Folded cards (by a fixed name, never by content), so a folded block stays folded. */
    fun isFolded(context: Context, key: String) = prefs(context).getStringSet("folded", emptySet())?.contains(key) == true

    fun setFolded(context: Context, key: String, folded: Boolean) {
        val now = prefs(context).getStringSet("folded", emptySet()).orEmpty().toMutableSet()
        if (folded) now += key else now -= key
        prefs(context).edit().putStringSet("folded", now).apply()
    }



    /**
     * "PIN only": the unlock prompt does not accept a fingerprint, only the phone's PIN or
     * password (a finger can be forced onto the sensor; a PIN has to be said). Kept here because
     * the prompt needs it before unlocking. Unreadable means off, which is the usual behaviour.
     */
    fun pinOnly(context: Context): Boolean =
        try {
            prefs(context).getBoolean("pin_only", false)
        } catch (e: Exception) {
            false
        }

    fun setPinOnly(context: Context, on: Boolean): Boolean = prefs(context).edit().putBoolean("pin_only", on).commit()

    private fun entry(context: Context, neutral: Boolean) =
        android.content.ComponentName(context.packageName, if (neutral) "ua.mandat.LauncherNeutral" else "ua.mandat.Launcher")

    /** The home-screen entry shows a neutral name and icon ("Notes") instead of "Mandat". */
    fun neutralIcon(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(entry(context, true)) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: Exception) {
        false
    }

    /**
     * Switches the home-screen entry. The new one is turned on before the old one is turned off,
     * so there is never a moment without any way to open Mandat. The launcher may take a few seconds.
     */
    fun setNeutralIcon(context: Context, neutral: Boolean) {
        val pm = context.packageManager
        val on = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val off = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val keep = android.content.pm.PackageManager.DONT_KILL_APP
        pm.setComponentEnabledSetting(entry(context, neutral), on, keep)
        pm.setComponentEnabledSetting(entry(context, !neutral), off, keep)
    }

    /** Minutes without a touch after which open Mandat locks itself (0: never). Kept here: needed before unlocking. */
    fun autoLockMinutes(context: Context): Int =
        try { prefs(context).getInt("autolock", DEFAULT_AUTOLOCK).takeIf { it in AUTOLOCK_CHOICES } ?: DEFAULT_AUTOLOCK } catch (e: Exception) { DEFAULT_AUTOLOCK }

    fun setAutoLockMinutes(context: Context, minutes: Int) { prefs(context).edit().putInt("autolock", minutes).apply() }

    const val DEFAULT_AUTOLOCK = 5
    val AUTOLOCK_CHOICES = listOf(0, 1, 2, 5, 10, 30)

    /**
     * Screenshots are forbidden unless the owner explicitly allows them (for example to show the
     * app to testers). Kept here because the lock screen must be protected before unlocking.
     */
    /**
     * Android 12 cannot hide the recent-apps preview reliably, so screenshots can be allowed
     * only from Android 13 on.
     */
    val canAllowScreenshots get() = android.os.Build.VERSION.SDK_INT >= 33

    fun screenshotsAllowed(context: Context): Boolean =
        try {
            canAllowScreenshots && prefs(context).getBoolean("screenshots", false)
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
