package de.danoeh.antennapod;

import android.app.Activity;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianGateTestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GuardianGateLifecycleTrackerTest {
    private static final long START = 1_000_000_000_000L;
    private final GuardianGateLifecycleTracker tracker = new GuardianGateLifecycleTracker();
    private Activity first;
    private Activity second;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        GuardianGateTestUtil.configureClosed(context);
        GuardianGateTestUtil.setNow(START);
        GuardianGateTestUtil.open();
        first = Robolectric.buildActivity(Activity.class).get();
        second = Robolectric.buildActivity(Activity.class).get();
    }

    @After
    public void tearDown() {
        GuardianGateTestUtil.resetToUnconfigured(ApplicationProvider.getApplicationContext());
    }

    private void minutesLater(long minutes) {
        GuardianGateTestUtil.setNow(START + TimeUnit.MINUTES.toMillis(minutes));
    }

    @Test
    public void switchingBetweenActivitiesDoesNotCountAsBackground() {
        tracker.onActivityStarted(first);
        tracker.onActivityStarted(second);
        tracker.onActivityStopped(first);
        minutesLater(2);
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void leavingTheAppForLongerThanTheGraceLocks() {
        tracker.onActivityStarted(first);
        tracker.onActivityStopped(first);
        minutesLater(2);
        tracker.onActivityStarted(first);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void shortTripOutsideTheAppKeepsTheGateOpen() {
        tracker.onActivityStarted(first);
        tracker.onActivityStopped(first);
        GuardianGateTestUtil.setNow(START + TimeUnit.SECONDS.toMillis(30));
        tracker.onActivityStarted(first);
        minutesLater(5);
        assertTrue(GuardianGate.isOpen());
    }
}
