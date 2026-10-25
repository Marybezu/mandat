package ua.mandat.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Asks the keyboard not to learn from what is typed in Mandat
 * (no personalised suggestions built from your notes, no incognito leaks).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val privateRequest = PlatformTextInputMethodRequest { outAttributes: EditorInfo ->
                val connection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = outAttributes.imeOptions or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                connection
            }
            nextHandler.startInputMethod(privateRequest)
        },
        content = content,
    )
}

