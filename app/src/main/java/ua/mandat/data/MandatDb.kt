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
        Deadline::class, Review::class, Event::class, EventPerson::class, Contact::class,
        InfoItem::class, KeyMessage::class, Alliance::class, MoneyEntry::class, AidRecord::class, ReviewLink::class,
        Community::class, CommunityLink::class, TimeEntry::class, MatterChange::class, TextDoc::class, TextVersion::class,
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
    abstract fun events(): EventDao
    abstract fun contacts(): ContactDao
    abstract fun time(): TimeDao
    abstract fun history(): HistoryDao
    abstract fun texts(): TextDao
    abstract fun info(): InfoDao
    abstract fun money(): MoneyDao
    abstract fun communities(): CommunityDao

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
        db.execSQL("ALTER TABLE `promises` ADD COLUMN `source` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `promises` ADD COLUMN `statusNote` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reviews` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`day` INTEGER NOT NULL, `progress` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `people` ADD COLUMN `category` TEXT")
        db.execSQL("ALTER TABLE `people` ADD COLUMN `categoryCustom` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `people` ADD COLUMN `teamCustom` TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Delegation done well: intent, what done means, freedom, check-in, returned, thanked
        db.execSQL("ALTER TABLE `delegations` ADD COLUMN `purpose` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `delegations` ADD COLUMN `doneMeans` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `delegations` ADD COLUMN `freedom` TEXT")
        db.execSQL("ALTER TABLE `delegations` ADD COLUMN `checkIn` INTEGER")
        db.execSQL("ALTER TABLE `delegations` ADD COLUMN `returned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `delegations` ADD COLUMN `thanked` INTEGER NOT NULL DEFAULT 0")
        // Influence map and team structure
        db.execSQL("ALTER TABLE `people` ADD COLUMN `influence` INTEGER")
        db.execSQL("ALTER TABLE `people` ADD COLUMN `stance` TEXT")
        db.execSQL("ALTER TABLE `people` ADD COLUMN `isGroup` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `people` ADD COLUMN `reportsToId` INTEGER")
        db.execSQL("ALTER TABLE `people` ADD COLUMN `lastContact` INTEGER")
        // Who leads, approves, is consulted, is informed
        db.execSQL("ALTER TABLE `matter_people` ADD COLUMN `raci` TEXT")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The owner's own calendar
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                "`day` INTEGER NOT NULL, `endDay` INTEGER, `startMin` INTEGER, `endMin` INTEGER, `kind` TEXT NOT NULL, " +
                "`place` TEXT NOT NULL, `matterId` INTEGER, `notes` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                "`remind` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_day` ON `events` (`day`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `event_people` (`eventId` INTEGER NOT NULL, `personId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`eventId`, `personId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_people_personId` ON `event_people` (`personId`)")
        // The address book
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contacts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`phones` TEXT NOT NULL, `emails` TEXT NOT NULL, `organization` TEXT NOT NULL, `jobTitle` TEXT NOT NULL, " +
                "`notes` TEXT NOT NULL, `label` TEXT NOT NULL, `sortKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_sortKey` ON `contacts` (`sortKey`)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Promises 2.0: how widely, where from, how risky. Old records keep their public/personal meaning.
        db.execSQL("ALTER TABLE `promises` ADD COLUMN `publicity` TEXT NOT NULL DEFAULT 'PERSONAL'")
        db.execSQL("ALTER TABLE `promises` ADD COLUMN `origin` TEXT")
        db.execSQL("ALTER TABLE `promises` ADD COLUMN `risk` INTEGER")
        db.execSQL("UPDATE `promises` SET `publicity` = 'PUBLIC' WHERE `isPublic` = 1")
        // Media and information, key messages, situational alliances
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `info_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `day` INTEGER NOT NULL, `source` TEXT NOT NULL, `personId` INTEGER, `matterId` INTEGER, " +
                "`reach` INTEGER, `stage` TEXT, `decision` TEXT, `notes` TEXT NOT NULL, `result` TEXT NOT NULL, `checks` TEXT NOT NULL, " +
                "`closed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_info_items_day` ON `info_items` (`day`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `key_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
                "`proof` TEXT NOT NULL, `audience` TEXT NOT NULL, `matterId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `alliances` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `withWhom` TEXT NOT NULL, " +
                "`personId` INTEGER, `about` TEXT NOT NULL, `matterId` INTEGER, `untilDay` INTEGER, `gives` TEXT NOT NULL, " +
                "`gets` TEXT NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The owner's own budget, with personal charity
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `money` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `category` TEXT NOT NULL, `amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                "`uah` INTEGER NOT NULL, `note` TEXT NOT NULL, `matterId` INTEGER, `charityKind` TEXT, `toWhom` TEXT NOT NULL, " +
                "`awaitingRefund` INTEGER NOT NULL, `refunded` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_day` ON `money` (`day`)")
        // Aid the owner organises, kept apart from the personal budget
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `aid_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `matterId` INTEGER NOT NULL, " +
                "`day` INTEGER NOT NULL, `collected` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                "`uah` INTEGER NOT NULL, `what` TEXT NOT NULL, `party` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_aid_records_matterId` ON `aid_records` (`matterId`)")
        // Diary: energy and mood of the day, and what the day was about
        db.execSQL("ALTER TABLE `reviews` ADD COLUMN `energy` INTEGER")
        db.execSQL("ALTER TABLE `reviews` ADD COLUMN `mood` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_links` (`reviewId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `refId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`reviewId`, `kind`, `refId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_links_refId` ON `review_links` (`refId`)")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The district: communities and what is linked to each
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `communities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `population` INTEGER, `head` TEXT NOT NULL, `notes` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `community_links` (`communityId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `refId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`communityId`, `kind`, `refId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_community_links_refId` ON `community_links` (`refId`)")
        // Trip mode: matters that are kept off the screen while it is on
        db.execSQL("ALTER TABLE `matters` ADD COLUMN `sensitive` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A person can belong to several categories
        db.execSQL("ALTER TABLE `people` ADD COLUMN `moreCategories` TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A vote counter for a matter, and who changed their stance when
        db.execSQL("ALTER TABLE `matters` ADD COLUMN `votesNeeded` INTEGER")
        db.execSQL("ALTER TABLE `matter_people` ADD COLUMN `stanceSince` INTEGER")
        db.execSQL("ALTER TABLE `matter_people` ADD COLUMN `previousStance` TEXT")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Time given to matters outside calendar events
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `time_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, " +
                "`minutes` INTEGER NOT NULL, `matterId` INTEGER, `roleId` INTEGER, `createdAt` INTEGER NOT NULL)",
        )
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A journal of attention, status and role changes, for looking back
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `matter_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `matterId` INTEGER NOT NULL, " +
                "`day` INTEGER NOT NULL, `at` INTEGER NOT NULL, `field` TEXT NOT NULL, `oldValue` TEXT, `newValue` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_matter_history_matterId` ON `matter_history` (`matterId`)")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Texts (speeches, columns, notes, drafts, reports, own templates) and their earlier versions
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `texts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, `matterId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `text_versions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `textId` INTEGER NOT NULL, " +
                "`body` TEXT NOT NULL, `savedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_text_versions_textId` ON `text_versions` (`textId`)")
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The owner's post in each body; for roles made before, guessed from the name ("Chair — …")
        db.execSQL("ALTER TABLE `roles` ADD COLUMN `post` TEXT")
        val found = ArrayList<Pair<Long, String>>()
        db.query("SELECT id, name FROM roles").use { c ->
            while (c.moveToNext()) RolePost.guess(c.getString(1))?.let { found.add(c.getLong(0) to it.name) }
        }
        found.forEach { (id, post) -> db.execSQL("UPDATE roles SET post = ? WHERE id = ?", arrayOf<Any>(post, id)) }
    }
}

val MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
)

/** The current database layout version; raise it together with a new migration. */
const val DB_VERSION = 16
