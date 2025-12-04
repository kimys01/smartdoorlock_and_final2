package com.example.smartdoorlock.ui.notifications

/**
 * 도어락 알림 로그 아이템 모델
 * - NotificationsFragment와 NotificationAdapter에서 공통으로 사용하기 위해 별도 파일로 분리함
 */
data class NotificationItem(
    val time: String = "",
    val state: String = "",
    val method: String = "",
    val user: String = ""
)