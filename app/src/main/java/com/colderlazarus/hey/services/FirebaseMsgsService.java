package com.colderlazarus.hey.services;

import android.util.Log;

import com.colderlazarus.hey.dynamodb.models.User;
import com.colderlazarus.hey.dynamodb.models.Users;
import com.colderlazarus.hey.services.messages.HailMessage;
import com.colderlazarus.hey.services.messages.MessageBase;
import com.colderlazarus.hey.utils.Utils;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class FirebaseMsgsService extends FirebaseMessagingService {

    private static final String TAG = "hey.FMsgsService";

    // If message is older than 30 seconds ago, it is stale and irrelevant
    private static final long MAX_ALLOWED_FCM_MSG_AGE_SEC = 60L;

    private static final String SENT_AT_EPOCH_SEC = "hey.SENT_AT_EPOCH_SEC";

    public enum MSG_TYPE {
        UNDEF(""),
        HAIL("hail");

        private String msgType;

        MSG_TYPE(String msgType) {
            this.msgType = msgType;
        }

        public String valueOf() {
            return msgType;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    /**
     * Called if InstanceID token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is called when the InstanceID token
     * is initially generated so this is where you would retrieve the token.
     */
    @Override
    public void onNewToken(@NotNull String newToken) {

        Log.d(TAG, "Refreshed token: " + newToken);

        // If you want to send messages to this application instance or
        // manage this apps subscriptions on the server side, send the
        // Instance ID token to your app server.
        Users.setUser(getApplicationContext(), newToken, User.build(Utils.identity(getApplicationContext()), newToken, Utils.nowSec(), 0L), 0L);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        // Handle FCM messages here.
        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        Log.d(TAG, String.format("From: %s, Type: %s", remoteMessage.getFrom(), remoteMessage.getMessageType()));

        Map<String, String> _data = remoteMessage.getData();

        // Check if message contains a data payload.
        if (_data.size() > 0) {

            Log.d(TAG, "Message data payload: " + _data);

            String msgType = _data.get("msg_type");

            if (null == msgType)
                return;

            // Timestamp reading and discard too old msgs
            String ts = _data.get(SENT_AT_EPOCH_SEC);
            if (null != ts) {
                long epochTimeSentAt = Long.parseLong(ts);
                long deltaSec = Utils.nowSec() - epochTimeSentAt;
                if (deltaSec >= MAX_ALLOWED_FCM_MSG_AGE_SEC) {
                    return;
                }
            }

            if (msgType.equalsIgnoreCase(MSG_TYPE.HAIL.valueOf())) {
                //
                // Hail
                //
                MessageBase hailMsg = new HailMessage(getApplicationContext());
                hailMsg.receiveMessage(getApplicationContext(), remoteMessage);
            }
        }
    }
}
