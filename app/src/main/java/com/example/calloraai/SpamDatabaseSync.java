
package com.example.calloraai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SpamDatabaseSync {

    private static final String TAG = "CalloraAI";

    private static final String DATABASE_URL =
            "https://raw.githubusercontent.com/SysAdminDoc/CallShield/master/data/spam_numbers.json";

    private static final String PREFS = "spam_sync_prefs";
    private static final String LAST_SYNC = "last_sync";

    private static final long ONE_DAY =
            24L * 60 * 60 * 1000;

    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private SpamDatabaseSync() {
        // Utility class
    }

    public static void updateDatabase(Context context) {

        Log.i(TAG, "Spam database sync method started");

        Context appContext = context.getApplicationContext();

        executor.execute(() -> {

            Log.i(TAG, "Background sync started");

            SharedPreferences prefs =
                    appContext.getSharedPreferences(
                            PREFS, Context.MODE_PRIVATE);

            long lastSync = prefs.getLong(LAST_SYNC, 0);

            if (System.currentTimeMillis() - lastSync < ONE_DAY) {
                Log.i(TAG,
                        "Spam database is already up to date.");
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(DATABASE_URL);

                connection =
                        (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty(
                        "Accept", "application/json");

                int responseCode = connection.getResponseCode();

                Log.i(TAG,
                        "Database HTTP response: " + responseCode);

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception(
                            "Download failed. HTTP code: "
                                    + responseCode);
                }

                StringBuilder jsonText = new StringBuilder();

                try (InputStream input =
                             connection.getInputStream();

                     BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             input,
                                             StandardCharsets.UTF_8))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        jsonText.append(line);
                    }
                }

                if (jsonText.length() == 0) {
                    throw new Exception(
                            "Downloaded JSON is empty");
                }

                Log.i(TAG,
                        "JSON downloaded. Characters: "
                                + jsonText.length());

                JSONObject root =
                        new JSONObject(jsonText.toString());

                JSONArray numbers = root.optJSONArray("numbers");

                if (numbers == null) {
                    throw new Exception(
                            "JSON does not contain a 'numbers' array. "
                                    + "Check the source JSON format.");
                }

                List<String> spamNumbers = new ArrayList<>();

                for (int i = 0; i < numbers.length(); i++) {

                    JSONObject item = numbers.optJSONObject(i);

                    if (item == null) {
                        continue;
                    }

                    String number =
                            item.optString("number", "").trim();

                    // Accept international numbers with optional
                    // leading +, while ignoring spaces and dashes.
                    number = number.replaceAll("[\\s()\\-]", "");

                    if (number.matches("\\+?[0-9]+")) {
                        spamNumbers.add(number);
                    }
                }

                if (spamNumbers.isEmpty()) {
                    throw new Exception(
                            "No valid phone numbers found. "
                                    + "Check the JSON structure.");
                }

                Log.i(TAG,
                        "Valid numbers found: "
                                + spamNumbers.size());

                SpamDatabaseHelper database =
                        new SpamDatabaseHelper(appContext);

                try {
                    database.addNumbers(spamNumbers);
                } finally {
                    database.close();
                }

                prefs.edit()
                        .putLong(
                                LAST_SYNC,
                                System.currentTimeMillis())
                        .apply();

                Log.i(TAG,
                        "Imported spam numbers: "
                                + spamNumbers.size());

            } catch (Exception e) {

                Log.e(TAG,
                        "Spam database sync failed: "
                                + e.getMessage(), e);

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }

                Log.i(TAG, "Database sync attempt finished");
            }
        });
    }
}
