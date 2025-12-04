package com.example.smartdoorlock.data

data class LoginResponse(
    val success: Boolean,
    val message: String,
    val name: String?
)
