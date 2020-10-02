package com.colderlazarus.hey.services;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.colderlazarus.hey.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

public class LocationListener implements android.location.LocationListener {
    // We compute bearing according to the 50m steps during the last kilometer
    public static final float LOCATION_HISTORY_RESOLUTION_METERS = MonitorForegroundService.MIN_METERS;
    private static final int MAX_LOCATION_HISTORY = 10;
    private static final float MIN_DISTANCE_TO_START_PREDICTING_METERS = MonitorForegroundService.MIN_METERS;

    public static final double HAZARD_ALERT_DISTANCE_METERS = 1000.0;

    private final MonitorForegroundService monitorForegroundService;
    private final String TAG = "LocationListener";
    private Location mLastLocation = null;

    private boolean suspendingLocationListener = false;

    private double currentSpeed = -1.0;

    protected Location lastWeatherSamplePoint = null;

    LocationListener(MonitorForegroundService monitorForegroundService, String provider) {
        this.monitorForegroundService = monitorForegroundService;
        mLastLocation = new Location(provider);
    }

    private void storeLocationHistory(Location location) {
        monitorForegroundService.lastLocations.add(location);
        if (monitorForegroundService.lastLocations.size() > MAX_LOCATION_HISTORY) {
            monitorForegroundService.lastLocations.remove(0);
        }

        Log.i(TAG, "LocationChanged (last locations size): " + monitorForegroundService.lastLocations.size());
    }

    private double getCurrentSpeed() {
        double distanceTraveledMeters = monitorForegroundService.lastLocations.get(monitorForegroundService.lastLocations.size() - 1).distanceTo(monitorForegroundService.lastLocations.get(0));
        double timeTraveledSecs = (monitorForegroundService.lastLocations.get(monitorForegroundService.lastLocations.size() - 1).getElapsedRealtimeNanos() - monitorForegroundService.lastLocations.get(0).getElapsedRealtimeNanos()) / 1.0e9;

        if (timeTraveledSecs == 0.0)
            return 0.0;

        return distanceTraveledMeters / timeTraveledSecs;
    }

    @Override
    public void onLocationChanged(Location location) {

        Context _ctx = monitorForegroundService.getApplicationContext();
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

        if (null != MonitorForegroundService.getFinalDestination())
            Log.d(TAG, String.valueOf(location.distanceTo(MonitorForegroundService.getFinalDestination())));

        // Check if we have arrived at destination, if so end trip, and/or distress response if any in flight
        // TODO do we have a "final destination" on distress response after we nav to?
        if (null != MonitorForegroundService.getFinalDestination() && location.distanceTo(MonitorForegroundService.getFinalDestination()) < 150.0) {

            Context _lctx = monitorForegroundService.getApplicationContext();

            // Notify
            AudioNotifications.getInstance(_lctx).speakMessage(_lctx, "You have arrived");

            // Kill the trip (if any)
            Intent intent = new Intent(_lctx, RoutePlanningActivity.class);
            intent.setAction(END_TRIP_ACTION);
            intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
            _lctx.startActivity(intent);

            // Kill response to distress (if any)
            Intent killDistressResponseIntent = new Intent(DISTRESS_MESSAGE_BOX_INTENT_FILTER);
            killDistressResponseIntent.putExtra(DISTRESS_RESPONSE_MESSAGE_EXTRA, KILL_RESPONSE_ACTIVITY);
            _lctx.sendBroadcast(killDistressResponseIntent);

            // Show an activity with "You have Arrived"
            Intent intentUHA = new Intent(_lctx, YouHaveArrivedActivity.class);
            intentUHA.setFlags(FLAG_ACTIVITY_NEW_TASK);
            _lctx.startActivity(intentUHA);

            // Disable further messages
            MonitorForegroundService.setFinalDestination(null);
        }

        // Invoke a map update
        Intent umIntent = new Intent(MapsActivity.MAP_ACTIVITY_INTENT_FILTER);
        umIntent.putExtra(MapsActivity.DISTANCE_KM, monitorForegroundService.distanceKm);
        monitorForegroundService.getApplicationContext().sendBroadcast(umIntent);

        Handler hOnLocWork = new Handler();
        Runnable rOnLocRunnable = () -> {
            String myId = Utils.identity(_ctx);
            try {
                // If not set yet (before trip start), we defer to our current location
                Location finalDst = MonitorForegroundService.getFinalDestination();
                if (null == finalDst) {
                    finalDst = location;
                }

                if (null != location) {
                    RidersCache.publishRiderToCache(
                            monitorForegroundService.getApplicationContext(),
                            myId,
                            location.getBearing(),
                            location.getSpeed(),
                            location,
                            finalDst,
                            -1L
                    );
                }

            } catch (IOException e) {
                Log.w(TAG, String.format("Could not publish rider location to cache for: %s, due to: %s", myId, e.getMessage()));
            }

            // If not enough data points yet, store and bail
            if (null == monitorForegroundService.lastLocations || monitorForegroundService.lastLocations.size() < 2) {
                storeLocationHistory(location);
                return;
            }

            // If not enough meters traveled, do NOT store, and bail. We'll wait for next sample.
            if (monitorForegroundService.lastLocations.get(monitorForegroundService.lastLocations.size() - 1).distanceTo(location) < LOCATION_HISTORY_RESOLUTION_METERS) {
                return;
            }

            // Store new data point
            storeLocationHistory(location);

            // This speed (speed of traveling towards bearing, vs. true ground speed) shows how fast
            // the rider is approaching his next predicted point, so it actually may be better than using
            // accelerators to measure ground speed.

            if (location.hasSpeed())
                currentSpeed = location.getSpeed();
            else
                currentSpeed = getCurrentSpeed();

            if (location.hasBearing())
                MonitorForegroundService.setLastKnownBearing(location.getBearing());
            else
                MonitorForegroundService.setLastKnownBearing(0.0f);

            // Ref. to current location
            mLastLocation = location;

            MonitorForegroundService.setLastKnownLocation(mLastLocation);
            MonitorForegroundService.setLastKnownSpeed((float) currentSpeed);

            Rider user = Users.getUser(monitorForegroundService.getApplicationContext(), myId);

            if (null != user) {

                if (user.state.equals(Rider.RiderState.RESPONDING_TO_INVITE.valueOf()) ||
                        user.state.equals(Rider.RiderState.NOTIFIED_ABOUT_INVITE.valueOf())) {
                    // Keep the state, and update the location
                    Users.setUser(
                            monitorForegroundService.getApplicationContext(),
                            null,
                            Rider.build(
                                    monitorForegroundService.getApplicationContext(),
                                    Rider.RiderState.fromString(user.state),
                                    SessionConsts.NO_SESSION));
                } else if (user.state.equals(Rider.RiderState.RESPONDING_TO_DISTRESS.valueOf()) ||
                        user.state.equals(Rider.RiderState.NOTIFIED_ABOUT_DISTRESS.valueOf())) {

                    // If we are in either of the above distress-oriented states we need to find out if we are:
                    // - Arrived at distress location -> return to undefined
                    // - Riding away from distress -> return to undefined state
                    // - Riding towards distress -> keep it (do nothing about the state)

                    // Compute distance from last notified/responded-to distress, and the time that passed since then
                    double distanceToLastKnownDistressMeters = mLastLocation.distanceTo(user.location);
                    long timeSinceLAstKnownDistressSec = Utils.nowSec() - user.timestamp;

                    Log.d(TAG, "---> Distance to destination: " + distanceToLastKnownDistressMeters);

                    // If we have arrived ...
                    if (distanceToLastKnownDistressMeters <= 50.0) {
                        // Clear DB state and state info
                        Users.setUser(monitorForegroundService.getApplicationContext(), null, Rider.build(monitorForegroundService.getApplicationContext(), UNDEFINED, SessionConsts.NO_SESSION));
                    }
                    // If we are either too far away, or too long from distress location, we reset the status.
                    // This also takes care of stale states due to crashes, DB out of sync etc.
                    else if (distanceToLastKnownDistressMeters > (MAX_ALLOWED_DISTANCE_TO_DISTRESS_METERS * 2) ||
                            timeSinceLAstKnownDistressSec > MAX_ALLOWED_DISTRESS_DELTA_SEC) {

                        if (user.state.equals(Rider.RiderState.RESPONDING_TO_DISTRESS.valueOf())) {
                            // If you confirmed you are coming, but going away, or did not arrive in due time
                            // we'll notify you we are canceling your confirmation
                            AudioNotifications.getInstance(monitorForegroundService.getApplicationContext()).speakMessage(monitorForegroundService.getApplicationContext(), "It seems you are not riding to the distress location. I'll cancel and notify the others.");
                        }

                        // Clear DB state and state info
                        Users.setUser(monitorForegroundService.getApplicationContext(), null, Rider.build(monitorForegroundService.getApplicationContext(), UNDEFINED, SessionConsts.NO_SESSION));
                    }
                }
            }


            //
            // --- Here we call all the APIs and predictors. This is the trigger for all detections. ---
            //
            synchronized (MonitorForegroundService.getFinalDstSyncObject()) {

                if (null != MonitorForegroundService.getFinalDestination() &&
                        (monitorForegroundService.lastLocations.size() >= 2 && monitorForegroundService.lastLocations.get(0).distanceTo(monitorForegroundService.lastLocations.get(monitorForegroundService.lastLocations.size() - 1)) >= MIN_DISTANCE_TO_START_PREDICTING_METERS)
                ) {

                    // Compute locations to look for weather conditions, and the ones for hazards
                    Location predictedLocation = new Location(location);
                    Location hazardsLookupLocation = null;
                    if (currentSpeed > 0.0) {
                        // We are wasting a little bit of CPU re-planning route here on top of the one
                        // planned in RoutePlanningActivity. This is due to the fact that passing the
                        // object to the service is more complicated than simply re-doing the work.

                        if (null != MonitorForegroundService.getFinalDestination()) {
                            // Need to call the planner here, if not called in the past in order to initialize its
                            // final destination. The final destination is later read by the predictor.
                            if (!MonitorForegroundService.routePlanner.hasValidRoute())
                                MonitorForegroundService.routePlanner.planRoute(monitorForegroundService.getApplicationContext(), location, MonitorForegroundService.getFinalDestination());

                            predictedLocation = Router.predictNextLocation(
                                    monitorForegroundService.getApplicationContext(),
                                    MonitorForegroundService.routePlanner,
                                    mLastLocation,
                                    monitorForegroundService.lastLocations,
                                    monitorForegroundService.distanceKm * 1000.0);

                            // We always look for hazards in the next 1km. This distance is fixed.
                            hazardsLookupLocation = Router.predictNextLocation(
                                    monitorForegroundService.getApplicationContext(),
                                    MonitorForegroundService.routePlanner,
                                    mLastLocation,
                                    monitorForegroundService.lastLocations,
                                    HAZARD_ALERT_DISTANCE_METERS);
                        }
                    }

                    // Check if any hazard lys ahead, and report it
                    if (null != hazardsLookupLocation) {
                        Collection<HazardEvent> hazards = Hazards.getInstance().getHazardsAt(_ctx, hazardsLookupLocation);
                        if (null != hazards && hazards.size() > 0) {
                            EventsCollector.collectHazardEvents(_ctx, hazards);
                        }
                    }

                    // If minimal sampling distance traveled call all providers, each computing its events
                    // combined and notified in the events collector
                    if (null == lastWeatherSamplePoint || lastWeatherSamplePoint.distanceTo(mLastLocation) >= MonitorForegroundService.WEATHER_SAMPLE_RATE_METERS) {

                        // Copy the curent location to the last location
                        lastWeatherSamplePoint = new Location(mLastLocation);

                        // Generate a group id for the events collector to be able to identify them and process them
                        String groupId = UUID.randomUUID().toString();

                        List<WeatherEvent> allWeatherEvents = new ArrayList<>();

                        if (null != MonitorForegroundService.getFinalDestination() && null != predictedLocation) {
                            // First call cached provider! And only if not around call others
                            List<WeatherEvent> _cachedEvents = monitorForegroundService.cachedProvider.execute(
                                    MonitorForegroundService.monitorForegroundServiceContext,
                                    groupId,
                                    Router.currentBearing(monitorForegroundService.lastLocations),
                                    mLastLocation.distanceTo(predictedLocation),
                                    mLastLocation,
                                    predictedLocation,
                                    MonitorForegroundService.getFinalDestination(),
                                    // Look at a radius of 1Km, unless distanceKm is lower than that (unlikely, but code is there)
                                    Math.min(1000.0, monitorForegroundService.distanceKm * 1000.0 / 2.0));

                            if (null != _cachedEvents) {
                                //
                                // If we have a cached weather event, report it, else ...
                                //
                                allWeatherEvents.addAll(_cachedEvents);
                            } else {
                                //
                                // ... else, use the online providers
                                //
                                // Call all providers. Results would be async sent to events collector for processing.
                                for (WeatherProvider weatherProvider : monitorForegroundService.weatherProviders) {
                                    List<WeatherEvent> _wEvents = weatherProvider.execute(
                                            MonitorForegroundService.monitorForegroundServiceContext,
                                            groupId,
                                            Router.currentBearing(monitorForegroundService.lastLocations),
                                            mLastLocation.distanceTo(predictedLocation),
                                            mLastLocation,
                                            predictedLocation,
                                            MonitorForegroundService.getFinalDestination(),
                                            // Look at a radius of 1Km, unless distanceKm is lower than that (unlikely, but code is there)
                                            Math.min(1000.0, monitorForegroundService.distanceKm * 1000.0 / 2.0));

                                    // Collect all opinions from all providers to be later used for the DynamoDB cached weather point
                                    if (null != _wEvents)
                                        allWeatherEvents.addAll(_wEvents);
                                }
                            }
                        }

                        // Combine weathers and store in DD
                        WeatherEvent sampledWeatherPoint = EventsCollector.processEventsBatch(allWeatherEvents);

                        if (null != MonitorForegroundService.getFinalDestination() && null != sampledWeatherPoint) {
                            // Store in DynamoDB (only in mid trip, when finalDestination set to non-null)
                            try {
                                WeatherCache.publishWeatherPointToCache(
                                        monitorForegroundService.getApplicationContext(),
                                        myId,
                                        Router.currentBearing(monitorForegroundService.lastLocations) == null ? 0.0f : Router.currentBearing(monitorForegroundService.lastLocations).floatValue(),
                                        mLastLocation.getSpeed(),
                                        sampledWeatherPoint,
                                        mLastLocation,
                                        predictedLocation,
                                        MonitorForegroundService.getFinalDestination());
                            } catch (IOException e) {
                                Log.e(TAG, String.format("Failed to register weather point: %s", e.getMessage()));
                            }
                        }

                        // Now trigger a scan of the queued notifications
                        NotificationsManager.handleEvents(monitorForegroundService.getApplicationContext(), mLastLocation);

                        // Update map activity
                        Intent mapIntent = new Intent(MapsActivity.MAP_ACTIVITY_INTENT_FILTER);
                        mapIntent.putExtra(MapsActivity.DISTANCE_KM, monitorForegroundService.distanceKm);
                        mapIntent.putExtra(MapsActivity.PREDICTED_LOCATION, predictedLocation);

                        monitorForegroundService.getApplicationContext().sendBroadcast(mapIntent);
                    }
                }
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
