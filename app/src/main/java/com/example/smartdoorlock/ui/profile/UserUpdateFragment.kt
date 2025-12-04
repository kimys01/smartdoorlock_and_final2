package com.example.smartdoorlock.ui.profile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.smartdoorlock.databinding.FragmentUserUpdateBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream

class UserUpdateFragment : Fragment() {

    private var _binding: FragmentUserUpdateBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private var selectedImageUri: Uri? = null

    // [수정] 갤러리 접근 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openGallery()
        } else {
            Toast.makeText(context, "사진을 선택하려면 갤러리 접근 권한을 허용해야 합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // 갤러리에서 사진 선택 후 결과 처리
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            selectedImageUri = data?.data
            if (selectedImageUri != null) {
                // 선택한 사진을 화면에 원형으로 미리 보여줌
                Glide.with(this)
                    .load(selectedImageUri)
                    .circleCrop()
                    .into(binding.imgProfile)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUserUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadUserInfo()

        // 사진 변경 버튼 클릭 시 권한 체크 후 갤러리 열기
        val imageClickListener = View.OnClickListener {
            checkPermissionAndOpenGallery()
        }
        binding.btnChangeImage.setOnClickListener(imageClickListener)
        binding.cardProfileImageContainer.setOnClickListener(imageClickListener)

        binding.btnSave.setOnClickListener {
            updateUserInfo()
        }

        binding.btnWithdraw.setOnClickListener {
            showWithdrawDialog()
        }
    }

    // [핵심] 안드로이드 버전에 맞춰 적절한 권한을 확인하고 요청하는 함수
    private fun checkPermissionAndOpenGallery() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            openGallery()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun loadUserInfo() {
        val user = auth.currentUser
        val userId = user?.uid ?: return

        if (user != null) {
            binding.etName.setText(user.displayName)

            // DB에 저장된 프로필 이미지 불러오기
            database.getReference("users").child(userId).child("profileImage").get().addOnSuccessListener {
                val dbImage = it.getValue(String::class.java)
                val targetUrl = if (!dbImage.isNullOrEmpty()) Uri.parse(dbImage) else user.photoUrl

                if (targetUrl != null) {
                    Glide.with(this).load(targetUrl).circleCrop().into(binding.imgProfile)
                }
            }

            // 이름 불러오기
            database.getReference("users").child(userId).child("name").get().addOnSuccessListener {
                val name = it.getValue(String::class.java)
                if (!name.isNullOrEmpty()) binding.etName.setText(name)
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryLauncher.launch(intent)
    }

    private fun updateUserInfo() {
        val user = auth.currentUser ?: return
        val newName = binding.etName.text.toString().trim()
        val newPw = binding.etPassword.text.toString().trim()
        val newPwConfirm = binding.etPasswordConfirm.text.toString().trim()

        if (newName.isEmpty()) {
            Toast.makeText(context, "이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        if (newPw.isNotEmpty()) {
            if (newPw != newPwConfirm) {
                Toast.makeText(context, "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                return
            }
            if (newPw.length < 6) {
                Toast.makeText(context, "비밀번호는 6자리 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
                return
            }
            user.updatePassword(newPw).addOnFailureListener {
                Toast.makeText(context, "비밀번호 변경 실패: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSave.isEnabled = false
        binding.btnSave.text = "저장 중..."

        // 이미지가 변경되었다면 업로드 후 정보 저장, 아니면 정보만 저장
        if (selectedImageUri != null) {
            uploadImageAndSaveProfile(user, newName)
        } else {
            saveProfileChanges(user, newName, null)
        }
    }

    private fun uploadImageAndSaveProfile(user: com.google.firebase.auth.FirebaseUser, name: String) {
        val ref = storage.reference.child("profile_images/${user.uid}.jpg")

        try {
            // [수정] ImageDecoder를 사용하여 최신 안드로이드 버전에서도 안전하게 비트맵 변환
            val source = ImageDecoder.createSource(requireActivity().contentResolver, selectedImageUri!!)
            val bitmap = ImageDecoder.decodeBitmap(source)

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos) // 용량 최적화 (품질 70)
            val data = baos.toByteArray()

            ref.putBytes(data)
                .addOnSuccessListener {
                    ref.downloadUrl.addOnSuccessListener { uri ->
                        saveProfileChanges(user, name, uri)
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(context, "이미지 업로드 실패: ${it.message}", Toast.LENGTH_SHORT).show()
                    resetSaveButton()
                }
        } catch (e: Exception) {
            Toast.makeText(context, "이미지 처리 중 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            resetSaveButton()
        }
    }

    private fun saveProfileChanges(user: com.google.firebase.auth.FirebaseUser, name: String, photoUri: Uri?) {
        val profileUpdates = UserProfileChangeRequest.Builder()
            .setDisplayName(name)

        if (photoUri != null) {
            profileUpdates.setPhotoUri(photoUri)
        }

        user.updateProfile(profileUpdates.build())
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val prefs = requireActivity().getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                    val userId = prefs.getString("saved_id", null)

                    if (userId != null) {
                        val updates = hashMapOf<String, Any>("name" to name)
                        // DB에도 이미지 URL을 저장해야 ProfileFragment에서 즉시 보임
                        if (photoUri != null) {
                            updates["profileImage"] = photoUri.toString()
                        }

                        database.getReference("users").child(userId).updateChildren(updates)
                            .addOnSuccessListener {
                                Toast.makeText(context, "프로필이 업데이트되었습니다.", Toast.LENGTH_SHORT).show()
                                findNavController().navigateUp()
                            }
                            .addOnFailureListener {
                                Toast.makeText(context, "DB 저장 실패", Toast.LENGTH_SHORT).show()
                                resetSaveButton()
                            }
                    } else {
                        findNavController().navigateUp()
                    }
                } else {
                    Toast.makeText(context, "업데이트 실패: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    resetSaveButton()
                }
            }
    }

    private fun resetSaveButton() {
        binding.btnSave.isEnabled = true
        binding.btnSave.text = "저장"
    }

    private fun showWithdrawDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("회원 탈퇴")
            .setMessage("정말 탈퇴하시겠습니까? 모든 데이터가 삭제됩니다.")
            .setPositiveButton("탈퇴") { _, _ -> performWithdraw() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun performWithdraw() {
        val user = auth.currentUser
        val userId = requireActivity().getSharedPreferences("login_prefs", Context.MODE_PRIVATE).getString("saved_id", null)

        user?.delete()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                if (userId != null) database.getReference("users").child(userId).removeValue()
                requireActivity().getSharedPreferences("login_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                Toast.makeText(context, "탈퇴 완료", Toast.LENGTH_SHORT).show()
                activity?.finish()
            } else {
                Toast.makeText(context, "탈퇴 실패: 다시 로그인 후 시도해주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}