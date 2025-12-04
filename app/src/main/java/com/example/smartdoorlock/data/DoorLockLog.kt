package com.example.smartdoorlock.data

data class DoorLockLog(
    val user_id: String = "",
    val status: String = "",
    val timestamp: String = "",
    val method: String? = null
)
