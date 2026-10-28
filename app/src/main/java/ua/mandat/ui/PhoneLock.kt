package ua.mandat.ui

import androidx.fragment.app.FragmentActivity

/** What the one phone-lock prompt on screen is for; kept in the Session, so it survives turning the phone. */
sealed interface PromptPurpose {
    /** Opening Mandat. */
    data object Unlock : PromptPurpose
    /** Confirming a sensitive step; [action] runs only after the phone's lock confirmed it. */
    class Confirm(val action: () -> Unit) : PromptPurpose
    /** Switching "PIN only": the passphrase is wrapped again with a new hardware key ([alias]). */
    class Rewrap(val alias: String, val pinOnly: Boolean) : PromptPurpose
}

/**
 * Asks the phone's own screen lock (fingerprint or PIN; PIN only when "PIN only" is on) to confirm
 * a step such as turning trip mode off; [done] runs only after it is confirmed. There is one prompt
 * for the whole app (the unlock prompt), so its answer always reaches the right place.
 */
fun confirmWithPhoneLock(activity: FragmentActivity, session: Session, title: String, done: () -> Unit) {
    // Nothing to confirm in the demo: it holds no real data and has no phone-lock key
    if (session.demo) { done(); return }
    (activity as? MainActivity)?.confirm(title, done)
}

/** The activity behind a Compose context (it may be wrapped, for example by a theme). */
tailrec fun android.content.Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
