package com.example.smartdoorlock.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.smartdoorlock.R
import com.example.smartdoorlock.databinding.FragmentSettingBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. 디바이스 연결
        binding.deviceScanFragment.setOnClickListener { safeNavigate(R.id.action_settings_to_scan) }

        // [수정] 2. 통합 설정 (새로 만든 버튼 ID 사용)
        // 주의: fragment_setting.xml에 buttonUnifiedSetting ID가 있어야 오류가 나지 않습니다.
        binding.buttonUnifiedSetting.setOnClickListener { safeNavigate(R.id.navigation_unified_setting) }

        // 3. Wi-Fi 설정 (중복되지 않게 별도로 설정)
        binding.buttonWifiSetting.setOnClickListener { safeNavigate(R.id.wifiSettingFragment) }

        // 4. 도움말
        binding.buttonHelp.setOnClickListener { safeNavigate(R.id.navigation_help) }

        // 5. 사용자 초대
        binding.buttonInviteMember.setOnClickListener { safeNavigate(R.id.navigation_add_member) }

        // 6. 도어락 비밀번호 변경
        binding.buttonChangeDoorlockPin.setOnClickListener { safeNavigate(R.id.navigation_doorlock_password) }
    }

    private fun safeNavigate(destinationId: Int) {
        val navController = findNavController()
        // 현재 위치가 설정 화면일 때만 이동 (중복 클릭 크래시 방지)
        if (navController.currentDestination?.id == R.id.navigation_settings) {
            try {
                navController.navigate(destinationId)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}