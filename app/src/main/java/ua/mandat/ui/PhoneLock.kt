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

/**
 * A step to confirm with the phone's lock that runs on whatever screen is showing when the answer
 * comes (turning the phone during the prompt recreates the screen and its file pickers). Returns
 * the function that asks; [action] runs on the current screen after confirmation.
 */
@androidx.compose.runtime.Composable
fun rememberConfirmed(session: Session, key: String, title: String, action: () -> Unit): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = androidx.compose.runtime.rememberUpdatedState(action)
    androidx.compose.runtime.LaunchedEffect(session.confirmed) {
        if (session.confirmed == key) {
            session.confirmed = null
            try { current.value() } catch (e: Exception) { session.pickingFile = false }
        }
    }
    return { context.findActivity()?.let { a -> confirmWithPhoneLock(a, session, title) { session.confirmed = key } } }
}

/** The activity behind a Compose context (it may be wrapped, for example by a theme). */
tailrec fun android.content.Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
