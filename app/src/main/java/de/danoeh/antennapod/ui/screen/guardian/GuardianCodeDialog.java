package de.danoeh.antennapod.ui.screen.guardian;

import android.content.Context;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.EditTextDialogBinding;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

public final class GuardianCodeDialog {

    private GuardianCodeDialog() {
    }

    public static void runWhenOpen(@NonNull Context context, @NonNull Runnable onOpen) {
        runWhenOpen(context, onOpen, null);
    }

    public static void runWhenOpen(@NonNull Context context, @NonNull Runnable onOpen,
                                   @Nullable Runnable onCancel) {
        if (GuardianGate.isOpen()) {
            onOpen.run();
        } else if (!GuardianGate.isConfigured()) {
            showNotConfigured(context, onCancel);
        } else {
            showCodePrompt(context, onOpen, onCancel);
        }
    }

    private static void showNotConfigured(Context context, @Nullable Runnable onCancel) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.guardian_not_configured_title)
                .setMessage(R.string.guardian_not_configured_message)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(dialog -> runIfSet(onCancel))
                .show();
    }

    private static void showCodePrompt(Context context, Runnable onOpen, @Nullable Runnable onCancel) {
        EditTextDialogBinding binding = EditTextDialogBinding.inflate(LayoutInflater.from(context));
        binding.textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        binding.textInputLayout.setHint(R.string.guardian_code_hint);

        AtomicBoolean opened = new AtomicBoolean(false);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.guardian_code_title)
                .setMessage(R.string.guardian_code_message)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.guardian_unlock, null)
                .setNegativeButton(R.string.cancel_label, null)
                .setOnDismissListener(d -> {
                    if (!opened.get()) {
                        runIfSet(onCancel);
                    }
                })
                .show();

        Button unlockButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        unlockButton.setOnClickListener(v -> {
            String code = String.valueOf(binding.textInput.getText());
            unlockButton.setEnabled(false);
            binding.textInputLayout.setError(null);
            Single.fromCallable(() -> GuardianGate.verify(code))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(result -> {
                        if (!dialog.isShowing()) {
                            return;
                        }
                        unlockButton.setEnabled(true);
                        if (result == GuardianGate.Result.ACCEPTED) {
                            opened.set(true);
                            dialog.dismiss();
                            onOpen.run();
                        } else {
                            binding.textInput.setText("");
                            binding.textInputLayout.setError(errorMessage(context, result));
                        }
                    }, error -> {
                        unlockButton.setEnabled(true);
                        binding.textInputLayout.setError(error.getLocalizedMessage());
                    });
        });
    }

    private static String errorMessage(Context context, GuardianGate.Result result) {
        if (result == GuardianGate.Result.LOCKED_OUT) {
            long seconds = Math.max(1, GuardianGate.getLockoutRemainingMillis() / 1000);
            return context.getString(R.string.guardian_locked_out, DateUtils.formatElapsedTime(seconds));
        }
        return context.getString(R.string.guardian_wrong_code);
    }

    private static void runIfSet(@Nullable Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }
}
