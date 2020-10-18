package com.colderlazarus.hey.services.messages;

import android.content.Context;

import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public abstract class MessageBase {

    public static final String MSG_TEXT = "msg_text";
    public static final String MSG_TYPE = "msg_type";
    public static final String HAILING_USER_ID = "hailing_rider_id";

    //
    // API
    //
    public abstract void receiveMessage(Context context, RemoteMessage remoteMessage);

    public abstract int sendMessage(Context context, Map<String, Object> message, boolean skipStartActivity);
}
