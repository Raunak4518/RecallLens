package org.photolibrary.app.feasibility;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.UUID;
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EncryptedCatalogTest {
    private static final String MARKER = "synthetic-catalog-record-719284";

    @Entity(tableName = "records")
    public static class RecordV1 {
        @PrimaryKey public long id;
        @NonNull public String value = "";
    }

    @Entity(tableName = "records")
    public static class RecordV2 {
        @PrimaryKey public long id;
        @NonNull public String value = "";
        @ColumnInfo(defaultValue = "0") public long revision;
    }

    @Database(entities = RecordV1.class, version = 1, exportSchema = false)
    public abstract static class CatalogV1 extends RoomDatabase {}

    @Database(entities = RecordV2.class, version = 2, exportSchema = false)
    public abstract static class CatalogV2 extends RoomDatabase {}

    private static final Migration ADD_REVISION = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE records ADD COLUMN revision INTEGER NOT NULL DEFAULT 0");
        }
    };

    @Test
    public void encryptedCatalogMigratesAndRejectsWrongKey() throws Exception {
        System.loadLibrary("sqlcipher");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "g0-catalog-" + UUID.randomUUID() + ".db";
        File databaseFile = context.getDatabasePath(name);
        byte[] key = randomKey();
        try {
            CatalogV1 catalog = Room.databaseBuilder(context, CatalogV1.class, name)
                    .openHelperFactory(new SupportOpenHelperFactory(key.clone()))
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .build();
            try {
                SupportSQLiteDatabase db = catalog.getOpenHelper().getWritableDatabase();
                db.execSQL("INSERT INTO records (id, value) VALUES (?, ?)", new Object[]{1, MARKER});
                File wal = new File(databaseFile.getPath() + "-wal");
                assertTrue("Expected an active WAL with frames", wal.isFile() && wal.length() > 32);
                assertAbsent(wal, MARKER);
            } finally {
                catalog.close();
            }
            assertAbsent(databaseFile, "SQLite format 3");
            assertAbsent(databaseFile, MARKER);
            assertThrows(SQLiteException.class, () -> {
                CatalogV2 wrongKey = open(context, name, randomKey());
                try {
                    wrongKey.getOpenHelper().getWritableDatabase();
                } finally {
                    wrongKey.close();
                }
            });
            CatalogV2 migrated = open(context, name, key.clone());
            try (var cursor = migrated.getOpenHelper().getWritableDatabase()
                         .query("SELECT id FROM records WHERE value = ? AND revision = 0", new Object[]{MARKER})) {
                assertTrue("The migration must preserve the row", cursor.moveToFirst() && cursor.getLong(0) == 1);
            } finally {
                migrated.close();
            }
            assertAbsent(databaseFile, MARKER);
        } finally {
            context.deleteDatabase(name);
        }
    }

    private static CatalogV2 open(Context context, String name, byte[] key) {
        return Room.databaseBuilder(context, CatalogV2.class, name)
                .openHelperFactory(new SupportOpenHelperFactory(key))
                .addMigrations(ADD_REVISION)
                .build();
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static void assertAbsent(File file, String value) throws Exception {
        String bytes = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("Unexpected plaintext in " + file.getName(), bytes.contains(value));
    }
}
