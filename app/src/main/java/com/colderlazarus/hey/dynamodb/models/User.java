package com.colderlazarus.hey.dynamodb.models;

import android.content.Context;

import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Document;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

public class User {

    private static final String TAG = "hey.Rider";

    public static final String USER_ID = "user_id";
    public static final String TIMESTAMP = "timestamp";
    public static final String REGISTRATION_TOKEN = "registration_token";
    public static final String TTL = "ttl";

    public String riderId = null;
    public Long timestamp = null;
    public String token = null;

    public static User build(Context context) {
        User r = new User();
        r.riderId = null;
        r.timestamp = -1L;
        r.token = null;
        return r;
    }

    public static User build(String userId, String token, long timestamp) {
        User r = new User();
        r.riderId = userId;
        r.timestamp = timestamp;
        r.token = token;
        return r;
    }

    public static User documentToUser(Document doc) {
        User r = new User();
        r.riderId = doc.get(USER_ID).asString();
        r.timestamp = doc.get(TIMESTAMP).asLong();
        r.token = doc.get(REGISTRATION_TOKEN).asString();
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
