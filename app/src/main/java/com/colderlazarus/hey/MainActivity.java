package com.colderlazarus.hey;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.colderlazarus.hey.services.FCMAdapter;
import com.colderlazarus.hey.utils.Utils;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.iid.FirebaseInstanceId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "hey.MainActivity";

    private FirebaseAnalytics mFirebaseAnalytics;

    private boolean looperPrepared = false;

    // TODO do we want to move this to the service?
    // Each time the app is started from scratch we get a new identity
    // we don't care if it's different fom last time as it only serves
    // to send FCM messages, and keep track of transient location
    public static String randomRecyclingIdentity = Utils.genStringUUID();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        Activity aContext = this;
        FirebaseInstanceId.getInstance().getInstanceId()
                .addOnFailureListener(command -> {
                    Log.e(TAG, "Failed registering Firebase: " + command);

                    if (command.getMessage().equals("MISSING_INSTANCEID_SERVICE")) {
                        Toast.makeText(getApplicationContext(), R.string.monitor_service_is_not_running, Toast.LENGTH_LONG).show();
                        startActivity(new Intent(aContext, CannotRunOnDeviceActivity.class));
                    } else {
                        Toast.makeText(getApplicationContext(), R.string.not_compatiable_with_your_device, Toast.LENGTH_LONG).show();
                    }

                    aContext.finish();
                    return;
                })
                .addOnSuccessListener(this, instanceIdResult -> {
                    String newToken = instanceIdResult.getToken();
                    getPreferences(Context.MODE_PRIVATE).edit().putString("fb", newToken).apply();

                    Utils.sendAnalytics(mFirebaseAnalytics, "new_token", "route_planning", "analytics");

                    // Test the token, and if fails DeleteInstanceId and restart
                    List<String> _tokenAsList = new ArrayList<>();
                    _tokenAsList.add(newToken);
                    if (!FCMAdapter.verifyFCMLink(_tokenAsList)) {

                        Toast.makeText(getApplicationContext(), R.string.your_network_connection_is_unstable, Toast.LENGTH_LONG).show();

                        // Delete the instance ID on firebase, AND the DB user. It will be recreated when we restart
                        Activity _this = this;
                        new Thread(() -> {
                            if (!looperPrepared) {
                                looperPrepared = true;
                                Looper.prepare();
                            }

                            try {
                                String myId = Utils.identity(this);
                                FirebaseInstanceId.getInstance().deleteInstanceId();
                                Users.deleteUser(_this, myId);
                                _this.finish();
                            } catch (IOException e) {
                                Log.e(TAG, "Could not delete instance ID!");
                                Toast.makeText(getApplicationContext(), R.string.transient_ntwork_error, Toast.LENGTH_LONG).show();
                            }
                        }).start();
                    } else {

                        // Save the new token in the cloud DB as well as the local Sqlite
                        Rider userCurrentData = Users.getUser(this, Utils.identity(this));

                        if (null == userCurrentData)
                            userCurrentData = Rider.build(this, Rider.RiderState.UNDEFINED, SessionConsts.NO_SESSION);

                        Users.setUser(this, newToken, userCurrentData);

                        // Start the monitor service (NOTE! we must start the service BEFORE the cachedToken tries to access it)
                        startServices();

                        NotificationsManager.init(getApplicationContext());

                        toolbar = findViewById(R.id.toolbar);
                        toolbar.setTitle("");
                        setSupportActionBar(toolbar);

                        // Init the tool bar buttons
                        routePlanningActivityControl = new RoutePlanningActivityControl(this);

                        String myId = Utils.identity(this);

                        try {
                            Location _lastKnownLocation = getLastKnownLocation(this);
                            if (null != _lastKnownLocation) {
                                RidersCache.publishRiderToCache(
                                        getApplicationContext(),
                                        myId,
                                        _lastKnownLocation.getBearing(),
                                        _lastKnownLocation.getSpeed(),
                                        _lastKnownLocation,
                                        _lastKnownLocation,
                                        -1L);
                            }
                        } catch (IOException e) {
                            Log.w(TAG, String.format("Could not publish rider location to cache for: %s, due to: %s", myId, e.getMessage()));
                        }

                        // Validate subscription in DB, and if no subscription AND subscription based features are enabled,
                        // suggest to re-subscribe else just turn them off
                        Subscription subscription = Subscriptions.getSubscription(this, myId);
                        if (null != subscription) {
                            // Subscription exists (may have expired)
                            if (!Subscriptions.validateSubscription(subscription)) {
                                // Subscription expired, not purchased, etc.
                                if (Subscriptions.proFeaturesActive(this)) {
                                    // Turn off pro features, and notify user he may resubscribe
                                    Subscriptions.turnOffProFeaturesAndSuggestResubscribe(this);
                                }
                                // Else: subscription is off in DB, and no pro feature currently active, do nothing.
                            }
                            // Else: subscription is valid, all is well
                        } else {
                            // No subscription, make sure all pro features are off (if they were on)
                            if (Subscriptions.proFeaturesActive(this)) {
                                // Turn off pro features, and notify user he may resubscribe
                                Subscriptions.turnOffProFeaturesAndSuggestResubscribe(this);
                            }
                        }

                        Utils.sendAnalytics(mFirebaseAnalytics, "started", "route_planning", "analytics");

                        // TODO DEBUG
                        // responseActivityDebugFunction();
                    }
                });

    }
}