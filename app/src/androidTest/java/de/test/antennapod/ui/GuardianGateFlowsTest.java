package de.test.antennapod.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.activity.OpmlImportActivity;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianGateTestUtil;
import de.danoeh.antennapod.storage.preferences.GuardianPendingFeeds;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UsageStatistics;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.screen.onlinefeedview.OnlineFeedViewActivity;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;
import de.danoeh.antennapod.ui.screen.subscriptions.SubscriptionFragment;
import de.test.antennapod.EspressoTestUtils;

import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.TimeUnit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.pressImeActionButton;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition;
import static androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static de.test.antennapod.EspressoTestUtils.actionBarOverflow;
import static de.test.antennapod.EspressoTestUtils.clickChildViewWithId;
import static de.test.antennapod.EspressoTestUtils.clickPreference;
import static de.test.antennapod.EspressoTestUtils.waitForViewGlobally;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class GuardianGateFlowsTest {
    private static final long TIMEOUT = 15000;
    private static final String WRONG_CODE = "not-the-code";

    @Rule
    public Timeout timeout = Timeout.seconds(120);

    private Context context;
    private UITestUtils uiTestUtils;
    private ActivityScenario<?> scenario;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EspressoTestUtils.clearPreferences();
        EspressoTestUtils.clearDatabase();
        GuardianGateTestUtil.configureClosed(context);
        uiTestUtils = new UITestUtils(context);
        uiTestUtils.setup();
    }

    @After
    public void tearDown() throws Exception {
        if (scenario != null) {
            scenario.close();
        }
        EspressoTestUtils.tryKillPlaybackService();
        uiTestUtils.tearDown();
        PodDBAdapter.deleteDatabase();
        GuardianGateTestUtil.resetToUnconfigured(context);
    }

    private ActivityScenario<MainActivity> launchMain(Intent intent) {
        ActivityScenario<MainActivity> mainScenario = ActivityScenario.launch(intent);
        scenario = mainScenario;
        return mainScenario;
    }

    private Intent openFeed(long feedId) {
        return new Intent(context, MainActivity.class)
                .putExtras(new MainActivityStarter(context).withOpenFeed(feedId).getIntent());
    }

    private Intent subscriptionsScreen() {
        EspressoTestUtils.setLaunchScreen(SubscriptionFragment.TAG);
        return new Intent(context, MainActivity.class);
    }

    private void waitForCodePrompt() {
        waitForViewGlobally(withText(R.string.guardian_code_title), TIMEOUT);
    }

    private void typeCode(String code) {
        waitForViewGlobally(withId(R.id.textInput), TIMEOUT);
        onView(withId(R.id.textInput)).perform(replaceText(code), pressImeActionButton());
    }

    private Feed subscribedFeedWithPreview() throws Exception {
        uiTestUtils.addHostedFeedData();
        uiTestUtils.hostedFeeds.get(0).setLastRefreshAttempt(System.currentTimeMillis());
        uiTestUtils.addLocalFeedData(false);
        Feed preview = uiTestUtils.hostedFeeds.get(0);
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setFeedState(preview.getId(), Feed.STATE_NOT_SUBSCRIBED);
        adapter.close();
        return preview;
    }

    private void assertNoCodePrompt() throws InterruptedException {
        Thread.sleep(1000);
        onView(withText(R.string.guardian_code_title)).check(doesNotExist());
    }

    @Test
    public void podcastLinkFromAnotherAppAsksForCodeBeforeLoading() throws Exception {
        uiTestUtils.addHostedFeedData();
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(uiTestUtils.hostedFeeds.get(0).getDownloadUrl()));
        link.setClass(context, OnlineFeedViewActivity.class);
        scenario = ActivityScenario.launch(link);

        waitForCodePrompt();
        onView(withText(R.string.subscribe_label)).check(doesNotExist());
        typeCode(GuardianGateTestUtil.CODE);
        waitForViewGlobally(withText(R.string.subscribe_label), TIMEOUT);
    }

    @Test
    public void podcastLinkToSubscribedPodcastOpensWithoutCode() throws Exception {
        uiTestUtils.addLocalFeedData(false);
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(uiTestUtils.hostedFeeds.get(0).getDownloadUrl()));
        link.setClass(context, OnlineFeedViewActivity.class);
        scenario = ActivityScenario.launch(link);

        waitForViewGlobally(withId(R.id.butShowSettings), TIMEOUT);
        onView(withText(R.string.guardian_code_title)).check(doesNotExist());
    }

    @Test
    public void streamingPreviewEpisodeAsksForCode() throws Exception {
        Feed preview = subscribedFeedWithPreview();
        long episodeId = preview.getItems().get(1).getId();
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, DBReader.getFeed(preview.getId(), false, 0, 0).getState());
        assertTrue(GuardianGate.isLockedPreview(DBReader.getFeedItem(episodeId)));
        launchMain(new Intent(context, MainActivity.class)
                .putExtras(new MainActivityStarter(context).withOpenEpisode(episodeId).getIntent()));

        waitForViewGlobally(allOf(withId(R.id.butAction1Text), withText(not(isEmptyOrNullString()))), TIMEOUT);
        onView(allOf(withId(R.id.butAction1), isDisplayed())).perform(click());

        try {
            waitForCodePrompt();
        } catch (RuntimeException | AssertionError e) {
            throw new AssertionError("No code prompt. gateOpen=" + GuardianGate.isOpen()
                    + " lockedPreview=" + GuardianGate.isLockedPreview(DBReader.getFeedItem(episodeId))
                    + " playingMediaId=" + PlaybackPreferences.getCurrentlyPlayingFeedMediaId()
                    + " streamBias=" + UsageStatistics.hasSignificantBiasTo(UsageStatistics.ACTION_STREAM), e);
        }
        onView(withText(R.string.cancel_label)).perform(click());
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void streamingSubscribedEpisodeDoesNotAskForCode() throws Exception {
        uiTestUtils.addLocalFeedData(false);
        Feed subscribed = uiTestUtils.hostedFeeds.get(1);
        launchMain(openFeed(subscribed.getId()));

        waitForViewGlobally(allOf(withId(R.id.recyclerView), isDisplayed(), hasMinimumChildCount(1)), TIMEOUT);
        onView(allOf(withId(R.id.recyclerView), isDisplayed()))
                .perform(actionOnItemAtPosition(0, clickChildViewWithId(R.id.secondaryActionButton)));
        assertNoCodePrompt();
    }

    @Test
    public void fiveWrongCodesShowTheLockout() {
        launchMain(subscriptionsScreen());
        onView(withId(R.id.subscriptions_add)).perform(click());
        waitForCodePrompt();

        String lockoutPrefix = context.getString(R.string.guardian_locked_out, "#").split("#")[0];
        for (int i = 1; i <= 5; i++) {
            typeCode(WRONG_CODE);
            int expectedFailures = i;
            Awaitility.await().atMost(TIMEOUT, TimeUnit.MILLISECONDS)
                    .until(() -> GuardianGate.getFailedAttempts() == expectedFailures);
        }
        waitForViewGlobally(withText(startsWith(lockoutPrefix)), TIMEOUT);
        typeCode(GuardianGateTestUtil.CODE);
        waitForViewGlobally(withText(startsWith(lockoutPrefix)), TIMEOUT);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void opmlImportFromAnotherAppAsksForCode() throws Exception {
        uiTestUtils.addHostedFeedData();
        File opml = new File(context.getCacheDir(), "import.opml");
        try (FileWriter writer = new FileWriter(opml)) {
            writer.write("<?xml version=\"1.0\"?><opml version=\"2.0\"><head/><body>"
                    + "<outline text=\"Imported\" type=\"rss\" xmlUrl=\""
                    + uiTestUtils.hostedFeeds.get(0).getDownloadUrl() + "\"/></body></opml>");
        }
        Intent intent = new Intent(context, OpmlImportActivity.class);
        intent.setData(Uri.fromFile(opml));
        scenario = ActivityScenario.launch(intent);

        waitForViewGlobally(withText("Imported"), TIMEOUT);
        onView(withId(R.id.butConfirm)).perform(click());
        waitForCodePrompt();
        onView(withText(R.string.cancel_label)).perform(click());
        assertTrue(DBReader.getFeedList().isEmpty());
    }

    @Test
    public void databaseImportAsksForCode() {
        scenario = ActivityScenario.launch(PreferenceActivity.class);
        clickPreference(R.string.import_export_pref);
        clickPreference(R.string.database_import_label);
        waitForCodePrompt();
    }

    @Test
    public void editingFeedUrlAsksForCode() throws Exception {
        uiTestUtils.addLocalFeedData(false);
        Feed feed = uiTestUtils.hostedFeeds.get(0);
        launchMain(openFeed(feed.getId()));

        waitForViewGlobally(withId(R.id.butShowInfo), TIMEOUT);
        onView(withId(R.id.butShowInfo)).perform(click());
        waitForViewGlobally(withId(R.id.infoContainer), TIMEOUT);
        onView(actionBarOverflow()).perform(click());
        onView(withText(R.string.edit_url_menu)).perform(click());
        waitForCodePrompt();
    }

    @Test
    public void approvingWaitingPodcastSubscribesItAndLocksAgain() throws Exception {
        uiTestUtils.addHostedFeedData();
        String url = uiTestUtils.hostedFeeds.get(0).getDownloadUrl();
        GuardianPendingFeeds.add(url, "Waiting podcast");

        scenario = ActivityScenario.launch(PreferenceActivity.class);
        clickPreference(R.string.guardian_pref_title);
        clickPreference(R.string.guardian_pending_title);
        onView(withText(R.string.guardian_pending_approve)).perform(click());
        waitForCodePrompt();
        typeCode(GuardianGateTestUtil.CODE);

        Awaitility.await().atMost(TIMEOUT, TimeUnit.MILLISECONDS)
                .until(() -> DBReader.getFeedList().size() == 1 && GuardianPendingFeeds.count() == 0);
        assertEquals(Feed.STATE_SUBSCRIBED, DBReader.getFeedList().get(0).getState());
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void rejectingWaitingPodcastDiscardsIt() throws Exception {
        GuardianPendingFeeds.add("https://example.com/rejected.xml", "Rejected podcast");

        scenario = ActivityScenario.launch(PreferenceActivity.class);
        clickPreference(R.string.guardian_pref_title);
        clickPreference(R.string.guardian_pending_title);
        onView(withText(R.string.guardian_pending_reject)).perform(click());
        waitForCodePrompt();
        typeCode(GuardianGateTestUtil.CODE);

        Awaitility.await().atMost(TIMEOUT, TimeUnit.MILLISECONDS)
                .until(() -> GuardianPendingFeeds.count() == 0);
        assertTrue(DBReader.getFeedList().isEmpty());
    }

    @Test
    public void lockNowClosesAnOpenGate() {
        GuardianGateTestUtil.open();
        scenario = ActivityScenario.launch(PreferenceActivity.class);
        clickPreference(R.string.guardian_pref_title);
        clickPreference(R.string.guardian_lock_now_title);
        waitForViewGlobally(withText(R.string.guardian_status_locked), TIMEOUT);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void leavingTheAppForMoreThanAMinuteLocks() {
        long start = System.currentTimeMillis();
        GuardianGateTestUtil.setNow(start);
        GuardianGateTestUtil.open();
        launchMain(subscriptionsScreen());
        waitForViewGlobally(withId(R.id.subscriptions_add), TIMEOUT);

        scenario.moveToState(Lifecycle.State.CREATED);
        GuardianGateTestUtil.setNow(start + TimeUnit.MINUTES.toMillis(2));
        scenario.moveToState(Lifecycle.State.RESUMED);

        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void shortTripOutsideTheAppKeepsGateOpen() {
        long start = System.currentTimeMillis();
        GuardianGateTestUtil.setNow(start);
        GuardianGateTestUtil.open();
        launchMain(subscriptionsScreen());
        waitForViewGlobally(withId(R.id.subscriptions_add), TIMEOUT);

        scenario.moveToState(Lifecycle.State.CREATED);
        GuardianGateTestUtil.setNow(start + TimeUnit.SECONDS.toMillis(30));
        scenario.moveToState(Lifecycle.State.RESUMED);

        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void rotatingWithThePromptOpenKeepsThePromptWorking() {
        ActivityScenario<MainActivity> mainScenario = launchMain(subscriptionsScreen());
        onView(withId(R.id.subscriptions_add)).perform(click());
        waitForCodePrompt();

        mainScenario.onActivity(activity ->
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        waitForCodePrompt();
        assertFalse(GuardianGate.isOpen());
        mainScenario.onActivity(activity ->
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        waitForCodePrompt();

        typeCode(GuardianGateTestUtil.CODE);
        waitForViewGlobally(withId(R.id.addViaUrlButton), TIMEOUT);
        assertTrue(GuardianGate.isOpen());
    }
}
