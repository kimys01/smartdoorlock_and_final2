package com.example.smartdoorlock.ui.dashboard

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.smartdoorlock.R
import com.example.smartdoorlock.databinding.FragmentDashboardBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    // Firebase 인스턴스
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    // 상태 감지 리스너 (Status 하나만 감시)
    private var statusListener: ValueEventListener? = null
    private var statusRef: DatabaseReference? = null
    private var currentDoorlockId: String? = null

    // 상태 캐시
    private var lastKnownState: String = ""

    // [설정] 만약 도어락 ID가 바뀌면 이 부분을 수정하세요!
    private val TARGET_DOORLOCK_ID = "U18F69"

    companion object {
        private const val TAG = "DashboardFragment"

        // UI 색상 상수
        private const val COLOR_LOCKED = "#DC2626"      // 빨간색 (LOCK은 위험/정지를 상징)
        private const val COLOR_LOCKED_BG = "#FEE2E2"
        private const val COLOR_UNLOCKED = "#2196F3"    // 파란색
        private const val COLOR_UNLOCKED_BG = "#E3F2FD"
        private const val COLOR_OFFLINE = "#9E9E9E"     // 회색
        private const val COLOR_OFFLINE_BG = "#F3F4F6"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnAddDevice.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_scan)
        }

        binding.btnUnlock.setOnClickListener {
            sendDoorCommand()
        }

        updateDashboardUI("연결 중...", false)

        // 도어락 찾기 시작
        findAndMonitorDoorlock()
    }

    // [1] 도어락 찾기 & 없으면 자동 등록
    private fun findAndMonitorDoorlock() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            updateDashboardUI("로그인 필요", false)
            return
        }
        val userId = currentUser.uid

        // 내 계정의 도어락 목록 조회
        database.getReference("users").child(userId).child("my_doorlocks")
            .limitToFirst(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener

                if (snapshot.exists() && snapshot.childrenCount > 0) {
                    // 1. 정상적으로 찾았을 때
                    currentDoorlockId = snapshot.children.first().key
                    if (currentDoorlockId != null) {
                        Log.d(TAG, "ID 발견: $currentDoorlockId")
                        startRealtimeMonitoring(currentDoorlockId!!)
                    }
                } else {
                    // 2. [자동 복구] 등록된 기기가 없으면 강제로 등록 시도!
                    Log.d(TAG, "등록된 기기 없음 -> 자동 등록 시도")
                    autoRegisterDoorlock(userId)
                }
            }
            .addOnFailureListener {
                updateDashboardUI("데이터 로드 실패", false)
                Toast.makeText(context, "인터넷 연결을 확인하세요", Toast.LENGTH_SHORT).show()
            }
    }

    // [2] 자동 등록 함수
    private fun autoRegisterDoorlock(userId: String) {
        Toast.makeText(context, "기기 자동 등록 중...", Toast.LENGTH_SHORT).show()

        // users/{userId}/my_doorlocks/{TARGET_ID} = true 로 저장
        database.getReference("users").child(userId).child("my_doorlocks")
            .child(TARGET_DOORLOCK_ID)
            .setValue(true)
            .addOnSuccessListener {
                Toast.makeText(context, "✅ 자동 등록 완료!", Toast.LENGTH_SHORT).show()
                currentDoorlockId = TARGET_DOORLOCK_ID
                startRealtimeMonitoring(TARGET_DOORLOCK_ID)
            }
            .addOnFailureListener {
                updateDashboardUI("등록 실패", false)
                Toast.makeText(context, "자동 등록 실패: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    // [핵심 3] 실시간 모니터링 (오직 Status/State만 봅니다)
    private fun startRealtimeMonitoring(doorlockId: String) {
        if (statusRef != null && statusListener != null) {
            statusRef?.removeEventListener(statusListener!!)
        }

        // 경로: doorlocks/{ID}/status
        statusRef = database.getReference("doorlocks").child(doorlockId).child("status")

        statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return

                if (snapshot.exists()) {
                    // 오직 'state'와 'last_time'만 가져옵니다. (센서 무시)
                    val state = snapshot.child("state").getValue(String::class.java) ?: "UNKNOWN"
                    val lastTime = snapshot.child("last_time").getValue(String::class.java) ?: ""

                    // 상태 변화 감지
                    if (state != lastKnownState || binding.txtStatus.text == "연결 중...") {
                        lastKnownState = state
                        updateUIByState(state, lastTime)
                    } else {
                        binding.txtLastUpdated.text = if (lastTime.isNotEmpty()) "마지막 동작: $lastTime" else "최신 상태"
                    }
                } else {
                    updateDashboardUI("상태 대기 중...", false)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                updateDashboardUI("연결 끊김", false)
            }
        }
        statusRef?.addValueEventListener(statusListener!!)
    }

    // [핵심 4] UI 업데이트 (단순화: 열림 vs 닫힘)
    private fun updateUIByState(state: String, time: String) {
        if (_binding == null) return

        // UNLOCK 또는 OPEN이면 열림으로 간주
        val isUnlocked = (state.uppercase().contains("UNLOCK") || state.uppercase().contains("OPEN"))

        var statusText = ""
        var themeColor = Color.parseColor(COLOR_OFFLINE)
        var bgColor = Color.parseColor(COLOR_OFFLINE_BG)
        var iconRes = android.R.drawable.ic_lock_idle_lock

        if (isUnlocked) {
            // [열림 상태] - 파란색 유지
            statusText = "문이 열렸습니다"
            themeColor = Color.parseColor(COLOR_UNLOCKED)
            bgColor = Color.parseColor(COLOR_UNLOCKED_BG)
            iconRes = R.drawable.ic_lock_open // 수정된 굵은 선의 열림 아이콘
        } else {
            // [닫힘 상태] - 초록색에서 빨간색으로 변경
            statusText = "문이 닫혔습니다"
            themeColor = Color.parseColor(COLOR_LOCKED) // <-- 빨간색 적용
            bgColor = Color.parseColor(COLOR_LOCKED_BG) // <-- 연한 빨간색 배경 적용
            iconRes = R.drawable.ic_lock_idle_lock // 굵은 선의 잠금 아이콘
        }

        binding.txtStatus.text = statusText
        binding.txtLastUpdated.text = if (time.isNotEmpty()) "마지막 동작: $time" else "업데이트 됨"

        binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(bgColor)
        binding.imgStatusIcon.setColorFilter(themeColor)
        binding.imgStatusIcon.setImageResource(iconRes)

        // 버튼 텍스트: 열려있으면 '잠그기', 닫혀있으면 '열기'
        binding.tvUnlockLabel.text = if (isUnlocked) "문 잠그기" else "문 열기"
        binding.imgUnlockBtnIcon.setColorFilter(themeColor)
        binding.imgUnlockBtnIcon.setImageResource(
            if (isUnlocked) R.drawable.ic_lock_idle_lock // 잠그기 아이콘 (수정된 굵은 선 아이콘)
            else R.drawable.ic_lock_open // 열기 아이콘
        )

        binding.btnUnlock.isEnabled = true
        binding.btnUnlock.alpha = 1.0f
    }

    private fun updateDashboardUI(statusText: String, isEnabled: Boolean) {
        if (_binding == null) return
        binding.txtStatus.text = statusText
        binding.btnUnlock.isEnabled = isEnabled
        binding.btnUnlock.alpha = if (isEnabled) 1.0f else 0.5f
    }

    private fun sendDoorCommand() {
        if (currentDoorlockId == null) return

        val targetCommand = if (lastKnownState.uppercase().contains("UNLOCK")) "LOCK" else "UNLOCK"

        binding.btnUnlock.isEnabled = false
        binding.btnUnlock.alpha = 0.5f
        binding.tvUnlockLabel.text = "처리 중..."

        database.getReference("doorlocks").child(currentDoorlockId!!).child("command")
            .setValue(targetCommand)
            .addOnSuccessListener {
                saveLogToSharedDoorlock(targetCommand)
                Toast.makeText(context, "명령 전송됨", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "전송 실패", Toast.LENGTH_SHORT).show()
                binding.btnUnlock.isEnabled = true
                binding.btnUnlock.alpha = 1.0f
                binding.tvUnlockLabel.text = "재시도"
            }
    }

    private fun saveLogToSharedDoorlock(command: String) {
        if (currentDoorlockId == null) return

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val userName = auth.currentUser?.displayName ?: auth.currentUser?.email ?: "AppUser"

        val logData = mapOf(
            "time" to timestamp,
            "state" to command,
            "method" to "APP_REMOTE",
            "user" to userName
        )

        // 1. Logs 저장
        database.getReference("doorlocks").child(currentDoorlockId!!).child("logs").push().setValue(logData)

        // 2. Status 업데이트 (화면 즉시 반영을 위해)
        val statusUpdates = mapOf(
            "state" to command,
            "last_time" to timestamp,
            "last_method" to "APP_REMOTE"
        )
        database.getReference("doorlocks").child(currentDoorlockId!!).child("status").updateChildren(statusUpdates)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (statusListener != null && statusRef != null) {
            statusRef?.removeEventListener(statusListener!!)
        }
        _binding = null
    }
}
