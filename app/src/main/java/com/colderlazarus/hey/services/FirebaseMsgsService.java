package com.colderlazarus.hey.services;

import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class FirebaseMsgsService extends FirebaseMessagingService {

    private static final String TAG = "rideinrain.FMsgsService";

    // If message is older than 30 seconds ago, it is stale and irrelevant
    private static final long MAX_ALLOWED_FCM_MSG_AGE_SEC = 60L;

    public enum MSG_TYPE {
        UNDEF(""),
        DISTRESS("distress"),
        CANCEL_DISTRESS("cancel_distress"),
        INVITE("invite"),
        CANCEL_INVITE("cancel_invite"),
        PHONE_NUMBER_VERIFICATION("phone_number_verification");

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
        Users.setUser(getApplicationContext(), newToken, Rider.build(getApplicationContext(), Rider.RiderState.UNDEFINED, SessionConsts.NO_SESSION));
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

            if (msgType.equalsIgnoreCase(MSG_TYPE.DISTRESS.valueOf())) {
                //
                // Distress
                //
                MessageBase distressMessage = new DistressMessage(getApplicationContext());
                distressMessage.receiveMessage(getApplicationContext(), remoteMessage);
            } else if (msgType.equalsIgnoreCase(MSG_TYPE.CANCEL_DISTRESS.valueOf())) {
                //
                // Cancel Distress
                //
                MessageBase distressCancelMessage = new DistressCancelMessage(null);
                distressCancelMessage.receiveMessage(getApplicationContext(), remoteMessage);
            } else if (msgType.equalsIgnoreCase(MSG_TYPE.INVITE.valueOf())) {
                //
                // Invite
                //
                // TODO
                String msgText = _data.get(MSG_TEXT);
                if (null != msgText && msgText.contains(SEPARATOR)) {
                    String sessionId = msgText.split(SEPARATOR)[0];
                    InviteMessage inviteMessage = new InviteMessage(getApplicationContext(), sessionId);
                    inviteMessage.receiveMessage(getApplicationContext(), remoteMessage);
                }
            }

        }
    }
}
