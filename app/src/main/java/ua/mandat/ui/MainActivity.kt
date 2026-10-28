package ua.mandat.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.security.KeyVault
import javax.crypto.Cipher

class MainActivity : AppCompatActivity() {

    private val session: Session by viewModels()
    private lateinit var vault: KeyVault
    private lateinit var prompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(Appearance.mode(this))
        // Draw behind the system bars; screens pad themselves and move above the keyboard
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, no preview in the recent apps list (unless allowed)
        Appearance.applyScreenGuard(this)
        // No autofill service (a password manager or the keyboard's) is shown Mandat's fields
        window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        protectWindow(window)

        // Only a fresh share: not after turning the phone or reopening from recent apps
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) takeShared(intent)
        vault = KeyVault(this)
        // A "PIN only" switch that stopped halfway may have left a spare key: it goes
        vault.cleanUpOldKeys()
        // Created here (not on tap) so the prompt keeps working after the screen turns
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        refreshState()

        setContent {
            MandatTheme(dynamic = session.dynamicColors) {
                App(session = session, onUnlock = ::unlock, onStartOver = ::startOver)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    /** Text from "Share" in another app; added to the Inbox only after the owner confirms. */
    private fun takeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val combined = listOf(subject, text).filter { it.isNotEmpty() }.distinct().joinToString("\n")
        if (combined.isNotEmpty()) session.sharedText = combined.take(ua.mandat.data.MAX_NOTE_LENGTH)
        setIntent(Intent(Intent.ACTION_MAIN))
    }

    /** Any touch or key: the idle timer for the automatic lock starts again. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        Idle.touch()
    }

    override fun onPause() {
        Appearance.guardWhileLeaving(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        Appearance.applyScreenGuard(this)
        session.screenshotsAllowed = Appearance.screenshotsAllowed(this)
        refreshState()
        if (session.state == LockState.Locked && session.autoPrompt) {
            session.autoPrompt = false
            unlock()
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks it; turning the screen does not
        if (!isChangingConfigurations && !session.pickingFile && session.lock()) session.autoPrompt = true
    }

    override fun onDestroy() {
        // Closed with the prompt still on screen (for example with Back): the prompt is gone,
        // so it must not stay "in progress". Not on rotation, where the prompt survives.
        // (Not cancelled in onStop: entering the phone PIN may briefly stop this screen.)
        if (isFinishing && session.prompting) {
            prompt.cancelAuthentication()
            session.prompting = false
        }
        super.onDestroy()
    }

    private fun refreshState() {
        val s = session.state
        if (s is LockState.Open || s == LockState.Opening) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        session.moveTo(
            when {
                !keyguard.isDeviceSecure -> LockState.NeedsScreenLock
                vault.isInvalidated() || vault.isKeyMissing() -> LockState.KeyLost
                // Data without a key: never start over silently, the owner must decide
                !vault.isSetUp() && MandatDb.exists(this) -> LockState.KeyLost
                !vault.isSetUp() -> LockState.FirstRun
                else -> LockState.Locked
            },
        )
    }

    /** The owner confirmed: forget the unreadable data and set up again (then restore a backup). */
    private fun startOver() {
        vault.reset()
        MandatDb.deleteFiles(this)
        // Nothing about the deleted data may stay behind, not even the days of reminders
        ua.mandat.notify.Reminders.clear(this)
        // ...nor access to a folder chosen for automatic backups
        contentResolver.persistedUriPermissions.forEach {
            try {
                contentResolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) {
                // Already gone
            }
        }
        session.autoPrompt = false
        refreshState()
    }

    /** The prompt allows the PIN alone when the key needs it (or the older setting asks for it). */
    private fun authenticators(): Int = if (vault.isPinOnly() || Appearance.pinOnly(this)) DEVICE_CREDENTIAL else BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /** True when the stored key itself accepts only the phone's PIN or password. */
    fun keyIsPinOnly(): Boolean = vault.isPinOnly()

    /** Confirms a sensitive step with the phone's lock; [action] runs only after it is confirmed. */
    fun confirm(title: String, action: () -> Unit) {
        if (session.prompting || session.state !is LockState.Open) return
        session.purpose = PromptPurpose.Confirm(action)
        session.prompting = true
        // Leaving for the PIN screen does not lock Mandat (screen off still does)
        session.pickingFile = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators())
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    /**
     * Switches "PIN only" on or off by wrapping the database passphrase again with a new hardware
     * key, after the phone's PIN or password (never a fingerprint) confirms it. Returns false when
     * it cannot start.
     */
    fun switchPinOnly(on: Boolean): Boolean {
        if (session.prompting || session.state !is LockState.Open || session.demo) return false
        val (cipher, alias) = try { vault.cipherForRewrap(on) } catch (e: Exception) { return false }
        session.purpose = PromptPurpose.Rewrap(alias, on)
        session.prompting = true
        session.pickingFile = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(if (on) R.string.pin_only_confirm_on else R.string.pin_only_confirm_off))
            .setAllowedAuthenticators(DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        return true
    }

    private fun unlock() {
        if (session.prompting) return
        session.lastError = false
        val firstRun = !vault.isSetUp()
        if (firstRun && MandatDb.exists(this)) {
            session.moveTo(LockState.KeyLost)
            return
        }
        val cipher: Cipher = try {
            if (firstRun) vault.cipherForSetup() else vault.cipherForUnlock()
        } catch (e: KeyPermanentlyInvalidatedException) {
            vault.markInvalidated()
            session.moveTo(LockState.KeyLost)
            return
        } catch (e: Exception) {
            session.lastError = true
            return
        }

        session.pendingSetup = firstRun
        session.promptGeneration = session.generationNow()
        session.purpose = PromptPurpose.Unlock
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_title))
            .setSubtitle(getString(R.string.unlock_subtitle))
            // "PIN only": the prompt offers no fingerprint, and the key itself refuses one
            .setAllowedAuthenticators(authenticators())
            .setConfirmationRequired(false)
            .build()
        session.prompting = true
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            session.prompting = false
            when (val p = session.purpose) {
                is PromptPurpose.Confirm -> {
                    session.purpose = PromptPurpose.Unlock
                    session.pickingFile = false
                    if (session.state is LockState.Open) p.action()
                    return
                }
                is PromptPurpose.Rewrap -> {
                    session.purpose = PromptPurpose.Unlock
                    session.pickingFile = false
                    val cipher = result.cryptoObject?.cipher
                    val raw = session.rawKeyForRewrap()
                    val ok = try {
                        if (cipher == null || raw == null) false
                        else { vault.finishRewrap(cipher, p.alias, p.pinOnly, raw); true }
                    } catch (e: Exception) {
                        false
                    } finally {
                        raw?.fill(0)
                    }
                    if (ok) Appearance.setPinOnly(this@MainActivity, p.pinOnly) else vault.abandonRewrap(p.alias)
                    session.pinOnlyResult = ok
                    return
                }
                PromptPurpose.Unlock -> {}
            }
            val confirmed = result.cryptoObject?.cipher ?: return
            try {
                val secret =
                    if (session.pendingSetup) vault.finishSetup(confirmed) else vault.finishUnlock(confirmed)
                session.open(secret, session.promptGeneration)
            } catch (e: Exception) {
                session.lastError = true
            }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            session.prompting = false
            val p = session.purpose
            if (p !is PromptPurpose.Unlock) {
                session.purpose = PromptPurpose.Unlock
                session.pickingFile = false
                if (p is PromptPurpose.Rewrap) vault.abandonRewrap(p.alias)
                // Cancelled because the owner left Mandat (Home): it locks now, as leaving always does
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && session.lock()) session.autoPrompt = true
                return
            }
            // Cancelled: stay locked quietly; the owner can tap "Unlock" again
            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_CANCELED
            if (!cancelled) session.lastError = true
        }
    }
}
