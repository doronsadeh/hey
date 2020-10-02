package com.colderlazarus.hey.services.messages;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.colderlazarus.hey.R;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public abstract class MessageBase {

    public static final String SESSION_UID = "com.colderlazarus.ridinrain.SESSION_UID";

    // This is a contract between the Firebase function and the app - DO NOT MODIFY this
    // without modifying the TypeScript code of the messaging function in Firebase.
    public static final String MSG_TEXT = "msg_text";
    public static final String MSG_TYPE = "msg_type";
    public static final String SENT_AT_EPOCH_SEC = "time";

    protected void createNotificationChannel(Context context, String channel_id) {

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = context.getString(R.string.channel_name);
            String description = context.getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(channel_id, name, importance);
            channel.setDescription(description);

            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    //
    // API
    //
    public abstract void receiveMessage(Context context, RemoteMessage remoteMessage);

    public abstract int sendMessage(Context context, Map<String, Object> message, boolean skipStartActivity);
}
