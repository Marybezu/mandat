package ua.mandat.backup

import android.content.ContentValues
import android.database.Cursor
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import ua.mandat.data.MandatDb

/**
 * Turns all of Mandat's tables into one JSON document and back. Kept in memory only:
 * nothing unencrypted is ever written to the phone's storage.
 */
object BackupData {

    private const val FORMAT = 1

    // Internal bookkeeping tables that are never backed up or restored
    private val SKIP = setOf("room_master_table", "android_metadata", "sqlite_sequence")

    class Summary(val createdAt: Long, val schema: Int, val rows: Int)

    /** Something in the backup does not fit this version of Mandat. */
    class Incompatible : Exception()

    fun dump(db: MandatDb): ByteArray {
        val sql = db.openHelper.readableDatabase
        val tables = JSONObject()
        for (table in tableNames(sql)) {
            val columns = JSONArray()
            val rows = JSONArray()
            sql.query("SELECT * FROM `$table`").use { c ->
                for (i in 0 until c.columnCount) columns.put(c.getColumnName(i))
                while (c.moveToNext()) {
                    val row = JSONArray()
                    for (i in 0 until c.columnCount) row.put(cell(c, i))
                    rows.put(row)
                }
            }
            tables.put(table, JSONObject().put("columns", columns).put("rows", rows))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("app", "mandat")
            .put("schema", sql.version)
            .put("created", System.currentTimeMillis())
            .put("tables", tables)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    /** Reads what a backup contains without changing anything. */
    fun summary(json: ByteArray): Summary {
        val root = parse(json)
        val tables = root.getJSONObject("tables")
        var rows = 0
        tables.keys().forEach { rows += tables.getJSONObject(it).getJSONArray("rows").length() }
        return Summary(root.getLong("created"), root.getInt("schema"), rows)
    }

    /** Replaces all current data with the backup, in one transaction: all or nothing. */
    fun restore(db: MandatDb, json: ByteArray) {
        val root = parse(json)
        val sql = db.openHelper.writableDatabase
        if (root.getInt("schema") != sql.version) throw Incompatible()
        val current = tableNames(sql).toSet()
        val tables = root.getJSONObject("tables")
        tables.keys().forEach { if (it !in current) throw Incompatible() }

        sql.beginTransaction()
        try {
            for (table in current) sql.execSQL("DELETE FROM `$table`")
            tables.keys().forEach { table ->
                val t = tables.getJSONObject(table)
                val columns = t.getJSONArray("columns")
                val rows = t.getJSONArray("rows")
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    if (row.length() != columns.length()) throw Incompatible()
                    val values = ContentValues()
                    for (i in 0 until columns.length()) put(values, columns.getString(i), row.get(i))
                    sql.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
                }
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    private fun parse(json: ByteArray): JSONObject {
        val root = try {
            JSONObject(String(json, Charsets.UTF_8))
        } catch (e: Exception) {
            throw Incompatible()
        }
        if (root.optString("app") != "mandat" || root.optInt("format") != FORMAT) throw Incompatible()
        return root
    }

    private fun tableNames(sql: SupportSQLiteDatabase): List<String> {
        val names = mutableListOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (name !in SKIP && !name.startsWith("sqlite_")) names += name
            }
        }
        return names
    }

    // Each cell keeps its type: null, number, text, or binary (marked as {"b64": …})
    private fun cell(c: Cursor, i: Int): Any = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("b64", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
        else -> c.getString(i)
    }

    private fun put(values: ContentValues, column: String, value: Any) {
        when (value) {
            JSONObject.NULL -> values.putNull(column)
            is Int -> values.put(column, value.toLong())
            is Long -> values.put(column, value)
            is Double -> values.put(column, value)
            is String -> values.put(column, value)
            is JSONObject -> values.put(column, Base64.decode(value.getString("b64"), Base64.NO_WRAP))
            else -> throw Incompatible()
        }
    }
}
