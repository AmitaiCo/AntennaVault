package de.danoeh.antennapod.storage.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class GuardianGate {
    public enum Result { ACCEPTED, WRONG_CODE, LOCKED_OUT, NOT_CONFIGURED }

    public static final int MIN_CODE_LENGTH = 6;
    public static final int DEFAULT_WINDOW_MINUTES = 10;

    @VisibleForTesting
    static final String PREF_NAME = "guardian_gate";
    @VisibleForTesting
    static final int ATTEMPTS_BEFORE_LOCKOUT = 5;
    @VisibleForTesting
    static final long BACKGROUND_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long MAX_LOCKOUT_MINUTES = TimeUnit.DAYS.toMinutes(1);

    private static final String KEY_HASH = "hash";
    private static final String KEY_SALT = "salt";
    private static final String KEY_ITERATIONS = "iterations";
    private static final String KEY_DISABLED = "disabled";
    private static final String KEY_WINDOW_MINUTES = "window_minutes";
    private static final String KEY_OPENED_AT = "opened_at";
    private static final String KEY_OPEN_UNTIL = "open_until";
    private static final String KEY_BACKGROUND_SINCE = "background_since";
    private static final String KEY_FAILED_COUNT = "failed_count";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until";

    private static final String ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final int ITERATIONS = 20000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    @VisibleForTesting
    interface Clock {
        long now();
    }

    private static SharedPreferences prefs;
    private static Clock clock = System::currentTimeMillis;

    public static void init(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isConfigured() {
        return prefs().getString(KEY_HASH, null) != null;
    }

    public static boolean isEnabled() {
        return isConfigured() && !prefs().getBoolean(KEY_DISABLED, false);
    }

    public static boolean isOpen() {
        if (!isConfigured()) {
            return false;
        }
        return !isEnabled() || getOpenRemainingMillis() > 0;
    }

    public static boolean isLockedPreview(@Nullable Feed feed) {
        return feed != null && feed.getState() == Feed.STATE_NOT_SUBSCRIBED && !isOpen();
    }

    public static boolean isLockedPreview(@Nullable FeedItem item) {
        return item != null && isLockedPreview(item.getFeed());
    }

    public static void requireOpen(@NonNull String action) {
        if (!isOpen()) {
            throw new GuardianGateClosedException(action);
        }
    }

    public static long getOpenRemainingMillis() {
        long now = clock.now();
        long openedAt = prefs().getLong(KEY_OPENED_AT, 0);
        long openUntil = prefs().getLong(KEY_OPEN_UNTIL, 0);
        if (now < openedAt || backgroundedTooLong(now)) {
            return 0;
        }
        return Math.max(0, openUntil - now);
    }

    public static long getLockoutRemainingMillis() {
        return Math.max(0, prefs().getLong(KEY_LOCKOUT_UNTIL, 0) - clock.now());
    }

    public static int getFailedAttempts() {
        return prefs().getInt(KEY_FAILED_COUNT, 0);
    }

    public static int getWindowMinutes() {
        return prefs().getInt(KEY_WINDOW_MINUTES, DEFAULT_WINDOW_MINUTES);
    }

    @NonNull
    @WorkerThread
    @SuppressLint("ApplySharedPref")
    public static Result verify(@NonNull String code) {
        Result result = checkCode(code);
        if (result == Result.ACCEPTED) {
            synchronized (GuardianGate.class) {
                long now = clock.now();
                prefs().edit()
                        .putLong(KEY_OPENED_AT, now)
                        .putLong(KEY_OPEN_UNTIL, now + TimeUnit.MINUTES.toMillis(getWindowMinutes()))
                        .putLong(KEY_BACKGROUND_SINCE, 0)
                        .commit();
            }
        }
        return result;
    }

    @NonNull
    @WorkerThread
    public static Result confirm(@NonNull String code) {
        return checkCode(code);
    }

    @SuppressLint("ApplySharedPref")
    public static synchronized void lock() {
        prefs().edit()
                .putLong(KEY_OPENED_AT, 0)
                .putLong(KEY_OPEN_UNTIL, 0)
                .putLong(KEY_BACKGROUND_SINCE, 0)
                .commit();
    }

    @WorkerThread
    public static void setInitialCode(@NonNull String code) {
        if (isConfigured()) {
            throw new IllegalStateException("A guardian code is already set");
        }
        storeCode(code, true);
    }

    @NonNull
    @WorkerThread
    public static Result changeCode(@NonNull String currentCode, @NonNull String newCode) {
        checkCodeLength(newCode);
        Result result = checkCode(currentCode);
        if (result == Result.ACCEPTED) {
            storeCode(newCode, false);
        }
        return result;
    }

    @NonNull
    @WorkerThread
    @SuppressLint("ApplySharedPref")
    public static Result setEnabled(@NonNull String code, boolean enabled) {
        Result result = checkCode(code);
        if (result == Result.ACCEPTED) {
            synchronized (GuardianGate.class) {
                prefs().edit().putBoolean(KEY_DISABLED, !enabled).commit();
            }
        }
        return result;
    }

    @NonNull
    @WorkerThread
    @SuppressLint("ApplySharedPref")
    public static Result setWindowMinutes(@NonNull String code, int minutes) {
        if (minutes < 1) {
            throw new IllegalArgumentException("Window must be at least one minute");
        }
        Result result = checkCode(code);
        if (result == Result.ACCEPTED) {
            synchronized (GuardianGate.class) {
                prefs().edit().putInt(KEY_WINDOW_MINUTES, minutes).commit();
            }
        }
        return result;
    }

    @SuppressLint("ApplySharedPref")
    public static synchronized void onAppBackgrounded() {
        if (getOpenRemainingMillis() > 0 && prefs().getLong(KEY_BACKGROUND_SINCE, 0) == 0) {
            prefs().edit().putLong(KEY_BACKGROUND_SINCE, clock.now()).commit();
        }
    }

    public static synchronized void onAppForegrounded() {
        if (backgroundedTooLong(clock.now())) {
            lock();
        } else if (prefs().getLong(KEY_BACKGROUND_SINCE, 0) != 0) {
            prefs().edit().putLong(KEY_BACKGROUND_SINCE, 0).apply();
        }
    }

    private static SharedPreferences prefs() {
        if (prefs == null) {
            throw new IllegalStateException("GuardianGate.init() has not been called");
        }
        return prefs;
    }

    private static boolean backgroundedTooLong(long now) {
        long since = prefs().getLong(KEY_BACKGROUND_SINCE, 0);
        return since != 0 && now - since > BACKGROUND_GRACE_MILLIS;
    }

    @SuppressLint("ApplySharedPref")
    private static Result checkCode(String code) {
        byte[] salt;
        byte[] expected;
        int iterations;
        synchronized (GuardianGate.class) {
            if (!isConfigured()) {
                return Result.NOT_CONFIGURED;
            }
            if (getLockoutRemainingMillis() > 0) {
                return Result.LOCKED_OUT;
            }
            salt = Base64.decode(prefs().getString(KEY_SALT, ""), Base64.NO_WRAP);
            expected = Base64.decode(prefs().getString(KEY_HASH, ""), Base64.NO_WRAP);
            iterations = prefs().getInt(KEY_ITERATIONS, ITERATIONS);
        }
        boolean matches = MessageDigest.isEqual(expected, hash(code, salt, iterations));
        synchronized (GuardianGate.class) {
            if (getLockoutRemainingMillis() > 0) {
                return Result.LOCKED_OUT;
            }
            if (matches) {
                prefs().edit().putInt(KEY_FAILED_COUNT, 0).putLong(KEY_LOCKOUT_UNTIL, 0).commit();
                return Result.ACCEPTED;
            }
            int failed = prefs().getInt(KEY_FAILED_COUNT, 0) + 1;
            SharedPreferences.Editor editor = prefs().edit().putInt(KEY_FAILED_COUNT, failed);
            if (failed % ATTEMPTS_BEFORE_LOCKOUT == 0) {
                long minutes = lockoutMinutes(failed / ATTEMPTS_BEFORE_LOCKOUT);
                editor.putLong(KEY_LOCKOUT_UNTIL, clock.now() + TimeUnit.MINUTES.toMillis(minutes));
            }
            editor.commit();
            return Result.WRONG_CODE;
        }
    }

    private static long lockoutMinutes(int round) {
        long minutes = 1;
        for (int i = 1; i < round && minutes < MAX_LOCKOUT_MINUTES; i++) {
            minutes *= 2;
        }
        return Math.min(minutes, MAX_LOCKOUT_MINUTES);
    }

    private static void checkCodeLength(String code) {
        if (code.length() < MIN_CODE_LENGTH) {
            throw new IllegalArgumentException("Code must be at least " + MIN_CODE_LENGTH + " characters");
        }
    }

    @SuppressLint("ApplySharedPref")
    private static void storeCode(String code, boolean onlyIfUnconfigured) {
        checkCodeLength(code);
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = hash(code, salt, ITERATIONS);
        synchronized (GuardianGate.class) {
            if (onlyIfUnconfigured && isConfigured()) {
                throw new IllegalStateException("A guardian code is already set");
            }
            prefs().edit()
                    .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .putInt(KEY_ITERATIONS, ITERATIONS)
                    .putInt(KEY_FAILED_COUNT, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0)
                    .commit();
        }
    }

    private static byte[] hash(String code, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    @VisibleForTesting
    static void setClock(Clock newClock) {
        clock = newClock;
    }
}
