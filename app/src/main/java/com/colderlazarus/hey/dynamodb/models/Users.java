package com.colderlazarus.hey.dynamodb.models;

import android.content.Context;

import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Document;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.colderlazarus.hey.dynamodb.DynamoDBAPI;
import com.colderlazarus.hey.utils.Utils;

import static com.colderlazarus.hey.dynamodb.models.User.TTL;


public class Users {

    private static final String TAG = "hey.Users";

    private static DynamoDBAPI db = null;

    public static final String DYNAMODB_TABLE = "hey_users";

    // Users are aged every 24 hours
    private static final long TTL_AGING_EPOCH_SEC = 24 * 60 * 60;

    public static User getUser(Context context, String _userId) {
        // If no DB make one
        if (null == db) {
            db = new DynamoDBAPI(context, DYNAMODB_TABLE);
        }

        String myId = Utils.identity(context);

        // If we are starting the app, and the FCM has not yet gotten the instanceId, we may
        // be called from RoutePlanningActivity onStart with a null myId, in which case
        // we simply return null, and hope it will get resolved in a short while
        if (null == myId)
            return null;

        User userData = null;
        if (null != _userId) {
            Document user = db.getOne(_userId);
            if (null != user)
                userData = User.documentToUser(user);
        }

        return userData;
    }

    public static synchronized boolean setUser(Context context, String token, User user, Long lastHailedAt) {
        // If no DB make one
        if (null == db) {
            db = new DynamoDBAPI(context, DYNAMODB_TABLE);
        }

        if (null != token)
            user.token = token;

        String myId = Utils.identity(context);
        user.set(User.USER_ID, new AttributeValue().withS(myId));
        user.set(User.TIMESTAMP, new AttributeValue().withN(String.valueOf((int) Utils.nowSec())));
        user.set(User.LAST_HAILED_AT, new AttributeValue().withN(String.valueOf(lastHailedAt)));

        long untilEpoch = Utils.nowSec() + TTL_AGING_EPOCH_SEC;
        user.set(TTL, new AttributeValue().withN(String.valueOf(untilEpoch)));

        // If null token was given, use the cached, else read back the current token stored in DB
        if (null == token) {
            // Get from rider
            token = user.token;

            // Else try to recover the cloud stored token
            User _u;
            if (null == token) {
                _u = getUser(context, myId);
                if (null != _u)
                    token = _u.token;
            }
        }

        if (null == token) {
            return false;
        }

        user.set(User.REGISTRATION_TOKEN, new AttributeValue().withS(token));

        try {
            db.put(user.toDocument());
        } catch (Exception e) {
            db = null;
            return false;
        }

        return true;
    }

    public static void deleteUser(Context context, String riderId) {
        // If no DB make one
        if (null == db) {
            db = new DynamoDBAPI(context, DYNAMODB_TABLE);
        }

        db.delete(riderId);
    }

}
