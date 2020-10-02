package com.colderlazarus.hey.services;


import android.location.Location;
import android.text.TextUtils;

import com.colderlazarus.hey.utils.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import static java.lang.Math.min;

public class FCMAdapter {

    public static boolean verifyFCMLink(List<String> registrationTokens) {
        String requestUri = String.format("https://us-central1-ridrz-79dad.cloudfunctions.net/verify?tokens=%s", String.join(",", registrationTokens));
        String response = Utils.HTTPGetCall(requestUri);
        try {
            if (null != response) {
                JSONObject jObj = new JSONObject(response);
                return jObj.getBoolean("verified");
            }
            return false;
        } catch (JSONException e) {
            return false;
        }
    }

    public static int sendFCMToDevices(FirebaseMsgsService.MSG_TYPE msgType,
                                       Location myLocation,
                                       String msgText,
                                       String userId,
                                       List<String> usersIds,
                                       List<String> registrationTokens) {

        // Cannot send multi-cast to more than 100 devices
        if (registrationTokens.size() > 100 || usersIds.size() > 100) {
            int _minSize = min(usersIds.size(), registrationTokens.size());
            registrationTokens = registrationTokens.subList(0, _minSize);
        }

        long _now = Utils.nowSec();

        String requestUri = null;

        requestUri = String.format("https://us-central1-hey-6b48e.cloudfunctions.net/msg?tokens=%s&rids=%s&ts=%s&sid=%s&mt=%s&text=%s&loc=%s",
                TextUtils.join(",", registrationTokens),
                TextUtils.join(",", usersIds),
                _now,
                userId,
                msgType.valueOf(),
                msgText,
                String.format("%s,%s", myLocation.getLatitude(), myLocation.getLongitude()));

        if (null != requestUri) {
            String response = Utils.HTTPGetCall(requestUri);

            try {
                if (null != response) {
                    JSONObject jObj = new JSONObject(response);
                    return jObj.getInt("success");
                }
                return 0;
            } catch (JSONException e) {
                return 0;
            }
        }

        return 0;
    }
}
