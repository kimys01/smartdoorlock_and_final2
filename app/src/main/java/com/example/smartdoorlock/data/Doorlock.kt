package com.example.smartdoorlock.data

/**
 * 공유 도어락 데이터 구조
 * 경로: doorlocks/{macAddress}
 * * 이 데이터는 도어락의 고유 정보이므로 위치(location)는 처음에 한 번 저장되면 고정됩니다.
 */
data class Doorlock(
    val mac: String = "",
    val ssid: String = "",
    val pw: String = "",

    // 상세 설정 (공유됨)
    val detailSettings: DetailSettings = DetailSettings(),

    // 이 도어락을 사용할 수 있는 사용자 목록
    val members: HashMap<String, String> = HashMap(),

    // [핵심] 도어락의 고정 위치 (설치 시점의 위도, 경도, 고도)
    val location: FixedLocation = FixedLocation(),

    val lastUpdated: String = ""
)

// 고정 위치 정보 클래스 (위도, 경도, 고도)
data class FixedLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0
)