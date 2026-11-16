package de.danoeh.antennapod.storage.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class GuardianGate {
    public enum Result { OPENED, WRONG_CODE, LOCKED_OUT, NOT_CONFIGURED }

    @VisibleForTesting
    interface Clock {
        long now();
    }

    public static final int MIN_CODE_LENGTH = 6;
    public static final int DEFAULT_WINDOW_MINUTES = 10;
    public static final int ATTEMPTS_BEFORE_LOCKOUT = 5;
    public static final long BACKGROUND_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long MAX_LOCKOUT_MINUTES = TimeUnit.DAYS.toMinutes(1);

    private static final String PREF_NAME = "guardian_gate";
    private static final String KEY_HASH = "hash";
    private static final String KEY_SALT = "salt";
    private static final String KEY_ITERATIONS = "iterations";
    private static final String KEY_OPENED_AT = "opened_at";
    private static final String KEY_OPEN_UNTIL = "open_until";
    private static final String KEY_WINDOW_MINUTES = "window_minutes";
    private static final String KEY_FAILED_COUNT = "failed_count";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until";
    private static final String KEY_BACKGROUND_SINCE = "background_since";

    private static final String ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final int ITERATIONS = 20000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static SharedPreferences prefs;
    private static Clock clock = System::currentTimeMillis;

    public static void init(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @VisibleForTesting
    static void setClock(Clock newClock) {
        clock = newClock;
    }

    private static SharedPreferences prefs() {
        if (prefs == null) {
            throw new IllegalStateException("GuardianGate.init() has not been called");
        }
        return prefs;
    }

    public static boolean isConfigured() {
        return prefs().getString(KEY_HASH, null) != null;
    }

    @WorkerThread
    @SuppressLint("ApplySharedPref")
    public static synchronized void setCode(@NonNull String code) {
        if (code.length() < MIN_CODE_LENGTH) {
            throw new IllegalArgumentException("Code must be at least " + MIN_CODE_LENGTH + " characters");
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = hash(code, salt, ITERATIONS);
        prefs().edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(KEY_ITERATIONS, ITERATIONS)
                .putInt(KEY_FAILED_COUNT, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0)
                .commit();
    }

    public static synchronized void clearCode() {
        prefs().edit().clear().apply();
    }

    public static boolean isOpen() {
        if (prefs == null || !isConfigured()) {
            return false;
        }
        long now = clock.now();
        long openedAt = prefs.getLong(KEY_OPENED_AT, 0);
        long openUntil = prefs.getLong(KEY_OPEN_UNTIL, 0);
        return now >= openedAt && now < openUntil && !backgroundedTooLong(now);
    }

    private static boolean backgroundedTooLong(long now) {
        long since = prefs().getLong(KEY_BACKGROUND_SINCE, 0);
        return since != 0 && now - since > BACKGROUND_GRACE_MILLIS;
    }

    @NonNull
    @WorkerThread
    @SuppressLint("ApplySharedPref")
    public static synchronized Result verify(@NonNull String code) {
        if (!isConfigured()) {
            return Result.NOT_CONFIGURED;
        }
        if (getLockoutRemainingMillis() > 0) {
            return Result.LOCKED_OUT;
        }
        byte[] salt = Base64.decode(prefs().getString(KEY_SALT, ""), Base64.NO_WRAP);
        byte[] expected = Base64.decode(prefs().getString(KEY_HASH, ""), Base64.NO_WRAP);
        int iterations = prefs().getInt(KEY_ITERATIONS, ITERATIONS);
        if (MessageDigest.isEqual(expected, hash(code, salt, iterations))) {
            prefs().edit().putInt(KEY_FAILED_COUNT, 0).putLong(KEY_LOCKOUT_UNTIL, 0).commit();
            openFor(getWindowMinutes());
            return Result.OPENED;
        }
        int failed = prefs().getInt(KEY_FAILED_COUNT, 0) + 1;
        SharedPreferences.Editor editor = prefs().edit().putInt(KEY_FAILED_COUNT, failed);
        if (failed % ATTEMPTS_BEFORE_LOCKOUT == 0) {
            int round = failed / ATTEMPTS_BEFORE_LOCKOUT;
            long minutes = Math.min(1L << Math.min(round - 1, 20), MAX_LOCKOUT_MINUTES);
            editor.putLong(KEY_LOCKOUT_UNTIL, clock.now() + TimeUnit.MINUTES.toMillis(minutes));
        }
        editor.commit();
        return Result.WRONG_CODE;
    }

    public static long getLockoutRemainingMillis() {
        return Math.max(0, prefs().getLong(KEY_LOCKOUT_UNTIL, 0) - clock.now());
    }

    public static int getFailedAttempts() {
        return prefs().getInt(KEY_FAILED_COUNT, 0);
    }

    public static synchronized void openFor(int minutes) {
        long now = clock.now();
        prefs().edit()
                .putLong(KEY_OPENED_AT, now)
                .putLong(KEY_OPEN_UNTIL, now + TimeUnit.MINUTES.toMillis(minutes))
                .putLong(KEY_BACKGROUND_SINCE, 0)
                .apply();
    }

    @SuppressLint("ApplySharedPref")
    public static synchronized void lock() {
        prefs().edit()
                .putLong(KEY_OPENED_AT, 0)
                .putLong(KEY_OPEN_UNTIL, 0)
                .putLong(KEY_BACKGROUND_SINCE, 0)
                .commit();
    }

    public static int getWindowMinutes() {
        return prefs().getInt(KEY_WINDOW_MINUTES, DEFAULT_WINDOW_MINUTES);
    }

    public static synchronized void setWindowMinutes(int minutes) {
        if (minutes < 1) {
            throw new IllegalArgumentException("Window must be at least one minute");
        }
        prefs().edit().putInt(KEY_WINDOW_MINUTES, minutes).apply();
    }

    public static synchronized void onAppBackgrounded() {
        if (isOpen()) {
            prefs().edit().putLong(KEY_BACKGROUND_SINCE, clock.now()).apply();
        }
    }

    public static synchronized void onAppForegrounded() {
        if (backgroundedTooLong(clock.now())) {
            lock();
        } else if (prefs().getLong(KEY_BACKGROUND_SINCE, 0) != 0) {
            prefs().edit().putLong(KEY_BACKGROUND_SINCE, 0).apply();
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
}
