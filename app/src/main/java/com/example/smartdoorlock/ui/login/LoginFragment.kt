package com.example.smartdoorlock.ui.login

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.smartdoorlock.R
import com.example.smartdoorlock.databinding.FragmentLoginBinding
import com.google.firebase.auth.FirebaseAuth

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()

        val prefs = requireActivity().getSharedPreferences("login_prefs", Context.MODE_PRIVATE)

        // 저장된 아이디와 설정 불러오기
        val savedId = prefs.getString("saved_id", "")
        val autoLogin = prefs.getBoolean("auto_login", false)
        val rememberId = prefs.getBoolean("remember_id", false)

        if (rememberId) {
            binding.editTextId.setText(savedId)
            binding.checkboxSaveId.isChecked = true
        }
        binding.checkboxAutoLogin.isChecked = autoLogin

        if (autoLogin && auth.currentUser != null && !savedId.isNullOrEmpty()) {
            navigateToDashboard()
            return
        }

        // 로그인 버튼 클릭
        binding.buttonLogin.setOnClickListener {
            val inputId = binding.editTextId.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()

            if (inputId.isEmpty() || password.isEmpty()) {
                Toast.makeText(context, "아이디와 비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val emailForAuth = if(inputId.contains("@")) inputId else "$inputId@doorlock.com"

            auth.signInWithEmailAndPassword(emailForAuth, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val editor = prefs.edit()
                        editor.putString("saved_id", inputId)
                        editor.putBoolean("remember_id", binding.checkboxSaveId.isChecked)
                        editor.putBoolean("auto_login", binding.checkboxAutoLogin.isChecked)
                        editor.apply()

                        Toast.makeText(context, "로그인 성공", Toast.LENGTH_SHORT).show()
                        navigateToDashboard()
                    } else {
                        Toast.makeText(context, "로그인 실패: 아이디나 비밀번호를 확인하세요.", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        binding.buttonSignUp.setOnClickListener {
            findNavController().navigate(R.id.navigation_register)
        }

        // [추가] 비밀번호 찾기 화면으로 이동
        binding.textFindPassword.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_find_password)
        }
    }

    private fun navigateToDashboard() {
        try {
            findNavController().navigate(R.id.action_login_to_dashboard)
        } catch (e: Exception) {
            findNavController().navigate(R.id.navigation_dashboard)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}