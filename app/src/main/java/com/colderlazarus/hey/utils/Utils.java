package com.colderlazarus.hey.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ThumbnailUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.colderlazarus.hey.MainActivity;
import com.colderlazarus.hey.R;
import com.colderlazarus.hey.services.MonitorForegroundService;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.lang.Math.abs;
import static java.lang.Math.round;
import static java.lang.Thread.sleep;

public class Utils {
    private static final String TAG = "utils.Utils";

    private static final long HTTP_GET_TIMEOUT_SEC = 7;

    private static final int TAG_CODE_LOCATION_PERMISSION = Utils.genIntUUID();

    private static final String NOTIFICATIONS_SETTINGS_DONE = "hey.NOTIFICATIONS_SETTINGS_DONE";

    public static SoundPool soundPool = null;

    public static int genIntUUID() {
        return abs((int) (UUID.randomUUID().getLeastSignificantBits()) & 0xFFFF);
    }

    public static String genStringUUID() {
        return UUID.randomUUID().toString();
    }

    public static Location LatLngToLocation(LatLng latLng) {
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(latLng.latitude);
        l.setLongitude(latLng.longitude);
        return l;
    }

    public static void notificationsSettingsDialog(Context context) {
        // TODO do this only on thr first time, with an explnation
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean notificationsSettingsDone = sharedPreferences.getBoolean(NOTIFICATIONS_SETTINGS_DONE, false);
        if (!notificationsSettingsDone) {

            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(NOTIFICATIONS_SETTINGS_DONE, true);
            editor.commit();

            new AlertDialog.Builder(new ContextThemeWrapper(context, R.style.AppTheme))
                    .setCancelable(false)
                    .setMessage(context.getResources().getString(R.string.notifications_initial_settings))
                    .setPositiveButton(R.string.yes_take_me_to_notification_settings, (dialog, whichButton) -> {
                        Intent notificationsSettingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        notificationsSettingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                        context.startActivity(notificationsSettingsIntent);

                    })
                    .setNegativeButton(R.string.no_dismiss_notifications_settings, (dialog, which) -> {
                        // Nothing
                    })
                    .setOnCancelListener(dialog -> {
                        // Nothing
                    })
                    .show();
        }

    }

    public static int getVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) (pInfo.getLongVersionCode());
            } else {
                return (int) (pInfo.versionCode);
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        return -1;
    }

    public static String HTTPGetCall(String url) {

        final String _url = url;

        Callable<String> callback = new Callable<String>() {
            @Override
            public String call() throws Exception {
                URLConnection connection = null;
                try {
                    connection = new URL(_url).openConnection();
                } catch (IOException e) {
                    Log.e(TAG, "Malformed URL: " + _url);
                }

                assert connection != null;
                connection.setRequestProperty("Accept-Charset", "UTF-8");

                BufferedReader response = null;
                try {
                    response = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    String lines = "";
                    String line = null;
                    while ((line = response.readLine()) != null)
                        lines += line;

                    return lines;
                } catch (Exception e) {
                    Log.e(TAG, "Cannot read response to " + _url);
                } finally {
                    if (null != connection)
                        connection.getInputStream().close();

                    if (null != response)
                        response.close();
                }

                return null;
            }
        };

        FutureTask<String> fTask = new FutureTask<>(callback);

        ExecutorService executor = Executors.newFixedThreadPool(1);

        // Run the item fetch
        executor.execute(fTask);

        String response = null;
        try {
            response = fTask.get(HTTP_GET_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Log.e(TAG, "Problem executing DynamoDB getItem: " + e.getMessage());
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while executing DynamoDB getItem: " + e.getMessage());
        } catch (TimeoutException e) {
            Log.e(TAG, "Timed-out while executing DynamoDB getItem: " + e.getMessage());
        } finally {
            executor.shutdown();
        }

        return response;
    }

    @SuppressLint("MissingPermission")
    public static Location getLastKnownLocation(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        Location utilLocation;

        for (int i = 0; i < 5; i++) {
            try {
                utilLocation = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

                if (null != utilLocation)
                    return utilLocation;
            } catch (Exception e) {
                // Silent
            }
            try {
                sleep(100);
            } catch (InterruptedException e) {
                // Silent
            }
        }

        return new Location(LocationManager.GPS_PROVIDER);
    }

    public static Location fakeLocation() {
        Location l = new Location(LocationManager.GPS_PROVIDER);

        l.setLongitude(0.0);
        l.setLatitude(0.0);
        l.setBearing(0);
        l.setSpeed(0.0f);

        return l;
    }

    public static boolean isFakeLocation(Location l) {
        return (l.getLongitude() == 0.0) && (l.getLatitude() == 0.0) && (l.getBearing() == 0) && (l.getSpeed() == 0.0);
    }

    public static synchronized Location getLastKnownLoaction(Activity context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        Location utilLocation;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(context, new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        TAG_CODE_LOCATION_PERMISSION);
            }
        }

        utilLocation = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        if (utilLocation != null)
            return utilLocation;

        return null;
    }

    public static boolean checkNetworkAvailability(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    public static File bitmapToFile(Context context, Bitmap bitmap, String filepath) throws IOException {
        if (null == filepath)
            filepath = Utils.genStringUUID();

        boolean dirCreated = true;
        File directory = new File(context.getCacheDir() + File.separator + "pictures");
        if (!directory.exists())
            dirCreated = directory.mkdirs();

        if (dirCreated) {
            File f = new File(context.getCacheDir(), filepath);

            if (!f.exists())
                f.createNewFile();

            //Convert bitmap to byte array
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 0 /*ignored for PNG*/, bos);
            byte[] bitmapdata = bos.toByteArray();

            //write the bytes in file
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(bitmapdata);
            fos.flush();
            fos.close();

            return f;
        } else {
            throw new IOException("Cannot create images directory");
        }
    }

    public static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    public static double C2F(double temperatureC) {
        return (temperatureC * (9.0 / 5.0)) + 32.0;
    }

    public static double F2C(double temperatureF) {
        return (temperatureF - 32.0) * (5.0 / 9.0);
    }

    public static double Kmh2Ms(double kmh) {
        return kmh / 3.6;
    }

    public static double Ms2Kmh(double ms) {
        return ms * 3.6;
    }

    public static double Km2Miles(double km) {
        return km / 1.609;
    }

    public static double MilesToKm(double miles) {
        return miles * 1.609;
    }

    public static double Kmh2Mph(int kmh) {
        return kmh / 1.609;
    }

    public static double Mph2Kmh(int mph) {
        return mph * 1.609;
    }

    public static int temperature(Context context, double valueInCelsius) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String units = sharedPreferences.getString("units_system_key", null);
        if (null != units) {
            if (units.equalsIgnoreCase("Metric")) {
                return (int) round(valueInCelsius);
            } else if (units.equalsIgnoreCase("Imperial")) {
                return (int) round(C2F(valueInCelsius));
            }
        }

        return (int) round(valueInCelsius);
    }

    public static int velocity(Context context, double metersPerSec) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String units = sharedPreferences.getString("units_system_key", null);
        if (null != units) {
            if (units.equalsIgnoreCase("Metric")) {
                return (int) round(Utils.Ms2Kmh(metersPerSec));
            } else if (units.equalsIgnoreCase("Imperial")) {
                return (int) round(metersPerSec * 2.237);
            }
        }

        return (int) round(Utils.Ms2Kmh(metersPerSec));
    }

    public static double distance(Context context, double distance) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String units = sharedPreferences.getString("units_system_key", null);
        if (null != units) {
            if (units.equalsIgnoreCase("Metric")) {
                return distance;
            } else if (units.equalsIgnoreCase("Imperial")) {
                return round((Utils.Km2Miles(distance)) * 100.0) / 100.0;
            }
        }

        return distance;
    }

    public static boolean isMetric(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String units = sharedPreferences.getString("units_system_key", null);
        return (units.equalsIgnoreCase("metric"));
    }

    public static double latLngDistanceMeters(LatLng position0, LatLng position1) {
        Location l0 = new Location(LocationManager.GPS_PROVIDER);
        Location l1 = new Location(LocationManager.GPS_PROVIDER);

        l0.setLongitude(position0.longitude);
        l0.setLatitude(position0.latitude);

        l1.setLongitude(position1.longitude);
        l1.setLatitude(position1.latitude);

        return l0.distanceTo(l1);
    }

    public static Bitmap toThumbnail(Context context, Uri profilePicture, int thumbSize) throws IOException {
        Bitmap imageBitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), profilePicture);
        Bitmap thumbImage = ThumbnailUtils.extractThumbnail(imageBitmap, thumbSize, thumbSize);
        return thumbImage;
    }

    public static void sendAnalytics(FirebaseAnalytics firebaseAnalytics, String id, String name, String contentType) {
        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, id);
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, name);
        bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, contentType);
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
    }

    private static String bin2hex(byte[] data) {
        StringBuilder hex = new StringBuilder(data.length * 2);
        for (byte b : data)
            hex.append(String.format("%02x", b & 0xFF));
        return hex.toString();
    }

    private static String getSha256Hash(String str) {
        try {
            MessageDigest digest = null;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e1) {
                Log.e(TAG, "No such hash algorithm: " + e1.getMessage());
            }
            digest.reset();
            return bin2hex(digest.digest(str.getBytes()));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String identity(Context context) {
        // Get android ID and hash it
        @SuppressLint("HardwareIds") String android_id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String hashedIdentity = getSha256Hash(android_id);
        if (null != hashedIdentity)
            return hashedIdentity;

        // Only if we can't hash we make up a random identity
        return MonitorForegroundService.randomRecyclingIdentity;
    }

    public static String epochToLocalTime(Long secondsSinceUnixEpoch) {
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm dd-MMM");
        String asString = formatter.format(secondsSinceUnixEpoch * 1000);
        return asString;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static synchronized void createNewSoundPool(Context context) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        soundPool = new SoundPool.Builder()
                .setAudioAttributes(attributes)
                .build();
    }

    @SuppressWarnings("deprecation")
    public static synchronized void createOldSoundPool(Context context) {
        soundPool = new SoundPool(5, AudioManager.STREAM_MUSIC, 0);
    }

    public static void Welcome(Context context) {
        if (null == soundPool) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                Utils.createNewSoundPool(context);
            else
                Utils.createOldSoundPool(context);
        }

        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                // First release the previous one
                soundPool.play(sampleId, 0.75f, 0.75f, 1, 0, 1);
            }
        });

        soundPool.load(context, R.raw.welcome, 1);
    }


    public static void Siren(Context context) {
        if (null == soundPool) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                Utils.createNewSoundPool(context);
            else
                Utils.createOldSoundPool(context);
        }

        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                // First release the previous one
                soundPool.play(sampleId, 0.75f, 0.75f, 1, 0, 1);
            }
        });

        soundPool.load(context, R.raw.siren, 1);
    }

    public static void Come(Context context) {
        if (null == soundPool) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                Utils.createNewSoundPool(context);
            else
                Utils.createOldSoundPool(context);
        }

        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                // First release the previous one
                soundPool.play(sampleId, 0.99f, 0.99f, 1, 2, 1);
            }
        });

        soundPool.load(context, R.raw.come, 1);
    }

    public static void CallPolice(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(MainActivity.CALL_POLICE_ACTION);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
