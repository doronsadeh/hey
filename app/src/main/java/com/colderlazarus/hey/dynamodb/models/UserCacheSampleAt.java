package com.colderlazarus.hey.dynamodb.models;

import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Document;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

public class UserCacheSampleAt {

    public static final String HASH_KEY = "hash_key";           // Geohash prefix (primary key)
    public static final String RIDER_ID = "user_id";            // Android ID of user's phone (range key)

    public static final String TTL = "ttl";                     // Epoch time until which this record lives
    public static final String TIMESTAMP = "timestamp";         // Sampled at (epoch)
    public static final String GEO_HASH = "geo_hash";           // Full geohash of location
    public static final String SPEED_KMH = "speed_kmh";         // Rider speed when sampled
    public static final String BEARING = "bearing";             // Rider bearing when sampled
    public static final String CURRENT_LAT = "current_lat";     // Rider current location (lat, lng)
    public static final String CURRENT_LNG = "current_lng";     // Rider current location (lat, lng)
    public static final String DST_LAT = "dst_lat";             // Rider destination location (lat, lng)
    public static final String DST_LNG = "dst_lng";             // Rider destination location (lat, lng)

    public String hashKey = null;
    public String userId = null;
    public Long timestamp = null;
    public String geoHash = null;
    public Double speedKmh = null;
    public Double bearing = null;
    public Double currentLat = null;
    public Double currentLng = null;
    public Double dstLat = null;
    public Double dstLng = null;

    public static UserCacheSampleAt documentToUser(Document doc) {
        UserCacheSampleAt r = new UserCacheSampleAt();

        r.hashKey = doc.get(HASH_KEY).asString();
        r.userId = doc.get(RIDER_ID).asString();
        r.timestamp = doc.get(TIMESTAMP).asLong();
        r.geoHash = doc.get(GEO_HASH).asString();
        r.speedKmh = Double.valueOf(doc.get(SPEED_KMH).asFloat());

        r.bearing = Double.valueOf(doc.get(BEARING).asFloat());
        r.currentLat = Double.valueOf(doc.get(CURRENT_LAT).asFloat());
        r.currentLng = Double.valueOf(doc.get(CURRENT_LNG).asFloat());
        r.dstLat = Double.valueOf(doc.get(DST_LAT).asFloat());
        r.dstLng = Double.valueOf(doc.get(DST_LNG).asFloat());

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
