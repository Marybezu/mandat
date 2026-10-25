package ua.mandat.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** A quick note put into the Inbox, to be sorted out later. */
@Entity(tableName = "inbox")
data class InboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<InboxItem>>

    @Insert
    suspend fun insert(item: InboxItem): Long

    @Query("DELETE FROM inbox WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [InboxItem::class], version = 1, exportSchema = true)
abstract class MandatDb : RoomDatabase() {
    abstract fun inbox(): InboxDao

    companion object {
        const val FILE_NAME = "mandat.db"

        /** Opens the SQLCipher-encrypted database. Call off the main thread. */
        fun open(context: Context, passphrase: ByteArray): MandatDb {
            System.loadLibrary("sqlcipher")
            val db = Room.databaseBuilder(context.applicationContext, MandatDb::class.java, FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
            // Open now, so a wrong key fails here and not later in the interface
            db.openHelper.writableDatabase
            return db
        }

        /** Deletes the database files; only after the owner explicitly confirmed a fresh start. */
        fun deleteFiles(context: Context) {
            context.deleteDatabase(FILE_NAME)
        }

        /** True if a database file exists on the phone. */
        fun exists(context: Context): Boolean = context.getDatabasePath(FILE_NAME).exists()
    }
}

/** Cleans a note before saving: trims it, refuses empty text, limits very long text. */
fun cleanInboxText(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return if (text.length > MAX_NOTE_LENGTH) text.take(MAX_NOTE_LENGTH) else text
}

const val MAX_NOTE_LENGTH = 20_000
