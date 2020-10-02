package com.colderlazarus.hey.services;


import android.location.Location;
import android.os.Build;

import androidx.annotation.RequiresApi;

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

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static int sendFCMToDevices(FirebaseMsgsService.MSG_TYPE msgType,
                                       Location myLocation,
                                       String msgText,
                                       String riderId,
                                       List<String> ridersIds,
                                       List<String> registrationTokens) {

        // Cannot send multi-cast to more than 100 devices
        if (registrationTokens.size() > 100 || ridersIds.size() > 100) {
            int _minSize = min(ridersIds.size(), registrationTokens.size());
            registrationTokens = registrationTokens.subList(0, _minSize);
        }

        long _now = Utils.nowSec();

        String requestUri = null;

        requestUri = String.format("https://us-central1-ridrz-79dad.cloudfunctions.net/msg?tokens=%s&rids=%s&ts=%s&sid=%s&mt=%s&text=%s&loc=%s",
                String.join(",", registrationTokens),
                String.join(",", ridersIds),
                _now,
                riderId,
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

    public static String SMSVerification(String phoneNumber) {

        long _now = Utils.nowSec();

        String requestUri = null;

        requestUri = String.format("https://us-central1-ridrz-79dad.cloudfunctions.net/sms_verification?ts=%s&phone_number=%s",
                _now,
                phoneNumber);

        String response = Utils.HTTPGetCall(requestUri);

        try {
            if (null != response) {
                JSONObject jObj = new JSONObject(response);
                return jObj.getString("code");
            }
            return null;
        } catch (JSONException e) {
            return null;
        }

    }

}
