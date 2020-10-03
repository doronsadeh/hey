package com.colderlazarus.hey.services;

import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.colderlazarus.hey.dynamodb.UsersCache;
import com.colderlazarus.hey.dynamodb.models.User;
import com.colderlazarus.hey.dynamodb.models.UserCacheSampleAt;
import com.colderlazarus.hey.dynamodb.models.Users;
import com.colderlazarus.hey.services.messages.HailMessage;
import com.colderlazarus.hey.utils.Utils;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocationListener implements android.location.LocationListener {

    private final String TAG = "LocationListener";

    public static final String USERS_BEING_HAILED_IDS = "hey.USERS_BEING_HAILED_IDS";
    public static final String HAILING_USER_LOCATION = "hey.HAILING_USER_LOCATION";
    public static final String HAIL_SENT_AT = "hey.HAIL_SENT_AT";

    private static final float MIN_HAIL_DISTANCE_METERS = 50;

    public static final long DONT_NUDGE_TIME_SEC = 15 * 60;

    private final MonitorForegroundService monitorForegroundService;

    private Location mLastLocation = null;

    private boolean suspendingLocationListener = false;

    public static int numPeopleInRange = 0;

    LocationListener(MonitorForegroundService monitorForegroundService, String provider) {
        this.monitorForegroundService = monitorForegroundService;
        mLastLocation = new Location(provider);
    }

    @Override
    public void onLocationChanged(Location location) {
        final Context _ctx = monitorForegroundService.getApplicationContext();

        if (!Utils.checkNetworkAvailability(_ctx)) {
            if (!suspendingLocationListener) {
                // Network down, notify the user, and bail
                suspendingLocationListener = true;
            }
            return;
        }

        // If there is a good connection and we were in suspension, notify the user we are back on line
        if (suspendingLocationListener) {
            suspendingLocationListener = false;
        }

        final Location _location = location;
        Handler hOnLocWork = new Handler();
        Runnable rOnLocRunnable = () -> {
            String myId = Utils.identity(_ctx);
            if (null != _location) {
                UsersCache.publishUserLocation(
                        monitorForegroundService.getApplicationContext(),
                        myId,
                        _location,
                        -1L
                );
            }

            // Ref. to current location
            mLastLocation = _location;

            MonitorForegroundService.setLastKnownLocation(mLastLocation);

            // TODO do work, hail uses in range
            hailUsersInRange(_ctx);
        };

        // Do work (in parallel)
        hOnLocWork.post(rOnLocRunnable);
    }

    public synchronized void hailUsersInRange(Context context) {
        List<UserCacheSampleAt> usersInRange = UsersCache.getCachedUsersAt(context, mLastLocation, MonitorForegroundService.radiusMeters);

        List<String> userIds = new ArrayList<>();

        for (UserCacheSampleAt u : usersInRange) {
            try {
                // Don't send to users that are too close, as they are with you
                if (mLastLocation.distanceTo(Utils.LatLngToLocation(new LatLng(u.currentLat, u.currentLng))) < MIN_HAIL_DISTANCE_METERS)
                    continue;

                User _user = Users.getUser(context, u.userId);
                if (null != _user) {
                    if ((Utils.nowSec() - _user.lastHailedAt) > DONT_NUDGE_TIME_SEC)
                        userIds.add(u.userId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Bad user info, cannot send: " + u.userId);
            }
        }

        if (userIds.size() > 0) {
            HailMessage msg = new HailMessage(context);

            numPeopleInRange = userIds.size();

            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put(USERS_BEING_HAILED_IDS, TextUtils.join(",", userIds));
            messageBody.put(HAILING_USER_LOCATION, String.format("%s,%s", mLastLocation.getLatitude(), mLastLocation.getLongitude()));
            messageBody.put(HAIL_SENT_AT, String.valueOf(Utils.nowSec()));

            msg.sendMessage(context, messageBody, true);

            for (String uid : userIds) {
                User _user = Users.getUser(context, uid);
                if (null != _user) {
                    Users.setUser(context, _user.token, _user, Utils.nowSec());
                }
            }
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.e(TAG, "onProviderDisabled: " + provider);
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.e(TAG, "onProviderEnabled: " + provider);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.e(TAG, "onStatusChanged: " + status);
    }
}
