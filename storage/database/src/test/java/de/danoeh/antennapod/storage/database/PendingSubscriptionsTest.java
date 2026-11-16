package de.danoeh.antennapod.storage.database;

import android.content.Context;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManagerStub;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianGateTestUtil;
import de.danoeh.antennapod.storage.preferences.GuardianPendingFeeds;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PendingSubscriptionsTest {
    private static final String URL = "https://example.com/feed.xml";
    private static final String OTHER_URL = "https://example.org/other.xml";
    private static final String CODE = GuardianGateTestUtil.CODE;
    private Context context;
    private final List<String> removedFromSync = new ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub() {
            @Override
            public void enqueueFeedRemoved(String downloadUrl) {
                removedFromSync.add(downloadUrl);
            }
        });
        FeedUpdateManager.setInstance(new FeedUpdateManagerStub());
        GuardianGateTestUtil.configureClosed(context);
    }

    @After
    public void tearDown() {
        DBWriter.tearDownTests();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void closedGateHoldsFeed() {
        assertTrue(PendingSubscriptions.addOrHold(context, URL, "Title"));
        assertTrue(DBReader.getFeedList().isEmpty());
        assertEquals("Title", GuardianPendingFeeds.getAll().get(URL));
    }

    @Test
    public void openGateStillHoldsFeed() {
        GuardianGateTestUtil.open();
        assertTrue(PendingSubscriptions.addOrHold(context, URL, "Title"));
        assertTrue(DBReader.getFeedList().isEmpty());
    }

    @Test
    public void disabledGateSubscribesDirectly() {
        GuardianGate.setEnabled(CODE, false);
        assertFalse(PendingSubscriptions.addOrHold(context, URL, "Title"));
        assertEquals(1, DBReader.getFeedList().size());
        assertEquals(0, GuardianPendingFeeds.count());
    }

    @Test
    public void holdingSameFeedTwiceIsNotNew() {
        assertTrue(PendingSubscriptions.addOrHold(context, URL, "Title"));
        assertFalse(PendingSubscriptions.addOrHold(context, URL, "Title"));
        assertEquals(1, GuardianPendingFeeds.count());
    }

    @Test
    public void approveSubscribesOnlySelectedFeedsAndLocksAgain() {
        PendingSubscriptions.addOrHold(context, URL, "Title");
        PendingSubscriptions.addOrHold(context, OTHER_URL, null);

        assertEquals(GuardianGate.Result.ACCEPTED,
                PendingSubscriptions.approve(context, CODE, Collections.singletonList(URL)));

        List<Feed> feeds = DBReader.getFeedList();
        assertEquals(1, feeds.size());
        assertEquals(URL, feeds.get(0).getDownloadUrl());
        assertEquals(Feed.STATE_SUBSCRIBED, feeds.get(0).getState());
        assertEquals(Collections.singleton(OTHER_URL), GuardianPendingFeeds.getAll().keySet());
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void approveKeepsAnAlreadyOpenGateOpenWithoutExtendingIt() {
        long start = 1_000_000_000_000L;
        GuardianGateTestUtil.setNow(start);
        GuardianGateTestUtil.open();
        PendingSubscriptions.addOrHold(context, URL, "Title");
        GuardianGateTestUtil.setNow(start + TimeUnit.MINUTES.toMillis(GuardianGate.DEFAULT_WINDOW_MINUTES - 1));

        assertEquals(GuardianGate.Result.ACCEPTED,
                PendingSubscriptions.approve(context, CODE, Collections.singletonList(URL)));

        assertEquals(1, DBReader.getFeedList().size());
        assertTrue(GuardianGate.isOpen());
        GuardianGateTestUtil.setNow(start + TimeUnit.MINUTES.toMillis(GuardianGate.DEFAULT_WINDOW_MINUTES));
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void approvedFeedWithoutTitleIsNamedAfterItsAddress() {
        PendingSubscriptions.addOrHold(context, URL, null);
        PendingSubscriptions.approve(context, CODE, Collections.singletonList(URL));
        assertEquals(URL, DBReader.getFeedList().get(0).getTitle());
    }

    @Test
    public void rejectKeepsSubscribedPodcastsOnTheSyncAccount() {
        GuardianGateTestUtil.open();
        Feed feed = new Feed(URL, null, "Subscribed");
        feed.setItems(new ArrayList<>());
        FeedDatabaseWriter.updateFeed(context, feed, false);
        GuardianGate.lock();
        PendingSubscriptions.addOrHold(context, URL, "Subscribed");

        PendingSubscriptions.reject(CODE, Collections.singletonList(URL));

        assertEquals(0, GuardianPendingFeeds.count());
        assertTrue(removedFromSync.isEmpty());
    }

    @Test
    public void approveWithWrongCodeChangesNothing() {
        PendingSubscriptions.addOrHold(context, URL, "Title");
        assertEquals(GuardianGate.Result.WRONG_CODE,
                PendingSubscriptions.approve(context, "wrong-code", Collections.singletonList(URL)));
        assertTrue(DBReader.getFeedList().isEmpty());
        assertEquals(1, GuardianPendingFeeds.count());
    }

    @Test
    public void approveSubscribesExistingPreview() throws Exception {
        GuardianGateTestUtil.open();
        Feed preview = new Feed(URL, null, "Preview");
        preview.setItems(new ArrayList<>());
        preview.setState(Feed.STATE_NOT_SUBSCRIBED);
        preview = FeedDatabaseWriter.updateFeed(context, preview, false);
        GuardianGate.lock();
        PendingSubscriptions.addOrHold(context, URL, null);

        PendingSubscriptions.approve(context, CODE, Collections.singletonList(URL));

        assertEquals(Feed.STATE_SUBSCRIBED, DBReader.getFeed(preview.getId(), false, 0, 0).getState());
        assertEquals(1, DBReader.getFeedList().size());
    }

    @Test
    public void approveIgnoresFeedsNoLongerPending() {
        PendingSubscriptions.addOrHold(context, URL, "Title");
        GuardianPendingFeeds.remove(URL);
        PendingSubscriptions.approve(context, CODE, Collections.singletonList(URL));
        assertTrue(DBReader.getFeedList().isEmpty());
    }

    @Test
    public void rejectDiscardsSelectedFeedsAndRemovesThemFromSync() {
        PendingSubscriptions.addOrHold(context, URL, "Title");
        PendingSubscriptions.addOrHold(context, OTHER_URL, null);

        assertEquals(GuardianGate.Result.ACCEPTED,
                PendingSubscriptions.reject(CODE, Collections.singletonList(URL)));

        assertEquals(Collections.singleton(OTHER_URL), GuardianPendingFeeds.getAll().keySet());
        assertEquals(Collections.singletonList(URL), removedFromSync);
        assertTrue(DBReader.getFeedList().isEmpty());
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void rejectWithWrongCodeChangesNothing() {
        PendingSubscriptions.addOrHold(context, URL, "Title");
        assertEquals(GuardianGate.Result.WRONG_CODE,
                PendingSubscriptions.reject("wrong-code", Collections.singletonList(URL)));
        assertEquals(1, GuardianPendingFeeds.count());
        assertTrue(removedFromSync.isEmpty());
    }
}
