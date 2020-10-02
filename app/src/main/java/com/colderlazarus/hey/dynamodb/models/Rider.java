package com.colderlazarus.hey.dynamodb.models;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Document;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.colderlazarus.hey.utils.Utils;
import com.colderlazarus.rain.utils.Log;
import com.colderlazarus.rain.utils.Utils;

import java.util.HashMap;
import java.util.Map;

import static com.colderlazarus.hey.utils.Utils.fakeLocation;
import static com.colderlazarus.hey.utils.Utils.getLastKnownLocation;
import static com.colderlazarus.rain.utils.Utils.fakeLocation;
import static com.colderlazarus.rain.utils.Utils.getLastKnownLocation;

public class Rider {

    private static final String TAG = "ridinrain.Rider";

    // TODO a class holding the information of a single rider.
    //      Used amongst other things to send push notifications to that rider.

    public enum RiderState {
        UNDEFINED("undefined"),
        NOTIFIED_ABOUT_DISTRESS("notified_about_distress"),
        RESPONDING_TO_DISTRESS("responding_to_distress"),
        NOTIFIED_ABOUT_INVITE("notified_about_invite"),
        RESPONDING_TO_INVITE("responding_to_invite");

        private String value;

        RiderState(String value) {
            this.value = value;
        }

        public String valueOf() {
            return value;
        }

        public long agingDurationSec() {
            if (value.equals(NOTIFIED_ABOUT_DISTRESS.valueOf()))
                return 5L * 60L;

            if (value.equals(RESPONDING_TO_DISTRESS.valueOf()))
                return 15L * 60L;

            if (value.equals(NOTIFIED_ABOUT_INVITE.valueOf()))
                return 5L * 60L;

            if (value.equals(RESPONDING_TO_INVITE.valueOf()))
                return 15L * 60L;

            return 0L;
        }

        public static RiderState fromString(String strValue) {
            for (RiderState state : RiderState.values()) {
                if (state.value.equalsIgnoreCase(strValue))
                    return state;
            }

            return UNDEFINED;
        }
    }

    public static final String RIDER_ID = "user_id";            // Android ID of user's phone (Primary Key)
    public static final String TIMESTAMP = "timestamp";         // Sampled at (epoch) (Range Key)
    public static final String REGISTRATION_TOKEN = "registration_token";   // FCM reg. token for this user
    public static final String TTL = "ttl";                     // Epoch time until which this record lives
    public static final String STATE = "state";                 // General usage state field
    // TODO add subscriptions data

    public String riderId = null;
    public Long timestamp = null;
    public String token = null;
    public String state = null;
    public String sessionId = null;
    public Location location = null;

    public static Rider build(Context context, RiderState state, String sessionId) {
        String myId = Utils.identity(context);

        Location location = getLastKnownLocation(context);
        if (null == location)
            location = fakeLocation();

        return build(myId, Utils.nowSec(), state.valueOf(), location, sessionId);
    }

    public static Rider build(String riderId, long timestamp, String stateStr, Location location, String sessionId) {
        String locStr = String.format("%s,%s", location.getLatitude(), location.getLongitude());
        return build(riderId, timestamp, stateStr, locStr, sessionId);
    }

    public static Rider build(String riderId, long timestamp, String stateStr, String locationStr, String sessionId) {
        Rider r = new Rider();

        r.riderId = riderId;
        r.timestamp = timestamp;
        r.token = null;
        r.state = stateStr;
        r.sessionId = sessionId;

        String[] locStrFields = locationStr.split(",");
        r.location = new Location(LocationManager.GPS_PROVIDER);
        r.location.setLatitude(Double.parseDouble(locStrFields[0]));
        r.location.setLongitude(Double.parseDouble(locStrFields[1]));

        return r;
    }

    public static Rider documentToUser(Document doc) {
        Rider r = new Rider();

        r.riderId = doc.get(RIDER_ID).asString();
        r.timestamp = doc.get(TIMESTAMP).asLong();
        r.token = doc.get(REGISTRATION_TOKEN).asString();

        String stateStr = doc.get(STATE).asString();
        if (null != stateStr) {
            String[] fields = stateStr.split("\\$");
            if (fields.length == 4) {
                // Extract state
                r.state = fields[0];

                // Disregard fields[1], timestamp for now

                // Extract location
                String[] locStrFields = fields[2].split(",");
                r.location = new Location(LocationManager.GPS_PROVIDER);
                r.location.setLatitude(Double.parseDouble(locStrFields[0]));
                r.location.setLongitude(Double.parseDouble(locStrFields[1]));

                // Extract session ID
                r.sessionId = fields[3];
            } else {
                // Set them to null to cause a crash so we can find the bug
                r.state = null;
                r.sessionId = null;
                r.location = null;
                Log.e(TAG, "Malformed rider data cloud doc state column: " + stateStr);
            }
        }

        return r;
    }

    private Map<String, AttributeValue> attributeValueMap = new HashMap<>();

    public void set(String key, AttributeValue value) {
        attributeValueMap.put(key, value);
    }

    public AttributeValue get(String key) {
        return attributeValueMap.get(key);
    }

    public Map attrMap() {
        return attributeValueMap;
    }

    public Document toDocument() {
        return Document.fromAttributeMap(attributeValueMap);
    }

}
