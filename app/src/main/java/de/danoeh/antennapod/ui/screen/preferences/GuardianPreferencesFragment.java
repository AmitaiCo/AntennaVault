package de.danoeh.antennapod.ui.screen.preferences;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.database.PendingSubscriptions;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.storage.preferences.GuardianPendingFeeds;
import de.danoeh.antennapod.ui.common.DateFormatter;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import de.danoeh.antennapod.ui.screen.guardian.GuardianCodeDialog;
import de.danoeh.antennapod.ui.screen.guardian.GuardianSetCodeDialog;
import de.danoeh.antennapod.ui.notifications.GuardianPendingNotification;

public class GuardianPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String PREF_STATUS = "guardianStatus";
    private static final String PREF_PENDING = "guardianPending";
    private static final String PREF_SET_CODE = "guardianSetCode";
    private static final String PREF_LOCK_NOW = "guardianLockNow";
    private static final String PREF_ENABLED = "guardianEnabled";
    private static final String PREF_WINDOW = "guardianWindow";
    private static final String PREF_CHANGE_CODE = "guardianChangeCode";
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
            GuardianGate.lock();
            refresh();
            return true;
        });
        findPreference(PREF_ENABLED).setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enable = (Boolean) newValue;
            GuardianCodeDialog.confirmWithCode(requireContext(),
                    enable ? R.string.guardian_turn_on_message : R.string.guardian_turn_off_message,
                    code -> GuardianGate.setEnabled(code, enable), this::refresh);
            return false;
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

    private void refresh() {
        String status = statusText();
        if (GuardianGate.isConfigured()) {
            status += "\n" + getString(R.string.guardian_code_set_on,
                    DateFormatter.formatAbbrev(requireContext(), new Date(GuardianGate.getCodeSetAt())));
        }
        findPreference(PREF_STATUS).setSummary(status);
        Map<String, String> pending = GuardianPendingFeeds.getAll();
        Preference pendingPreference = findPreference(PREF_PENDING);
        pendingPreference.setVisible(!pending.isEmpty());
        if (pending.isEmpty()) {
            GuardianPendingNotification.cancel(requireContext());
        }
        pendingPreference.setSummary(TextUtils.join(", ", pendingLabels(pending)));

        boolean configured = GuardianGate.isConfigured();
        boolean enabled = GuardianGate.isEnabled();
        findPreference(PREF_SET_CODE).setVisible(!configured);
        findPreference(PREF_LOCK_NOW).setVisible(enabled && GuardianGate.isOpen());
        findPreference(PREF_CHANGE_CODE).setVisible(configured);

        SwitchPreferenceCompat enabledPreference = findPreference(PREF_ENABLED);
        enabledPreference.setVisible(configured);
        enabledPreference.setChecked(enabled);

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
        Map<String, String> pending = GuardianPendingFeeds.getAll();
        List<String> urls = new ArrayList<>(pending.keySet());
        String[] labels = new String[urls.size()];
        boolean[] checked = new boolean[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            String title = pending.get(urls.get(i));
            labels[i] = title.isEmpty() ? urls.get(i) : title + "\n" + urls.get(i);
            checked[i] = true;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.guardian_pending_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.guardian_pending_approve, (dialog, which) ->
                        GuardianCodeDialog.confirmWithCode(requireContext(), R.string.guardian_pending_approve_message,
                                code -> {
                                    GuardianGate.Result result = PendingSubscriptions.approve(
                                            appContext, code, selected(urls, checked));
                                    if (result == GuardianGate.Result.ACCEPTED) {
                                        FeedUpdateManager.getInstance().runOnce(appContext);
                                    }
                                    return result;
                                }, this::refresh))
                .setNegativeButton(R.string.guardian_pending_reject, (dialog, which) ->
                        GuardianCodeDialog.confirmWithCode(requireContext(), R.string.guardian_pending_reject_message,
                                code -> PendingSubscriptions.reject(code, selected(urls, checked)),
                                this::refresh))
                .setNeutralButton(R.string.cancel_label, null)
                .show();
    }

    private static List<String> selected(List<String> urls, boolean[] checked) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            if (checked[i]) {
                result.add(urls.get(i));
            }
        }
        return result;
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
