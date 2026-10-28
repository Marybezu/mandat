package ua.mandat.screens

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
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
import ua.mandat.backup.BackupData
import ua.mandat.data.DemoData
import ua.mandat.data.MandatDb
import ua.mandat.ui.MandatTheme
import ua.mandat.ui.Session
import java.io.File
import java.util.concurrent.Executor

/**
 * The demo base, the largest data the app ships, drawn on every main screen: a screen that
 * crashes with a lot of data fails the build. Also times how long each screen takes to settle.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w400dp-h1400dp-xhdpi")
class DemoScreensTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var db: MandatDb
    private lateinit var session: Session

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(app, MandatDb::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        val gz = app.assets.open(DemoData.ASSET).use { it.readBytes() }
        BackupData.restore(db, DemoData.prepare(gz, ua.mandat.ui.Dates.today()))
        session = Session(app)
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    @After fun tearDown() = db.close()

    private fun shot(name: String, content: @Composable () -> Unit) = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        val started = System.nanoTime()
        scenario.onActivity { activity ->
            activity.setContent { MandatTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        }
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "demo-$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "demo-timings.txt").appendText("$name ${ms}ms\n")
        Unit
    }

    /** The whole app as it opens on the demo: tabs, banner, dialogs at app level. */
    @Test fun wholeApp() {
        session.moveTo(ua.mandat.ui.LockState.Open(db))
        shot("app") { ua.mandat.ui.App(session = session, onUnlock = {}, onStartOver = {}) }
    }
    @Test fun today() = shot("today") { ua.mandat.ui.Today(db, session, onOpen = {}, onToMatter = {}) }
    @Test fun matters() = shot("matters") { ua.mandat.ui.MattersList(db, onOpen = {}, session = session) }
    @Test fun people() = shot("people") { ua.mandat.ui.PeopleList(db, session) }
    @Test fun time() = shot("time") { ua.mandat.ui.TimeScreen(db, session) }
    @Test fun review() = shot("review") { ua.mandat.ui.ReviewScreen(db, session) }
    @Test fun calendar() = shot("calendar") { ua.mandat.ui.CalendarScreen(db, session) }
    @Test fun week() = shot("week") { ua.mandat.ui.CalendarScreenForTest(db, session, 1) }
    @Test fun graph() = shot("graph") { ua.mandat.ui.GraphScreen(db, session) }
    @Test fun retro() = shot("retro") { ua.mandat.ui.RetroScreen(db, session) }
    @Test fun diary() = shot("diary") { ua.mandat.ui.DiaryScreen(db, session) }
    @Test fun district() = shot("district") { ua.mandat.ui.DistrictScreen(db, session) }
    @Test fun budget() = shot("budget") { ua.mandat.ui.BudgetScreen(db, session) }
    @Test fun promises() = shot("promises") { ua.mandat.ui.PromisesScreen(db, session) }
    @Test fun topics() = shot("topics") { ua.mandat.ui.TopicsScreen(db, session) }
    @Test fun triage() = shot("triage") { ua.mandat.ui.TriageScreen(db, session) }
    @Test fun info() = shot("info") { ua.mandat.ui.InfoScreen(db, session) }
    @Test fun directory() = shot("directory") { ua.mandat.ui.DirectoryScreen(db, session) }
    @Test fun texts() = shot("texts") { ua.mandat.ui.TextsScreen(db, session) }
    @Test fun influence() = shot("influence") { ua.mandat.ui.InfluenceMapScreen(db, session, null) }
}
