package de.danoeh.antennapod.storage.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianPendingFeeds;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public final class PendingSubscriptions {

    private PendingSubscriptions() {
    }

    @WorkerThread
    public static boolean addOrHold(@NonNull Context context, @NonNull String url, @Nullable String title) {
        if (GuardianGate.isTurnedOff()) {
            subscribe(context, url, title);
            return false;
        }
        return GuardianPendingFeeds.add(url, title);
    }

    @NonNull
    @WorkerThread
    public static GuardianGate.Result approve(@NonNull Context context, @NonNull String code,
                                              @NonNull Collection<String> urls) {
        boolean wasOpen = GuardianGate.isOpen();
        GuardianGate.Result result = GuardianGate.unlock(code);
        if (result != GuardianGate.Result.ACCEPTED) {
            return result;
        }
        try {
            Map<String, String> pending = GuardianPendingFeeds.getAll();
            for (String url : urls) {
                if (pending.containsKey(url)) {
                    subscribe(context, url, pending.get(url));
                    GuardianPendingFeeds.remove(url);
                }
            }
        } finally {
            if (!wasOpen) {
                GuardianGate.lock();
            }
        }
        return result;
    }

    @NonNull
    @WorkerThread
    public static GuardianGate.Result reject(@NonNull String code, @NonNull Collection<String> urls) {
        GuardianGate.Result result = GuardianGate.verify(code);
        if (result != GuardianGate.Result.ACCEPTED) {
            return result;
        }
        for (String url : urls) {
            GuardianPendingFeeds.remove(url);
            SynchronizationQueue.getInstance().enqueueFeedRemoved(url);
        }
        return result;
    }

    private static void subscribe(Context context, String url, @Nullable String title) {
        Feed existing = findByDownloadUrl(url);
        if (existing == null) {
            Feed feed = new Feed(url, null, title == null || title.isEmpty() ? url : title);
            feed.setItems(Collections.emptyList());
            FeedDatabaseWriter.updateFeed(context, feed, false);
        } else if (existing.getState() == Feed.STATE_NOT_SUBSCRIBED) {
            try {
                DBWriter.setFeedState(context, existing, Feed.STATE_SUBSCRIBED).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Nullable
    private static Feed findByDownloadUrl(String url) {
        for (Feed feed : DBReader.getFeedList()) {
            if (url.equals(feed.getDownloadUrl())) {
                return feed;
            }
        }
        return null;
    }
}
