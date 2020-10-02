package com.colderlazarus.hey.services;

import android.content.Context;
import android.location.Location;

import com.colderlazarus.rain.weather.WeatherEvent;

import java.util.List;

public interface WeatherProvider {
    List<WeatherEvent> execute(Context context,
                               String groupId,
                               Double currentBearing,
                               double distanceMeters,
                               Location currentLocation,
                               Location predictedLocation,
                               Location finalDestination,
                               double radiusMeters);
}
