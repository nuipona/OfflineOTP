/*
 * Copyright (C) 2018 Jakob Nixdorf
 * Copyright (C) 2018 Richy HBM
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.shadowice.flocke.andotp.R;

import static android.content.Context.NOTIFICATION_SERVICE;

/** Creates the backup notification channels and posts the backup notifications. */
public class NotificationHelper {
    private static final String POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final int POST_NOTIFICATIONS_REQUEST_CODE = 1;

    /**
     * Asks for the notification permission on Android 13+ if it is not granted yet.
     * Written with literals so it compiles against older SDKs; a no-op before Android 13 (API 33).
     */
    public static void requestNotificationPermission(android.app.Fragment fragment) {
        Context context = fragment.getActivity();
        if (context == null || android.os.Build.VERSION.SDK_INT < 33) {
            return;
        }

        if (ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            fragment.requestPermissions(new String[] { POST_NOTIFICATIONS_PERMISSION }, POST_NOTIFICATIONS_REQUEST_CODE);
        }
    }

    private static String channelId(Constants.NotificationChannel channel) {
        return "andOTP_" + channel.name().toLowerCase();
    }

    /** Creates the channel for the given type. */
    private static void createNotificationChannel(Context context, Constants.NotificationChannel channel) {
        NotificationChannel notificationChannel = new NotificationChannel(channelId(channel), context.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT);

        switch(channel) {
            case BACKUP_FAILED:
                notificationChannel.setName(context.getString(R.string.notification_channel_name_backup_failed));
                notificationChannel.setDescription(context.getString(R.string.notification_channel_desc_backup_failed));
                notificationChannel.setImportance(NotificationManager.IMPORTANCE_HIGH);
                break;
            case BACKUP_SUCCESS:
                notificationChannel.setName(context.getString(R.string.notification_channel_name_backup_success));
                notificationChannel.setDescription(context.getString(R.string.notification_channel_desc_backup_success));
                notificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
                break;
            default:
                break;
        }

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
    }

    /** Creates all notification channels the app can use. */
    public static void initializeNotificationChannels(Context context) {
        for (Constants.NotificationChannel channel : Constants.NotificationChannel.values()) {
            NotificationHelper.createNotificationChannel(context, channel);
        }
    }

    /** Posts a notification with a string resource as its body. */
    public static void notify(Context context, Constants.NotificationChannel channel, int resIdTitle, int resIdBody) {
        notify(context, channel, resIdTitle, context.getText(resIdBody).toString());
    }

    /** Posts a notification with an already resolved body text. */
    public static void notify(Context context, Constants.NotificationChannel channel , int resIdTitle, String resBody) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, null)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getText(resIdTitle))
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(resBody));

        createNotificationChannel(context, channel);
        builder.setChannelId(channelId(channel));

        int notificationId = 1;

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(notificationId, builder.build());
    }
}
