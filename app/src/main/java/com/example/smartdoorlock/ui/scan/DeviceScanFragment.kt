package com.example.smartdoorlock.ui.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartdoorlock.R
import com.example.smartdoorlock.databinding.FragmentDeviceScanBinding

/**
 * [수정] SmartDoorlock 기기만 필터링하여 표시
 */
class DeviceScanFragment : Fragment() {

    private var _binding: FragmentDeviceScanBinding? = null
    private val binding get() = _binding!!

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // SmartDoorlock 기기만 저장
    private val scanResults = ArrayList<Pair<BluetoothDevice, String>>()
    private lateinit var deviceAdapter: DeviceAdapterWithName
    private val SCAN_PERIOD: Long = 10000

    // [핵심] 필터링할 기기 이름
    private val TARGET_DEVICE_NAME = "SmartDoorlock"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            Log.d("BLE_SCAN", "✅ 모든 권한 허용됨")
            startScan()
        } else {
            Log.e("BLE_SCAN", "❌ 거부된 권한: ${permissions.filter { !it.value }.keys}")
            Toast.makeText(context, "모든 권한을 허용해야 도어락을 검색할 수 있습니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeviceScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager == null) {
            Toast.makeText(context, "블루투스를 지원하지 않는 기기입니다.", Toast.LENGTH_LONG).show()
            return
        }

        bluetoothAdapter = bluetoothManager.adapter

        deviceAdapter = DeviceAdapterWithName(scanResults) { device, name ->
            stopScan()
            navigateToWifiSetting(device)
        }
        binding.recyclerViewDevices.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewDevices.adapter = deviceAdapter

        binding.btnStartScan.setOnClickListener {
            Log.d("BLE_SCAN", "스캔 버튼 클릭")
            if (checkPermissions()) {
                startScan()
            } else {
                Log.d("BLE_SCAN", "권한 요청 시작")
                requestPermissionLauncher.launch(getRequiredPermissions())
            }
        }

        // 초기 권한 상태 로그
        logPermissionStatus()
    }

    private fun logPermissionStatus() {
        val context = context ?: return
        Log.d("BLE_SCAN", "=== 권한 상태 확인 ===")
        getRequiredPermissions().forEach { permission ->
            val granted = ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            Log.d("BLE_SCAN", "$permission: ${if(granted) "✅ 허용" else "❌ 거부"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) {
            Log.d("BLE_SCAN", "이미 스캔 중")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "블루투스를 켜주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        scanResults.clear()
        deviceAdapter.notifyDataSetChanged()

        isScanning = true
        binding.progressBarScanning.visibility = View.VISIBLE
        binding.tvScanStatus.text = "주변 도어락 검색 중..."
        binding.btnStartScan.isEnabled = false

        Log.d("BLE_SCAN", "========================================")
        Log.d("BLE_SCAN", "도어락 스캔 시작")
        Log.d("BLE_SCAN", "필터: $TARGET_DEVICE_NAME")
        Log.d("BLE_SCAN", "========================================")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            bluetoothAdapter.bluetoothLeScanner?.startScan(null, scanSettings, leScanCallback)
            Log.d("BLE_SCAN", "✅ 스캔 시작 성공")
        } catch (e: Exception) {
            Log.e("BLE_SCAN", "❌ 스캔 시작 실패: ${e.message}")
            Toast.makeText(context, "스캔 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            stopScan()
        }

        handler.postDelayed({ stopScan() }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return

        isScanning = false

        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(leScanCallback)
            Log.d("BLE_SCAN", "========================================")
            Log.d("BLE_SCAN", "스캔 종료: ${scanResults.size}개 도어락 발견")
            Log.d("BLE_SCAN", "========================================")
        } catch (e: Exception) {
            Log.e("BLE_SCAN", "스캔 종료 실패: ${e.message}")
        }

        binding.progressBarScanning.visibility = View.INVISIBLE
        binding.tvScanStatus.text = "스캔 완료 (${scanResults.size}개 발견)"
        binding.btnStartScan.isEnabled = true

        if (scanResults.isEmpty()) {
            Toast.makeText(context,
                "주변에서 도어락을 찾을 수 없습니다.\nESP32가 켜져있는지 확인하세요.",
                Toast.LENGTH_LONG).show()
        }
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address

            // 1. 이름 가져오기 시도 (ScanRecord 우선, 없으면 Device 이름)
            val deviceName = result.scanRecord?.deviceName ?: device.name

            // 2. [디버깅 로그] 무엇이 스캔되었는지 로그창에 모두 출력
            Log.d("BLE_SCAN", "감지됨: ${deviceName ?: "이름없음"} [$address] (RSSI: ${result.rssi})")

            // 3. [수정됨] 필터링 조건 제거 (모든 기기 표시)
            // 원래 코드: if (deviceName != "SmartDoorlock") return
            // 수정 코드: 무조건 리스트에 추가하여 눈으로 확인

            // 중복 방지 (이미 리스트에 있으면 추가 안 함)
            val existingIndex = scanResults.indexOfFirst { it.first.address == address }
            if (existingIndex == -1) {
                // 리스트에 추가 (이름이 없으면 "이름 없음"으로 표시)
                scanResults.add(Pair(device, deviceName ?: "이름 없음"))

                // UI 업데이트
                handler.post {
                    deviceAdapter.notifyItemInserted(scanResults.size - 1)
                    binding.tvScanStatus.text = "검색 중... (${scanResults.size}개 발견)"
                }
            } else {
                // 이미 있는 기기라면, 이름이 갱신되었을 때 업데이트 (중요!)
                // 처음에 "이름 없음"이었다가 나중에 "SmartDoorlock"으로 정보가 들어올 수 있음
                if (deviceName != null && scanResults[existingIndex].second != deviceName) {
                    scanResults[existingIndex] = Pair(device, deviceName)
                    handler.post {
                        deviceAdapter.notifyItemChanged(existingIndex)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE_SCAN", "스캔 실패 에러: $errorCode")
            Toast.makeText(context, "스캔 실패 (에러코드: $errorCode)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        val context = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun navigateToWifiSetting(device: BluetoothDevice) {
        Log.d("BLE_SCAN", "도어락 선택: ${device.address}")

        val bundle = Bundle()
        bundle.putString("DEVICE_ADDRESS", device.address)

        try {
            findNavController().navigate(R.id.wifiSettingFragment, bundle)
        } catch (e: Exception) {
            Toast.makeText(context, "화면 이동 실패: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("BLE_SCAN", "네비게이션 실패", e)
        }
    }

    private fun checkPermissions(): Boolean {
        val context = context ?: return false
        return getRequiredPermissions().all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 이상
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)  // 이름 보기 위해 필수!
        } else {
            // Android 11 이하
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        return permissions.toTypedArray()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopScan()
        _binding = null
    }
}

/**
 * 도어락 목록 어댑터
 */
class DeviceAdapterWithName(
    private val devices: List<Pair<BluetoothDevice, String>>,
    private val onClick: (BluetoothDevice, String) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<DeviceAdapterWithName.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val name: android.widget.TextView = view.findViewById(R.id.deviceName)
        val address: android.widget.TextView = view.findViewById(R.id.deviceAddress)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DeviceViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val (device, name) = devices[position]

        // 도어락 이름 표시
        holder.name.text = name
        holder.name.setTextColor(0xFF2196F3.toInt()) // 파란색으로 강조

        holder.address.text = device.address

        holder.itemView.setOnClickListener {
            onClick(device, name)
        }
    }

    override fun getItemCount() = devices.size
}