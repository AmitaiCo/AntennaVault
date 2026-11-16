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
        GuardianGate.setClock(System::currentTimeMillis);
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

    public static void open() {
        GuardianGate.Result result = GuardianGate.verify(CODE);
        if (result != GuardianGate.Result.ACCEPTED) {
            throw new IllegalStateException("Could not open guardian gate: " + result);
        }
    }
}
