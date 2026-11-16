package de.danoeh.antennapod;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.danoeh.antennapod.storage.preferences.GuardianGate;

class GuardianGateLifecycleTracker implements Application.ActivityLifecycleCallbacks {
    private int startedActivities = 0;

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (startedActivities++ == 0) {
            GuardianGate.onAppForegrounded();
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        if (--startedActivities == 0) {
            GuardianGate.onAppBackgrounded();
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}
