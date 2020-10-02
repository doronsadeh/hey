package com.colderlazarus.hey.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.colderlazarus.rain.AdvSettingsActivity;
import com.colderlazarus.rain.MapsActivity;
import com.colderlazarus.rain.R;
import com.colderlazarus.rain.RoutePlanningActivity;
import com.colderlazarus.rain.apis.aws.dynamodb.Provider;
import com.colderlazarus.rain.apis.aws.dynamodb.RidersCache;
import com.colderlazarus.rain.apis.aws.dynamodb.Users;
import com.colderlazarus.rain.apis.aws.dynamodb.models.Rider;
import com.colderlazarus.rain.apis.directionsapi.RoutePlanner;
import com.colderlazarus.rain.commands.GCPSpeechRecognition;
import com.colderlazarus.rain.commands.HeadsetButtonsManager;
import com.colderlazarus.rain.config.Configuration;
import com.colderlazarus.rain.events.EventsCollector;
import com.colderlazarus.rain.notifiers.NotificationsManager;
import com.colderlazarus.rain.peers.sessions.SessionConsts;
import com.colderlazarus.rain.tts.SoundManager;
import com.colderlazarus.rain.utils.Log;
import com.colderlazarus.rain.utils.Utils;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.colderlazarus.rain.RoutePlanningActivity.END_TRIP_ACTION;
import static com.colderlazarus.rain.RoutePlanningActivity.EXIT_APP_ACTION;
import static com.colderlazarus.rain.RoutePlanningActivity.REOPEN_ACTIVITY_ACTION;
import static com.colderlazarus.rain.utils.Utils.fakeLocation;

public class MonitorForegroundService extends Service {

    private final String TAG = "MonitorForegroundService";

    public static final int MIN_MILLISECONDS = 1 * 1000;
    public static final float MIN_METERS = (float) 100.0;

    // The max amount of seconds a rider is allowed to be in distress notified state
    // before the state is re-set to undefined.
    private static final long DISTRESS_NOTIFIED_STATE_AGING_PERIOD_SEC = 5L * 60L;

    // The max amount of seconds a rider is allowed to be in distress responding state
    // before the state is re-set to undefined.
    private static final long DISTRESS_RESPONDING_STATE_AGING_PERIOD_SEC = 30L * 60L;

    private static final long LOCATION_SAMPLE_RATE_MINUTES = 1L;

    private final static long USER_STATE_AGING_MINUTES = 5L;

    // Number of meters between weather sampling
    public static final double WEATHER_SAMPLE_RATE_METERS = 1000.0;

    private FirebaseAnalytics mFirebaseAnalytics;

    public static Service monitorForegroundServiceContext = null;

    private static final String CHANNEL_ID = "LocationServiceForegroundServiceChannel";

    private static final int LOCATION_SERVICE_NOTIFICATION_ID = Utils.genIntUUID();

    public static final String NOTIFICATION_DELETE_ACTION = "com.colderlazarus.rain.MonitorForegroundService.NOTIFICATION_DELETE_ACTION";

    private static final Object finalDestinationSync = new Object();
    private static final Object lastKnownLocationSync = new Object();
    private static final Object lastKnownSpeedSync = new Object();
    private static final Object lastKnownBearingSync = new Object();

    private final LocationServiceBinder binder = new LocationServiceBinder();

    private HeadsetButtonsManager headsetButtonsManager;

    // Preferences
    private SharedPreferences sharedPreferences;

    private boolean rainDetection;
    private boolean shortTermRainForecast;
    private double rainProbabilityThreshold;
    private boolean windDetection;
    private double windVelocityKmh;
    private double crosswindVelocityKmh;
    private double windGustsVelocityKmh;
    private boolean lowTemperatureDetection;
    private double lowTemperatureThresholdC;

    public double distanceKm;

    public static RoutePlanner routePlanner = new RoutePlanner();

    private static float lastKnownSpeed = 0.0f;

    private static float lastKnownBearing = 0.0f;

    private static Location lastKnownLocation = null;

    private static Location finalDestination = null;

    // TODO disabling sensor manager to save battery till we need it
    // Sensors
    // private SensorManager sensorManager;

    // Providers
    public List<WeatherProvider> weatherProviders = new ArrayList<>();

    // Location tracking
    private LocationListener mLocationListener;
    private LocationManager mLocationManager;
    protected ArrayList<Location> lastLocations = new ArrayList<>();

    public Provider cachedProvider;

    private ScheduledExecutorService staticLLocationScheduler;

    private ScheduledExecutorService userStateAgingScheduler;

    private GCPSpeechRecognition gcpCloudSpeechRecognition;

    @Override
    public IBinder onBind(Intent intent) {

        Log.i(TAG, "Ridinrain foreground service onBind called");

        return binder;
    }

    public static Location getFinalDestination() {
        synchronized (finalDestinationSync) {
            return finalDestination;
        }
    }

    public static void setFinalDestination(Location _finalDestination) {
        synchronized (finalDestinationSync) {
            finalDestination = _finalDestination;
        }
    }

    public static Object getFinalDstSyncObject() {
        return finalDestinationSync;
    }

    public static float getLastKnownBearing() {
        synchronized (lastKnownBearingSync) {
            return lastKnownBearing;
        }
    }

    public static void setLastKnownBearing(float currentBearing) {
        synchronized (lastKnownBearingSync) {
            lastKnownBearing = currentBearing;
        }
    }

    public static float getLastKnownSpeed() {
        synchronized (lastKnownSpeedSync) {
            return lastKnownSpeed;
        }
    }

    public static void setLastKnownSpeed(float currentSpeed) {
        synchronized (lastKnownSpeedSync) {
            lastKnownSpeed = currentSpeed;
        }
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

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener = (sharedPreferences, key) -> {
        if (key.equals("alert_distance_km")) {
            distanceKm = AdvSettingsActivity.getAlertRangeKm(getApplicationContext());
        } else if (key.equals("rain")) {
            rainDetection = sharedPreferences.getBoolean("rain", Configuration.rainDetectionDefault);
        } else if (key.equals("short_term_forecast")) {
            shortTermRainForecast = sharedPreferences.getBoolean("short_term_forecast", Configuration.shortTermRainForecastDefault);
        } else if (key.equals("short_term_forecast_min_rain_probability")) {
            rainProbabilityThreshold = sharedPreferences.getInt("short_term_forecast_min_rain_probability", Configuration.rainProbabilityThresholdDefault) / 100.0;
        } else if (key.equals("wind")) {
            windDetection = sharedPreferences.getBoolean("wind", Configuration.windDetectionDefault);
        } else if (key.equals("wind_velocity_threshold_kmh")) {
            windVelocityKmh = Utils.Ms2Kmh(AdvSettingsActivity.getWindVelocityMs(getApplicationContext()));
        } else if (key.equals("crosswind_velocity_threshold_kmh")) {
            crosswindVelocityKmh = Utils.Ms2Kmh(AdvSettingsActivity.getCrossWindVelocityMs(getApplicationContext()));
        } else if (key.equals("wind_gust_velocity_threshold_kmh")) {
            windGustsVelocityKmh = Utils.Ms2Kmh(AdvSettingsActivity.getWindGustsVelocityMs(getApplicationContext()));
        } else if (key.equals("low_temp")) {
            lowTemperatureDetection = sharedPreferences.getBoolean("low_temp", Configuration.lowTemperatureDetectionDefault);
        } else if (key.equals("low_temp_threshold_c")) {
            lowTemperatureThresholdC = AdvSettingsActivity.getTemperatureC(getApplicationContext());
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        registerReceiver(mNotificationReceiver, new IntentFilter(NOTIFICATION_DELETE_ACTION));

        // Moving this code to a handler to avoid first time load delay
        Handler h = new Handler();
        Runnable r = () -> {
            Log.i(TAG, "Rider360 foreground service onStart called");

            PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).registerOnSharedPreferenceChangeListener(mPrefsListener);

            // Get current location and Update map activity, and DB
            lastKnownLocation = Utils.getLastKnownLocation(getApplicationContext());

            if (null == lastKnownLocation)
                lastKnownLocation = fakeLocation();

            Rider currentUserData = Users.getUser(getApplicationContext(), Utils.identity(getApplicationContext()));
            if (null == currentUserData)
                currentUserData = Rider.build(getApplicationContext(), Rider.RiderState.UNDEFINED, SessionConsts.NO_SESSION);

            Users.setUser(getApplicationContext(), null, currentUserData);

            Intent mapIIntent = new Intent(MapsActivity.MAP_ACTIVITY_INTENT_FILTER);
            mapIIntent.putExtra(MapsActivity.DISTANCE_KM, distanceKm);
            getApplicationContext().sendBroadcast(mapIIntent);

            String myId = Utils.identity(getApplicationContext());
            try {
                if (null != lastKnownLocation) {
                    RidersCache.publishRiderToCache(
                            getApplicationContext(),
                            myId,
                            lastKnownLocation.getBearing(),
                            lastKnownLocation.getSpeed(),
                            lastKnownLocation,
                            lastKnownLocation,
                            60
                    );
                }
            } catch (IOException e) {
                Log.w(TAG, String.format("Could not publish rider location to cache for: %s, due to: %s", myId, e.getMessage()));
            }

            startTracking();
        };

        h.post(r);

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Ridinrain foreground service onCreate called");

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        SoundManager.init(getApplicationContext());

        // Start the broadcast receiver listening to headset events
        headsetButtonsManager = new HeadsetButtonsManager(this);

        startForeground(LOCATION_SERVICE_NOTIFICATION_ID, getNotification());

        monitorForegroundServiceContext = this;

        String myId = Utils.identity(getApplicationContext());
        try {
            lastKnownLocation = Utils.getLastKnownLocation(this);
            if (null != lastKnownLocation) {
                RidersCache.publishRiderToCache(
                        getApplicationContext(),
                        myId,
                        lastKnownLocation.getBearing(),
                        lastKnownLocation.getSpeed(),
                        lastKnownLocation,
                        lastKnownLocation,
                        60
                );
            }
        } catch (IOException e) {
            Log.w(TAG, String.format("Could not publish rider location to cache for: %s, due to: %s", myId, e.getMessage()));
        }

        // Schedule location sampling when not moving
        staticLLocationScheduler = Executors.newSingleThreadScheduledExecutor();
        staticLLocationScheduler.scheduleAtFixedRate
                (() -> {
                    try {

                        Location currentLocation = Utils.getLastKnownLocation(getApplicationContext());

                        // If no motion since last check, update the timestamp in the cloud DB, else we are
                        // moving so no need to do anything, as onLocationChangedListener would update it.
                        if (null != lastKnownLocation && null != currentLocation && (Utils.isFakeLocation(lastKnownLocation) || currentLocation.distanceTo(lastKnownLocation) < 50.0)) {
                            setLastKnownLocation(currentLocation);
                            RidersCache.publishRiderToCache(
                                    getApplicationContext(),
                                    myId,
                                    currentLocation.getBearing(),
                                    currentLocation.getSpeed(),
                                    currentLocation,
                                    MonitorForegroundService.getFinalDestination(),
                                    5 * LOCATION_SAMPLE_RATE_MINUTES * 60
                            );

                            // Check rider state aging (ridrz_users table), and clear too old statuses
                            // that were probably caused by abrupt app shutdowns.
                            Rider _rider = Users.getUser(getApplicationContext(), myId);
                            if (null != _rider && !_rider.state.equalsIgnoreCase(Rider.RiderState.UNDEFINED.valueOf())) {
                                long _now = Utils.nowSec();
                                boolean aged = false;
                                if (_rider.state.equalsIgnoreCase(Rider.RiderState.NOTIFIED_ABOUT_DISTRESS.valueOf())) {
                                    aged = (_now - _rider.timestamp) > DISTRESS_NOTIFIED_STATE_AGING_PERIOD_SEC;
                                } else if (_rider.state.equalsIgnoreCase(Rider.RiderState.RESPONDING_TO_DISTRESS.valueOf())) {
                                    aged = (_now - _rider.timestamp) > DISTRESS_RESPONDING_STATE_AGING_PERIOD_SEC;
                                }

                                // If state too old, clear it.
                                if (aged) {
                                    Users.setUser(getApplicationContext(), null, Rider.build(getApplicationContext(), Rider.RiderState.UNDEFINED, SessionConsts.NO_SESSION));
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Could not update rider's cache for rider ID " + myId + " in standstill mode due to: " + e.getMessage());
                    }
                }, 0, LOCATION_SAMPLE_RATE_MINUTES, TimeUnit.MINUTES);

        // Schedule user state aging
        userStateAgingScheduler = Executors.newSingleThreadScheduledExecutor();
        userStateAgingScheduler.scheduleAtFixedRate
                (() -> {
                    try {
                        // Calling getUser will check the state, and if too old, it will
                        // clean it up according to the state type
                        Users.getUser(getApplicationContext(), Utils.identity(getApplicationContext()));
                    } catch (Exception e) {
                        Log.e(TAG, "Could not age user state rider ID " + myId + " due to: " + e.getMessage());
                    }
                }, 0, USER_STATE_AGING_MINUTES, TimeUnit.MINUTES);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        try {
            unregisterReceiver(mNotificationReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Trying to unregister a non-registered receiver [notification receiver]");
        }

        Log.i(TAG, "Ridinrain foreground service onDestroy called");

        try {
            PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(mPrefsListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove preferences listener." + e);
        }

        if (mLocationManager != null) {
            try {
                mLocationManager.removeUpdates(mLocationListener);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove location listeners." + e);
            }
        }

        // Clear all cached data
        EventsCollector.clear();
        NotificationsManager.clear(this);
        staticLLocationScheduler.shutdown();
        userStateAgingScheduler.shutdown();
    }

    public void startTracking() {

        // Update values set in preferences
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Update prefs. for the first time (later via listener)
        distanceKm = (double) AdvSettingsActivity.getAlertRangeKm(getApplicationContext());
        rainDetection = sharedPreferences.getBoolean("rain", Configuration.rainDetectionDefault);
        shortTermRainForecast = sharedPreferences.getBoolean("short_term_forecast", Configuration.shortTermRainForecastDefault);
        rainProbabilityThreshold = sharedPreferences.getInt("short_term_forecast_min_rain_probability", Configuration.rainProbabilityThresholdDefault) / 100.0;
        windDetection = sharedPreferences.getBoolean("wind", Configuration.windDetectionDefault);
        windVelocityKmh = Utils.Ms2Kmh(AdvSettingsActivity.getWindVelocityMs(getApplicationContext()));
        crosswindVelocityKmh = Utils.Ms2Kmh(AdvSettingsActivity.getCrossWindVelocityMs(getApplicationContext()));
        windGustsVelocityKmh = Utils.Ms2Kmh(AdvSettingsActivity.getWindGustsVelocityMs(getApplicationContext()));
        lowTemperatureDetection = sharedPreferences.getBoolean("low_temp", Configuration.lowTemperatureDetectionDefault);
        lowTemperatureThresholdC = AdvSettingsActivity.getTemperatureC(getApplicationContext());

        // Providers
        cachedProvider = new com.colderlazarus.rain.apis.aws.dynamodb.Provider((this));
        weatherProviders.add(new com.colderlazarus.rain.apis.ims.Provider(this));
        weatherProviders.add(new com.colderlazarus.rain.apis.climacell.Provider(this));
        weatherProviders.add(new com.colderlazarus.rain.apis.rainviewer.Provider(this));
        weatherProviders.add(new com.colderlazarus.rain.apis.weatherstack.Provider(this));
        EventsCollector.setProviders(weatherProviders);

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

    private Notification getNotification() {

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Rider360 Channel", NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        Objects.requireNonNull(notificationManager).createNotificationChannel(channel);

        // TODO need to find out why it sends a kill even though we asked for: false
        Intent roueActivityIntent = new Intent(this, RoutePlanningActivity.class);
        roueActivityIntent.setAction(REOPEN_ACTIVITY_ACTION);
        roueActivityIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingRoueActivityIntent = PendingIntent.getActivity(getApplicationContext(), 0, roueActivityIntent, 0);

        Intent intentKill = new Intent(this, RoutePlanningActivity.class);
        intentKill.setAction(END_TRIP_ACTION);
        intentKill.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingStopIntent = PendingIntent.getActivity(getApplicationContext(), 0, intentKill, 0);

        Intent intentExit = new Intent(this, RoutePlanningActivity.class);
        intentExit.setAction(EXIT_APP_ACTION);
        intentExit.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingExitIntent = PendingIntent.getActivity(getApplicationContext(), 0, intentExit, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(R.mipmap.sports)
                .setContentTitle(getString(R.string.notification_title))
                .setContentIntent(pendingRoueActivityIntent)
                // Note in newer androids the icon will not show, but it is still required
                .addAction(R.drawable.stop_red, getResources().getString(R.string.end_ride_notification_button), pendingStopIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getResources().getString(R.string.exit_app), pendingExitIntent)
                .setAutoCancel(true);
        return builder.build();
    }

    public class LocationServiceBinder extends Binder {
        public MonitorForegroundService getService() {
            return MonitorForegroundService.this;
        }
    }

    public BroadcastReceiver mNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null || !action.equals(NOTIFICATION_DELETE_ACTION)) {
                return;
            }

            // Clean my state
            Users.setUser(context, null, Rider.build(context, Rider.RiderState.UNDEFINED, SessionConsts.NO_SESSION));
        }
    };

}
