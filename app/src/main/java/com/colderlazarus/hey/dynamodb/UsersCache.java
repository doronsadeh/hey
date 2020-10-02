package com.colderlazarus.hey.dynamodb;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Document;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.colderlazarus.hey.dynamodb.models.UserCacheSampleAt;
import com.colderlazarus.hey.geo.GeoHash;
import com.colderlazarus.hey.utils.Utils;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.min;


public class UsersCache {

    private static final String TAG = "hey.UsersCache";

    public static final String HASH_KEY = "hash_key";
    public static final String USER_ID = "user_id";
    public static final String TIMESTAMP = "timestamp";
    public static final String TTL = "ttl";
    public static final String GEO_HASH = "geo_hash";
    public static final String CURRENT_LAT = "current_lat";
    public static final String CURRENT_LNG = "current_lng";

    private static DynamoDBAPI db = null;

    private static final String DYNAMODB_TABLE = "hey_users_cache";

    // We want to manage users within ~20Km land cells. This
    // allows us to pick and choose your nearest users from the
    // list stored under the 5-char geohash hash_key.
    private static final int GEOHASH_PREFIX_LENGTH = 4;

    // Note that the DEFAULT_DISTRESS_RADIUS_METERS must match or be lower than the "radius" of the above GEOHASH_PREFIX_LENGTH!
    public static final double MAX_RADIUS_METERS = 1000.0;

    // We age users location every location changed sample times two seconds.
    // Note that each user would be updated every time his location changes by more
    // than USER_MIN_LOCATION_CACHE_UPDATE_METERS, but wer want this short TTL in place
    // to make sure a user that has gone offline is not kept in the cache for too long.
    private static final long TTL_AGING_EPOCH_SEC = 30;

    public static List<UserCacheSampleAt> getCachedUsersAt(Context context, Location at, double radiusMeters) {
        if (null == db) {
            db = new DynamoDBAPI(context, DYNAMODB_TABLE);
        }

        String geoHash = GeoHash.fromLocation(at).toString();
        long cTime = Utils.nowSec();
        List<Document> users = db.get(geoHash.substring(0, GEOHASH_PREFIX_LENGTH), cTime);

        String myId = Utils.identity(context);

        Location myCurrentLocation = Utils.getLastKnownLocation(context);

        List<UserCacheSampleAt> rList = new ArrayList<>();
        if (null != users) {
            for (Document d : users) {
                UserCacheSampleAt rUser = UserCacheSampleAt.documentToUser(d);

                // Skip self
                if (rUser.userId.equals(myId))
                    continue;

                // Check they are within distress distance, only then add them
                Location rUserLocation = new Location(LocationManager.GPS_PROVIDER);
                rUserLocation.setLatitude(rUser.currentLat);
                rUserLocation.setLongitude(rUser.currentLng);
                if (null != myCurrentLocation && myCurrentLocation.distanceTo(rUserLocation) <= min(MAX_RADIUS_METERS, radiusMeters)) {
                    rList.add(rUser);
                }
            }
        }

        return rList;
    }

    public static synchronized boolean publishUserLocation(
            Context context,
            String userId,
            Location at,
            long aging) {

        if (null == db) {
            db = new DynamoDBAPI(context, DYNAMODB_TABLE);
        }

        String geoHash = GeoHash.fromLocation(at).toString();

        // Set user cache sample
        UserCacheSampleAt r = new UserCacheSampleAt();

        r.set(HASH_KEY, new AttributeValue().withS(geoHash.substring(0, GEOHASH_PREFIX_LENGTH)));
        r.set(USER_ID, new AttributeValue().withS(userId));

        r.set(TIMESTAMP, new AttributeValue().withN(String.valueOf((int) Utils.nowSec())));

        long _aging = aging < 0 ? TTL_AGING_EPOCH_SEC : aging;
        long untilEpoch = Utils.nowSec() + _aging;
        r.set(TTL, new AttributeValue().withN(String.valueOf(untilEpoch)));

        r.set(GEO_HASH, new AttributeValue().withS(geoHash));
        r.set(CURRENT_LAT, new AttributeValue().withN(String.valueOf((float) at.getLatitude())));
        r.set(CURRENT_LNG, new AttributeValue().withN(String.valueOf((float) at.getLongitude())));

        try {
            db.put(r.toDocument());
        } catch (Throwable t) {
            // If error, renew the DB connection on next attempt
            Log.e(TAG, String.format("Error accessing DynamoDB: %s", t.getMessage()));
            db = null;
            return false;
        }

        return true;
    }
}
