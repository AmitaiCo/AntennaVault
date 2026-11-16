package de.danoeh.antennapod.ui.screen.preferences;

import android.os.Bundle;
import android.text.format.DateUtils;

import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import de.danoeh.antennapod.ui.screen.guardian.GuardianCodeDialog;
import de.danoeh.antennapod.ui.screen.guardian.GuardianSetCodeDialog;

public class GuardianPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String PREF_STATUS = "guardianStatus";
    private static final String PREF_SET_CODE = "guardianSetCode";
    private static final String PREF_LOCK_NOW = "guardianLockNow";
    private static final String PREF_ENABLED = "guardianEnabled";
    private static final String PREF_WINDOW = "guardianWindow";
    private static final String PREF_CHANGE_CODE = "guardianChangeCode";
    private static final int[] WINDOW_OPTIONS_MINUTES = {5, 10, 15, 30, 60};

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_guardian);
        findPreference(PREF_SET_CODE).setOnPreferenceClickListener(preference -> {
            GuardianSetCodeDialog.showInitialSetup(requireContext(), this::refresh, null);
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
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.guardian_pref_title);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean configured = GuardianGate.isConfigured();
        boolean enabled = GuardianGate.isEnabled();
        findPreference(PREF_STATUS).setSummary(statusText());
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
