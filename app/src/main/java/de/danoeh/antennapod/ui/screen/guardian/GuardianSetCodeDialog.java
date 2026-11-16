package de.danoeh.antennapod.ui.screen.guardian;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.GuardianSetCodeDialogBinding;
import de.danoeh.antennapod.storage.preferences.GuardianGate;

public final class GuardianSetCodeDialog {

    private GuardianSetCodeDialog() {
    }

    public static void showFirstRunPromptIfNeeded(@NonNull Context context) {
        if (GuardianGate.isConfigured()) {
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.guardian_first_run_title)
                .setMessage(R.string.guardian_first_run_message)
                .setPositiveButton(R.string.guardian_set_up, (dialog, which) ->
                        showInitialSetup(context, () -> { }))
                .setNegativeButton(R.string.guardian_later, null)
                .show();
    }

    public static void showInitialSetup(@NonNull Context context, @NonNull Runnable onDone) {
        show(context, false, R.string.guardian_set_code_title, onDone);
    }

    public static void showChangeCode(@NonNull Context context, @NonNull Runnable onDone) {
        show(context, true, R.string.guardian_change_code_title, onDone);
    }

    private static void show(Context context, boolean changing, @StringRes int title, Runnable onDone) {
        GuardianSetCodeDialogBinding binding = GuardianSetCodeDialogBinding.inflate(LayoutInflater.from(context));
        binding.currentCodeLayout.setVisibility(changing ? View.VISIBLE : View.GONE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.confirm_label, null)
                .setNegativeButton(R.string.cancel_label, null)
                .show();

        Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        saveButton.setOnClickListener(v -> {
            binding.currentCodeLayout.setError(null);
            binding.newCodeLayout.setError(null);
            binding.confirmCodeLayout.setError(null);
            String newCode = String.valueOf(binding.newCode.getText());
            if (newCode.length() < GuardianGate.MIN_CODE_LENGTH) {
                binding.newCodeLayout.setError(context.getResources().getQuantityString(
                        R.plurals.guardian_code_too_short, GuardianGate.MIN_CODE_LENGTH,
                        GuardianGate.MIN_CODE_LENGTH));
                return;
            }
            if (!newCode.equals(String.valueOf(binding.confirmCode.getText()))) {
                binding.confirmCodeLayout.setError(context.getString(R.string.guardian_codes_do_not_match));
                return;
            }
            String currentCode = String.valueOf(binding.currentCode.getText());
            GuardianCodeDialog.checkInBackground(context, dialog, saveButton,
                    () -> changing ? GuardianGate.changeCode(currentCode, newCode) : setInitialCode(newCode),
                    result -> {
                        if (result == GuardianGate.Result.ACCEPTED) {
                            dialog.dismiss();
                            onDone.run();
                        } else {
                            binding.currentCode.setText("");
                            binding.currentCodeLayout.setError(GuardianCodeDialog.errorMessage(context, result));
                        }
                    }, error -> binding.newCodeLayout.setError(error.getLocalizedMessage()), false);
        });
    }

    private static GuardianGate.Result setInitialCode(String code) {
        GuardianGate.setInitialCode(code);
        return GuardianGate.Result.ACCEPTED;
    }
}
