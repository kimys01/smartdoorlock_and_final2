package com.example.smartdoorlock.ui.notifications

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartdoorlock.databinding.FragmentNotificationsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.*
import kotlin.collections.ArrayList

/**
 * NotificationsFragment v2.4
 * - [Fix] 문잠김(LOCK) 필터링 시 문열림(UNLOCK)이 같이 나오는 버그 수정
 */
class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    // NotificationItem은 이제 같은 패키지의 별도 파일(NotificationItem.kt)에서 가져옵니다.
    private val fullLogList = ArrayList<NotificationItem>()
    private val filteredList = ArrayList<NotificationItem>()

    private lateinit var adapter: NotificationAdapter
    private var currentDoorlockId: String? = null

    private enum class FilterType { ALL, UNLOCK, LOCK, WARNING }
    private var currentFilter = FilterType.ALL

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        fetchDoorlockId()
    }

    private fun setupRecyclerView() {
        adapter = NotificationAdapter(filteredList)
        binding.recyclerViewNotifications.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewNotifications.adapter = adapter
    }

    private fun setupButtons() {
        binding.filterAll.setOnClickListener { applyFilter(FilterType.ALL) }
        binding.filterUnlock.setOnClickListener { applyFilter(FilterType.UNLOCK) }
        binding.filterLock.setOnClickListener { applyFilter(FilterType.LOCK) }
    }

    private fun fetchDoorlockId() {
        val prefs = requireActivity().getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("saved_id", null)

        if (userId == null) {
            updateEmptyState(true)
            return
        }

        database.getReference("users").child(userId).child("my_doorlocks")
            .limitToFirst(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists() && snapshot.childrenCount > 0) {
                    currentDoorlockId = snapshot.children.first().key
                    if (currentDoorlockId != null) {
                        startLogMonitoring(currentDoorlockId!!)
                    }
                } else {
                    updateEmptyState(true)
                }
            }
    }

    private fun startLogMonitoring(doorlockId: String) {
        val logsRef = database.getReference("doorlocks").child(doorlockId).child("logs")

        logsRef.limitToLast(100).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                fullLogList.clear()
                for (data in snapshot.children) {
                    try {
                        val time = data.child("time").getValue(String::class.java) ?: ""
                        val state = data.child("state").getValue(String::class.java) ?: ""
                        val method = data.child("method").getValue(String::class.java) ?: ""
                        val user = data.child("user").getValue(String::class.java) ?: ""

                        // 외부 파일의 NotificationItem 생성자를 호출합니다.
                        fullLogList.add(NotificationItem(time, state, method, user))
                    } catch (e: Exception) {
                        Log.e("Notifications", "Error parsing log", e)
                    }
                }
                fullLogList.reverse()
                applyFilter(currentFilter)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "로그 로드 실패", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun applyFilter(type: FilterType) {
        currentFilter = type
        updateFilterButtonsUI(type)

        filteredList.clear()
        if (type == FilterType.ALL) {
            filteredList.addAll(fullLogList)
        } else {
            for (item in fullLogList) {
                if (isItemMatchingFilter(item, type)) {
                    filteredList.add(item)
                }
            }
        }

        adapter.notifyDataSetChanged()
        updateEmptyState(filteredList.isEmpty())
    }

    private fun isItemMatchingFilter(item: NotificationItem, type: FilterType): Boolean {
        val state = item.state.uppercase()
        val method = item.method.uppercase()

        return when (type) {
            FilterType.UNLOCK -> state.contains("UNLOCK") || state.contains("OPEN")

            // [Fix] 문잠김 필터 수정: "LOCK"을 포함하지만 "UNLOCK"은 포함하지 않아야 함
            FilterType.LOCK -> (state.contains("LOCK") || state.contains("CLOSE")) && !state.contains("UNLOCK")

            FilterType.WARNING -> state.contains("FAIL") || state.contains("WARN") || method.contains("FAIL")
            else -> true
        }
    }

    private fun updateFilterButtonsUI(selectedType: FilterType) {
        resetButtonStyle(binding.filterAll)
        resetButtonStyle(binding.filterUnlock)
        resetButtonStyle(binding.filterLock)

        val selectedBtn = when (selectedType) {
            FilterType.ALL -> binding.filterAll
            FilterType.UNLOCK -> binding.filterUnlock
            FilterType.LOCK -> binding.filterLock
            else -> binding.filterAll
        }
        selectedBtn.setTextColor(Color.parseColor("#2196F3"))
    }

    private fun resetButtonStyle(btn: AppCompatButton) {
        btn.setTextColor(Color.parseColor("#555555"))
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            binding.layoutNoData.visibility = View.VISIBLE
            binding.recyclerViewNotifications.visibility = View.GONE
        } else {
            binding.layoutNoData.visibility = View.GONE
            binding.recyclerViewNotifications.visibility = View.VISIBLE
        }
    }

    private fun showDeleteConfirmDialog() {
        if (fullLogList.isEmpty()) {
            Toast.makeText(context, "삭제할 기록이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("기록 삭제")
            .setMessage("모든 활동 기록을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                clearAllLogs()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun clearAllLogs() {
        if (currentDoorlockId == null) return
        val logsRef = database.getReference("doorlocks").child(currentDoorlockId!!).child("logs")
        logsRef.removeValue()
            .addOnSuccessListener {
                Toast.makeText(context, "기록이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "삭제 실패", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}