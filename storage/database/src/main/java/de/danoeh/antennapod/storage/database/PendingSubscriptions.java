package de.danoeh.antennapod.storage.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianPendingFeeds;

import java.util.Collections;
import java.util.Map;

public final class PendingSubscriptions {

    private PendingSubscriptions() {
    }

    @WorkerThread
    public static boolean subscribeOrHold(@NonNull Context context, @NonNull String url, @Nullable String title) {
        if (!GuardianGate.isOpen()) {
            GuardianPendingFeeds.add(url, title);
            return false;
        }
        subscribe(context, url, title);
        return true;
    }

    @WorkerThread
    public static void approveAll(@NonNull Context context) {
        GuardianGate.requireOpen("approve waiting podcasts");
        for (Map.Entry<String, String> entry : GuardianPendingFeeds.getAll().entrySet()) {
            subscribe(context, entry.getKey(), entry.getValue());
            GuardianPendingFeeds.remove(entry.getKey());
        }
    }

    @WorkerThread
    public static void rejectAll() {
        GuardianGate.requireOpen("reject waiting podcasts");
        for (String url : GuardianPendingFeeds.getAll().keySet()) {
            GuardianPendingFeeds.remove(url);
        }
    }

    private static void subscribe(Context context, String url, @Nullable String title) {
        Feed feed = new Feed(url, null, title == null || title.isEmpty() ? url : title);
        feed.setItems(Collections.emptyList());
        FeedDatabaseWriter.updateFeed(context, feed, false);
    }
}
