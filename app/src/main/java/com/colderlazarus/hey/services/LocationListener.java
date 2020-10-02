package com.colderlazarus.hey.services;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.colderlazarus.hey.MainActivity;
import com.colderlazarus.hey.dynamodb.UsersCache;
import com.colderlazarus.hey.dynamodb.models.UserCacheSampleAt;
import com.colderlazarus.hey.services.messages.HailMessage;
import com.colderlazarus.hey.utils.Utils;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.colderlazarus.hey.MainActivity.UPDATE_UI_ACTION;
import static com.colderlazarus.hey.MainActivity.USER_IN_RANGE_EXTRA;

public class LocationListener implements android.location.LocationListener {

    private final String TAG = "LocationListener";

    public static final String USERS_BEING_HAILED_IDS = "hey.USERS_BEING_HAILED_IDS";
    public static final String HAILING_USER_LOCATION = "hey.HAILING_USER_LOCATION";
    public static final String HAIL_SENT_AT = "hey.HAIL_SENT_AT";

    private static final float MIN_HAIL_DISTANCE_METERS = 50;

    private static final long DONT_NUDGE_TIME_SEC = 15 * 60;

    private final MonitorForegroundService monitorForegroundService;

    private Location mLastLocation = null;

    private boolean suspendingLocationListener = false;

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

    // Stored the last time we hailed a user, if too long ago, we can re-hail. else don't!
    private Map<String, Long> hailedUsersAtTime = new HashMap<>();

    public synchronized void hailUsersInRange(Context context) {
        List<UserCacheSampleAt> usersInRange = UsersCache.getCachedUsersAt(context, mLastLocation, MonitorForegroundService.radiusMeters);

        List<String> userIds = new ArrayList<>();

        for (UserCacheSampleAt u : usersInRange) {
            try {
                // Don't send to users that are too close, as they are with you
                if (mLastLocation.distanceTo(Utils.LatLngToLocation(new LatLng(u.currentLat, u.currentLng))) < MIN_HAIL_DISTANCE_METERS)
                    continue;

                if (hailedUsersAtTime.containsKey(u.userId) && (Utils.nowSec() - hailedUsersAtTime.get(u.userId)) < DONT_NUDGE_TIME_SEC)
                    continue;
                else
                    hailedUsersAtTime.put(u.userId, Utils.nowSec());

                userIds.add(u.userId);
            } catch (Exception e) {
                Log.e(TAG, "Bad user info, cannot send: " + u.userId);
            }
        }

        try {
            // Update the users in range on the UI
            Intent updateUI = new Intent(context, MainActivity.class);
            updateUI.setAction(UPDATE_UI_ACTION);
            updateUI.putExtra(USER_IN_RANGE_EXTRA, userIds.size());
//        updateUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(updateUI);
        } catch (Exception e) {
            // Nothing
        }

        if (userIds.size() > 0) {
            HailMessage msg = new HailMessage(context);

            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put(USERS_BEING_HAILED_IDS, TextUtils.join(",", userIds));
            messageBody.put(HAILING_USER_LOCATION, String.format("%s,%s", mLastLocation.getLatitude(), mLastLocation.getLongitude()));
            messageBody.put(HAIL_SENT_AT, String.valueOf(Utils.nowSec()));

            msg.sendMessage(context, messageBody, true);
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
