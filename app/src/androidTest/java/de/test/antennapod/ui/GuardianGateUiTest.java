package de.test.antennapod.ui;

import android.content.Context;
import android.content.Intent;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.intent.rule.IntentsTestRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianGateTestUtil;
import de.danoeh.antennapod.ui.screen.AddFeedFragment;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;
import de.danoeh.antennapod.ui.screen.subscriptions.SubscriptionFragment;
import de.test.antennapod.EspressoTestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static de.test.antennapod.EspressoTestUtils.clickPreference;
import static de.test.antennapod.EspressoTestUtils.waitForViewGlobally;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class GuardianGateUiTest {
    private static final long TIMEOUT = 15000;

    private Context context;
    private UITestUtils uiTestUtils;

    @Rule
    public IntentsTestRule<MainActivity> mainActivityRule = new IntentsTestRule<>(MainActivity.class, false, false);

    @Rule
    public ActivityTestRule<PreferenceActivity> preferenceActivityRule =
            new ActivityTestRule<>(PreferenceActivity.class, false, false);

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
        uiTestUtils.tearDown();
        PodDBAdapter.deleteDatabase();
    }

    private void enterCode(String code) {
        onView(withId(R.id.textInput)).perform(replaceText(code));
        onView(withText(R.string.guardian_unlock)).perform(click());
    }

    @Test
    public void addButtonAsksForCodeWhileLocked() {
        EspressoTestUtils.setLaunchScreen(SubscriptionFragment.TAG);
        mainActivityRule.launchActivity(new Intent());

        onView(withId(R.id.subscriptions_add)).perform(click());
        waitForViewGlobally(withText(R.string.guardian_code_title), TIMEOUT);

        enterCode("not-the-code");
        waitForViewGlobally(withText(R.string.guardian_wrong_code), TIMEOUT);
        assertFalse(GuardianGate.isOpen());

        enterCode(GuardianGateTestUtil.CODE);
        waitForViewGlobally(withId(R.id.addViaUrlButton), TIMEOUT);
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void addFeedByUrlAfterEnteringCode() throws Exception {
        EspressoTestUtils.setLaunchScreen(AddFeedFragment.TAG);
        mainActivityRule.launchActivity(new Intent());

        waitForViewGlobally(withText(R.string.guardian_code_title), TIMEOUT);
        enterCode(GuardianGateTestUtil.CODE);

        uiTestUtils.addHostedFeedData();
        Feed feed = uiTestUtils.hostedFeeds.get(0);
        waitForViewGlobally(withId(R.id.addViaUrlButton), TIMEOUT);
        onView(withId(R.id.addViaUrlButton)).perform(scrollTo(), click());
        onView(withId(R.id.textInput)).perform(replaceText(feed.getDownloadUrl()));
        onView(withText(R.string.confirm_label)).perform(scrollTo(), click());

        Espresso.closeSoftKeyboard();
        waitForViewGlobally(withText(R.string.subscribe_label), TIMEOUT);
        onView(withText(R.string.subscribe_label)).perform(click());
        waitForViewGlobally(withId(R.id.butShowSettings), TIMEOUT);
    }

    @Test
    public void firstStartAsksGuardianToChooseACode() {
        GuardianGateTestUtil.resetToUnconfigured(context);
        EspressoTestUtils.setLaunchScreen(SubscriptionFragment.TAG);
        mainActivityRule.launchActivity(new Intent());

        waitForViewGlobally(withText(R.string.guardian_first_run_title), TIMEOUT);
        onView(withText(R.string.guardian_set_up)).perform(click());
        onView(withId(R.id.newCode)).perform(replaceText("123456"));
        onView(withId(R.id.confirmCode)).perform(replaceText("123456"));
        onView(withText(R.string.guardian_save_code)).perform(click());

        waitForViewGlobally(withId(R.id.subscriptions_add), TIMEOUT);
        assertTrue(GuardianGate.isConfigured());
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void guardianSettingsNeedTheCodeToTurnTheLockOff() {
        preferenceActivityRule.launchActivity(new Intent());
        clickPreference(R.string.guardian_pref_title);
        waitForViewGlobally(withText(R.string.guardian_status_locked), TIMEOUT);

        clickPreference(R.string.guardian_enabled_title);
        waitForViewGlobally(withText(R.string.guardian_turn_off_message), TIMEOUT);
        onView(withId(R.id.textInput)).perform(replaceText(GuardianGateTestUtil.CODE));
        onView(withText(R.string.confirm_label)).perform(click());

        waitForViewGlobally(withText(R.string.guardian_status_disabled), TIMEOUT);
        onView(withText(R.string.guardian_enabled_off)).check(matches(isDisplayed()));
        assertTrue(GuardianGate.isTurnedOff());
    }
}
