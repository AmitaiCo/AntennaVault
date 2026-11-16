package de.danoeh.antennapod.storage.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.util.Map;
import java.util.TreeMap;

public class GuardianPendingFeeds {
    @VisibleForTesting
    static final String PREF_NAME = "guardian_pending_feeds";

    private static SharedPreferences prefs;

    public static void init(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @WorkerThread
    @SuppressLint("ApplySharedPref")
    public static synchronized boolean add(@NonNull String url, @Nullable String title) {
        if (prefs().contains(url)) {
            return false;
        }
        prefs().edit().putString(url, title != null ? title : "").commit();
        return true;
    }

    @NonNull
    public static synchronized Map<String, String> getAll() {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return result;
    }

    public static synchronized int count() {
        return prefs().getAll().size();
    }

    @WorkerThread
    @SuppressLint("ApplySharedPref")
    public static synchronized void remove(@NonNull String url) {
        prefs().edit().remove(url).commit();
    }

    private static SharedPreferences prefs() {
        if (prefs == null) {
            throw new IllegalStateException("GuardianPendingFeeds.init() has not been called");
        }
        return prefs;
    }
}
