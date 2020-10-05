package com.colderlazarus.hey;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.colderlazarus.hey.dynamodb.UsersCache;
import com.colderlazarus.hey.dynamodb.models.User;
import com.colderlazarus.hey.dynamodb.models.Users;
import com.colderlazarus.hey.services.FCMAdapter;
import com.colderlazarus.hey.services.LocationListener;
import com.colderlazarus.hey.services.MonitorForegroundService;
import com.colderlazarus.hey.utils.Utils;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.iid.FirebaseInstanceId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.round;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "hey.MainActivity";

    private static final int TAG_CODE_MANDATORY_PERMISSIONS = Utils.genIntUUID();

    public static final String EXIT_APP_ACTION = "hey.EXIT_APP_ACTION";
    public static final String CALL_POLICE_ACTION = "hey.CALL_POLICE_ACTION";

    public static final String HAILING_USER_LOCATION_EXTRA = "hey.HAILING_USER_LOCATION_EXTRA";
    public static final String OPEN_NAV_APP_EXTRA = "hey.OPEN_NAV_APP_EXTRA";
    public static final String NUM_PEOPLE_IN_RANGE_EXTRA = "hey.NUM_PEOPLE_IN_RANGE_EXTRA";

    public static final String HEY_IS_HAILING = "hey.prefs.HEY_IS_HAILING";

    private FirebaseAnalytics mFirebaseAnalytics;

    private boolean looperPrepared = false;

    private Animation anim = new AlphaAnimation(0.0f, 1.0f);

    private int sirenSoundId;

    private void validatePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // We have permissions
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ActivityCompat.requestPermissions(this, new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                Manifest.permission.CALL_PHONE},
                        TAG_CODE_MANDATORY_PERMISSIONS);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
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

                        // Save the new token in the cloud DB as well as the local Sqlite
                        User userCurrentData = Users.getUser(this, Utils.identity(this));

                        if (null == userCurrentData)
                            userCurrentData = User.build(this, Utils.identity(this));

                        Users.setUser(this, newToken, userCurrentData, 0L);

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

                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                        if (sharedPreferences.getBoolean(HEY_IS_HAILING, false)) {
                            ((ImageView) findViewById(R.id.switch_hailing_on_off)).setImageResource(R.drawable.app_icon);
                            ((TextView) findViewById(R.id.people_in_range)).setText(String.format(getString(R.string.hail_in_range_ticker), LocationListener.numPeopleInRange));
                        } else {
                            ((ImageView) findViewById(R.id.switch_hailing_on_off)).setImageResource(R.drawable.app_icon_off);
                            ((TextView) findViewById(R.id.people_in_range)).setText(R.string.press_to_start_hailing);
                        }

                        findViewById(R.id.switch_hailing_on_off).setOnClickListener(v -> {
                            // If there is imminent rain or snow, suggest a shelter
                            boolean hailing = sharedPreferences.getBoolean(HEY_IS_HAILING, false);

                            // Toggle
                            hailing = !hailing;

                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putBoolean(HEY_IS_HAILING, hailing);
                            editor.commit();

                            TextView explanationText = findViewById(R.id.people_in_range);

                            if (hailing) {
                                ((ImageView) v).setImageResource(R.drawable.app_icon);
                                explanationText.setText(String.format(getString(R.string.hail_in_range_ticker), LocationListener.numPeopleInRange));
                                anim.setDuration(1500);
                                anim.setStartOffset(20);
                                anim.setRepeatMode(Animation.REVERSE);
                                anim.setRepeatCount(Animation.INFINITE);
                                explanationText.startAnimation(anim);
                            } else {
                                ((ImageView) v).setImageResource(R.drawable.app_icon_off);
                                anim.cancel();
                                explanationText.setText(R.string.press_to_start_hailing);
                            }


                        });

                        findViewById(R.id.sos_button).setOnClickListener(v -> {
                            v.performHapticFeedback(
                                    HapticFeedbackConstants.VIRTUAL_KEY,
                                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            );

                            Toast.makeText(this, R.string.long_press_sos, Toast.LENGTH_LONG).show();
                        });

                        findViewById(R.id.sos_button).setOnLongClickListener(v -> {
                            v.performHapticFeedback(
                                    HapticFeedbackConstants.VIRTUAL_KEY,
                                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            );

                            // TODO hail ALL people in area w/SoSMessage --> siren sounds, + location + press for waze
                            Intent serviceIntent = new Intent(MonitorForegroundService.class.getName());
                            serviceIntent.setPackage(this.getPackageName());
                            serviceIntent.setAction(MonitorForegroundService.SEND_SOS_ACTION);
                            this.startService(serviceIntent);
                            return true;
                        });
                    }
                });


    }

    private boolean navUsingGoogleMaps(Context context, Location to) {
        Uri gmmIntentUri = Uri.parse(
                String.format(
                        "google.navigation:q=%s,%s",
                        to.getLatitude(),
                        to.getLongitude()));
        Intent googleMapsIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        googleMapsIntent.setPackage("com.google.android.apps.maps");
        googleMapsIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (googleMapsIntent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(googleMapsIntent);
            return true;
        }

        return false;
    }

    private boolean navUsingWaze(Context context, Location to) {
        try {
            // Example: https://www.waze.com/ul?ll=40.75889500%2C-73.98513100&navigate=yes&zoom=17
            String url = "waze://?ll="
                    + to.getLatitude()
                    + "%2C"
                    + to.getLongitude()
                    + "&navigate=yes";
            Intent wazeIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            wazeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(wazeIntent);
            return true;
        } catch (ActivityNotFoundException ex) {
            return false;
        }
    }

    private void nav(Context context, Location goingTo) {
        // Try google maps, and if not installed try Waze
        if (!navUsingWaze(context, goingTo)) {
            if (!navUsingGoogleMaps(context, goingTo)) {
                Log.e(TAG, "No supported navigation app installed");
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        actionsHandler(getIntent());
    }

    private void actionsHandler(Intent intent) {
        if (null != intent) {
            String action = intent.getAction();
            if (null != action) {
                if (action.equals(CALL_POLICE_ACTION)) {
                    phonePolice100(this);
                }
            }

            if (intent.getBooleanExtra(OPEN_NAV_APP_EXTRA, false)) {
                final Location hailingUserLocation = intent.getParcelableExtra(HAILING_USER_LOCATION_EXTRA);
                new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.AppTheme))
                        .setMessage(String.format(getResources().getString(R.string.nav_to_distress_location_format_str), round(hailingUserLocation.distanceTo(Utils.getLastKnownLocation(this)))))
                        .setPositiveButton(R.string.yes, (dialog, whichButton) -> {
                            nav(this, hailingUserLocation);
                        })
                        .setNegativeButton(R.string.no, (dialog, which) -> {
                            // Nothing
                        })
                        .setOnCancelListener(dialog -> {
                            // Nothing
                        })
                        .show();

            }

            int numInRange = intent.getIntExtra(NUM_PEOPLE_IN_RANGE_EXTRA, -1);
            if (numInRange >= 0) {
                ((TextView) findViewById(R.id.people_in_range)).setText(String.format(getString(R.string.hail_in_range_ticker), numInRange));
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

    private void phonePolice100(Context context) {
        new AlertDialog.Builder(new ContextThemeWrapper(context, R.style.AppTheme))
                .setMessage(context.getResources().getString(R.string.to_call_the_police))
                .setPositiveButton(R.string.yes, (dialog, whichButton) -> {
                    String number = ("tel:+100");
                    Intent intentCall = new Intent(Intent.ACTION_CALL);
                    intentCall.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intentCall.setData(Uri.parse(number));

                    try {
                        context.startActivity(intentCall);
                    } catch (SecurityException e) {
                        Log.e(TAG, "Cannot call police");
                    }

                    if (null == Utils.soundPool) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                            Utils.createNewSoundPool(context);
                        else
                            Utils.createOldSoundPool(context);
                    }

                    Utils.soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                        @Override
                        public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                            soundPool.play(sampleId, 0.75f, 0.75f, 1, 3, 1);
                        }
                    });

                    sirenSoundId = Utils.soundPool.load(context, R.raw.calling_police, 1);
                })
                .setNegativeButton(R.string.no, (dialog, which) -> {
                    // Nothing
                })
                .setOnCancelListener(dialog -> {
                    // Nothing
                })
                .show();
    }

}