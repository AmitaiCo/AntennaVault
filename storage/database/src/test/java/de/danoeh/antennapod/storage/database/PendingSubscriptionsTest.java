package de.danoeh.antennapod.storage.database;

import android.content.Context;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.GuardianGateClosedException;
import de.danoeh.antennapod.storage.preferences.GuardianGateTestUtil;
import de.danoeh.antennapod.storage.preferences.GuardianPendingFeeds;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PendingSubscriptionsTest {
    private static final String URL = "https://example.com/feed.xml";
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
        GuardianGateTestUtil.configureClosed(context);
    }

    @After
    public void tearDown() {
        DBWriter.tearDownTests();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void closedGateHoldsFeedInsteadOfSubscribing() {
        assertFalse(PendingSubscriptions.subscribeOrHold(context, URL, "Title"));
        assertTrue(DBReader.getFeedList().isEmpty());
        assertEquals("Title", GuardianPendingFeeds.getAll().get(URL));
    }

    @Test
    public void openGateSubscribesDirectly() {
        GuardianGateTestUtil.open();
        assertTrue(PendingSubscriptions.subscribeOrHold(context, URL, "Title"));
        assertEquals(1, DBReader.getFeedList().size());
        assertEquals(0, GuardianPendingFeeds.count());
    }

    @Test
    public void approveAllSubscribesHeldFeeds() {
        PendingSubscriptions.subscribeOrHold(context, URL, "Title");
        PendingSubscriptions.subscribeOrHold(context, "https://example.org/other.xml", null);
        GuardianGateTestUtil.open();

        PendingSubscriptions.approveAll(context);

        List<Feed> feeds = DBReader.getFeedList();
        assertEquals(2, feeds.size());
        for (Feed feed : feeds) {
            assertEquals(Feed.STATE_SUBSCRIBED, feed.getState());
        }
        assertEquals(0, GuardianPendingFeeds.count());
    }

    @Test
    public void rejectAllDiscardsHeldFeeds() {
        PendingSubscriptions.subscribeOrHold(context, URL, "Title");
        GuardianGateTestUtil.open();

        PendingSubscriptions.rejectAll();

        assertEquals(0, GuardianPendingFeeds.count());
        assertTrue(DBReader.getFeedList().isEmpty());
    }

    @Test
    public void approvingNeedsOpenGate() {
        PendingSubscriptions.subscribeOrHold(context, URL, "Title");
        assertThrows(GuardianGateClosedException.class, () -> PendingSubscriptions.approveAll(context));
        assertEquals(1, GuardianPendingFeeds.count());
    }

    @Test
    public void rejectingNeedsOpenGate() {
        PendingSubscriptions.subscribeOrHold(context, URL, "Title");
        assertThrows(GuardianGateClosedException.class, PendingSubscriptions::rejectAll);
        assertEquals(1, GuardianPendingFeeds.count());
    }

    @Test
    public void heldFeedIsKeptOnlyOnce() {
        PendingSubscriptions.subscribeOrHold(context, URL, "Title");
        PendingSubscriptions.subscribeOrHold(context, URL, "Title");
        assertEquals(1, GuardianPendingFeeds.count());
    }
}
