package de.danoeh.antennapod.ui.notifications;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public final class GuardianPendingNotification {

    private GuardianPendingNotification() {
    }

    public static void show(Context context, int count) {
        if (count == 0) {
            return;
        }
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getActivity(context, R.id.pending_intent_guardian_pending,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = context.getResources().getQuantityString(
                R.plurals.guardian_pending_notification_text, count, count);
        Notification notification = new NotificationCompat.Builder(context, NotificationUtils.CHANNEL_ID_USER_ACTION)
                .setContentTitle(context.getString(R.string.guardian_pending_notification_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_notification_guardian)
                .setAutoCancel(true)
                .build();
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(R.id.notification_guardian_pending, notification);
        }
    }
}
