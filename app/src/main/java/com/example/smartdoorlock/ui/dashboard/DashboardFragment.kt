package com.example.smartdoorlock.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.smartdoorlock.R
import com.example.smartdoorlock.databinding.FragmentDashboardBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * DashboardFragment v2.1 - UI Update
 *
 * 📱 XML Layout: 새로운 CardView 기반 디자인 적용
 * 📡 Logic: Firebase 실시간 연동 유지
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    // Firebase 리스너
    private var statusListener: ValueEventListener? = null
    private var statusRef: DatabaseReference? = null
    private var currentDoorlockId: String? = null

    // 상태 캐시 (중복 업데이트 방지)
    private var lastKnownState: String = ""

    companion object {
        private const val TAG = "Dashboard"
        // 색상 상수
        private const val COLOR_LOCKED = "#4CAF50"   // 초록색 (잠김)
        private const val COLOR_LOCKED_BG = "#E8F5E9" // 연한 초록색 배경
        private const val COLOR_UNLOCKED = "#2196F3" // 파란색 (열림)
        private const val COLOR_UNLOCKED_BG = "#E3F2FD" // 연한 파란색 배경
        private const val COLOR_OFFLINE = "#9E9E9E"  // 회색 (오프라인)
        private const val COLOR_OFFLINE_BG = "#F3F4F6" // 연한 회색 배경
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. 기기 추가 버튼 (CardView)
        binding.btnAddDevice.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_dashboard_to_scan)
            } catch (e: Exception) {
                Log.e(TAG, "Navigation error", e)
            }
        }

        // 2. 문 제어 버튼 (CardView)
        binding.btnUnlock.setOnClickListener {
            sendDoorCommand()
        }

        // 3. 초기 상태 설정 (로딩 중)
        updateDashboardUI("연결 중...", false)

        // 4. 도어락 모니터링 시작
        checkAndMonitorDoorlock()
    }

    /**
     * 사용자의 도어락 ID 조회 후 모니터링 시작
     */
    private fun checkAndMonitorDoorlock() {
        val prefs = requireActivity().getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("saved_id", null)

        if (userId == null) {
            updateDashboardUI("로그인 필요", false)
            return
        }

        database.getReference("users").child(userId).child("my_doorlocks")
            .limitToFirst(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener

                if (snapshot.exists() && snapshot.childrenCount > 0) {
                    currentDoorlockId = snapshot.children.first().key
                    if (currentDoorlockId != null) {
                        startRealtimeMonitoring(currentDoorlockId!!)
                    }
                } else {
                    updateDashboardUI("기기 없음", false)
                    // 기기가 없을 때 상태 텍스트 안내
                    binding.txtStatus.text = "등록된 기기가 없습니다"
                    binding.txtLastUpdated.text = "기기 추가 버튼을 눌러 등록해주세요"
                }
            }
            .addOnFailureListener {
                updateDashboardUI("로드 실패", false)
            }
    }

    /**
     * 실시간 상태 모니터링
     */
    private fun startRealtimeMonitoring(doorlockId: String) {
        if (statusRef != null && statusListener != null) {
            statusRef?.removeEventListener(statusListener!!)
        }

        statusRef = database.getReference("doorlocks").child(doorlockId).child("status")

        statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return

                if (!snapshot.exists()) {
                    updateDashboardUI("상태 정보 없음", false)
                    return
                }

                val state = snapshot.child("state").getValue(String::class.java) ?: "UNKNOWN"
                val lastMethod = snapshot.child("last_method").getValue(String::class.java) ?: ""
                val lastTime = snapshot.child("last_time").getValue(String::class.java) ?: ""

                // 상태 변경 감지
                if (state != lastKnownState) {
                    lastKnownState = state
                    updateUIByState(state, lastMethod, lastTime)
                } else {
                    // 상태는 같아도 시간 정보는 업데이트
                    binding.txtLastUpdated.text = "마지막 동작: $lastTime"
                }
            }

            override fun onCancelled(error: DatabaseError) {
                updateDashboardUI("연결 오류", false)
            }
        }

        statusRef?.addValueEventListener(statusListener!!)
    }

    /**
     * 상태(LOCK/UNLOCK)에 따른 UI 디자인 변경
     */
    private fun updateUIByState(state: String, method: String, time: String) {
        if (_binding == null) return

        val isUnlocked = (state.uppercase() == "UNLOCK" || state.uppercase() == "OPEN")

        // 1. 메인 상태 텍스트 & 시간
        binding.txtStatus.text = if (isUnlocked) "문이 열려 있습니다" else "문이 잠겨 있습니다"
        binding.txtLastUpdated.text = if (time.isNotEmpty()) "마지막 동작: $time" else "업데이트 됨"

        // 2. 색상 설정
        val themeColor = Color.parseColor(if (isUnlocked) COLOR_UNLOCKED else COLOR_LOCKED)
        val bgColor = Color.parseColor(if (isUnlocked) COLOR_UNLOCKED_BG else COLOR_LOCKED_BG)

        // 3. 상태 아이콘 영역 (원형 배경 + 아이콘 색상)
        binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(bgColor)
        binding.imgStatusIcon.setColorFilter(themeColor)

        // 아이콘 리소스 변경 (기본 제공 아이콘 활용)
        binding.imgStatusIcon.setImageResource(
            if (isUnlocked) R.drawable.ic_lock_open // 생성한 로컬 리소스 사용
            else android.R.drawable.ic_lock_idle_lock
        )

        // 4. 컨트롤 버튼 (CardView) 업데이트
        binding.tvUnlockLabel.text = if (isUnlocked) "문 잠그기" else "문 열기"
        binding.imgUnlockBtnIcon.setImageResource(
            if (isUnlocked) android.R.drawable.ic_lock_idle_lock
            else R.drawable.ic_lock_open
        )

        binding.imgUnlockBtnIcon.setColorFilter(themeColor) // 버튼 내부 아이콘도 상태 색상 따라감

        // 버튼 활성화
        binding.btnUnlock.isEnabled = true
        binding.btnUnlock.alpha = 1.0f
    }

    /**
     * 기본 UI 업데이트 (에러, 로딩 등)
     */
    private fun updateDashboardUI(statusText: String, isEnabled: Boolean) {
        if (_binding == null) return

        binding.txtStatus.text = statusText
        binding.btnUnlock.isEnabled = isEnabled
        binding.btnUnlock.alpha = if (isEnabled) 1.0f else 0.5f // 비활성화 시 흐리게

        // 오프라인/대기 모드 색상
        if (!isEnabled) {
            val greyColor = Color.parseColor(COLOR_OFFLINE)
            val greyBg = Color.parseColor(COLOR_OFFLINE_BG)

            binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(greyBg)
            binding.imgStatusIcon.setColorFilter(greyColor)
            binding.imgUnlockBtnIcon.setColorFilter(greyColor)
        }
    }

    /**
     * 명령 전송 (LOCK <-> UNLOCK 토글)
     */
    private fun sendDoorCommand() {
        if (currentDoorlockId == null) {
            Toast.makeText(context, "기기가 연결되지 않았습니다", Toast.LENGTH_SHORT).show()
            return
        }

        // 현재 상태의 반대 명령
        val newCommand = if (lastKnownState.uppercase() == "UNLOCK" ||
            lastKnownState.uppercase() == "OPEN") "LOCK" else "UNLOCK"

        // 버튼 임시 비활성화 (UX)
        binding.btnUnlock.isEnabled = false
        binding.btnUnlock.alpha = 0.5f
        binding.tvUnlockLabel.text = "처리 중..."

        val commandRef = database.getReference("doorlocks")
            .child(currentDoorlockId!!)
            .child("command")

        commandRef.setValue(newCommand)
            .addOnSuccessListener {
                saveLogToDoorlock(newCommand)
                Toast.makeText(context, "명령 전송됨: $newCommand", Toast.LENGTH_SHORT).show()
                // 버튼 상태는 리스너(startRealtimeMonitoring)가 상태 변화를 감지하면 다시 활성화됨
            }
            .addOnFailureListener { e ->
                binding.btnUnlock.isEnabled = true
                binding.btnUnlock.alpha = 1.0f
                binding.tvUnlockLabel.text = "재시도"
                Toast.makeText(context, "전송 실패", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveLogToDoorlock(command: String) {
        if (currentDoorlockId == null) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val user = auth.currentUser?.displayName ?: "AppUser"

        val logData = mapOf(
            "time" to timestamp,
            "state" to command,
            "method" to "APP_REMOTE",
            "user" to user
        )
        database.getReference("doorlocks").child(currentDoorlockId!!).child("logs").push().setValue(logData)
    }

    override fun onResume() {
        super.onResume()
        currentDoorlockId?.let { startRealtimeMonitoring(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (statusListener != null && statusRef != null) {
            statusRef?.removeEventListener(statusListener!!)
        }
        _binding = null
    }
}