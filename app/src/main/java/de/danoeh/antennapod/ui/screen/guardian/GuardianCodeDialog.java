package de.danoeh.antennapod.ui.screen.guardian;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.EditTextDialogBinding;
import de.danoeh.antennapod.storage.preferences.GuardianGate;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GuardianCodeDialog {

    public interface CodeAction {
        @WorkerThread
        GuardianGate.Result apply(String code);
    }

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
            showCodePrompt(context, R.string.guardian_code_message, R.string.guardian_unlock,
                    GuardianGate::verify, onOpen, onCancel, true);
        }
    }

    public static void confirmWithCode(@NonNull Context context, @StringRes int message,
                                       @NonNull CodeAction action, @NonNull Runnable onAccepted) {
        showCodePrompt(context, message, R.string.confirm_label, action, onAccepted, null, false);
    }

    static void checkInBackground(Context context, AlertDialog dialog, Button button,
                                  Callable<GuardianGate.Result> check, Consumer<GuardianGate.Result> onResult,
                                  Consumer<Throwable> onError, boolean lockIfAbandoned) {
        button.setEnabled(false);
        Single.fromCallable(check)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (!dialog.isShowing() || isGone(context)) {
                        if (lockIfAbandoned && result == GuardianGate.Result.ACCEPTED) {
                            GuardianGate.lock();
                        }
                        return;
                    }
                    button.setEnabled(true);
                    onResult.accept(result);
                }, error -> {
                    if (dialog.isShowing()) {
                        button.setEnabled(true);
                        onError.accept(error);
                    }
                });
    }

    static String errorMessage(Context context, GuardianGate.Result result) {
        if (result == GuardianGate.Result.NOT_CONFIGURED) {
            return context.getString(R.string.guardian_not_configured_message);
        }
        long lockoutMillis = GuardianGate.getLockoutRemainingMillis();
        if (lockoutMillis > 0) {
            long seconds = Math.max(1, lockoutMillis / 1000);
            return context.getString(R.string.guardian_locked_out, DateUtils.formatElapsedTime(seconds));
        }
        return context.getString(R.string.guardian_wrong_code);
    }

    private static boolean isGone(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof FragmentActivity
                    && ((FragmentActivity) context).getSupportFragmentManager().isStateSaved()) {
                return true;
            }
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                return activity.isFinishing() || activity.isDestroyed();
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return false;
    }

    private static void showNotConfigured(Context context, @Nullable Runnable onCancel) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.guardian_not_configured_title)
                .setMessage(R.string.guardian_not_configured_message)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(dialog -> runIfSet(onCancel))
                .show();
    }

    private static void showCodePrompt(Context context, @StringRes int message, @StringRes int positiveText,
                                       CodeAction action, Runnable onAccepted, @Nullable Runnable onCancel,
                                       boolean lockIfAbandoned) {
        EditTextDialogBinding binding = EditTextDialogBinding.inflate(LayoutInflater.from(context));
        binding.textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        binding.textInputLayout.setHint(R.string.guardian_code_hint);

        AtomicBoolean accepted = new AtomicBoolean(false);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.guardian_code_title)
                .setMessage(message)
                .setView(binding.getRoot())
                .setPositiveButton(positiveText, null)
                .setNegativeButton(R.string.cancel_label, null)
                .setOnDismissListener(d -> {
                    if (!accepted.get()) {
                        runIfSet(onCancel);
                    }
                })
                .show();

        Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positiveButton.setOnClickListener(v -> {
            String code = String.valueOf(binding.textInput.getText());
            binding.textInputLayout.setError(null);
            checkInBackground(context, dialog, positiveButton, () -> action.apply(code), result -> {
                if (result == GuardianGate.Result.ACCEPTED) {
                    accepted.set(true);
                    dialog.dismiss();
                    onAccepted.run();
                } else {
                    binding.textInput.setText("");
                    binding.textInputLayout.setError(errorMessage(context, result));
                }
            }, error -> binding.textInputLayout.setError(error.getLocalizedMessage()), lockIfAbandoned);
        });
    }

    private static void runIfSet(@Nullable Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }
}
