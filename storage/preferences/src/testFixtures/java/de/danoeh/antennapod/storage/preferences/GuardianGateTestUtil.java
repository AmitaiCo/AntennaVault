package de.danoeh.antennapod.storage.preferences;

import android.content.Context;

public final class GuardianGateTestUtil {
    public static final String CODE = "guardian";

    private GuardianGateTestUtil() {
    }

    public static void resetToUnconfigured(Context context) {
        GuardianGate.init(context);
        GuardianGate.resetForTesting();
    }

    public static void configureClosed(Context context) {
        resetToUnconfigured(context);
        GuardianGate.setCode(CODE);
    }

    public static void configureOpen(Context context) {
        configureClosed(context);
        GuardianGate.verify(CODE);
    }
}
