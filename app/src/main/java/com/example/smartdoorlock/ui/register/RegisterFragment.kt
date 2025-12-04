package com.example.smartdoorlock.ui.register

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.smartdoorlock.R
import com.example.smartdoorlock.data.*
import com.example.smartdoorlock.databinding.FragmentRegisterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.HashMap

class RegisterFragment : Fragment() {

    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()

        // 1. 뒤로가기 버튼 리스너
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // 2. 하단 '로그인하기' 텍스트 리스너
        binding.tvLoginLink.setOnClickListener {
            findNavController().navigateUp() // 로그인 화면(이전 화면)으로 복귀
        }

        // 3. 회원가입 버튼 리스너
        binding.buttonRegister.setOnClickListener {
            val id = binding.editTextId.text.toString().trim()
            val pw = binding.editTextPassword.text.toString().trim()
            val name = binding.editTextName.text.toString().trim()
            val phone = binding.editTextPhone.text.toString().trim()

            // 입력값 검증
            if (id.isEmpty() || pw.isEmpty() || name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(context, "모든 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!Pattern.matches("^[a-zA-Z0-9]*$", id)) {
                Toast.makeText(context, "아이디는 영어와 숫자만 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (pw.length < 6) {
                Toast.makeText(context, "비밀번호는 6자리 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!Pattern.matches("^[0-9]*$", phone)) {
                Toast.makeText(context, "휴대폰 번호는 숫자만 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 가입 프로세스 시작
            registerUser(id, pw, name, phone)
        }
    }

    private fun registerUser(username: String, password: String, name: String, phone: String) {
        binding.buttonRegister.isEnabled = false
        binding.buttonRegister.text = "가입 처리 중..."

        // Firebase Auth는 이메일 형식을 요구하므로 가짜 이메일 생성
        val fakeEmail = "$username@doorlock.com"

        auth.createUserWithEmailAndPassword(fakeEmail, password)
            .addOnSuccessListener { authResult ->
                val user = authResult.user
                val uid = user?.uid

                if (uid != null) {
                    // 프로필 이름 업데이트
                    val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(name).build()
                    user.updateProfile(profileUpdates).addOnCompleteListener {
                        // Realtime Database에 추가 정보 저장
                        saveFullUserStructure(username, password, name, phone)
                    }
                }
            }
            .addOnFailureListener { e ->
                binding.buttonRegister.isEnabled = true
                binding.buttonRegister.text = "회원가입 완료"

                val errorMsg = when {
                    e.message?.contains("email") == true -> "이미 사용 중인 아이디입니다."
                    e.message?.contains("weak") == true -> "비밀번호가 너무 쉽습니다."
                    else -> "가입 실패: ${e.message}"
                }
                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveFullUserStructure(username: String, password: String, name: String, phone: String) {
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        // 초기 앱 로그
        val initialLogs = HashMap<String, AppLogItem>()
        val logKey = database.reference.push().key ?: "init_log"
        initialLogs[logKey] = AppLogItem("계정 생성: Initial Set", currentTime)

        // 초기 위치 로그 (HashMap 사용) - 노드 생성용 더미 데이터
        val initialLocationLogs = HashMap<String, Any>()
        val locKey = database.reference.push().key ?: "init_loc"
        val dummyLoc = hashMapOf(
            "altitude" to 0.0,
            "latitude" to 0.0,
            "longitude" to 0.0,
            "timestamp" to currentTime
        )
        initialLocationLogs[locKey] = dummyLoc

        val initialDoorlock = UserDoorlock(
            status = DoorlockStatus(true, "INIT", currentTime, "LOCK")
        )

        val newUser = User(
            username = username,
            password = password,
            name = name,
            phoneNumber = phone,
            authMethod = "BLE",
            detailSettings = DetailSettings(true, 5, true),
            app_logs = initialLogs,
            doorlock = initialDoorlock,
            uwb_logs = HashMap(),
            location_logs = initialLocationLogs
        )

        // Firebase Realtime Database에 저장
        database.getReference("users").child(username).setValue(newUser)
            .addOnSuccessListener {
                Toast.makeText(context, "회원가입 완료! 로그인 해주세요.", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp() // 로그인 화면으로 돌아가기
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "DB 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.buttonRegister.isEnabled = true
                binding.buttonRegister.text = "회원가입 완료"
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}