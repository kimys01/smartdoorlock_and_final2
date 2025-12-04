package com.example.smartdoorlock.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.smartdoorlock.R
import com.example.smartdoorlock.data.DoorlockLog
import com.example.smartdoorlock.data.LocationLog
import com.example.smartdoorlock.utils.LocationUtils
import com.google.android.gms.location.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val database = FirebaseDatabase.getInstance()

    private val CHANNEL_ID = "location_service_channel"
    private val NOTIFICATION_ID = 1

    // 위치 업데이트 간격 (30초마다 확인)
    private val LOCATION_UPDATE_INTERVAL = 300000L // 300초
    private val FASTEST_INTERVAL = 95000L // 95초

    override fun onCreate() {
        super.onCreate()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 위치 콜백 초기화
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    // 위치를 받을 때마다 DB 및 서버에 저장
                    saveLocationToDB(location)
                    checkProximityToAllDoorlocks(location)
                }
            }
        }

        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "서비스 시작됨")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 위치 업데이트 시작
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("LocationService", "위치 권한이 없습니다.")
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_INTERVAL)
            setMaxUpdateDelayMillis(LOCATION_UPDATE_INTERVAL)
        }.build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        Log.d("LocationService", "위치 업데이트 시작 (${LOCATION_UPDATE_INTERVAL / 1000}초마다)")
    }

    // 위치 로그 Firebase에 저장
    private fun saveLocationToDB(location: Location) {
        val prefs = getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("saved_id", null)

        if (username.isNullOrEmpty()) {
            Log.w("LocationService", "저장된 사용자 아이디가 없습니다.")
            return
        }

        // 타임스탬프 생성 (표준 포맷)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        // Firebase에 저장
        val log = LocationLog(
            altitude = location.altitude,
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = timestamp
        )

        database.getReference("users")
            .child(username)
            .child("location_logs")
            .push()
            .setValue(log)
            .addOnSuccessListener {
                Log.d("LocationService", "위치 저장 성공: (${location.latitude}, ${location.longitude})")
            }
            .addOnFailureListener { e ->
                Log.e("LocationService", "위치 저장 실패: ${e.message}")
            }
    }

    // 모든 도어락과의 거리 체크
    private fun checkProximityToAllDoorlocks(currentLocation: Location) {
        val prefs = getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("saved_id", null) ?: return

        // 사용자의 도어락 목록 가져오기
        database.getReference("users")
            .child(userId)
            .child("my_doorlocks")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (doorlockSnapshot in snapshot.children) {
                        val doorlockId = doorlockSnapshot.key ?: continue
                        checkDoorlockProximity(doorlockId, currentLocation, userId)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LocationService", "도어락 목록 조회 실패: ${error.message}")
                }
            })
    }

    // 특정 도어락과의 거리 체크 및 자동 잠금/해제
    private fun checkDoorlockProximity(doorlockId: String, currentLocation: Location, userId: String) {
        database.getReference("doorlocks")
            .child(doorlockId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val doorlockLat = snapshot.child("location/latitude").getValue(Double::class.java) ?: return
                    val doorlockLon = snapshot.child("location/longitude").getValue(Double::class.java) ?: return
                    val doorlockAlt = snapshot.child("location/altitude").getValue(Double::class.java) ?: 0.0

                    val doorlockLocation = Location("doorlock").apply {
                        latitude = doorlockLat
                        longitude = doorlockLon
                        altitude = doorlockAlt
                    }

                    // 3D 거리 계산
                    val distance3D = LocationUtils.calculateDistance3D(currentLocation, doorlockLocation)

                    Log.d("LocationService", "도어락(${doorlockId})까지 3D 거리: ${"%.2f".format(distance3D)}m")

                    // 거리 기반 자동 잠금/해제 로직
                    val currentState = snapshot.child("status/state").getValue(String::class.java)
                    val autoLockEnabled = snapshot.child("detailSettings/autoLockEnabled").getValue(Boolean::class.java) ?: true

                    if (distance3D <= 5.0 && currentState == "LOCK") {
                        // 5m 이내 진입 -> 자동 해제
                        unlockDoor(doorlockId, userId, "BLE_AUTO")
                    } else if (distance3D > 10.0 && currentState == "UNLOCK" && autoLockEnabled) {
                        // 10m 이상 멀어짐 -> 자동 잠금
                        lockDoor(doorlockId, userId, "AUTO_LOCK")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LocationService", "도어락 정보 조회 실패: ${error.message}")
                }
            })
    }

    // 도어락 잠금
    private fun lockDoor(doorlockId: String, userId: String, method: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val statusRef = database.getReference("doorlocks").child(doorlockId).child("status")
        val logsRef = database.getReference("doorlocks").child(doorlockId).child("logs")
        val userLogsRef = database.getReference("users").child(userId).child("doorlock").child("logs")

        val updates = mapOf(
            "state" to "LOCK",
            "last_method" to method,
            "last_time" to timestamp,
            "door_closed" to true
        )

        statusRef.updateChildren(updates)

        val logData = DoorlockLog(
            method = method,
            state = "LOCK",
            time = timestamp,
            user = userId
        )

        logsRef.push().setValue(logData)
        userLogsRef.push().setValue(logData)

        Log.d("LocationService", "자동 잠금 실행: $doorlockId")
    }

    // 도어락 해제
    private fun unlockDoor(doorlockId: String, userId: String, method: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val statusRef = database.getReference("doorlocks").child(doorlockId).child("status")
        val logsRef = database.getReference("doorlocks").child(doorlockId).child("logs")
        val userLogsRef = database.getReference("users").child(userId).child("doorlock").child("logs")

        val updates = mapOf(
            "state" to "UNLOCK",
            "last_method" to method,
            "last_time" to timestamp,
            "door_closed" to false
        )

        statusRef.updateChildren(updates)

        val logData = DoorlockLog(
            method = method,
            state = "UNLOCK",
            time = timestamp,
            user = userId
        )

        logsRef.push().setValue(logData)
        userLogsRef.push().setValue(logData)

        Log.d("LocationService", "자동 해제 실행: $doorlockId")
    }

    // 알림 채널 생성
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "위치 추적 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "도어락 근처 위치를 추적합니다."
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // 알림 생성
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("스마트 도어락 실행 중")
            .setContentText("위치를 추적하고 있습니다.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d("LocationService", "위치 서비스 종료")
    }
}