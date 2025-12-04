package com.example.smartdoorlock.data

data class AccessLog(
    val user_id: String,
    val time: String,
    val status: String,
    val method: String
)
