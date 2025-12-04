package com.example.smartdoorlock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
// [UWB 최신 라이브러리 임포트]
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

class DoorlockService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHAR_UUID = UUID.fromString("abcd1234-5678-90ab-cdef-1234567890ab")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var uwbManager: UwbManager
    private var uwbSession: UwbClientSessionScope? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isReadySent = false

    // UWB 주소 및 거리 변수
    private var addressOutside: UwbAddress? = null
    private var addressInside: UwbAddress? = null
    private var distOutside: Double? = null
    private var distInside: Double? = null

    private var isUwbSupported = false

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "DOORLOCK_SERVICE_CHANNEL"
        const val NOTIFICATION_ID = 101
        const val UWB_THRESHOLD_CM = 300.0 // 3m
        const val RSSI_THRESHOLD = -55     // UWB 없을 때 대략적인 근접 RSSI
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        isUwbSupported = packageManager.hasSystemFeature("android.hardware.uwb")
        Log.d("DoorLockService", "UWB 지원 여부: $isUwbSupported")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("DoorLockService", "🚀 서비스 시작 (50m 진입)")
        startBleScan()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("DoorLockService", "🛑 서비스 종료")
        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: SecurityException) {}
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Smart Doorlock")
        .setContentText("집 근처 자동 연결 중...")
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()
        .also { createNotificationChannel() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Doorlock Control",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startBleScan() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            scanner.startScan(listOf(filter), settings, bleScanCallback)
        } catch (e: SecurityException) {
            Log.e("DoorLockService", "BLE 권한 없음")
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d("DoorLockService", "✅ 도어락 발견! 연결 시도")
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(this)
                result.device.connectGatt(this@DoorlockService, false, gattCallback)
            } catch (e: SecurityException) {}
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("DoorLockService", "🔗 BLE 연결됨")
                bluetoothGatt = gatt
                try { gatt.discoverServices() } catch (e: SecurityException) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("DoorLockService", "❌ BLE 연결 끊김")
                stopSelf() // 연결 끊기면 서비스 종료 (다시 50m 진입 시 켜지도록)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (isUwbSupported) {
                    // UWB 지원 시: 알림 켜고 주소 교환 시작
                    enableNotification(gatt)
                } else {
                    // UWB 미지원 시: RSSI 모니터링 시작
                    startRssiMonitoring(gatt)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleBleMessage(gatt, String(value))
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleBleMessage(gatt, String(characteristic.value))
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && !isUwbSupported) {
                Log.d("DoorLockService", "📶 RSSI: $rssi")
                if (rssi > RSSI_THRESHOLD) {
                    if (!isReadySent) {
                        sendBleCommand(gatt!!, "READY")
                        isReadySent = true
                        Log.d("DoorLockService", "🔓 [BLE-Only] 근접 감지 -> READY 전송")
                    }
                } else {
                    if (rssi < RSSI_THRESHOLD - 10) isReadySent = false
                }

                // 계속 모니터링 (1초 주기)
                scope.launch {
                    kotlinx.coroutines.delay(1000)
                    try { gatt?.readRemoteRssi() } catch (e: SecurityException) {}
                }
            }
        }
    }

    private fun startRssiMonitoring(gatt: BluetoothGatt) {
        scope.launch {
            try { gatt.readRemoteRssi() } catch (e: SecurityException) {}
        }
    }

    private fun enableNotification(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHAR_UUID)
        if (characteristic != null) {
            try {
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    val payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, payload)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = payload
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }

                    // 알림 설정 완료 후 0.5초 뒤 주소 요청
                    Thread.sleep(500)
                    sendBleCommand(gatt, "REQ_UWB_IDS")
                }
            } catch (e: SecurityException) {
                Log.e("DoorLockService", "Notify 설정 실패")
            }
        }
    }

    private fun handleBleMessage(gatt: BluetoothGatt, message: String) {
        Log.d("DoorLockService", "📩 BLE 수신: $message")

        // 하드웨어 응답: "UWB_IDS:0001:0002" (Out:0001, In:0002)
        if (isUwbSupported && message.startsWith("UWB_IDS:")) {
            val parts = message.split(":")
            if (parts.size == 3) {
                addressOutside = UwbAddress(hexStringToByteArray(parts[1]))
                addressInside = UwbAddress(hexStringToByteArray(parts[2]))
                Log.d("DoorLockService", "🎯 UWB 주소 확보. 거리 측정 시작")
                startUwbRanging(gatt)
            }
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun startUwbRanging(gatt: BluetoothGatt) = scope.launch {
        if (addressOutside == null || addressInside == null) return@launch

        try {
            uwbManager = UwbManager.createInstance(this@DoorlockService)
            uwbSession = uwbManager.controllerSessionScope() // 폰이 Controller

            // 하드웨어 UWB 모듈 2개 (Controlee)
            val peerDevices = listOf(
                UwbDevice(addressOutside!!),
                UwbDevice(addressInside!!)
            )

            // 채널 9, Preamble 10 (MK8000 기본값)
            val complexChannel = UwbComplexChannel(channel = 9, preambleIndex = 10)

            // [FIX] Builder 대신 생성자 직접 사용 (alpha08 호환)
            val rangingParams = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId = 12345, // 세션 ID
                subSessionId = 0,
                sessionKeyInfo = null,
                subSessionKeyInfo = null,
                complexChannel = complexChannel,
                peerDevices = peerDevices,
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
            )

            uwbSession!!.prepareSession(rangingParams).collect { result ->
                if (result is RangingResult.RangingResultPosition) {
                    val distanceCm = (result.position.distance?.value ?: 0.0f) * 100
                    val deviceAddress = result.device.address

                    // 모듈 구분하여 거리 저장
                    if (deviceAddress == addressOutside) distOutside = distanceCm.toDouble()
                    else if (deviceAddress == addressInside) distInside = distanceCm.toDouble()

                    Log.d("UWB", "Out: $distOutside cm | In: $distInside cm")

                    checkAndUnlock(gatt)
                }
            }
        } catch (e: Exception) {
            Log.e("DoorLockService", "UWB 세션 오류: ${e.message}")
        }
    }

    private fun checkAndUnlock(gatt: BluetoothGatt) {
        val outDist = distOutside ?: return
        val inDist = distInside ?: return

        // 조건: 바깥 < 3m AND 바깥 < 안쪽
        if (outDist < UWB_THRESHOLD_CM && outDist < inDist) {
            if (!isReadySent) {
                sendBleCommand(gatt, "READY")
                isReadySent = true
                Log.d("DoorLockService", "🔓 [UWB] 조건 만족 -> READY 전송")
            }
        } else {
            // 3.5m 밖으로 나가면 리셋
            if (outDist > UWB_THRESHOLD_CM + 50) {
                isReadySent = false
            }
        }
    }

    private fun sendBleCommand(gatt: BluetoothGatt, command: String) {
        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHAR_UUID) ?: return
        val payload = command.toByteArray()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            Log.e("DoorLockService", "명령 전송 실패")
        }
    }
}