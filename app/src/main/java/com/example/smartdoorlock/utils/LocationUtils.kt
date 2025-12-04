package com.example.smartdoorlock.utils

import android.location.Location
import kotlin.math.pow
import kotlin.math.sqrt

object LocationUtils {

    /**
     * 고도를 포함한 3D 거리를 계산합니다. (단위: 미터)
     * 피타고라스 정리 활용: 빗변^2 = 밑변(2D거리)^2 + 높이(고도차)^2
     */
    fun calculateDistance3D(loc1: Location, loc2: Location): Double {
        // 1. 안드로이드 기본 API로 수평(2D) 거리 계산
        val distance2D = loc1.distanceTo(loc2).toDouble()

        // 2. 고도 차이 계산
        val heightDiff = loc1.altitude - loc2.altitude

        // 3. 3D 거리 계산
        val distance3D = sqrt(distance2D.pow(2) + heightDiff.pow(2))

        return distance3D
    }
}