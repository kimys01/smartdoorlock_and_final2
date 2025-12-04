package com.example.smartdoorlock.helper

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

class LocationHelper(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    interface Callback {
        fun onLocationReceived(latitude: Double, longitude: Double, timestamp: String)
        fun onFailure(message: String)
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(callback: Callback) {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L // interval in ms
        )
            .setMinUpdateIntervalMillis(5000L)
            .setMaxUpdateDelayMillis(10000L)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                fusedLocationClient.removeLocationUpdates(this)

                val location: Location? = locationResult.lastLocation
                if (location != null) {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    callback.onLocationReceived(location.latitude, location.longitude, timestamp)
                } else {
                    callback.onFailure("위치 정보를 가져올 수 없습니다.")
                }
            }
        }, Looper.getMainLooper())
    }
}
