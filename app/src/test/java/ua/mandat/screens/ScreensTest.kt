package ua.mandat.screens

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.mandat.data.MandatDb
import ua.mandat.ui.Dashboard
import ua.mandat.ui.MandatTheme
import ua.mandat.ui.MattersList
import ua.mandat.ui.PeopleList
import ua.mandat.ui.PromisesScreen
import ua.mandat.ui.ReviewScreen
import ua.mandat.ui.Session
import ua.mandat.ui.SmartList
import ua.mandat.ui.SmartListScreen
import ua.mandat.ui.TimeScreen
import ua.mandat.ui.Today
import java.io.File
import java.util.concurrent.Executor

/**
 * Draws every main screen with neutral test data, in light and dark, on the computer.
 * A screen that crashes fails the build; the pictures go to build/screens for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w400dp-h1400dp-xhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: MandatDb
    private lateinit var session: Session

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val direct = Executor { it.run() }
        // A plain in-memory database (no encryption needed for made-up data)
        db = Room.inMemoryDatabaseBuilder(app, MandatDb::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        runBlocking { NeutralData.fill(db) }
        session = Session(app)
    }

    @After fun tearDown() = db.close()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            MandatTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        // Let the database flows deliver and the screen settle
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun today() = shot("01-today") { Today(db, session, onOpen = {}, onToMatter = {}) }
    @Config(qualifiers = "+night")
    @Test fun todayDark() = shot("01-today-dark") { Today(db, session, onOpen = {}, onToMatter = {}) }
    @Test fun matters() = shot("02-matters") { MattersList(db, onOpen = {}) }
    @Test fun people() = shot("03-people") { PeopleList(db, session) }
    @Test fun time() = shot("04-time") { TimeScreen(db, session) }
    @Config(qualifiers = "+night")
    @Test fun timeDark() = shot("04-time-dark") { TimeScreen(db, session) }
    @Test fun review() = shot("05-review") { ReviewScreen(db, session) }
    @Test fun dashboard() = shot("05-dashboard") { Dashboard(db, session) }
    @Test fun promises() = shot("06-promises") { PromisesScreen(db, session) }
    @Test fun waiting() = shot("07-smart-waiting") { SmartListScreen(db, session, SmartList.WAITING) }
    @Test fun next() = shot("07-smart-next") { SmartListScreen(db, session, SmartList.NEXT) }
}
