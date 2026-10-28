package ua.mandat

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.mandat.backup.BackupData
import ua.mandat.data.DB_VERSION
import ua.mandat.data.MIGRATIONS
import ua.mandat.data.MandatDb
import java.io.File

/**
 * An old database, filled with made-up rows, is opened by today's Mandat: every migration runs,
 * Room checks the result against the current layout, and no row may be lost. A backup made by
 * an old version must restore into today's database. Runs on the computer (Robolectric), without
 * encryption: migrations are plain SQL and do not depend on it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun schema(version: Int): JSONObject =
        JSONObject(File("schemas/ua.mandat.data.MandatDb/$version.json").readText()).getJSONObject("database")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    /** A database file exactly as that version of Mandat left it, with two made-up rows in every table. */
    private fun oldDatabase(name: String, version: Int, onlyTables: Set<String>? = null, layoutOf: Int = version): Map<String, Int> {
        val file = app.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        val counts = HashMap<String, Int>()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val s = schema(layoutOf)
            s.getJSONArray("entities").objects().filter { onlyTables == null || it.getString("tableName") in onlyTables }.forEach { e ->
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.objects()?.forEach { i -> db.execSQL(i.getString("createSql").replace("\${TABLE_NAME}", table)) }
                val fields = e.getJSONArray("fields").objects()
                for (row in 1..2) {
                    val values = android.content.ContentValues()
                    fields.forEach { f ->
                        val col = f.getString("columnName")
                        when (f.getString("affinity")) {
                            "INTEGER" -> values.put(col, row.toLong())
                            "REAL" -> values.put(col, row + 0.5)
                            "BLOB" -> values.put(col, byteArrayOf(row.toByte()))
                            else -> values.put(col, "Тест $row")
                        }
                    }
                    db.insertOrThrow(table, null, values)
                }
                counts[table] = 2
            }
            if (onlyTables == null) s.getJSONArray("setupQueries").let { q -> (0 until q.length()).forEach { db.execSQL(q.getString(it)) } }
            db.version = version
        }
        return counts
    }

    private fun openToday(name: String): MandatDb =
        Room.databaseBuilder(app, MandatDb::class.java, name).addMigrations(*MIGRATIONS).allowMainThreadQueries().build()

    private fun count(db: MandatDb, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    private fun migratesWithoutLoss(version: Int, onlyTables: Set<String>? = null, layoutOf: Int = version) {
        val name = "old-$version.db"
        val before = oldDatabase(name, version, onlyTables, layoutOf)
        val db = openToday(name)
        try {
            // Opening runs the migrations and Room's own check of every table, column and index
            val sql = db.openHelper.writableDatabase
            assertEquals(DB_VERSION, sql.version)
            before.forEach { (table, n) -> assertEquals("rows of $table after migrating from $version", n, count(db, table)) }
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    // Version 1 had only the Inbox, laid out as in version 2
    @Test fun fromVersion1() = migratesWithoutLoss(1, onlyTables = setOf("inbox"), layoutOf = 2)

    @Test fun fromVersion2() = migratesWithoutLoss(2)

    @Test fun fromVersion3() = migratesWithoutLoss(3)

    @Test fun fromVersion5() = migratesWithoutLoss(5)

    @Test fun fromVersion7() = migratesWithoutLoss(7)

    @Test fun migrationKeepsMeaning() {
        val name = "meaning.db"
        oldDatabase(name, 7)
        // An old public promise stays public after the new "publicity" column arrives
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE promises SET isPublic = 1 WHERE id = 1")
            it.execSQL("UPDATE promises SET isPublic = 0 WHERE id = 2")
        }
        val db = openToday(name)
        try {
            val sql = db.openHelper.readableDatabase
            sql.query("SELECT publicity FROM promises ORDER BY id").use { c ->
                c.moveToNext(); assertEquals("PUBLIC", c.getString(0))
                c.moveToNext(); assertEquals("PERSONAL", c.getString(0))
            }
            // Matters made before trip mode are not sensitive
            sql.query("SELECT COUNT(*) FROM matters WHERE sensitive = 0").use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    /** A backup document as an old Mandat wrote it: its own tables and columns, its own schema number. */
    private fun oldBackup(version: Int): ByteArray {
        val name = "backup-src-$version.db"
        oldDatabase(name, version)
        val tables = JSONObject()
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            schema(version).getJSONArray("entities").objects().map { it.getString("tableName") }.forEach { t ->
                db.rawQuery("SELECT * FROM `$t`", null).use { c ->
                    val cols = JSONArray().apply { c.columnNames.forEach { put(it) } }
                    val rows = JSONArray()
                    while (c.moveToNext()) rows.put(JSONArray().apply {
                        for (i in 0 until c.columnCount) when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> put(JSONObject.NULL)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> put(c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> put(c.getDouble(i))
                            else -> put(c.getString(i))
                        }
                    })
                    tables.put(t, JSONObject().put("columns", cols).put("rows", rows))
                }
            }
        }
        app.deleteDatabase(name)
        return JSONObject().put("format", 1).put("app", "mandat").put("schema", version).put("created", 1L).put("tables", tables)
            .toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun oldBackupsRestore() {
        listOf(2, 5, 7).forEach { version ->
            val db = Room.inMemoryDatabaseBuilder(app, MandatDb::class.java).allowMainThreadQueries().build()
            try {
                BackupData.restore(db, oldBackup(version))
                schema(version).getJSONArray("entities").objects().map { it.getString("tableName") }.forEach { t ->
                    assertEquals("rows of $t from a version $version backup", 2, count(db, t))
                }
            } finally {
                db.close()
            }
        }
    }

    @Test fun todaysBackupRoundTrip() {
        val name = "roundtrip.db"
        oldDatabase(name, 7)
        val db = openToday(name)
        try {
            val dump = BackupData.dump(db)
            val fresh = Room.inMemoryDatabaseBuilder(app, MandatDb::class.java).allowMainThreadQueries().build()
            try {
                BackupData.restore(fresh, dump)
                listOf("matters", "people", "promises", "events", "contacts").forEach { t -> assertEquals(count(db, t), count(fresh, t)) }
                assertTrue(count(fresh, "matters") > 0)
            } finally {
                fresh.close()
            }
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun newerBackupIsRefused() {
        val db = Room.inMemoryDatabaseBuilder(app, MandatDb::class.java).allowMainThreadQueries().build()
        try {
            val json = JSONObject().put("format", 1).put("app", "mandat").put("schema", DB_VERSION + 1).put("created", 1L).put("tables", JSONObject())
            try {
                BackupData.restore(db, json.toString().toByteArray())
                fail("a backup from a newer Mandat must be refused")
            } catch (e: BackupData.Incompatible) {
                // expected
            }
        } finally {
            db.close()
        }
    }
}
