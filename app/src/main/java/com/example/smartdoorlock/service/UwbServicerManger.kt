package com.example.smartdoorlock.service

import android.content.Context
import android.util.Log
import androidx.core.uwb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs

class UwbServiceManager(private val context: Context) {

    private var uwbManager: UwbManager? = null
    private var uwbJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val frontAddress = UwbAddress(byteArrayOf(0x12, 0x34))
    private val backAddress = UwbAddress(byteArrayOf(0x56, 0x78))

    private var distFront: Double? = null
    private var distBack: Double? = null

    private var lastLogTime: Long = 0
    private val LOG_INTERVAL = 5000L

    var onLogUpdate: ((Double, Double) -> Unit)? = null
    var onUnlockRangeEntered: (() -> Unit)? = null

    fun init() {
        // [수정] UWB 미지원 기기에서 크래시 방지
        scope.launch {
            try {
                if (context.packageManager.hasSystemFeature("android.hardware.uwb")) {
                    uwbManager = UwbManager.createInstance(context)
                    Log.d("UWB", "UWB Manager initialized")
                } else {
                    Log.w("UWB", "이 기기는 UWB를 지원하지 않습니다.")
                }
            } catch (e: Exception) {
                Log.e("UWB", "UWB 초기화 실패: ${e.message}")
            }
        }
    }

    fun startRanging() {
        if (uwbManager == null) {
            Log.w("UWB", "UWB Manager가 null입니다. (지원하지 않는 기기일 수 있음)")
            return
        }
        if (uwbJob?.isActive == true) return

        Log.d("UWB", "🚀 UWB 거리 측정 시작")
        lastLogTime = 0

        uwbJob = scope.launch {
            try {
                val sessionScope = uwbManager!!.controllerSessionScope()
                val complexChannel = UwbComplexChannel(channel = 9, preambleIndex = 10)
                val peerDevices = listOf(UwbDevice(frontAddress), UwbDevice(backAddress))

                val params = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = 12345,
                    subSessionId = 0,
                    sessionKeyInfo = null,
                    subSessionKeyInfo = null,
                    complexChannel = complexChannel,
                    peerDevices = peerDevices,
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                )

                sessionScope.prepareSession(params).collect { result ->
                    processRangingResult(result)
                }
            } catch (e: Exception) {
                Log.e("UWB", "Ranging 오류 발생: ${e.message}")
                stopRanging() // 오류 발생 시 안전하게 중지
            }
        }
    }

    private fun processRangingResult(result: RangingResult) {
        when (result) {
            is RangingResult.RangingResultPosition -> {
                val distance = result.position.distance?.value?.toDouble() ?: return
                val address = result.device.address

                if (address == frontAddress) distFront = distance
                else if (address == backAddress) distBack = distance

                // 두 센서 값 모두 있을 때만 계산
                if (distFront != null && distBack != null) {
                    checkPositionAndUnlock()

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime >= LOG_INTERVAL) {
                        onLogUpdate?.invoke(distFront!!, distBack!!)
                        lastLogTime = currentTime
                    }
                }
            }
            is RangingResult.RangingResultPeerDisconnected -> {
                Log.d("UWB", "장치 연결 끊김")
            }
            else -> {}
        }
    }

    private fun checkPositionAndUnlock() {
        val front = distFront ?: return
        val back = distBack ?: return

        // [로직] 앞이 뒤보다 가깝고, 거리가 3m 이내일 때
        if (front < back && front <= 3.0) {
            Log.i("UWB", "🔓 실외 3m 진입 (앞:$front < 뒤:$back)")

            // 메인 스레드에서 콜백 실행 보장하지 않아도 됨 (Service에서 처리)
            onUnlockRangeEntered?.invoke()

            stopRanging() // 한 번 열면 중지
            resetDistances()
        }
    }

    private fun resetDistances() {
        distFront = null
        distBack = null
    }

    fun stopRanging() {
        if (uwbJob?.isActive == true) {
            Log.d("UWB", "🛑 UWB 거리 측정 중지")
            uwbJob?.cancel()
            uwbJob = null
            resetDistances()
        }
    }
}