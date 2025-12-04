package com.example.smartdoorlock.data

import java.util.HashMap

/**
 * Firebase Realtime Database 전체 구조
 * 경로: users/{username}
 */
data class User(
    val username: String = "",
    val password: String = "",
    val name: String = "",
    val authMethod: String = "BLE",
    val phoneNumber: String = "",

    val doorlock: UserDoorlock = UserDoorlock(),

    val location_logs: HashMap<String, Any> = HashMap(),

    val uwb_logs: HashMap<String, UwbLog> = HashMap(),
    val detailSettings: DetailSettings = DetailSettings(),
    val app_logs: HashMap<String, AppLogItem> = HashMap(),

    val createdAt: Long = System.currentTimeMillis()
)

// --- 하위 데이터 모델 ---

data class AppLogItem(
    val message: String = "",
    val timestamp: String = ""
)

data class UwbLog(
    val front_distance: Double = 0.0,
    val back_distance: Double = 0.0,
    val timestamp: String = ""
)

data class DetailSettings(
    val autoLockEnabled: Boolean = true,
    val autoLockTime: Int = 5,
    val notifyOnLock: Boolean = true
)

data class UserDoorlock(
    val status: DoorlockStatus = DoorlockStatus(),
    val logs: HashMap<String, DoorlockLog> = HashMap()
)

// [수정] 하드웨어(ESP32)에서 보내주는 데이터 필드 추가 (dist_out, dist_in)
data class DoorlockStatus(
    val door_closed: Boolean = true,
    val last_method: String = "NONE",
    val last_time: String = "",
    val state: String = "LOCK",
    val dist_out: Double = 0.0, // 외부 UWB 거리
    val dist_in: Double = 0.0   // 내부 UWB 거리
)

data class DoorlockLog(
    val method: String = "",
    val state: String = "",
    val time: String = "",
    val user: String = ""
)
data class NotificationItem(
    val time: String = "",
    val state: String = "",
    val method: String = "",
    val user: String = ""
)