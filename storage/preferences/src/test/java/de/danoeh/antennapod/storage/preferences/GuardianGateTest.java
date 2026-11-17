package de.danoeh.antennapod.storage.preferences;

import android.content.Context;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;

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
    private static final long BOOT_TIME = 1_000_000_000_000L;
    private Context context;
    private long elapsed;
    private long wall;
    private int bootCount;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        GuardianGateTestUtil.resetToUnconfigured(context);
        elapsed = TimeUnit.HOURS.toMillis(1);
        wall = BOOT_TIME + elapsed;
        bootCount = 3;
        GuardianGate.setClock(new GuardianGate.Clock() {
            @Override
            public long elapsedRealtime() {
                return elapsed;
            }

            @Override
            public long currentTimeMillis() {
                return wall;
            }

            @Override
            public int bootCount() {
                return bootCount;
            }
        });
    }

    @After
    public void tearDown() {
        GuardianGate.useSystemClock();
    }

    private void advanceMinutes(long minutes) {
        elapsed += TimeUnit.MINUTES.toMillis(minutes);
        wall += TimeUnit.MINUTES.toMillis(minutes);
    }

    private void setWallClockMinutes(long minutes) {
        wall += TimeUnit.MINUTES.toMillis(minutes);
    }

    private void reboot() {
        elapsed = TimeUnit.SECONDS.toMillis(30);
        bootCount++;
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.unlock(WRONG_CODE));
        }
    }

    @Test
    public void notConfiguredIsClosed() {
        assertFalse(GuardianGate.isConfigured());
        assertFalse(GuardianGate.isTurnedOff());
        assertFalse(GuardianGate.isOpen());
        assertEquals(GuardianGate.Result.NOT_CONFIGURED, GuardianGate.unlock(CODE));
    }

    @Test
    public void correctCodeOpens() {
        GuardianGate.setInitialCode(CODE);
        assertTrue(GuardianGate.isConfigured());
        assertFalse(GuardianGate.isOpen());
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.unlock(CODE));
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void wrongCodeStaysClosed() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.unlock(WRONG_CODE));
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
        GuardianGate.unlock(CODE);
        advanceMinutes(GuardianGate.DEFAULT_WINDOW_MINUTES - 1);
        assertTrue(GuardianGate.isOpen());
        advanceMinutes(1);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void openRemainingCountsDown() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(0, GuardianGate.getOpenRemainingMillis());
        GuardianGate.unlock(CODE);
        advanceMinutes(4);
        assertEquals(TimeUnit.MINUTES.toMillis(GuardianGate.DEFAULT_WINDOW_MINUTES - 4),
                GuardianGate.getOpenRemainingMillis());
    }

    @Test
    public void customWindowAppliesFromNextOpen() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.setWindowMinutes(CODE, 30));
        GuardianGate.unlock(CODE);
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
        GuardianGate.unlock(CODE);
        setWallClockMinutes(-5);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void lockCloses() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        GuardianGate.lock();
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void shortBackgroundKeepsOpenForRestOfWindow() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        GuardianGate.onAppBackgrounded();
        elapsed += GuardianGate.BACKGROUND_GRACE_MILLIS;
        wall += GuardianGate.BACKGROUND_GRACE_MILLIS;
        GuardianGate.onAppForegrounded();
        advanceMinutes(2);
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void longBackgroundClosesEvenBeforeForegroundCallback() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        GuardianGate.onAppBackgrounded();
        elapsed += GuardianGate.BACKGROUND_GRACE_MILLIS + 1;
        wall += GuardianGate.BACKGROUND_GRACE_MILLIS + 1;
        assertFalse(GuardianGate.isOpen());
        GuardianGate.onAppForegrounded();
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void repeatedBackgroundCallbacksDoNotRestartGrace() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        GuardianGate.onAppBackgrounded();
        elapsed += GuardianGate.BACKGROUND_GRACE_MILLIS;
        wall += GuardianGate.BACKGROUND_GRACE_MILLIS;
        GuardianGate.onAppBackgrounded();
        elapsed += 1;
        wall += 1;
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void fiveWrongCodesLockOutForOneMinute() {
        GuardianGate.setInitialCode(CODE);
        failTimes(GuardianGate.ATTEMPTS_BEFORE_LOCKOUT - 1);
        assertEquals(0, GuardianGate.getLockoutRemainingMillis());
        failTimes(1);
        assertEquals(TimeUnit.MINUTES.toMillis(1), GuardianGate.getLockoutRemainingMillis());
        assertEquals(GuardianGate.Result.LOCKED_OUT, GuardianGate.unlock(CODE));
        assertFalse(GuardianGate.isOpen());
        advanceMinutes(1);
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.unlock(CODE));
    }

    @Test
    public void guessesDuringLockoutAreNotCounted() {
        GuardianGate.setInitialCode(CODE);
        failTimes(GuardianGate.ATTEMPTS_BEFORE_LOCKOUT);
        assertEquals(GuardianGate.Result.LOCKED_OUT, GuardianGate.unlock(WRONG_CODE));
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
        GuardianGate.unlock(CODE);
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
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.unlock(CODE));
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.unlock(OTHER_CODE));
    }

    @Test
    public void openWindowDoesNotAllowSettingsChangesWithoutCode() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.changeCode(WRONG_CODE, OTHER_CODE));
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.setEnabled(WRONG_CODE, false));
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.setWindowMinutes(WRONG_CODE, 60));
        assertTrue(GuardianGate.isEnabled());
        assertEquals(GuardianGate.DEFAULT_WINDOW_MINUTES, GuardianGate.getWindowMinutes());
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.unlock(CODE));
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
        assertTrue(GuardianGate.isTurnedOff());
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void turningGateBackOnClosesAnOpenWindow() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        GuardianGate.setEnabled(CODE, false);
        GuardianGate.setEnabled(CODE, true);
        assertFalse(GuardianGate.isOpen());
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
    public void verifyChecksCodeWithoutUnlocking() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.verify(CODE));
        assertFalse(GuardianGate.isOpen());
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.verify(WRONG_CODE));
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
        GuardianGate.unlock(CODE);
        assertFalse(GuardianGate.isLockedPreview(preview));
    }

    @Test
    public void expiredWindowStaysClosedWhenClockIsSetBack() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        advanceMinutes(GuardianGate.DEFAULT_WINDOW_MINUTES + 5);
        assertFalse(GuardianGate.isOpen());
        setWallClockMinutes(-(GuardianGate.DEFAULT_WINDOW_MINUTES + 4));
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void movingClockForwardDoesNotEndLockout() {
        GuardianGate.setInitialCode(CODE);
        failTimes(GuardianGate.ATTEMPTS_BEFORE_LOCKOUT);
        setWallClockMinutes(TimeUnit.DAYS.toMinutes(2));
        assertTrue(GuardianGate.getLockoutRemainingMillis() > 0);
        assertEquals(GuardianGate.Result.LOCKED_OUT, GuardianGate.verify(CODE));
    }

    @Test
    public void rebootLocksAndRestartsLockout() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        reboot();
        assertFalse(GuardianGate.isOpen());

        failTimes(GuardianGate.ATTEMPTS_BEFORE_LOCKOUT);
        reboot();
        assertEquals(TimeUnit.MINUTES.toMillis(1), GuardianGate.getLockoutRemainingMillis());
    }

    @Test
    public void finishedLockoutDoesNotComeBackAfterReboot() {
        GuardianGate.setInitialCode(CODE);
        failTimes(GuardianGate.ATTEMPTS_BEFORE_LOCKOUT);
        advanceMinutes(2);
        assertEquals(0, GuardianGate.getLockoutRemainingMillis());
        reboot();
        assertEquals(0, GuardianGate.getLockoutRemainingMillis());
        assertEquals(GuardianGate.Result.ACCEPTED, GuardianGate.verify(CODE));
    }

    @Test
    public void rememberWhenTheCodeWasSet() {
        GuardianGate.setInitialCode(CODE);
        assertEquals(wall, GuardianGate.getCodeSetAt());
    }

    @Test
    public void episodeWithoutLoadedPodcastCountsAsLockedWhileClosed() {
        GuardianGate.setInitialCode(CODE);
        FeedItem episode = new FeedItem();
        episode.setFeedId(42);
        assertTrue(GuardianGate.isLockedPreview(episode));
        GuardianGate.unlock(CODE);
        assertFalse(GuardianGate.isLockedPreview(episode));
    }

    @Test
    public void rebootCannotBeDisguisedBySettingTheClock() {
        GuardianGate.setInitialCode(CODE);
        GuardianGate.unlock(CODE);
        long unlockedAtUptime = elapsed;
        long unlockedAtWallClock = wall;
        reboot();
        elapsed = unlockedAtUptime + TimeUnit.SECONDS.toMillis(10);
        wall = unlockedAtWallClock + TimeUnit.SECONDS.toMillis(10);
        assertFalse(GuardianGate.isOpen());
    }
}
