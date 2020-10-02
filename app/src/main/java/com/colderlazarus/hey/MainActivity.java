package com.colderlazarus.hey;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.colderlazarus.hey.dynamodb.UsersCache;
import com.colderlazarus.hey.dynamodb.models.User;
import com.colderlazarus.hey.dynamodb.models.Users;
import com.colderlazarus.hey.services.FCMAdapter;
import com.colderlazarus.hey.services.MonitorForegroundService;
import com.colderlazarus.hey.utils.Utils;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.iid.FirebaseInstanceId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "hey.MainActivity";

    private static final int TAG_CODE_MANDATORY_PERMISSIONS = Utils.genIntUUID();

    public static final String EXIT_APP_ACTION = "hey.EXIT_APP_ACTION";
    public static final String UPDATE_UI_ACTION = "hey.UPDATE_UI_ACTION";

    public static final String USER_IN_RANGE_EXTRA = "hey.USER_IN_RANGE_EXTRA";

    private FirebaseAnalytics mFirebaseAnalytics;

    private boolean looperPrepared = false;

    private void validatePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // We have permissions
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ActivityCompat.requestPermissions(this, new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.CALL_PHONE},
                        TAG_CODE_MANDATORY_PERMISSIONS);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.CALL_PHONE},
                        TAG_CODE_MANDATORY_PERMISSIONS);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        Utils.sendAnalytics(mFirebaseAnalytics, "main_activity_create", "main_activity", "analytics");

        validatePermissions();

        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (!Utils.checkNetworkAvailability(this)) {
            Utils.sendAnalytics(mFirebaseAnalytics, "network_off", "main_activity", "analytics");
            Toast.makeText(this, getString(R.string.no_network), Toast.LENGTH_LONG).show();
            stopServices();
            finish();
            return;
        }

        Intent intent = getIntent();

        String action = null;

        if (null != intent) {
            action = intent.getAction();
            if (null != action && action.equals(UPDATE_UI_ACTION)) {
                int numUsersInRange = intent.getIntExtra(USER_IN_RANGE_EXTRA, 0);
                ((TextView)findViewById(R.id.people_in_range)).setText(String.format(getString(R.string.users_in_range_format_string), numUsersInRange));
            }
        }

        if (null != action && action.equalsIgnoreCase(EXIT_APP_ACTION)) {
            Utils.sendAnalytics(mFirebaseAnalytics, "exit_app", "main_activity", "analytics");
            stopServices();
            finish();
            return;
        }

        if (null == MonitorForegroundService.randomRecyclingIdentity)
            MonitorForegroundService.randomRecyclingIdentity = Utils.genStringUUID();

        Activity aContext = this;
        FirebaseInstanceId.getInstance().getInstanceId()
                .addOnFailureListener(command -> {
                    Log.e(TAG, "Failed registering Firebase: " + command);

                    if (command.getMessage().equals("MISSING_INSTANCEID_SERVICE")) {
                        Toast.makeText(getApplicationContext(), R.string.monitor_service_is_not_running, Toast.LENGTH_LONG).show();
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
                                Toast.makeText(getApplicationContext(), R.string.transient_network_error, Toast.LENGTH_LONG).show();
                            }
                        }).start();
                    } else {
                        // Set listener
                        ((SeekBar) findViewById(R.id.lookup_range_seekbar)).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                            @Override
                            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                                MonitorForegroundService.radiusMeters = progress;
                                ((TextView) findViewById(R.id.hail_distance)).setText(String.format("%s %s", progress, getString(R.string.meters)));
                            }

                            @Override
                            public void onStartTrackingTouch(SeekBar seekBar) {

                            }

                            @Override
                            public void onStopTrackingTouch(SeekBar seekBar) {

                            }
                        });

                        // Set initial range
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            ((SeekBar) findViewById(R.id.lookup_range_seekbar)).setProgress(MonitorForegroundService.radiusMeters, true);
                        }

                        TextView explanationText = findViewById(R.id.explanation);

                        Animation anim = new AlphaAnimation(0.0f, 1.0f);
                        anim.setDuration(1500);
                        anim.setStartOffset(20);
                        anim.setRepeatMode(Animation.REVERSE);
                        anim.setRepeatCount(Animation.INFINITE);
                        explanationText.startAnimation(anim);

                        explanationText.setOnClickListener(v -> {
                            anim.cancel();
                            explanationText.setAlpha(1.0f);
                        });

                        // Save the new token in the cloud DB as well as the local Sqlite
                        User userCurrentData = Users.getUser(this, Utils.identity(this));

                        if (null == userCurrentData)
                            userCurrentData = User.build(this);

                        Users.setUser(this, newToken, userCurrentData);

                        // Start the monitor service (NOTE! we must start the service BEFORE the cachedToken tries to access it)
                        startServices();

                        String myId = Utils.identity(this);

                        Location _lastKnownLocation = Utils.getLastKnownLocation(this);
                        if (null != _lastKnownLocation) {
                            UsersCache.publishUserLocation(
                                    getApplicationContext(),
                                    myId,
                                    _lastKnownLocation,
                                    -1L);
                        }

                        Utils.sendAnalytics(mFirebaseAnalytics, "hey_started", "User", "analytics");
                    }
                });


    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent intent = getIntent();

        if (null != intent) {
            String action = intent.getAction();
            if (null != action && action.equals(UPDATE_UI_ACTION)) {
                String numUsersInRange = intent.getStringExtra(USER_IN_RANGE_EXTRA);
                ((TextView)findViewById(R.id.people_in_range)).setText(String.format(getString(R.string.users_in_range_format_string), numUsersInRange));
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        Intent intent = getIntent();

        if (null != intent) {
            String action = intent.getAction();
            if (null != action && action.equals(UPDATE_UI_ACTION)) {
                String numUsersInRange = intent.getStringExtra(USER_IN_RANGE_EXTRA);
                ((TextView)findViewById(R.id.people_in_range)).setText(String.format(getString(R.string.users_in_range_format_string), numUsersInRange));
            }
        }
    }

    public static boolean isServiceRunningInForeground(Context context, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                if (service.foreground) {
                    return true;
                }

            }
        }

        return false;
    }

    public void startServices() {
        // Start monitor
        Intent serviceIntentMonitor = new Intent(this, MonitorForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntentMonitor);
        } else {
            startService(serviceIntentMonitor);
        }

        Utils.sendAnalytics(mFirebaseAnalytics, "start_foreground_service", "User", "analytics");

        Activity aContext = this;
        Handler h = new Handler();
        Runnable r = () -> {
            boolean isSrvRunningFg = isServiceRunningInForeground(getApplicationContext(), MonitorForegroundService.class);
            if (!isSrvRunningFg) {
                Toast.makeText(getApplicationContext(), R.string.monitor_service_is_not_running, Toast.LENGTH_LONG).show();
                aContext.finish();
                return;
            }
        };
        h.postDelayed(r, 5000);
    }

    public void stopServices() {
        // Stop monitor
        Intent serviceIntentMonitor = new Intent(this, MonitorForegroundService.class);
        stopService(serviceIntentMonitor);

        Utils.sendAnalytics(mFirebaseAnalytics, "stop_foreground_service", "route_planning", "analytics");
    }

}