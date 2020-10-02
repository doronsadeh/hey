package com.colderlazarus.hey.services;

import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.colderlazarus.hey.dynamodb.UsersCache;
import com.colderlazarus.hey.utils.Utils;

public class LocationListener implements android.location.LocationListener {
    private final String TAG = "LocationListener";

    public static final float LOCATION_HISTORY_RESOLUTION_METERS = MonitorForegroundService.MIN_METERS;
    private static final int MAX_LOCATION_HISTORY = 10;

    private final MonitorForegroundService monitorForegroundService;

    private Location mLastLocation = null;

    private boolean suspendingLocationListener = false;

    LocationListener(MonitorForegroundService monitorForegroundService, String provider) {
        this.monitorForegroundService = monitorForegroundService;
        mLastLocation = new Location(provider);
    }

    private void storeLocationHistory(Location location) {
        monitorForegroundService.lastLocations.add(location);
        if (monitorForegroundService.lastLocations.size() > MAX_LOCATION_HISTORY) {
            monitorForegroundService.lastLocations.remove(0);
        }
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
        Runnable rOnLocRunnable = new Runnable() {
            @Override
            public void run() {
                String myId = Utils.identity(_ctx);
                if (null != _location) {
                    UsersCache.publishUserLocation(
                            monitorForegroundService.getApplicationContext(),
                            myId,
                            _location,
                            -1L
                    );
                }

                // If not enough data points yet, store and bail
                if (null == monitorForegroundService.lastLocations || monitorForegroundService.lastLocations.size() < 2) {
                    LocationListener.this.storeLocationHistory(_location);
                    return;
                }

                // If not enough meters traveled, do NOT store, and bail. We'll wait for next sample.
                if (monitorForegroundService.lastLocations.get(monitorForegroundService.lastLocations.size() - 1).distanceTo(_location) < LOCATION_HISTORY_RESOLUTION_METERS) {
                    return;
                }

                // Store new data point
                LocationListener.this.storeLocationHistory(_location);

                // Ref. to current location
                mLastLocation = _location;

                MonitorForegroundService.setLastKnownLocation(mLastLocation);

                // TODO do work
            }

        };

        // Do work (in parallel)
        hOnLocWork.post(rOnLocRunnable);
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
