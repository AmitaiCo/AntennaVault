package de.danoeh.antennapod.ui.screen.preferences;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Map;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.database.PendingSubscriptions;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianPendingFeeds;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import de.danoeh.antennapod.ui.screen.guardian.GuardianCodeDialog;
import de.danoeh.antennapod.ui.screen.guardian.GuardianSetCodeDialog;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class GuardianPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String PREF_STATUS = "guardianStatus";
    private static final String PREF_PENDING = "guardianPending";
    private static final String PREF_SET_CODE = "guardianSetCode";
    private static final String PREF_LOCK_NOW = "guardianLockNow";
    private static final String PREF_ENABLED = "guardianEnabled";
    private static final String PREF_WINDOW = "guardianWindow";
    private static final String PREF_CHANGE_CODE = "guardianChangeCode";
    private static final String TAG = "GuardianPrefFragment";
    private static final int[] WINDOW_OPTIONS_MINUTES = {5, 10, 15, 30, 60};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTicker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, DateUtils.SECOND_IN_MILLIS);
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_guardian);
        findPreference(PREF_SET_CODE).setOnPreferenceClickListener(preference -> {
            GuardianSetCodeDialog.showInitialSetup(requireContext(), this::refresh);
            return true;
        });
        findPreference(PREF_CHANGE_CODE).setOnPreferenceClickListener(preference -> {
            GuardianSetCodeDialog.showChangeCode(requireContext(), this::refresh);
            return true;
        });
        findPreference(PREF_LOCK_NOW).setOnPreferenceClickListener(preference -> {
            Completable.fromAction(GuardianGate::lock)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::refreshIfAdded, error -> Log.e(TAG, "Failed to lock", error));
            return true;
        });
        findPreference(PREF_ENABLED).setOnPreferenceClickListener(preference -> {
            boolean enable = !GuardianGate.isEnabled();
            GuardianCodeDialog.confirmWithCode(requireContext(),
                    enable ? R.string.guardian_turn_on_message : R.string.guardian_turn_off_message,
                    code -> GuardianGate.setEnabled(code, enable), this::refresh);
            return true;
        });
        findPreference(PREF_WINDOW).setOnPreferenceClickListener(preference -> {
            showWindowChooser();
            return true;
        });
        findPreference(PREF_PENDING).setOnPreferenceClickListener(preference -> {
            showPendingFeeds();
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.guardian_pref_title);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshTicker.run();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTicker);
    }

    private void refreshIfAdded() {
        if (isAdded()) {
            refresh();
        }
    }

    private void refresh() {
        boolean configured = GuardianGate.isConfigured();
        boolean enabled = GuardianGate.isEnabled();
        findPreference(PREF_STATUS).setSummary(statusText());
        Map<String, String> pending = GuardianPendingFeeds.getAll();
        Preference pendingPreference = findPreference(PREF_PENDING);
        pendingPreference.setVisible(!pending.isEmpty());
        pendingPreference.setSummary(TextUtils.join(", ", pendingLabels(pending)));
        findPreference(PREF_SET_CODE).setVisible(!configured);
        findPreference(PREF_LOCK_NOW).setVisible(enabled && GuardianGate.isOpen());
        findPreference(PREF_CHANGE_CODE).setVisible(configured);

        Preference enabledPreference = findPreference(PREF_ENABLED);
        enabledPreference.setVisible(configured);
        enabledPreference.setSummary(enabled ? R.string.guardian_enabled_on : R.string.guardian_enabled_off);

        Preference windowPreference = findPreference(PREF_WINDOW);
        windowPreference.setVisible(configured);
        windowPreference.setSummary(windowLabel(GuardianGate.getWindowMinutes()));
    }

    private String statusText() {
        if (!GuardianGate.isConfigured()) {
            return getString(R.string.guardian_status_not_configured);
        } else if (!GuardianGate.isEnabled()) {
            return getString(R.string.guardian_status_disabled);
        } else if (GuardianGate.isOpen()) {
            long seconds = GuardianGate.getOpenRemainingMillis() / 1000;
            return getString(R.string.guardian_status_open, DateUtils.formatElapsedTime(seconds));
        }
        return getString(R.string.guardian_status_locked);
    }

    private static String[] pendingLabels(Map<String, String> pending) {
        String[] labels = new String[pending.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            labels[i++] = entry.getValue().isEmpty() ? entry.getKey() : entry.getValue();
        }
        return labels;
    }

    private void showPendingFeeds() {
        Context appContext = requireContext().getApplicationContext();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.guardian_pending_title)
                .setItems(pendingLabels(GuardianPendingFeeds.getAll()), null)
                .setPositiveButton(R.string.guardian_pending_approve_all, (dialog, which) ->
                        GuardianCodeDialog.runWhenOpen(requireContext(), () -> runPendingAction(() -> {
                            PendingSubscriptions.approveAll(appContext);
                            FeedUpdateManager.getInstance().runOnce(appContext);
                        })))
                .setNegativeButton(R.string.guardian_pending_reject_all, (dialog, which) ->
                        GuardianCodeDialog.runWhenOpen(requireContext(),
                                () -> runPendingAction(PendingSubscriptions::rejectAll)))
                .setNeutralButton(R.string.cancel_label, null)
                .show();
    }

    private void runPendingAction(Action action) {
        Completable.fromAction(action)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::refreshIfAdded, error -> Log.e(TAG, "Failed to handle waiting podcasts", error));
    }

    private String windowLabel(int minutes) {
        return getResources().getQuantityString(R.plurals.guardian_window_minutes, minutes, minutes);
    }

    private void showWindowChooser() {
        String[] labels = new String[WINDOW_OPTIONS_MINUTES.length];
        int checked = -1;
        for (int i = 0; i < WINDOW_OPTIONS_MINUTES.length; i++) {
            labels[i] = windowLabel(WINDOW_OPTIONS_MINUTES[i]);
            if (WINDOW_OPTIONS_MINUTES[i] == GuardianGate.getWindowMinutes()) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.guardian_window_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    int minutes = WINDOW_OPTIONS_MINUTES[which];
                    GuardianCodeDialog.confirmWithCode(requireContext(), R.string.guardian_window_message,
                            code -> GuardianGate.setWindowMinutes(code, minutes), this::refresh);
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}
