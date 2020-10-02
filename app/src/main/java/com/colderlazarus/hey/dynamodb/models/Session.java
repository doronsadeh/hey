package com.colderlazarus.hey.dynamodb.models;

import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Document;
import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.DynamoDBEntry;
import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.DynamoDBList;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Session {
    public static final String HASH_KEY = "session_id";         // Primary key
    public static final String TIMESTAMP = "timestamp";         // sort key
    public static final String TTL = "ttl";                     // Epoch time until which this record lives
    public static final String RIDS = "rids";                   // Riders in session (riders IDs)
    public static final String SESSION_DATA = "session_data";   // JSON format string with contextual session data


    private Map<String, AttributeValue> attributeValueMap = new HashMap<>();

    public static List<String> extractRIDs(Document doc) {
        List<String> ridsList = new ArrayList<>();

        if (null != doc.get(RIDS)) {
            DynamoDBList _rids = doc.get(RIDS).asDynamoDBList();

            for (DynamoDBEntry _r : _rids.getEntries()) {
                ridsList.add(_r.asString());
            }
        }

        return ridsList;
    }

    public static String getSessionDataAsString(Document doc) {
        return doc.get(SESSION_DATA).asString();
    }

    public void set(String key, AttributeValue value) {
        attributeValueMap.put(key, value);
    }

    public AttributeValue get(String key) {
        return attributeValueMap.get(key);
    }

    public Document toDocument() {
        return Document.fromAttributeMap(attributeValueMap);
    }

}
