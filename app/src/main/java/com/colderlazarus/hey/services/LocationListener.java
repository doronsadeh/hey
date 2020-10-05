package com.colderlazarus.hey.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.colderlazarus.hey.MainActivity;
import com.colderlazarus.hey.R;
import com.colderlazarus.hey.dynamodb.UsersCache;
import com.colderlazarus.hey.dynamodb.models.User;
import com.colderlazarus.hey.dynamodb.models.UserCacheSampleAt;
import com.colderlazarus.hey.dynamodb.models.Users;
import com.colderlazarus.hey.services.messages.HailMessage;
import com.colderlazarus.hey.services.messages.SOSMessage;
import com.colderlazarus.hey.utils.Utils;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.colderlazarus.hey.services.messages.MessageBase.HAILING_USER_ID;

public class LocationListener implements android.location.LocationListener {

    private final String TAG = "LocationListener";

    public static final String USERS_BEING_HAILED_IDS = "hey.USERS_BEING_HAILED_IDS";
    public static final String HAILING_USER_LOCATION = "hey.HAILING_USER_LOCATION";
    public static final String HAIL_SENT_AT = "hey.HAIL_SENT_AT";

    private static final double SOS_RADIUS_METERS = 3000;

    private static final float MIN_HAIL_DISTANCE_METERS = 50;
    private static final float MIN_SOS_DISTANCE_METERS = 10;

    public static final long DONT_NUDGE_TIME_SEC = 5 * 60;

    private final MonitorForegroundService monitorForegroundService;

    private final SoundPool soundPool;

    private Location mLastLocation = null;

    private boolean suspendingLocationListener = false;

    public static Integer previousNumPeopleInRange = null;
    public static int numPeopleInRange = 0;

    LocationListener(MonitorForegroundService monitorForegroundService, String provider) {
        this.monitorForegroundService = monitorForegroundService;
        mLastLocation = new Location(provider);
        soundPool = new SoundPool(5, AudioManager.STREAM_MUSIC, 0);
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

            // Do work, hail uses in range
            hailUsersInRange(_ctx, false);
        };

        // Do work (in parallel)
        hOnLocWork.post(rOnLocRunnable);
    }

    private void updateNotification(Context context, String text) {
        Notification notification = monitorForegroundService.getNotification(text);
        NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(MonitorForegroundService.LOCATION_SERVICE_NOTIFICATION_ID, notification);
    }

    public synchronized void hailUsersInRange(Context context, boolean dryrun) {
        try {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MonitorForegroundService.appContext);
            boolean hailing = sharedPreferences.getBoolean(MainActivity.HEY_IS_HAILING, false);
            if (!hailing) {
                updateNotification(context, "");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "No appContext found yet, disabling hailing for now.");
            updateNotification(context, "");
            return;
        }

        String myId = Utils.identity(context);

        List<UserCacheSampleAt> usersInRange = UsersCache.getCachedUsersAt(context, MonitorForegroundService.getLastKnownLocation(), MonitorForegroundService.radiusMeters);

        List<String> userIds = new ArrayList<>();

        int numNotified = 0;
        for (UserCacheSampleAt u : usersInRange) {
            try {
                if (u.userId.equals(myId))
                    continue;

                // Don't send to users that are too close, as they are with you
                if (mLastLocation.distanceTo(Utils.LatLngToLocation(new LatLng(u.currentLat, u.currentLng))) < MIN_HAIL_DISTANCE_METERS)
                    continue;

                User _user = Users.getUser(context, u.userId);
                if (null != _user) {
                    // Count both already notified as well as the ones we do now
                    numNotified++;

                    if ((Utils.nowSec() - _user.lastHailedAt) > DONT_NUDGE_TIME_SEC)
                        userIds.add(u.userId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Bad user info, cannot send: " + u.userId);
            }
        }

        numPeopleInRange = numNotified;

        Log.d(TAG, "Num in range=" + numPeopleInRange + ", previously=" + previousNumPeopleInRange);

        if (null == previousNumPeopleInRange || numPeopleInRange != previousNumPeopleInRange) {
            previousNumPeopleInRange = numPeopleInRange;
            try {
                // Update foreground service notification
                String notificationText = String.format(context.getString(R.string.hail_in_range_ticker), LocationListener.numPeopleInRange);
                updateNotification(context, notificationText);
            } catch (Exception e) {
                // Silent, if the activity is not on top, never mind the update
            }
        }

        if (!dryrun && userIds.size() > 0) {
            HailMessage msg = new HailMessage(context);

            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put(USERS_BEING_HAILED_IDS, TextUtils.join(",", userIds));

            Location lkLoc = Utils.getLastKnownLocation(context);
            messageBody.put(HAILING_USER_LOCATION, String.format("%s,%s", lkLoc.getLatitude(), lkLoc.getLongitude()));

            messageBody.put(HAIL_SENT_AT, String.valueOf(Utils.nowSec()));
            messageBody.put(HAILING_USER_ID, myId);

            msg.sendMessage(context, messageBody, true);

            for (String uid : userIds) {
                User _user = Users.getUser(context, uid);
                if (null != _user) {
                    Users.setUser(context, _user.token, _user, Utils.nowSec());
                }
            }
        }
    }

    public synchronized void sosAllUsersInRange(Context context) {
        Utils.CallPolice(context);

        String myId = Utils.identity(context);

        List<UserCacheSampleAt> usersInRange = UsersCache.getCachedUsersAt(context, MonitorForegroundService.getLastKnownLocation(), SOS_RADIUS_METERS);

        List<String> userIds = new ArrayList<>();

        for (UserCacheSampleAt u : usersInRange) {
            try {
                if (u.userId.equals(myId))
                    continue;

                // Don't send to users that are too close, as they are with you
                if (mLastLocation.distanceTo(Utils.LatLngToLocation(new LatLng(u.currentLat, u.currentLng))) < MIN_SOS_DISTANCE_METERS)
                    continue;

                User _user = Users.getUser(context, u.userId);
                if (null != _user) {
                    userIds.add(u.userId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Bad user info, cannot send: " + u.userId);
            }
        }

        if (userIds.size() > 0) {
            SOSMessage msg = new SOSMessage(context);

            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put(USERS_BEING_HAILED_IDS, TextUtils.join(",", userIds));

            Location lkLoc = Utils.getLastKnownLocation(context);
            messageBody.put(HAILING_USER_LOCATION, String.format("%s,%s", lkLoc.getLatitude(), lkLoc.getLongitude()));

            messageBody.put(HAIL_SENT_AT, String.valueOf(Utils.nowSec()));
            messageBody.put(HAILING_USER_ID, myId);

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
