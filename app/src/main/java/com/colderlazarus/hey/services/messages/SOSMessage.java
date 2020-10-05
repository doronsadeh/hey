package com.colderlazarus.hey.services.messages;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.media.SoundPool;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.colderlazarus.hey.MainActivity;
import com.colderlazarus.hey.R;
import com.colderlazarus.hey.dynamodb.models.User;
import com.colderlazarus.hey.dynamodb.models.Users;
import com.colderlazarus.hey.services.FCMAdapter;
import com.colderlazarus.hey.services.FirebaseMsgsService;
import com.colderlazarus.hey.services.LocationListener;
import com.colderlazarus.hey.utils.Utils;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.messaging.RemoteMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.colderlazarus.hey.services.LocationListener.HAILING_USER_LOCATION;
import static com.colderlazarus.hey.services.LocationListener.HAIL_SENT_AT;
import static com.colderlazarus.hey.utils.Utils.getLastKnownLocation;
import static java.lang.Math.round;

public class SOSMessage extends MessageBase {

    private static final String TAG = "hey.SOSMessage";

    private static final String SEPARATOR = "_sep_";

    public static final String CHANNEL_ID = "HeySOSChannel";

    public static final long MAX_ALLOWED_SOS_DELTA_SEC = 30L * 60L;

    private NotificationChannel notificationsChannel = null;

    private SoundPool soundPool;
    private int soundId;

    public SOSMessage(Context context) {
        super();
        notificationsChannel = createNotificationChannel(context, CHANNEL_ID);
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void receiveMessage(Context context, RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();

        String msgType = data.get(MSG_TYPE);
        String msgText = data.get(MSG_TEXT);
        String hailingUserId = data.get(HAILING_USER_ID);

        // Don't self notify
        if (hailingUserId.equals(Utils.identity(context)))
            return;

        String[] fields = msgText.split(SEPARATOR);
        if (fields.length != 2)
            return;

        String location = fields[0];
        Long epochTimeSentAt = Long.parseLong(fields[1]);

        // If corrupted call, discard it
        if (null == msgType || null == msgText || null == location || null == epochTimeSentAt)
            return;

        Location hailingUserLocation = null;
        String[] locLatLng = location.split(",");
        if (locLatLng.length == 2) {
            hailingUserLocation = Utils.LatLngToLocation(new LatLng(Double.parseDouble(locLatLng[0]), Double.parseDouble(locLatLng[1])));
        }

        String metersAway = "---";
        if (null != hailingUserLocation) {
            metersAway = String.valueOf(round(getLastKnownLocation(context).distanceTo(hailingUserLocation)));
        }

        if ((Utils.nowSec() - epochTimeSentAt) > MAX_ALLOWED_SOS_DELTA_SEC)
            return;

        Intent notificationIntent = new Intent(context, MainActivity.class);
        notificationIntent.putExtra(MainActivity.HAILING_USER_LOCATION_EXTRA, hailingUserLocation);
        notificationIntent.putExtra(MainActivity.OPEN_NAV_APP_EXTRA, true);
        PendingIntent pIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "SOS Channel", NotificationManager.IMPORTANCE_HIGH);
            channel.enableLights(true);
            channel.enableVibration(true);
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            Objects.requireNonNull(notificationManager).createNotificationChannel(channel);
            @SuppressLint("StringFormatMatches") Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentTitle(context.getString(R.string.you_are_being_sosed))
                    .setContentText(String.format(context.getString(R.string.sos_notification_format_string), metersAway, Utils.epochToLocalTime(epochTimeSentAt)))
                    .setContentIntent(pIntent)
                    .setAutoCancel(true);
            notificationManager.notify(Utils.genIntUUID(), builder.build());
        } else {
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
            @SuppressLint("StringFormatMatches") NotificationCompat.Builder builder = new NotificationCompat.Builder(context, SOSMessage.CHANNEL_ID)
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentTitle(context.getString(R.string.you_are_being_sosed))
                    .setContentText(String.format(context.getString(R.string.sos_notification_format_string), metersAway, Utils.epochToLocalTime(epochTimeSentAt)))
                    .setContentIntent(pIntent)
                    .setAutoCancel(true);
            notificationManager.notify(Utils.genIntUUID(), builder.build());
        }

        // Play siren
        Utils.Siren(context);

//        // open the map
//        Intent openMapIntent = new Intent(context, MainActivity.class);
//        openMapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
//        openMapIntent.putExtra(MainActivity.HAILING_USER_LOCATION_EXTRA, hailingUserLocation);
//        openMapIntent.putExtra(MainActivity.OPEN_NAV_APP_EXTRA, true);
//        context.startActivity(openMapIntent);
    }

    @Override
    public int sendMessage(Context context, Map<String, Object> message, boolean skipUI) {
        String usersBeingHailedIds = (String) message.get(LocationListener.USERS_BEING_HAILED_IDS);
        String hailingUserLocation = (String) message.get(HAILING_USER_LOCATION);
        String hailSentAt = (String) message.get(HAIL_SENT_AT);
        String hailingUserId = (String) message.get(HAILING_USER_ID);

        List<String> usersIds = Arrays.asList(usersBeingHailedIds.split(","));

        int numRegistredHailedRiders = 0;

        if (null != hailingUserLocation && null != hailSentAt) {

            String myId = Utils.identity(context);

            ArrayList<String> ridersIds = new ArrayList<>();
            ArrayList<String> ridersRegistrationTokens = new ArrayList<>();

            for (String userID : usersIds) {
                // Get the user details
                User user = Users.getUser(context.getApplicationContext(), userID);

                // Skip users which are already notified or responding to other distress calls
                if (null != user) {
                    if (!user.userId.equals(myId)) {
                        // Collect the riders' ids (hash keys)
                        ridersIds.add(user.userId);

                        // Collect riders' tokens
                        ridersRegistrationTokens.add(user.token);
                    }
                }
            }

            User me = Users.getUser(context.getApplicationContext(), myId);

            numRegistredHailedRiders = ridersRegistrationTokens.size();

            if (null != me && null != me.token && numRegistredHailedRiders > 0) {
                int successfulSends = FCMAdapter.sendFCMToDevices(
                        FirebaseMsgsService.MSG_TYPE.SOS,
                        getLastKnownLocation(context),
                        String.format("%s%s%s",
                                hailingUserLocation,
                                SEPARATOR,
                                hailSentAt),
                        hailingUserId,
                        ridersIds,
                        ridersRegistrationTokens);

                if (successfulSends < 1) {
                    String reply = context.getString(R.string.msg_not_delivered);
                    Toast.makeText(context, reply, Toast.LENGTH_LONG).show();
                    return 0;
                }

                Toast.makeText(context, String.format(context.getString(R.string.sos_sent_format_str), successfulSends), Toast.LENGTH_LONG).show();
            }
        }

        return numRegistredHailedRiders;
    }
}

