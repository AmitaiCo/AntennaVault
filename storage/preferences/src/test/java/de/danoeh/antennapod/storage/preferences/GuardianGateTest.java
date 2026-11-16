package de.danoeh.antennapod.storage.preferences;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GuardianGateTest {
    private static final String CODE = "482193";
    private long now;

    @Before
    public void setUp() {
        now = 1_000_000_000_000L;
        GuardianGate.setClock(() -> now);
        GuardianGate.init(ApplicationProvider.getApplicationContext());
        GuardianGate.resetForTesting();
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
            assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.verify("0000"));
        }
    }

    @Test
    public void notConfiguredIsClosed() {
        assertFalse(GuardianGate.isConfigured());
        assertFalse(GuardianGate.isOpen());
        assertEquals(GuardianGate.Result.NOT_CONFIGURED, GuardianGate.verify(CODE));
    }

    @Test
    public void correctCodeOpensWindow() {
        GuardianGate.setCode(CODE);
        assertTrue(GuardianGate.isConfigured());
        assertFalse(GuardianGate.isOpen());
        assertEquals(GuardianGate.Result.OPENED, GuardianGate.verify(CODE));
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void wrongCodeStaysClosed() {
        GuardianGate.setCode(CODE);
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.verify("1234"));
        assertFalse(GuardianGate.isOpen());
        assertEquals(1, GuardianGate.getFailedAttempts());
    }

    @Test
    public void codeIsNotStoredInPlainText() {
        GuardianGate.setCode(CODE);
        Context context = ApplicationProvider.getApplicationContext();
        for (Object value : context.getSharedPreferences("guardian_gate", Context.MODE_PRIVATE).getAll().values()) {
            assertFalse(String.valueOf(value).contains(CODE));
        }
    }

    @Test
    public void changingCodeInvalidatesOldCode() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.setCode("999999");
        GuardianGate.lock();
        assertEquals(GuardianGate.Result.WRONG_CODE, GuardianGate.verify(CODE));
        assertEquals(GuardianGate.Result.OPENED, GuardianGate.verify("999999"));
    }

    @Test
    public void windowExpires() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        advanceMinutes(GuardianGate.DEFAULT_WINDOW_MINUTES - 1);
        assertTrue(GuardianGate.isOpen());
        advanceMinutes(1);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void customWindowLength() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.setWindowMinutes(30);
        GuardianGate.lock();
        GuardianGate.verify(CODE);
        advanceMinutes(29);
        assertTrue(GuardianGate.isOpen());
        advanceMinutes(1);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void clockSetBackwardsCloses() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        now -= TimeUnit.MINUTES.toMillis(1);
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void lockCloses() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.lock();
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void shortBackgroundKeepsOpen() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.onAppBackgrounded();
        now += GuardianGate.BACKGROUND_GRACE_MILLIS;
        GuardianGate.onAppForegrounded();
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void longBackgroundLocks() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.onAppBackgrounded();
        now += GuardianGate.BACKGROUND_GRACE_MILLIS + 1;
        GuardianGate.onAppForegrounded();
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void longBackgroundClosesBeforeForegroundCallback() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.onAppBackgrounded();
        now += GuardianGate.BACKGROUND_GRACE_MILLIS + 1;
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void backgroundWhileClosedDoesNotAffectNextOpen() {
        GuardianGate.setCode(CODE);
        GuardianGate.onAppBackgrounded();
        advanceMinutes(5);
        GuardianGate.verify(CODE);
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void fiveWrongCodesLockOutForOneMinute() {
        GuardianGate.setCode(CODE);
        failTimes(4);
        assertEquals(0, GuardianGate.getLockoutRemainingMillis());
        failTimes(1);
        assertEquals(TimeUnit.MINUTES.toMillis(1), GuardianGate.getLockoutRemainingMillis());
        assertEquals(GuardianGate.Result.LOCKED_OUT, GuardianGate.verify(CODE));
        assertFalse(GuardianGate.isOpen());
        advanceMinutes(1);
        assertEquals(GuardianGate.Result.OPENED, GuardianGate.verify(CODE));
    }

    @Test
    public void lockoutDoubles() {
        GuardianGate.setCode(CODE);
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
        GuardianGate.setCode(CODE);
        for (int round = 0; round < 15; round++) {
            failTimes(5);
            advanceMinutes(TimeUnit.DAYS.toMinutes(1));
        }
        failTimes(5);
        assertEquals(TimeUnit.DAYS.toMillis(1), GuardianGate.getLockoutRemainingMillis());
    }

    @Test
    public void correctCodeResetsFailures() {
        GuardianGate.setCode(CODE);
        failTimes(4);
        GuardianGate.verify(CODE);
        assertEquals(0, GuardianGate.getFailedAttempts());
        failTimes(4);
        assertEquals(0, GuardianGate.getLockoutRemainingMillis());
    }

    @Test(expected = GuardianGateClosedException.class)
    public void changingCodeRequiresOpenGate() {
        GuardianGate.setCode(CODE);
        GuardianGate.setCode("999999");
    }

    @Test(expected = GuardianGateClosedException.class)
    public void changingWindowRequiresOpenGate() {
        GuardianGate.setCode(CODE);
        GuardianGate.setWindowMinutes(30);
    }

    @Test(expected = GuardianGateClosedException.class)
    public void disablingRequiresOpenGate() {
        GuardianGate.setCode(CODE);
        GuardianGate.setEnabled(false);
    }

    @Test(expected = GuardianGateClosedException.class)
    public void removingCodeRequiresOpenGate() {
        GuardianGate.setCode(CODE);
        GuardianGate.removeCodeAndSettings();
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroMinuteWindowRejected() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.setWindowMinutes(0);
    }

    @Test
    public void disabledGateIsAlwaysOpen() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.setEnabled(false);
        GuardianGate.lock();
        advanceMinutes(TimeUnit.DAYS.toMinutes(30));
        assertFalse(GuardianGate.isEnabled());
        assertTrue(GuardianGate.isOpen());
    }

    @Test
    public void reenablingClosesAfterWindow() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        GuardianGate.setEnabled(false);
        GuardianGate.setEnabled(true);
        GuardianGate.lock();
        assertTrue(GuardianGate.isEnabled());
        assertFalse(GuardianGate.isOpen());
    }

    @Test
    public void removingCodeLeavesGateUnconfiguredAndClosed() {
        GuardianGate.setCode(CODE);
        GuardianGate.verify(CODE);
        failTimes(3);
        GuardianGate.removeCodeAndSettings();
        assertFalse(GuardianGate.isConfigured());
        assertFalse(GuardianGate.isOpen());
        assertEquals(0, GuardianGate.getFailedAttempts());
    }

    @Test
    public void openRemainingCountsDown() {
        GuardianGate.setCode(CODE);
        assertEquals(0, GuardianGate.getOpenRemainingMillis());
        GuardianGate.verify(CODE);
        advanceMinutes(4);
        assertEquals(TimeUnit.MINUTES.toMillis(GuardianGate.DEFAULT_WINDOW_MINUTES - 4),
                GuardianGate.getOpenRemainingMillis());
    }

    @Test(expected = GuardianGateClosedException.class)
    public void requireOpenThrowsWhenClosed() {
        GuardianGate.setCode(CODE);
        GuardianGate.requireOpen("test");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shortCodeRejected() {
        GuardianGate.setCode("12345");
    }
}
