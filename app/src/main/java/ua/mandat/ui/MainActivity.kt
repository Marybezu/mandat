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

        // Only a fresh share: not after turning the phone or reopening from recent apps
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) takeShared(intent)
        vault = KeyVault(this)
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

    override fun onResume() {
        super.onResume()
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
        session.autoPrompt = false
        refreshState()
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
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_title))
            .setSubtitle(getString(R.string.unlock_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        session.prompting = true
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            session.prompting = false
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
            // Cancelled: stay locked quietly; the owner can tap "Unlock" again
            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_CANCELED
            if (!cancelled) session.lastError = true
        }
    }
}
