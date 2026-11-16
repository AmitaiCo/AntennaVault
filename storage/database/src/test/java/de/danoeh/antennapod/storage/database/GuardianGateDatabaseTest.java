package de.danoeh.antennapod.storage.database;

import android.content.Context;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManagerStub;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianGateClosedException;
import de.danoeh.antennapod.storage.preferences.GuardianGateTestUtil;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GuardianGateDatabaseTest {
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
        FeedUpdateManager.setInstance(new FeedUpdateManagerStub());
        GuardianGateTestUtil.configureClosed(context);
    }

    @After
    public void tearDown() {
        DBWriter.tearDownTests();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void closedGateBlocksNewSubscription() {
        Feed feed = createFeed("https://example.com/new.xml", Feed.STATE_SUBSCRIBED);
        assertThrows(GuardianGateClosedException.class, () -> FeedDatabaseWriter.updateFeed(context, feed, false));
        assertTrue(DBReader.getFeedList().isEmpty());
    }

    @Test
    public void closedGateBlocksNewArchivedFeed() {
        Feed feed = createFeed("https://example.com/archived.xml", Feed.STATE_ARCHIVED);
        assertThrows(GuardianGateClosedException.class, () -> FeedDatabaseWriter.updateFeed(context, feed, false));
        assertTrue(DBReader.getFeedList().isEmpty());
    }

    @Test
    public void notConfiguredGateBlocksNewSubscription() {
        GuardianGateTestUtil.resetToUnconfigured(context);
        Feed feed = createFeed("https://example.com/new.xml", Feed.STATE_SUBSCRIBED);
        assertThrows(GuardianGateClosedException.class, () -> FeedDatabaseWriter.updateFeed(context, feed, false));
    }

    @Test
    public void openGateAllowsNewSubscription() {
        open();
        Feed saved = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/new.xml", Feed.STATE_SUBSCRIBED), false);
        assertEquals(Feed.STATE_SUBSCRIBED, DBReader.getFeed(saved.getId(), false, 0, 0).getState());
    }

    @Test
    public void closedGateAllowsPreview() {
        Feed saved = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/preview.xml", Feed.STATE_NOT_SUBSCRIBED), false);
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, DBReader.getFeed(saved.getId(), false, 0, 0).getState());
    }

    @Test
    public void closedGateAllowsRefreshOfExistingSubscription() {
        open();
        String url = "https://example.com/existing.xml";
        FeedDatabaseWriter.updateFeed(context, createFeed(url, Feed.STATE_SUBSCRIBED), false);
        GuardianGate.lock();

        Feed refreshed = createFeed(url, Feed.STATE_SUBSCRIBED);
        refreshed.getItems().add(createItem("new-episode", refreshed));
        Feed saved = FeedDatabaseWriter.updateFeed(context, refreshed, false);

        Feed fromDb = DBReader.getFeed(saved.getId(), false, 0, Integer.MAX_VALUE);
        assertEquals(Feed.STATE_SUBSCRIBED, fromDb.getState());
        assertEquals(1, fromDb.getItems().size());
    }

    @Test
    public void closedGateBlocksSubscribingToPreview() throws Exception {
        Feed preview = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/preview.xml", Feed.STATE_NOT_SUBSCRIBED), false);

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> DBWriter.setFeedState(context, preview, Feed.STATE_SUBSCRIBED).get());
        assertTrue(e.getCause() instanceof GuardianGateClosedException);
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, preview.getState());
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, DBReader.getFeed(preview.getId(), false, 0, 0).getState());
    }

    @Test
    public void openGateAllowsSubscribingToPreview() throws Exception {
        Feed preview = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/preview.xml", Feed.STATE_NOT_SUBSCRIBED), false);
        open();

        DBWriter.setFeedState(context, preview, Feed.STATE_SUBSCRIBED).get();
        assertEquals(Feed.STATE_SUBSCRIBED, DBReader.getFeed(preview.getId(), false, 0, 0).getState());
    }

    @Test
    public void closedGateAllowsRestoringArchivedSubscription() throws Exception {
        open();
        Feed feed = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/archived.xml", Feed.STATE_SUBSCRIBED), false);
        DBWriter.setFeedState(context, feed, Feed.STATE_ARCHIVED).get();
        GuardianGate.lock();

        DBWriter.setFeedState(context, feed, Feed.STATE_SUBSCRIBED).get();
        assertEquals(Feed.STATE_SUBSCRIBED, DBReader.getFeed(feed.getId(), false, 0, 0).getState());
    }

    @Test
    public void closedGateAllowsUnsubscribing() throws Exception {
        open();
        Feed feed = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/feed.xml", Feed.STATE_SUBSCRIBED), false);
        GuardianGate.lock();

        DBWriter.setFeedState(context, feed, Feed.STATE_NOT_SUBSCRIBED).get();
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, DBReader.getFeed(feed.getId(), false, 0, 0).getState());
    }

    @Test
    public void closedGateBlocksForeignFeedMergingIntoSubscriptionByIdentifier() {
        open();
        String subscribedUrl = "https://example.com/subscribed.xml";
        Feed subscribed = FeedDatabaseWriter.updateFeed(context,
                createFeed(subscribedUrl, Feed.STATE_SUBSCRIBED), false);
        GuardianGate.lock();

        Feed impostor = createFeed("https://attacker.example/feed.xml", Feed.STATE_NOT_SUBSCRIBED);
        impostor.setFeedIdentifier(subscribedUrl);
        impostor.getItems().add(createItem("injected", impostor));

        assertThrows(GuardianGateClosedException.class, () -> FeedDatabaseWriter.updateFeed(context, impostor, false));
        assertTrue(DBReader.getFeed(subscribed.getId(), false, 0, Integer.MAX_VALUE).getItems().isEmpty());
    }

    @Test
    public void closedGateAllowsPreviewOfAlreadySubscribedUrl() {
        open();
        String url = "https://example.com/subscribed.xml";
        Feed subscribed = FeedDatabaseWriter.updateFeed(context, createFeed(url, Feed.STATE_SUBSCRIBED), false);
        GuardianGate.lock();

        FeedDatabaseWriter.updateFeed(context, createFeed(url, Feed.STATE_NOT_SUBSCRIBED), false);
        assertEquals(Feed.STATE_SUBSCRIBED, DBReader.getFeed(subscribed.getId(), false, 0, 0).getState());
    }

    @Test
    public void closedGateBlocksArchivingPreview() {
        Feed preview = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/preview.xml", Feed.STATE_NOT_SUBSCRIBED), false);

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> DBWriter.setFeedState(context, preview, Feed.STATE_ARCHIVED).get());
        assertTrue(e.getCause() instanceof GuardianGateClosedException);
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, DBReader.getFeed(preview.getId(), false, 0, 0).getState());
    }

    @Test
    public void closedGateBlocksSubscribingPreviewEvenIfCallerThinksItIsArchived() {
        Feed preview = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/preview.xml", Feed.STATE_NOT_SUBSCRIBED), false);
        preview.setState(Feed.STATE_ARCHIVED);

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> DBWriter.setFeedState(context, preview, Feed.STATE_SUBSCRIBED).get());
        assertTrue(e.getCause() instanceof GuardianGateClosedException);
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, DBReader.getFeed(preview.getId(), false, 0, 0).getState());
    }

    @Test
    public void savingFeedDoesNotChangeItsState() throws Exception {
        Feed preview = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/preview.xml", Feed.STATE_NOT_SUBSCRIBED), false);
        preview.setState(Feed.STATE_SUBSCRIBED);

        DBWriter.setCompleteFeed(preview).get();
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, DBReader.getFeed(preview.getId(), false, 0, 0).getState());
    }

    @Test
    public void closedGateAllowsRefreshOfExistingSubscriptionById() {
        open();
        Feed saved = FeedDatabaseWriter.updateFeed(context,
                createFeed("https://example.com/existing.xml", Feed.STATE_SUBSCRIBED), false);
        GuardianGate.lock();

        Feed refreshed = createFeed("https://example.com/existing.xml", Feed.STATE_SUBSCRIBED);
        refreshed.setId(saved.getId());
        refreshed.getItems().add(createItem("new-episode", refreshed));
        FeedDatabaseWriter.updateFeed(context, refreshed, false);

        assertEquals(1, DBReader.getFeed(saved.getId(), false, 0, Integer.MAX_VALUE).getItems().size());
    }

    @Test
    public void openGateAllowsFeedWithSameIdentifierAtNewUrl() {
        open();
        String oldUrl = "https://example.com/old.xml";
        Feed subscribed = FeedDatabaseWriter.updateFeed(context, createFeed(oldUrl, Feed.STATE_SUBSCRIBED), false);

        Feed moved = createFeed("https://example.com/new.xml", Feed.STATE_NOT_SUBSCRIBED);
        moved.setFeedIdentifier(oldUrl);
        moved.getItems().add(createItem("episode", moved));
        FeedDatabaseWriter.updateFeed(context, moved, false);

        assertEquals(1, DBReader.getFeed(subscribed.getId(), false, 0, Integer.MAX_VALUE).getItems().size());
    }

    private static void open() {
        assertEquals(GuardianGate.Result.OPENED, GuardianGate.verify(GuardianGateTestUtil.CODE));
    }

    private static Feed createFeed(String url, int state) {
        Feed feed = new Feed(url, null, "Feed " + url);
        feed.setItems(new ArrayList<>());
        feed.setState(state);
        return feed;
    }

    private static FeedItem createItem(String id, Feed feed) {
        return new FeedItem(0, "Episode " + id, id, "https://example.com/" + id,
                new Date(), FeedItem.UNPLAYED, feed);
    }
}
