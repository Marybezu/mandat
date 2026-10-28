package ua.mandat.ui

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Asks the phone's own screen lock (fingerprint or PIN; PIN only when "PIN only" is on) to confirm
 * a step such as turning trip mode off; [done] runs only after it is confirmed. Leaving for the
 * PIN screen does not lock Mandat meanwhile; turning the screen off still does.
 */
fun confirmWithPhoneLock(activity: FragmentActivity, session: Session, title: String, done: () -> Unit) {
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setAllowedAuthenticators(if (Appearance.pinOnly(activity)) DEVICE_CREDENTIAL else BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .setConfirmationRequired(false)
        .build()
    session.pickingFile = true
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            session.pickingFile = false
            if (session.state is LockState.Open) done()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            session.pickingFile = false
            // Cancelled because the owner left Mandat (Home): it locks now, as leaving always does
            if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && session.lock()) session.autoPrompt = true
        }
    }).authenticate(info)
}

/** The activity behind a Compose context (it may be wrapped, for example by a theme). */
tailrec fun android.content.Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
