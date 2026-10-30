
package com.example.calloraai;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class CallHistoryManager {

    private static final String PREFS = "callora_history";

    public static final String ACTION_HISTORY_UPDATED =
            "com.example.calloraai.CALL_HISTORY_UPDATED";

    public static synchronized void saveCall(
            Context context,
            String number,
            boolean spam) {

        saveCall(context, number, spam ? "SPAM" : "NOT FLAGGED");
    }

    // New method: save a specific call status.
    public static synchronized void saveCall(
            Context context,
            String number,
            String status) {

        Context appContext = context.getApplicationContext();

        SharedPreferences prefs = appContext.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        try {
            JSONArray history = new JSONArray(
                    prefs.getString("history", "[]"));

            JSONObject record = new JSONObject();
            record.put("number", number == null ? "Unknown" : number);
            record.put("status", normalizeStatus(status));
            record.put("time", System.currentTimeMillis());

            JSONArray updatedHistory = new JSONArray();
            updatedHistory.put(record);

            // Keep the latest 20 records.
            for (int i = 0; i < history.length() && i < 19; i++) {
                JSONObject oldRecord = history.optJSONObject(i);
                if (oldRecord != null) {
                    updatedHistory.put(oldRecord);
                }
            }

            int total = prefs.getInt("total_calls", 0);
            int spamCount = prefs.getInt("spam_calls", 0);

            boolean isSpam = "SPAM".equals(normalizeStatus(status));

            boolean saved = prefs.edit()
                    .putInt("total_calls", total + 1)
                    .putInt("spam_calls", spamCount + (isSpam ? 1 : 0))
                    .putString("history", updatedHistory.toString())
                    .commit();

            if (!saved) {
                Log.e("CalloraAI", "Could not save call history");
                return;
            }

            Intent updateIntent = new Intent(ACTION_HISTORY_UPDATED);
            updateIntent.setPackage(appContext.getPackageName());
            appContext.sendBroadcast(updateIntent);

            Log.d("CalloraAI",
                    "History saved: " + number + ", status="
                            + normalizeStatus(status));

        } catch (Exception e) {
            Log.e("CalloraAI", "Error saving call history", e);
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null) {
            return "UNKNOWN";
        }

        String value = status.trim().toUpperCase();

        if (value.equals("SPAM")) {
            return "SPAM";
        } else if (value.equals("UNKNOWN")) {
            return "UNKNOWN";
        } else if (value.equals("NOT FLAGGED")
                || value.equals("NOT FLAGGED - UNVERIFIED")
                || value.equals("NOT FLAGGED - NEEDS VERIFICATION")) {
            return "NOT FLAGGED";
        }

        return "UNKNOWN";
    }

    public static int getTotalCalls(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("total_calls", 0);
    }

    public static int getSpamCalls(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("spam_calls", 0);
    }

    public static JSONArray getHistory(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        try {
            return new JSONArray(prefs.getString("history", "[]"));
        } catch (Exception e) {
            Log.e("CalloraAI", "Error reading call history", e);
            return new JSONArray();
        }
    }

    public static synchronized void addSpamNumber(
            Context context, String number) {

        if (number == null) return;

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        Set<String> spamNumbers = new HashSet<>(
                prefs.getStringSet("reported_spam",
                        Collections.emptySet()));

        String normalized = number.replaceAll("[^+\\d]", "");

        if (!normalized.isEmpty()) {
            spamNumbers.add(normalized);

            prefs.edit()
                    .putStringSet("reported_spam", spamNumbers)
                    .apply();
        }
    }

    public static boolean isSpamNumber(
            Context context, String number) {

        if (number == null) return false;

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        Set<String> spamNumbers = prefs.getStringSet(
                "reported_spam", Collections.emptySet());

        String normalized = number.replaceAll("[^+\\d]", "");

        return spamNumbers.contains(normalized);
    }
}
