package com.colderlazarus.hey.dynamodb.models;

import org.json.JSONException;

public interface SessionData {
    String sessionId();

    String toJSONString() throws JSONException;
}
