package com.example.smartdoorlock.ui.setting

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.smartdoorlock.data.AppLogItem
import com.example.smartdoorlock.databinding.FragmentDoorlockPasswordBinding
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class DoorlockPasswordFragment : Fragment() {

    private var _binding: FragmentDoorlockPasswordBinding? = null
    private val binding get() = _binding!!
    private val database = FirebaseDatabase.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDoorlockPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnChangePin.setOnClickListener {
            val inputCurrentPin = binding.etCurrentDoorlockPin.text.toString().trim()
            val newPin = binding.etNewDoorlockPin.text.toString().trim()
            val confirmPin = binding.etConfirmDoorlockPin.text.toString().trim()

            // 1. 저장된 도어락 비밀번호 불러오기 (기본값: 1234)
            val prefs = requireActivity().getSharedPreferences("doorlock_prefs", Context.MODE_PRIVATE)
            val savedPin = prefs.getString("saved_doorlock_pin", "1234") ?: "1234"

            // 2. 유효성 검사
            if (inputCurrentPin.isEmpty()) {
                Toast.makeText(context, "현재 도어락 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (inputCurrentPin != savedPin) {
                Toast.makeText(context, "현재 비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPin.length < 4) {
                Toast.makeText(context, "새 비밀번호는 4자리 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPin != confirmPin) {
                Toast.makeText(context, "새 비밀번호가 서로 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPin == savedPin) {
                Toast.makeText(context, "새 비밀번호가 현재 비밀번호와 동일합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 3. 검증 완료 -> 변경 시도
            changeDoorlockPin(newPin)
        }
    }

    private fun changeDoorlockPin(newPin: String) {
        val prefsLogin = requireActivity().getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val userId = prefsLogin.getString("saved_id", null) ?: return

        binding.btnChangePin.isEnabled = false
        binding.btnChangePin.text = "변경 요청 중..."

        // 내 도어락 ID 찾기
        database.getReference("users").child(userId).child("my_doorlocks")
            .limitToFirst(1).get().addOnSuccessListener { snapshot ->
                if (snapshot.exists() && snapshot.hasChildren()) {
                    val doorlockId = snapshot.children.first().key

                    if (doorlockId != null) {
                        // [명령 전송] SET_PIN:새비밀번호
                        val command = "SET_PIN:$newPin"

                        database.getReference("doorlocks").child(doorlockId).child("command")
                            .setValue(command)
                            .addOnSuccessListener {
                                // [성공] 앱 내부 저장소도 업데이트
                                updateLocalPin(newPin)

                                logAppEvent(userId, "도어락 비밀번호 변경 완료")
                                Toast.makeText(context, "비밀번호 변경이 완료되었습니다.", Toast.LENGTH_LONG).show()
                                findNavController().popBackStack()
                            }
                            .addOnFailureListener {
                                Toast.makeText(context, "전송 실패: ${it.message}", Toast.LENGTH_SHORT).show()
                                binding.btnChangePin.isEnabled = true
                                binding.btnChangePin.text = "비밀번호 변경하기"
                            }
                    }
                } else {
                    Toast.makeText(context, "등록된 도어락을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    binding.btnChangePin.isEnabled = true
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "DB 오류: ${it.message}", Toast.LENGTH_SHORT).show()
                binding.btnChangePin.isEnabled = true
            }
    }

    private fun updateLocalPin(newPin: String) {
        val prefs = requireActivity().getSharedPreferences("doorlock_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("saved_doorlock_pin", newPin).apply()
    }

    private fun logAppEvent(userId: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logItem = AppLogItem(message, timestamp)
        database.getReference("users").child(userId).child("app_logs").push().setValue(logItem)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}