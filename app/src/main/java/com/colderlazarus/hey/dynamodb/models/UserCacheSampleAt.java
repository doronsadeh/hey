package com.colderlazarus.hey.dynamodb.models;

import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Document;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.colderlazarus.hey.dynamodb.UsersCache;

import java.util.HashMap;
import java.util.Map;

public class UserCacheSampleAt {

    public String hashKey = null;
    public String userId = null;
    public Long timestamp = null;
    public String geoHash = null;
    public Double currentLat = null;
    public Double currentLng = null;

    public static UserCacheSampleAt documentToUser(Document doc) {
        UserCacheSampleAt r = new UserCacheSampleAt();

        r.hashKey = doc.get(UsersCache.HASH_KEY).asString();
        r.userId = doc.get(UsersCache.USER_ID).asString();
        r.timestamp = doc.get(UsersCache.TIMESTAMP).asLong();
        r.geoHash = doc.get(UsersCache.GEO_HASH).asString();
        r.currentLat = Double.valueOf(doc.get(UsersCache.CURRENT_LAT).asFloat());
        r.currentLng = Double.valueOf(doc.get(UsersCache.CURRENT_LNG).asFloat());

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
