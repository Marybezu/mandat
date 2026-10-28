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
    val context = androidx.compose.ui.platform.LocalContext.current
    val baseClipboard = androidx.compose.ui.platform.LocalClipboard.current
    @Suppress("DEPRECATION") val baseManager = androidx.compose.ui.platform.LocalClipboardManager.current
    // Copying from any field is marked sensitive and taken off the clipboard again on lock, like the AI's copies
    val clipboard = androidx.compose.runtime.remember(baseClipboard) { MarkedClipboard(context.applicationContext, baseClipboard) }
    @Suppress("DEPRECATION") val manager = androidx.compose.runtime.remember(baseManager) { MarkedClipboardManager(context.applicationContext, baseManager) }
    @Suppress("DEPRECATION")
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalClipboard provides clipboard,
        androidx.compose.ui.platform.LocalClipboardManager provides manager,
    ) { TypingPrivately(content) }
}

/** Plain text of a clip, for copying it again with Mandat's mark. */
private fun clipText(context: android.content.Context, data: android.content.ClipData): String =
    (0 until data.itemCount).joinToString("\n") { data.getItemAt(it).coerceToText(context).toString() }

private class MarkedClipboard(private val context: android.content.Context, private val base: androidx.compose.ui.platform.Clipboard) : androidx.compose.ui.platform.Clipboard {
    override suspend fun getClipEntry() = base.getClipEntry()
    override suspend fun setClipEntry(clipEntry: androidx.compose.ui.platform.ClipEntry?) {
        val data = clipEntry?.clipData
        if (data == null) base.setClipEntry(null) else copyToClipboard(context, clipText(context, data))
    }
    override val nativeClipboard get() = base.nativeClipboard
}

@Suppress("DEPRECATION")
private class MarkedClipboardManager(private val context: android.content.Context, private val base: androidx.compose.ui.platform.ClipboardManager) : androidx.compose.ui.platform.ClipboardManager {
    override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) = copyToClipboard(context, annotatedString.text)
    override fun getText() = base.getText()
    override fun hasText() = base.hasText()
    override fun getClip() = base.getClip()
    override fun setClip(clipEntry: androidx.compose.ui.platform.ClipEntry?) {
        val data = clipEntry?.clipData
        if (data == null) base.setClip(null) else copyToClipboard(context, clipText(context, data))
    }
    override val nativeClipboard get() = base.nativeClipboard
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TypingPrivately(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val privateRequest = PlatformTextInputMethodRequest { outAttributes: EditorInfo ->
                val connection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = outAttributes.imeOptions or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                // Typing counts as use: the automatic lock never closes Mandat in the middle of writing
                object : android.view.inputmethod.InputConnectionWrapper(connection, false) {
                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { Idle.touch(); return super.commitText(text, newCursorPosition) }
                    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { Idle.touch(); return super.setComposingText(text, newCursorPosition) }
                    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { Idle.touch(); return super.deleteSurroundingText(beforeLength, afterLength) }
                    override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean { Idle.touch(); return super.sendKeyEvent(event) }
                }
            }
            nextHandler.startInputMethod(privateRequest)
        },
        content = content,
    )
}

