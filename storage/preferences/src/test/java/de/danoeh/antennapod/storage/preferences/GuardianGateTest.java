package de.danoeh.antennapod.storage.preferences;

import android.content.Context;

import de.danoeh.antennapod.model.feed.Feed;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.TimeUnit;

import static de.danoeh.antennapod.storage.preferences.GuardianGateTestUtil.CODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GuardianGateTest {
    private static final String OTHER_CODE = "999999";
    private static final String WRONG_CODE = "000000";
    private Context context;
    private long now;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        GuardianGateTestUtil.resetToUnconfigured(context);
        now = 1_000_000_000_000L;
        GuardianGate.setClock(() -> now);
    }

    @After
    public void tearDown() {
        GuardianGate.setClock(System::currentTimeMillis);
    }

    private void advanceMinutes(long minutes) {
        now += TimeUnit.MINUTES.toMillis(minutes);
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.verify(WRONG_CODE));
        }
    }

    @Test
    public void notConfiguredIsClosed() {
        assertFalse(GuardianGate.isConfigured());
        assertFalse(GuardianGate.isOpen());
        assertEquals(GuardianGate.Result.NOT_CONFIGURED, GuardianGate.verify(CODE));
    }

    @Test
    public void correctCodeOpens() {
        GuardianGate.setInitialCode(CODE);
        assertTrue(GuardianGate.isConfigured());
        assertFalse(GuardianGate.isOpen());
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.verify(CODE));
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void wrongCodeStaysClosed() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.verify(WRONG_CODE));
        assertFalse(GuardianGate.isOpen());
        assertEquals(1, GuardianGate.getFailedAttempts());
    }

    @Test
    public void codeIsNotStoredInPlainText() {
        GuardianGate.setInitialCode(CODE);
        for (Object value : context.getSharedPreferences(GuardianGate.PREF_NAME, Context.MODE_PRIVATE)
                .getAll().values()) {
            assertFalse(String.valueOf(value).contains(CODE));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void shortCodeRejected() {
        GuardianGate.setInitialCode("12345");
    }

    @Test(expected = IllegalStateException.class)
    public void initialCodeCannotReplaceExistingCode() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.setInitialCode(OTHER_CODE);
    }

    @Test(expected = GuardianGateClosedException.class)
    public void requireOpenThrowsWhenClosed() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.requireOpen("test");
    }

    @Test
    public void windowExpires() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.verify(CODE);
        advanceMinutes(GuardianGate.DEFAULT_WINDOW_MINUTES - 1);
        assertTrue(GuardianGate.isOpen());
        advanceMinutes(1);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void openRemainingCountsDown() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(0, GuardianGate.getOpenRemainingMillis());
        GuardianGate.verify(CODE);
        advanceMinutes(4);
        assertEquals(TimeUnit.MINUTES.toMillis(GuardianGate.DEFAULT_WINDOW_MINUTES - 4),
                GuardianGate.getOpenRemainingMillis());
    }

    @Test
    public void customWindowAppliesFromNextOpen() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.setWindowMinutes(CODE, 30));
        GuardianGate.verify(CODE);
        advanceMinutes(29);
        assertTrue(GuardianGate.isOpen());
        advanceMinutes(1);
        assertFalse(GuardianGate.isOpen());
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroMinuteWindowRejected() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.setWindowMinutes(CODE, 0);
    }

    @Test
    public void clockSetBackwardsCloses() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.verify(CODE);
        now -= TimeUnit.MINUTES.toMillis(1);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void lockCloses() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.lock();
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void shortBackgroundKeepsOpenForRestOfWindow() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.onAppBackgrounded();
        now += GuardianGate.BACKGROUND_GRACE_MILLIS;
        GuardianGate.onAppForegrounded();
        advanceMinutes(2);
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void longBackgroundClosesEvenBeforeForegroundCallback() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.onAppBackgrounded();
        now += GuardianGate.BACKGROUND_GRACE_MILLIS + 1;
        assertFalse(GuardianGate.isOpen());
        GuardianGate.onAppForegrounded();
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void repeatedBackgroundCallbacksDoNotRestartGrace() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.onAppBackgrounded();
        now += GuardianGate.BACKGROUND_GRACE_MILLIS;
        GuardianGate.onAppBackgrounded();
        now += 1;
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void fiveWrongCodesLockOutForOneMinute() {
        GuardianGate.setInitialCode(CODE);
        failTimes(GuardianGate.ATTEMPTS_BEFORE_LOCKOUT - 1);
        assertEquals(0, GuardianGate.getLockoutRemainingMillis());
        failTimes(1);
        assertEquals(TimeUnit.MINUTES.toMillis(1), GuardianGate.getLockoutRemainingMillis());
        assertEquals(GuardianGate.Result.LOCKED_OUT, GuardianGate.verify(CODE));
        assertFalse(GuardianGate.isOpen());
        advanceMinutes(1);
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.verify(CODE));
    }

    @Test
    public void guessesDuringLockoutAreNotCounted() {
        GuardianGate.setInitialCode(CODE);
        failTimes(GuardianGate.ATTEMPTS_BEFORE_LOCKOUT);
        assertEquals(GuardianGate.Result.LOCKED_OUT, GuardianGate.verify(WRONG_CODE));
        assertEquals(GuardianGate.ATTEMPTS_BEFORE_LOCKOUT, GuardianGate.getFailedAttempts());
    }

    @Test
    public void lockoutDoubles() {
        GuardianGate.setInitialCode(CODE);
        failTimes(5);
        advanceMinutes(1);
        failTimes(5);
        assertEquals(TimeUnit.MINUTES.toMillis(2), GuardianGate.getLockoutRemainingMillis());
        advanceMinutes(2);
        failTimes(5);
        assertEquals(TimeUnit.MINUTES.toMillis(4), GuardianGate.getLockoutRemainingMillis());
    }

    @Test
    public void lockoutIsCappedAtOneDay() {
        GuardianGate.setInitialCode(CODE);
        for (int round = 0; round < 15; round++) {
            failTimes(5);
            advanceMinutes(TimeUnit.DAYS.toMinutes(1));
        }
        failTimes(5);
        assertEquals(TimeUnit.DAYS.toMillis(1), GuardianGate.getLockoutRemainingMillis());
    }

    @Test
    public void correctCodeResetsFailures() {
        GuardianGate.setInitialCode(CODE);
        failTimes(4);
        GuardianGate.verify(CODE);
        assertEquals(0, GuardianGate.getFailedAttempts());
        failTimes(4);
        assertEquals(0, GuardianGate.getLockoutRemainingMillis());
    }

    @Test
    public void changeCodeNeedsCurrentCode() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.changeCode(WRONG_CODE, OTHER_CODE));
        assertEquals(1, GuardianGate.getFailedAttempts());
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.changeCode(CODE, OTHER_CODE));
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.verify(CODE));
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.verify(OTHER_CODE));
    }

    @Test
    public void openWindowDoesNotAllowSettingsChangesWithoutCode() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.verify(CODE);
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.changeCode(WRONG_CODE, OTHER_CODE));
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.setEnabled(WRONG_CODE, false));
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.setWindowMinutes(WRONG_CODE, 60));
        assertTrue(GuardianGate.isEnabled());
        assertEquals(GuardianGate.DEFAULT_WINDOW_MINUTES, GuardianGate.getWindowMinutes());
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.verify(CODE));
    }

    @Test
    public void settingsChangesAreBlockedDuringLockout() {
        GuardianGate.setInitialCode(CODE);
        failTimes(5);
        assertEquals(GuardianGate.Result.LOCKED_OUT, GuardianGate.setEnabled(CODE, false));
        assertTrue(GuardianGate.isEnabled());
    }

    @Test
    public void disabledGateIsAlwaysOpen() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.setEnabled(CODE, false));
        advanceMinutes(TimeUnit.DAYS.toMinutes(30));
        assertFalse(GuardianGate.isEnabled());
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void reenabledGateIsClosedUntilVerified() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.setEnabled(CODE, false);
        GuardianGate.setEnabled(CODE, true);
        assertTrue(GuardianGate.isEnabled());
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void confirmChecksCodeWithoutOpening() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.confirm(CODE));
        assertFalse(GuardianGate.isOpen());
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.confirm(WRONG_CODE));
        assertEquals(1, GuardianGate.getFailedAttempts());
    }

    @Test
    public void previewIsLockedOnlyWhileGateIsClosed() {
        GuardianGate.setInitialCode(CODE);
        Feed preview = new Feed("https://example.com/feed.xml", null, "Preview");
        preview.setState(Feed.STATE_NOT_SUBSCRIBED);
        Feed subscribed = new Feed("https://example.com/other.xml", null, "Subscribed");

        assertTrue(GuardianGate.isLockedPreview(preview));
        assertFalse(GuardianGate.isLockedPreview(subscribed));
        assertFalse(GuardianGate.isLockedPreview((Feed) null));
        GuardianGate.verify(CODE);
        assertFalse(GuardianGate.isLockedPreview(preview));
    }
}
