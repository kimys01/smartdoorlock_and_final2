package com.example.smartdoorlock.ui.login

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.smartdoorlock.databinding.FragmentFindPasswordBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class FindPasswordFragment : Fragment() {

    private var _binding: FragmentFindPasswordBinding? = null
    private val binding get() = _binding!!
    private val database = FirebaseDatabase.getInstance()

    // SMS 권한 요청 런처
    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            findAndSendPassword()
        } else {
            Toast.makeText(context, "문자 발송을 위해 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFindPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSendPassword.setOnClickListener {
            val id = binding.etFindId.text.toString().trim()
            val phone = binding.etFindPhone.text.toString().trim()

            if (id.isEmpty() || phone.isEmpty()) {
                Toast.makeText(context, "아이디와 휴대폰 번호를 모두 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // SMS 권한 체크
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                findAndSendPassword()
            } else {
                requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            }
        }
    }

    private fun findAndSendPassword() {
        val targetId = binding.etFindId.text.toString().trim()
        val targetPhone = binding.etFindPhone.text.toString().trim()

        binding.btnSendPassword.isEnabled = false
        Toast.makeText(context, "정보 확인 중...", Toast.LENGTH_SHORT).show()

        // DB에서 해당 아이디 조회
        database.getReference("users").child(targetId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                binding.btnSendPassword.isEnabled = true
                if (snapshot.exists()) {
                    val dbPhone = snapshot.child("phoneNumber").getValue(String::class.java)
                    val dbPassword = snapshot.child("password").getValue(String::class.java)

                    if (dbPhone == targetPhone) {
                        // 정보 일치 -> 비밀번호 전송
                        if (dbPassword != null) {
                            sendSms(targetPhone, "[SmartDoorLock] 비밀번호 안내: $dbPassword")
                        } else {
                            Toast.makeText(context, "비밀번호 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "휴대폰 번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "존재하지 않는 아이디입니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                binding.btnSendPassword.isEnabled = true
                Toast.makeText(context, "오류 발생: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun sendSms(phoneNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)

            Toast.makeText(context, "비밀번호가 문자로 전송되었습니다.", Toast.LENGTH_LONG).show()

            // [개발용 편의] 에뮬레이터에서는 문자가 안 갈 수 있으므로 토스트로도 살짝 보여줌
            // Toast.makeText(context, "(테스트용) PW: ${message.split(": ").last()}", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(context, "문자 전송 실패: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}