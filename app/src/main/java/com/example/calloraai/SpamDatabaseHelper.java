
package com.example.calloraai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

public class SpamDatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "CalloraAI";
    private static final String DB_NAME = "callora_spam.db";
    private static final int DB_VERSION = 1;

    // Test number stored in the local spam database.
    private static final List<String> TEST_SPAM_NUMBERS =
            Arrays.asList(
                    "+919510314247",
                    "+919023068709",
                    "+918320751310"
            );

    public SpamDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);

        SQLiteDatabase db = getWritableDatabase();
        insertNumbers(db, TEST_SPAM_NUMBERS);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE spam_numbers (" +
                        "number TEXT PRIMARY KEY)"
        );

        // Add the test number when the database is first created.
        insertNumbers(db, TEST_SPAM_NUMBERS);
    }

    @Override
    public void onUpgrade(
            SQLiteDatabase db,
            int oldVersion,
            int newVersion) {
        // Preserve existing data.
    }

    public synchronized boolean isSpam(String number) {
        String normalized = normalize(number);

        if (normalized.isEmpty()) {
            return false;
        }

        SQLiteDatabase db = getReadableDatabase();

        try (Cursor cursor = db.query(
                "spam_numbers",
                new String[]{"number"},
                "number = ?",
                new String[]{normalized},
                null,
                null,
                null)) {

            return cursor.moveToFirst();
        }
    }

    public synchronized void addNumbers(List<String> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return;
        }

        insertNumbers(getWritableDatabase(), numbers);
    }

    private void insertNumbers(
            SQLiteDatabase db,
            List<String> numbers) {

        db.beginTransaction();

        try {
            for (String number : numbers) {
                String normalized = normalize(number);

                if (!normalized.isEmpty()) {
                    ContentValues values = new ContentValues();
                    values.put("number", normalized);

                    db.insertWithOnConflict(
                            "spam_numbers",
                            null,
                            values,
                            SQLiteDatabase.CONFLICT_IGNORE
                    );
                }
            }

            db.setTransactionSuccessful();

            Log.d(TAG, "Spam numbers saved: " + numbers.size());

        } finally {
            db.endTransaction();
        }
    }

    private String normalize(String number) {
        if (number == null) {
            return "";
        }

        // Remove spaces, brackets and hyphens.
        return number.replaceAll("[\\s()\\-]", "").trim();
    }
}
