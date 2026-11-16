package de.danoeh.antennapod.ui.screen.guardian;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

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
    private static final String TAG = "GuardianCodeDialog";

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
                    GuardianGate::unlock, onOpen, onCancel, true);
        }
    }

    public static void confirmWithCode(@NonNull Context context, @StringRes int message,
                                       @NonNull CodeAction action, @NonNull Runnable onAccepted) {
        showCodePrompt(context, message, R.string.confirm_label, action, onAccepted, null, false);
    }

    static void checkInBackground(AlertDialog dialog, Callable<GuardianGate.Result> check,
                                  Consumer<GuardianGate.Result> onResult, TextInputLayout errorLayout,
                                  boolean lockIfAbandoned) {
        Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        button.setEnabled(false);
        Single.fromCallable(check)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (!dialog.isShowing() || isGone(dialog.getContext())) {
                        if (lockIfAbandoned && result == GuardianGate.Result.ACCEPTED) {
                            GuardianGate.lock();
                        }
                        return;
                    }
                    button.setEnabled(true);
                    onResult.accept(result);
                }, error -> {
                    Log.e(TAG, "Guardian code check failed", error);
                    if (dialog.isShowing()) {
                        button.setEnabled(true);
                        errorLayout.setError(dialog.getContext().getString(R.string.guardian_unexpected_error));
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

    static void excludeFromAutofill(View view) {
        if (Build.VERSION.SDK_INT >= 26) {
            view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
    }

    static void submitOnDone(TextView field, AlertDialog dialog) {
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });
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
        binding.textInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        binding.textInputLayout.setHint(R.string.guardian_code_hint);
        binding.textInputLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        excludeFromAutofill(binding.getRoot());

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

        submitOnDone(binding.textInput, dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = String.valueOf(binding.textInput.getText());
            binding.textInputLayout.setError(null);
            checkInBackground(dialog, () -> action.apply(code), result -> {
                if (result == GuardianGate.Result.ACCEPTED) {
                    accepted.set(true);
                    dialog.dismiss();
                    onAccepted.run();
                } else {
                    binding.textInput.setText("");
                    binding.textInputLayout.setError(errorMessage(context, result));
                }
            }, binding.textInputLayout, lockIfAbandoned);
        });
    }

    private static void runIfSet(@Nullable Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }
}
