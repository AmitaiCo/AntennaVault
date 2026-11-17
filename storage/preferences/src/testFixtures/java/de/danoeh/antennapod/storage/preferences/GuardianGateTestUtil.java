package de.danoeh.antennapod.storage.preferences;

import android.content.Context;

public final class GuardianGateTestUtil {
    public static final String CODE = "guardian";

    private GuardianGateTestUtil() {
    }

    public static void resetToUnconfigured(Context context) {
        for (String name : new String[] {GuardianGate.PREF_NAME, GuardianPendingFeeds.PREF_NAME}) {
            context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply();
        }
        GuardianGate.useSystemClock();
        GuardianGate.init(context);
        GuardianPendingFeeds.init(context);
    }

    public static void configureClosed(Context context) {
        resetToUnconfigured(context);
        GuardianGate.setInitialCode(CODE);
    }

    public static void configureOpen(Context context) {
        configureClosed(context);
        open();
    }

    public static void setNow(long millis) {
        GuardianGate.setClock(new GuardianGate.Clock() {
            @Override
            public long elapsedRealtime() {
                return millis;
            }

            @Override
            public long currentTimeMillis() {
                return millis;
            }

            @Override
            public int bootCount() {
                return 1;
            }
        });
    }

    public static void open() {
        GuardianGate.Result result = GuardianGate.unlock(CODE);
        if (result != GuardianGate.Result.ACCEPTED) {
            throw new IllegalStateException("Could not open guardian gate: " + result);
        }
    }
}
