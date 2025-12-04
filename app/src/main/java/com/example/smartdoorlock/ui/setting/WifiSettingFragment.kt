package com.example.smartdoorlock.ui.setting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.smartdoorlock.databinding.FragmentWifiSettingBinding

class WifiSettingFragment : Fragment() {

    private var _binding: FragmentWifiSettingBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: WifiSettingViewModel
    private var targetDeviceAddress: String = ""

    private val requestBlePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            // 권한 허용됨, 연결 시도
            if (targetDeviceAddress.isNotEmpty()) {
                viewModel.connectToDevice(targetDeviceAddress)
            }
        } else {
            Toast.makeText(context, "블루투스 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWifiSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 이전 화면(스캔 화면)에서 넘겨준 주소 받기
        arguments?.getString("DEVICE_ADDRESS")?.let { targetDeviceAddress = it }

        viewModel = ViewModelProvider(this)[WifiSettingViewModel::class.java]

        // 1. 화면 시작 시 연결 시도 (주소가 있을 경우에만)
        if (targetDeviceAddress.isNotEmpty()) {
            if (checkBlePermissions()) {
                viewModel.connectToDevice(targetDeviceAddress)
            } else {
                requestBlePermissions.launch(getRequiredBlePermissions())
            }
        } else {
            binding.textViewStatus.text = "기기 정보가 없습니다. 다시 스캔해주세요."
        }

        // 2. ViewModel 관찰
        viewModel.statusText.observe(viewLifecycleOwner) { status ->
            binding.textViewStatus.text = status
        }

        viewModel.isBleConnected.observe(viewLifecycleOwner) { isConnected ->
            binding.buttonLoginApp.isEnabled = isConnected
            binding.buttonConnectWifi.isEnabled = isConnected
        }

        viewModel.currentStep.observe(viewLifecycleOwner) { step ->
            updateUiStep(step)
            if (step == 2) fetchCurrentWifiSsid()
        }

        // 3. 버튼 리스너
        binding.buttonLoginApp.setOnClickListener {
            val id = binding.editTextUserId.text.toString().trim()
            val pw = binding.editTextUserPw.text.toString().trim()
            if (id.isNotEmpty() && pw.isNotEmpty()) viewModel.verifyAppAdmin(id, pw)
            else Toast.makeText(context, "아이디와 비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
        }

        binding.buttonConnectWifi.setOnClickListener {
            val ssid = binding.editTextSsid.text.toString()
            val pw = binding.editTextPassword.text.toString()
            if (ssid.isNotEmpty() && pw.isNotEmpty()) viewModel.sendWifiSettings(ssid, pw)
            else Toast.makeText(context, "Wi-Fi 정보를 입력하세요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchCurrentWifiSsid() {
        try {
            if (binding.editTextSsid.text.toString().isNotEmpty()) return
            val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            if (info != null && info.ssid != null && info.ssid != "<unknown ssid>") {
                // 따옴표 제거 로직
                val ssid = info.ssid.replace("\"", "")
                binding.editTextSsid.setText(ssid)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateUiStep(step: Int) {
        binding.layoutLoginSection.visibility = if (step == 0) View.VISIBLE else View.GONE
        binding.layoutWifiSection.visibility = if (step == 2) View.VISIBLE else View.GONE
    }

    private fun checkBlePermissions(): Boolean {
        val context = context ?: return false
        return getRequiredBlePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onStop() {
        super.onStop()
        // 화면 벗어나면 연결 해제 (배터리 절약 및 안정성)
        viewModel.disconnect()
    }
}