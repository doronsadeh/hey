package com.colderlazarus.hey.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.colderlazarus.hey.MainActivity;
import com.colderlazarus.hey.R;
import com.colderlazarus.hey.dynamodb.UsersCache;
import com.colderlazarus.hey.utils.Utils;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.colderlazarus.hey.utils.Utils.fakeLocation;

public class MonitorForegroundService extends Service {

    private final String TAG = "hey.MonitorService";

    public static final int MIN_MILLISECONDS = 1000;
    public static final float MIN_METERS = (float) 10.0;

    private static final String REOPEN_ACTIVITY_ACTION = "hey.REOPEN_ACTIVITY_ACTION";
    private static final String EXIT_APP_ACTION = "hey.EXIT_APP_ACTION";

    private static final long LOCATION_SAMPLE_RATE_MINUTES = 1L;

    private FirebaseAnalytics mFirebaseAnalytics;

    public static Service monitorForegroundServiceContext = null;

    private static final String CHANNEL_ID = "LocationServiceForegroundServiceChannel";

    private static final int LOCATION_SERVICE_NOTIFICATION_ID = Utils.genIntUUID();

    private static final Object lastKnownLocationSync = new Object();

    private final LocationServiceBinder binder = new LocationServiceBinder();

    // Each time the app is started from scratch we get a new identity
    // we don't care if it's different fom last time as it only serves
    // to send FCM messages, and keep track of transient location
    public static String randomRecyclingIdentity = null;

    public static int radiusMeters = 1000;

    private static Location lastKnownLocation = null;

    // Location tracking
    private LocationListener mLocationListener;
    private LocationManager mLocationManager;
    protected ArrayList<Location> lastLocations = new ArrayList<>();

    private ScheduledExecutorService staticLLocationScheduler;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public static Location getLastKnownLocation() {
        synchronized (lastKnownLocationSync) {
            return lastKnownLocation;
        }
    }

    public static void setLastKnownLocation(Location mLastLocation) {
        synchronized (lastKnownLocationSync) {
            lastKnownLocation = mLastLocation;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        // Moving this code to a handler to avoid first time load delay
        Handler h = new Handler();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                // Get current location and Update map activity, and DB
                lastKnownLocation = Utils.getLastKnownLocation(MonitorForegroundService.this.getApplicationContext());

                if (null == lastKnownLocation)
                    lastKnownLocation = fakeLocation();

                String myId = Utils.identity(MonitorForegroundService.this.getApplicationContext());
                if (null != lastKnownLocation) {
                    UsersCache.publishUserLocation(
                            MonitorForegroundService.this.getApplicationContext(),
                            myId,
                            lastKnownLocation,
                            60
                    );
                }

                MonitorForegroundService.this.startTracking();
            }
        };

        h.post(r);

        return START_NOT_STICKY;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onCreate() {
        super.onCreate();

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        startForeground(LOCATION_SERVICE_NOTIFICATION_ID, getNotification());

        monitorForegroundServiceContext = this;

        final String myId = Utils.identity(getApplicationContext());
        lastKnownLocation = Utils.getLastKnownLocation(this);
        if (null != lastKnownLocation) {
            UsersCache.publishUserLocation(
                    getApplicationContext(),
                    myId,
                    lastKnownLocation,
                    60
            );
        }

        // Schedule location sampling when not moving
        staticLLocationScheduler = Executors.newSingleThreadScheduledExecutor();
        staticLLocationScheduler.scheduleAtFixedRate
                (new Runnable() {
                    @Override
                    public void run() {
                        try {

                            Location currentLocation = Utils.getLastKnownLocation(MonitorForegroundService.this.getApplicationContext());

                            // If no motion since last check, update the timestamp in the cloud DB, else we are
                            // moving so no need to do anything, as onLocationChangedListener would update it.
                            if (null != lastKnownLocation && null != currentLocation && (Utils.isFakeLocation(lastKnownLocation) || currentLocation.distanceTo(lastKnownLocation) < 50.0)) {
                                setLastKnownLocation(currentLocation);
                                UsersCache.publishUserLocation(
                                        MonitorForegroundService.this.getApplicationContext(),
                                        myId,
                                        currentLocation,
                                        5 * LOCATION_SAMPLE_RATE_MINUTES * 60
                                );
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Could not update rider's cache for rider ID " + myId + " in standstill mode due to: " + e.getMessage());
                        }
                    }
                }, 0, LOCATION_SAMPLE_RATE_MINUTES, TimeUnit.MINUTES);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mLocationManager != null) {
            try {
                mLocationManager.removeUpdates(mLocationListener);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove location listeners." + e);
            }
        }

        // Clear all cached data
        staticLLocationScheduler.shutdown();
    }

    public void startTracking() {
        // Location
        if (mLocationManager == null) {
            mLocationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        }
        mLocationListener = new LocationListener(this, LocationManager.GPS_PROVIDER);

        try {
            // We MUST set the min time for location updates otherwise we won't get any
            // we set it to 10 seconds which is about 300 meters when traveling at 100 km/h
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_MILLISECONDS, MIN_METERS, mLocationListener);

        } catch (SecurityException ex) {
            Log.i(TAG, "Failed to request location update, ignore" + ex);
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "GPS provider does not exist " + ex.getMessage());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private Notification getNotification() {

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Hey Channel", NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);

        Intent roueActivityIntent = new Intent(this, MainActivity.class);
        roueActivityIntent.setAction(REOPEN_ACTIVITY_ACTION);
        roueActivityIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingRoueActivityIntent = PendingIntent.getActivity(getApplicationContext(), 0, roueActivityIntent, 0);

        Intent intentExit = new Intent(this, MainActivity.class);
        intentExit.setAction(EXIT_APP_ACTION);
        intentExit.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingExitIntent = PendingIntent.getActivity(getApplicationContext(), 0, intentExit, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(getString(R.string.notification_title))
                .setContentIntent(pendingRoueActivityIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getResources().getString(R.string.exit_app), pendingExitIntent)
                .setAutoCancel(true);
        return builder.build();
    }

    public class LocationServiceBinder extends Binder {
        public MonitorForegroundService getService() {
            return MonitorForegroundService.this;
        }
    }
}
