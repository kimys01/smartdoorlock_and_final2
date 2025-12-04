package com.example.smartdoorlock.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.smartdoorlock.databinding.FragmentAddMemberBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class AddMemberFragment : Fragment() {

    private var _binding: FragmentAddMemberBinding? = null
    private val binding get() = _binding!!
    private val database = FirebaseDatabase.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddMemberBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonInvite.setOnClickListener {
            val inviteId = binding.editTextInviteId.text.toString().trim()
            if (inviteId.isEmpty()) {
                Toast.makeText(context, "아이디를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 초대 시작
            checkAndInviteUser(inviteId)
        }
    }

    private fun checkAndInviteUser(targetUsername: String) {
        // 1. 초대할 사용자(targetUsername)가 실제로 DB에 존재하는지 확인
        val targetUserRef = database.getReference("users").child(targetUsername)

        targetUserRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    // 사용자가 존재함 -> 내 도어락 정보 가져와서 등록 절차 진행
                    addMemberToMyDoorlock(targetUsername)
                } else {
                    Toast.makeText(context, "존재하지 않는 사용자 아이디입니다.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "오류 발생: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun addMemberToMyDoorlock(targetUsername: String) {
        // 현재 로그인한 내 아이디 가져오기
        val prefs = requireActivity().getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val myId = prefs.getString("saved_id", null) ?: return

        // 내 도어락 목록에서 첫 번째 도어락의 MAC 주소 가져오기
        // (추후 도어락이 여러 개라면 선택하는 로직 필요)
        database.getReference("users").child(myId).child("my_doorlocks")
            .limitToFirst(1).get().addOnSuccessListener { snapshot ->
                if (snapshot.exists() && snapshot.hasChildren()) {
                    val myMac = snapshot.children.first().key

                    if (myMac != null) {
                        // [핵심 로직 1] 공용 도어락 데이터의 'members'에 사용자 추가
                        // 경로: doorlocks/{mac}/members/{targetID} = "member"
                        database.getReference("doorlocks").child(myMac).child("members")
                            .child(targetUsername).setValue("member")
                            .addOnSuccessListener {

                                // [핵심 로직 2] 초대된 사용자의 'my_doorlocks' 목록에도 내 도어락 추가
                                // 경로: users/{targetID}/my_doorlocks/{mac} = true
                                database.getReference("users").child(targetUsername).child("my_doorlocks")
                                    .child(myMac).setValue(true)
                                    .addOnSuccessListener {
                                        Toast.makeText(context, "$targetUsername 님이 도어락 멤버로 추가되었습니다!", Toast.LENGTH_LONG).show()
                                        // 완료 후 이전 화면으로 복귀
                                        findNavController().popBackStack()
                                    }
                            }
                    }
                } else {
                    Toast.makeText(context, "초대할 도어락이 없습니다. 먼저 도어락을 등록해주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "도어락 정보 로드 실패: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}