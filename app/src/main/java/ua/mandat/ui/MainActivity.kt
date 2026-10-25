package ua.mandat.ui

import android.app.KeyguardManager
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.compose.setContent
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
    private var prompting = false
    // Show the unlock prompt by itself once after opening the app, not again after a cancel
    private var autoPrompt = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, no preview in the recent apps list
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)

        vault = KeyVault(this)
        if (session.state !is LockState.Open) session.setState(initialState())

        setContent {
            MandatTheme {
                App(session = session, onUnlock = ::unlock)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (session.state !is LockState.Open) session.setState(initialState())
        if (session.state == LockState.Locked && autoPrompt) {
            autoPrompt = false
            unlock()
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks it; turning the screen does not
        if (!isChangingConfigurations && session.lock()) autoPrompt = true
    }

    private fun initialState(): LockState {
        val keyguard = getSystemService(KeyguardManager::class.java)
        return when {
            !keyguard.isDeviceSecure -> LockState.NeedsScreenLock
            vault.isKeyMissing() -> LockState.KeyLost
            !vault.isSetUp() -> LockState.FirstRun
            session.state == LockState.Opening -> LockState.Opening
            else -> LockState.Locked
        }
    }

    private fun unlock() {
        if (prompting) return
        session.lastError = false
        val firstRun = !vault.isSetUp()
        val cipher: Cipher = try {
            if (firstRun) {
                MandatDb.deleteFiles(this)
                vault.cipherForSetup()
            } else {
                vault.cipherForUnlock()
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            session.setState(LockState.KeyLost)
            return
        } catch (e: Exception) {
            session.lastError = true
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    val confirmed = result.cryptoObject?.cipher ?: return
                    try {
                        val passphrase =
                            if (firstRun) vault.finishSetup(confirmed) else vault.finishUnlock(confirmed)
                        session.open(passphrase)
                    } catch (e: Exception) {
                        session.lastError = true
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    // Cancelled: stay locked quietly; the owner can tap "Unlock" again
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    if (!cancelled) session.lastError = true
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_title))
            .setSubtitle(getString(R.string.unlock_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        prompting = true
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
