package com.colderlazarus.hey.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.colderlazarus.hey.MainActivity;
import com.colderlazarus.hey.R;
import com.colderlazarus.hey.dynamodb.UsersCache;
import com.colderlazarus.hey.dynamodb.models.User;
import com.colderlazarus.hey.dynamodb.models.Users;
import com.colderlazarus.hey.utils.Utils;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.colderlazarus.hey.utils.Utils.fakeLocation;

public class MonitorForegroundService extends Service {

    private final String TAG = "hey.MonitorService";

    public static final int MIN_MILLISECONDS = 60 * 1000;
    public static final float MIN_METERS = (float) 100.0;

    private static final String REOPEN_ACTIVITY_ACTION = "hey.REOPEN_ACTIVITY_ACTION";
    private static final String EXIT_APP_ACTION = "hey.EXIT_APP_ACTION";

    private static final long LOCATION_SAMPLE_RATE_SEC = 60L;

    private FirebaseAnalytics mFirebaseAnalytics;

    public static Service monitorForegroundServiceContext = null;

    private static final String CHANNEL_ID = "LocationServiceForegroundServiceChannel";

    private static final int LOCATION_SERVICE_NOTIFICATION_ID = Utils.genIntUUID();

    private static final Object lastKnownLocationSync = new Object();

    private static final Object lastTimeHailedSync = new Object();

    private final LocationServiceBinder binder = new LocationServiceBinder();

    // Each time the app is started from scratch we get a new identity
    // we don't care if it's different fom last time as it only serves
    // to send FCM messages, and keep track of transient location
    public static String randomRecyclingIdentity = null;

    public static int radiusMeters = 1000;

    private static Location lastKnownLocation = null;

    private static Long lastTimeIWasHailed = null;

    public static Context appContext = null;

    // Location tracking
    private LocationListener mLocationListener;
    private LocationManager mLocationManager;

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

    public static long getLastTimeHailed() {
        synchronized (lastTimeHailedSync) {
            if (null == lastTimeIWasHailed) {
                if (null != appContext) {
                    User _user = Users.getUser(appContext, Utils.identity(appContext));
                    if (null != _user) {
                        lastTimeIWasHailed = _user.lastHailedAt;
                    } else {
                        lastTimeIWasHailed = 0L;
                    }
                } else {
                    lastTimeIWasHailed = 0L;
                }
            }

            return lastTimeIWasHailed;
        }
    }

    public static void setLastTimeHailed(long hailedAt) {
        synchronized (lastTimeHailedSync) {
            lastTimeIWasHailed = hailedAt;
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

        appContext = getApplicationContext();

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

                            if (null != lastKnownLocation && null != currentLocation && (Utils.isFakeLocation(lastKnownLocation) || currentLocation.distanceTo(lastKnownLocation) < (1.5 * MIN_METERS))) {
                                setLastKnownLocation(currentLocation);
                                UsersCache.publishUserLocation(
                                        MonitorForegroundService.this.getApplicationContext(),
                                        myId,
                                        currentLocation,
                                        4 * LOCATION_SAMPLE_RATE_SEC
                                );

                                mLocationListener.hailUsersInRange(getApplicationContext(), false);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Could not update rider's cache for rider ID " + myId + " in standstill mode due to: " + e.getMessage());
                        }
                    }
                }, 0, LOCATION_SAMPLE_RATE_SEC, TimeUnit.SECONDS);

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

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            if (sharedPreferences.getBoolean(MainActivity.HEY_IS_HAILING, false)) {
                // Make sure the number of users in area is up to date, if we are in hail mode
                mLocationListener.hailUsersInRange(getApplicationContext(), true);
            }

        } catch (SecurityException ex) {
            Log.i(TAG, "Failed to request location update, ignore" + ex);
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "GPS provider does not exist " + ex.getMessage());
        }
    }

    private Notification getNotification() {
        NotificationChannel channel = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            channel = new NotificationChannel(CHANNEL_ID, "Rider360 Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            Objects.requireNonNull(notificationManager).createNotificationChannel(channel);
        }

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
