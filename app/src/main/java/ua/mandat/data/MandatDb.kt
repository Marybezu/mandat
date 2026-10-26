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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Database(
    entities = [
        InboxItem::class, Role::class, Matter::class, Setting::class,
        Person::class, MatterPerson::class, Delegation::class, Promise::class,
        Deadline::class, Review::class,
    ],
    version = DB_VERSION,
    exportSchema = true,
)
abstract class MandatDb : RoomDatabase() {
    abstract fun inbox(): InboxDao
    abstract fun roles(): RoleDao
    abstract fun matters(): MatterDao
    abstract fun settings(): SettingDao
    abstract fun people(): PersonDao
    abstract fun delegations(): DelegationDao
    abstract fun promises(): PromiseDao
    abstract fun deadlines(): DeadlineDao
    abstract fun reviews(): ReviewDao
    abstract fun search(): SearchDao

    companion object {
        const val FILE_NAME = "mandat.db"

        /** Opens the SQLCipher-encrypted database. Call off the main thread. */
        fun open(context: Context, passphrase: ByteArray): MandatDb {
            System.loadLibrary("sqlcipher")
            val db = Room.databaseBuilder(context.applicationContext, MandatDb::class.java, FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(*MIGRATIONS)
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

/**
 * Every change to the database layout gets a migration here, so data is never lost on update.
 * The SQL must match the schema Room exports to app/schemas exactly.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `roles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `position` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `matters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `type` TEXT NOT NULL, `roleId` INTEGER, `attention` TEXT NOT NULL, " +
                "`nextStep` TEXT NOT NULL, `goal` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                "PRIMARY KEY(`key`))",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `organization` TEXT NOT NULL, `jobTitle` TEXT NOT NULL, " +
                "`contact` TEXT NOT NULL, `notes` TEXT NOT NULL, `teamRole` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `matter_people` (`matterId` INTEGER NOT NULL, `personId` INTEGER NOT NULL, " +
                "`stance` TEXT, `note` TEXT NOT NULL, PRIMARY KEY(`matterId`, `personId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `delegations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`what` TEXT NOT NULL, `personId` INTEGER, `matterId` INTEGER, `due` INTEGER, " +
                "`status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `closedAt` INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `promises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`what` TEXT NOT NULL, `personId` INTEGER, `toWhom` TEXT NOT NULL, `isPublic` INTEGER NOT NULL, " +
                "`matterId` INTEGER, `due` INTEGER, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`closedAt` INTEGER)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `deadlines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `day` INTEGER NOT NULL, `kind` TEXT NOT NULL, `basis` TEXT NOT NULL, " +
                "`matterId` INTEGER, `remindBefore` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `closedAt` INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reviews` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`day` INTEGER NOT NULL, `progress` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
    }
}

val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

/** The current database layout version; raise it together with a new migration. */
const val DB_VERSION = 4
