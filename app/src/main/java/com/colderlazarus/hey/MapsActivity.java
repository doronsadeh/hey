package com.colderlazarus.hey;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.colderlazarus.hey.services.MonitorForegroundService;
import com.colderlazarus.hey.utils.Utils;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.util.Objects;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {

    private static final String TAG = "map";

    public static final String MAP_ACTIVITY_INTENT_FILTER = "com.colderlazarus.rain.MapsActivity.MAPS";
    public static final String KILL_MAPS_ACTIVITY = "com.colderlazarus.rain.MapsActivity.KILL_MAPS_ACTIVITY";

    private FirebaseAnalytics mFirebaseAnalytics;

    public static GoogleMap mMap;

    // How many map updates are currently running concurrently. Ideally we want it to be one.
    private int runningMapUpdates;

    private Location protest = null;

    private Circle circle = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        runningMapUpdates = 0;

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        Utils.sendAnalytics(mFirebaseAnalytics, "show", "map", "analytics");

        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_maps);

        // Make screen stay on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        Objects.requireNonNull(mapFragment).getMapAsync(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Paint path and update markers
        paintMap();

        // Make screen stay on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStart() {
        super.onStart();

        Intent intent = getIntent();
        if (null != intent) {
            protest = intent.getParcelableExtra(MainActivity.HAILING_USER_LOCATION_EXTRA);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Let screen shut off
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Let screen shut off
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private synchronized void overlay(Location current, Location protest, double radiusMeters) {

        // Overlay MUST be synchronized to avoid the markers fields to be overwritten by map update threads
        // leaving a trail of non-removed markers.
        if (null == current)
            current = Utils.getLastKnownLoaction(this);

        if (null == mMap || null == current)
            return;

        LatLng _current = new LatLng(current.getLatitude(), current.getLongitude());
        LatLng _protest = new LatLng(protest.getLatitude(), protest.getLongitude());

        if (null != circle)
            circle.remove();

        circle = mMap.addCircle(new CircleOptions()
                .center(_protest)
                .radius(radiusMeters)
                .strokeColor(0x000000FF)
                .fillColor(0x600000FF));

        try {
            LatLngBounds.Builder builder = new LatLngBounds.Builder();
            builder.include(_current);
            builder.include(_protest);

            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 300));

        } catch (Exception e) {
            Log.e(TAG, "Problem when trying to set overlay on map: " + e);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        Utils.sendAnalytics(mFirebaseAnalytics, "ready", "map", "analytics");

        mMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {

            @Override
            public View getInfoWindow(Marker arg0) {
                return null;
            }

            @Override
            public View getInfoContents(Marker marker) {

                LinearLayout info = new LinearLayout(getApplicationContext());
                info.setOrientation(LinearLayout.VERTICAL);

                TextView title = new TextView(getApplicationContext());
                title.setTextColor(Color.BLACK);
                title.setGravity(Gravity.CENTER);
                title.setTypeface(null, Typeface.BOLD);
                title.setText(marker.getTitle());

                TextView snippet = new TextView(getApplicationContext());
                snippet.setTextColor(Color.GRAY);
                snippet.setText(marker.getSnippet());

                info.addView(title);
                info.addView(snippet);

                return info;
            }
        });

        paintMap();
    }

    private static long tCounter = 0L;

    private synchronized void paintMap() {
        // We want to avoid frequent updates to re-invoke before older update has completed, so we
        // first synchronize, however on top of that we fire the update in a thread (handler).
        // We also measure the number of outstanding handlers and if it is too high per time unit we drop
        // older ones.

        if (runningMapUpdates > 0) {
            // If we have outstanding update threads (handlers), we cannot just inject a new one
            // as it will bring the Android to its knees and we'll start getting ANRs and the app
            // would be slow to respond. We can't shutdown exiting handlers as they would leave a
            // trail of markers no one would remove, so the only viable option is to drop the
            // current update and hope the next one comes a better time when the older ones are done. As this should
            // only happen when the rider is raveling VERY fast, the next update is sure to come quickly.
            return;
        }

        Handler h = new Handler();
        Thread r = new Thread() {
            @Override
            public void run() {

                // Update the outstanding map updates count
                runningMapUpdates++;

                try {
                    if (null != protest)
                        overlay(MonitorForegroundService.getLastKnownLocation(), protest, 200.0);
                } finally {
                    // Mark update as done
                    runningMapUpdates--;
                }
            }
        };

        r.setName("paint-" + tCounter);

        h.post(r);
    }
}
