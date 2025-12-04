package com.example.smartdoorlock.ui.setting

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.smartdoorlock.data.DetailSettings
import com.example.smartdoorlock.data.Doorlock
import com.example.smartdoorlock.data.FixedLocation
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap

@SuppressLint("MissingPermission")
class WifiSettingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        val PROV_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
        val WIFI_CTRL_UUID: UUID = UUID.fromString("abcd1234-5678-90ab-cdef-1234567890ab")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(getApplication<Application>())
    }

    private val _statusText = MutableLiveData<String>("기기 연결 대기 중...")
    val statusText: LiveData<String> = _statusText

    private val _isBleConnected = MutableLiveData<Boolean>(false)
    val isBleConnected: LiveData<Boolean> = _isBleConnected

    private val _currentStep = MutableLiveData<Int>(0)
    val currentStep: LiveData<Int> = _currentStep

        private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetAddress: String = ""

    private fun getSavedUserId(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        return prefs.getString("saved_id", null)
    }

    fun connectToDevice(address: String) {
        targetAddress = address
        _statusText.value = "도어락에 연결을 시도합니다..."
        connectGatt(address)
    }

    fun verifyAppAdmin(inputId: String, inputPw: String) {
        val trimId = inputId.trim()
        val trimPw = inputPw.trim()

        // 관리자용 백도어 (테스트용)
        if (trimId == "123456" && trimPw == "1234qwer") {
            _statusText.value = "테스트 계정 승인. 설정 진행..."
            _currentStep.value = 2
            return
        }

        val userId = getSavedUserId()
        if (userId == null) {
            _statusText.value = "오류: 앱 로그인 정보 없음. 다시 로그인하세요."
            return
        }

        _statusText.value = "서버 정보 확인 중..."

        db.getReference("users").child(userId).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val dbId = snapshot.child("username").getValue(String::class.java)?.trim() ?: ""
                    val dbPw = snapshot.child("password").getValue(String::class.java)?.trim() ?: ""

                    if (dbId == trimId && dbPw == trimPw) {
                        _statusText.value = "본인 확인 완료. Wi-Fi 설정 이동."
                        _currentStep.value = 2
                    } else {
                        _statusText.value = "인증 실패: 정보 불일치"
                    }
                } else {
                    _statusText.value = "오류: 회원 정보를 찾을 수 없습니다."
                }
            }
            .addOnFailureListener { e ->
                _statusText.value = "서버 연결 실패: ${e.message}"
            }
    }

    // [핵심 로직] 기존 등록된 도어락인지 확인 후 전송
    fun sendWifiSettingsWithLocation(ssid: String, pw: String, lat: Double, lon: Double, alt: Double) {
        if (bluetoothGatt == null) {
            _statusText.value = "BLE 연결 상태를 확인해주세요."
            return
        }

        _statusText.value = "기기 등록 상태 확인 중..."

        // 1. 서버에서 이 MAC 주소를 가진 도어락 ID가 있는지 찾기
        findExistingDoorlockId(targetAddress) { existingId ->
            val finalId: String

            if (existingId != null) {
                // [CASE 1] 이미 등록된 기기 -> 기존 ID 유지 (로그 보존)
                finalId = existingId
                Log.d("WifiSetting", "기존 도어락 ID 유지: $finalId")
                _statusText.value = "기존 설정 유지하며 Wi-Fi 변경 중..."
            } else {
                // [CASE 2] 신규 기기 -> 6자리 새 ID 생성
                finalId = generateShortId()
                Log.d("WifiSetting", "새로운 도어락 ID 생성: $finalId")
                _statusText.value = "새 도어락 등록 중..."
            }

            // 2. 결정된 ID로 설정 진행
            if (lat == 0.0 && lon == 0.0) {
                getCurrentLocationAndRegister(targetAddress, finalId, ssid, pw)
            } else {
                registerSharedDoorlock(targetAddress, finalId, ssid, pw, lat, lon, alt)
                sendBlePayload(ssid, pw, finalId)
            }
        }
    }

    // 6자리 영문 대문자 + 숫자 랜덤 생성 함수
    private fun generateShortId(): String {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6)
            .map { charset.random() }
            .joinToString("")
    }

    // Firebase에서 내 도어락 목록을 뒤져서 MAC 주소가 일치하는 ID 찾기
    private fun findExistingDoorlockId(targetMac: String, onResult: (String?) -> Unit) {
        val userId = getSavedUserId()
        if (userId == null) {
            onResult(null)
            return
        }

        db.getReference("users").child(userId).child("my_doorlocks")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        onResult(null)
                        return
                    }
                    val doorlockIds = snapshot.children.mapNotNull { it.key }
                    checkMacAddressRecursive(doorlockIds, 0, targetMac, onResult)
                }

                override fun onCancelled(error: DatabaseError) {
                    onResult(null)
                }
            })
    }

    // 도어락 ID 리스트를 순회하며 MAC 주소 대조 (비동기 재귀)
    private fun checkMacAddressRecursive(ids: List<String>, index: Int, targetMac: String, onResult: (String?) -> Unit) {
        if (index >= ids.size) {
            onResult(null) // 다 찾았는데 없음 -> 신규
            return
        }

        val currentId = ids[index]
        db.getReference("doorlocks").child(currentId).child("mac").get()
            .addOnSuccessListener { macSnapshot ->
                val dbMac = macSnapshot.getValue(String::class.java)
                if (dbMac == targetMac) {
                    onResult(currentId) // 찾았다!
                } else {
                    checkMacAddressRecursive(ids, index + 1, targetMac, onResult) // 다음 것 검색
                }
            }
            .addOnFailureListener {
                checkMacAddressRecursive(ids, index + 1, targetMac, onResult)
            }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocationAndRegister(mac: String, doorlockId: String, ssid: String, pass: String) {
        val cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                val alt = location?.altitude ?: 0.0
                registerSharedDoorlock(mac, doorlockId, ssid, pass, lat, lon, alt)
                sendBlePayload(ssid, pass, doorlockId)
            }
            .addOnFailureListener {
                registerSharedDoorlock(mac, doorlockId, ssid, pass, 0.0, 0.0, 0.0)
                sendBlePayload(ssid, pass, doorlockId)
            }
    }

    private fun sendBlePayload(ssid: String, pw: String, id: String) {
        val payload = "ssid:$ssid,password:$pw,id:$id"
        Log.d("BLE", "Sending payload: $payload")

        // UI에는 비밀번호 등 민감정보 제외하고 표시
        _statusText.postValue("설정 전송 중... (ID: $id)")

        val result = writeCharacteristic(WIFI_CTRL_UUID, payload)
        if (!result) {
            _statusText.postValue("전송 실패: 도어락 서비스를 찾을 수 없습니다.")
        }
    }

    fun sendWifiSettings(ssid: String, pass: String) {
        sendWifiSettingsWithLocation(ssid, pass, 0.0, 0.0, 0.0)
    }

    private fun registerSharedDoorlock(mac: String, doorlockId: String, ssid: String, pass: String, lat: Double, lon: Double, alt: Double) {
        val userId = getSavedUserId() ?: return
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val doorlocksRef = db.getReference("doorlocks").child(doorlockId)
        val userDoorlocksRef = db.getReference("users").child(userId).child("my_doorlocks")

        val fixedLocation = FixedLocation(latitude = lat, longitude = lon, altitude = alt)
        val members = HashMap<String, String>()
        members[userId] = "admin"

        val newLock = Doorlock(
            mac = mac,
            ssid = ssid,
            pw = pass,
            detailSettings = DetailSettings(true, 5, true),
            members = members,
            location = fixedLocation,
            lastUpdated = currentTime
        )

        // 1. 도어락 정보 저장 (기존 정보가 있어도 업데이트됨)
        doorlocksRef.setValue(newLock).addOnSuccessListener {
            // 초기 명령 및 상태 설정 (이미 존재하는 경우 상태를 덮어쓰지 않으려면 체크 필요하지만, 설정 변경이므로 초기화함)
            doorlocksRef.child("command").setValue("INIT")

            // 상태 정보가 없을 때만 초기화 (기존 로그 보존 위해)
            doorlocksRef.child("status").get().addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    val initialStatus = mapOf(
                        "state" to "LOCK",
                        "last_method" to "INIT",
                        "last_time" to currentTime,
                        "door_closed" to true
                    )
                    doorlocksRef.child("status").setValue(initialStatus)
                }
            }
        }

        // 2. 내 도어락 목록에 추가 (이미 있으면 변화 없음)
        userDoorlocksRef.child(doorlockId).setValue(true)
    }

    private fun connectGatt(address: String) {
        try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            bluetoothGatt?.close()
            bluetoothGatt = device?.connectGatt(getApplication(), false, gattCallback)
        } catch (e: Exception) {
            _statusText.value = "주소 오류: $address"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _isBleConnected.postValue(true)
                _statusText.postValue("도어락 연결 성공! 서비스 탐색 중...")
                gatt?.discoverServices()
            } else {
                _isBleConnected.postValue(false)
                _statusText.postValue("연결 끊어짐")
                closeGatt()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(PROV_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(WIFI_CTRL_UUID)
                if (service != null && characteristic != null) {
                    subscribeNotifications()
                } else {
                    _statusText.postValue("도어락 서비스(UUID)를 찾을 수 없습니다.")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val response = String(value, Charsets.UTF_8)
            handleResponse(response)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, c: BluetoothGattCharacteristic?) {
            c?.let { handleResponse(String(it.value, Charsets.UTF_8)) }
        }

        private fun handleResponse(response: String) {
            if (response == "WIFI_TRYING") {
                _statusText.postValue("도어락이 Wi-Fi에 연결 중입니다...")
            } else if (response == "WIFI_OK") {
                _statusText.postValue("성공: 설정 완료! (기기 등록됨)")
                closeGatt()
            } else if (response == "WIFI_FAIL") {
                _statusText.postValue("실패: Wi-Fi 연결 실패 (비번 확인)")
            } else if (response == "FMT_ERR") {
                _statusText.postValue("실패: 데이터 형식 오류")
            } else {
                _statusText.postValue("응답: $response")
            }
        }
    }

    private fun subscribeNotifications() {
        val s = bluetoothGatt?.getService(PROV_SERVICE_UUID)
        val c = s?.getCharacteristic(WIFI_CTRL_UUID)
        val d = c?.getDescriptor(CCCD_UUID)
        if (c != null && d != null) {
            bluetoothGatt?.setCharacteristicNotification(c, true)
            val payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeDescriptor(d, payload)
            } else {
                @Suppress("DEPRECATION")
                d.value = payload
                @Suppress("DEPRECATION")
                bluetoothGatt?.writeDescriptor(d)
            }
        }
    }

    private fun writeCharacteristic(uuid: UUID, value: String): Boolean {
        val service = bluetoothGatt?.getService(PROV_SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(uuid) ?: return false
        val payload = value.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeCharacteristic(characteristic)
        }
        return true
    }

    fun disconnect() = closeGatt()
    private fun closeGatt() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _isBleConnected.postValue(false)
    }
}