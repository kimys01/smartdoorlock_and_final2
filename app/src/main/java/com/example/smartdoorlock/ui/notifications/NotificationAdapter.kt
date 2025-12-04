package com.example.smartdoorlock.ui.notifications

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdoorlock.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * NotificationAdapter
 * - NotificationsFragment의 NotificationItem 데이터를 받아 리스트에 표시
 * - DoorlockLog 대신 NotificationItem 사용 (Fragment와 통일)
 */
class NotificationAdapter(private val logs: List<NotificationItem>) :
    RecyclerView.Adapter<NotificationAdapter.LogViewHolder>() {

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val frameIconBackground: FrameLayout? = view.findViewById(R.id.frameIconBackground)
        val imgIcon: ImageView = view.findViewById(R.id.imgLogIcon)
        val txtTitle: TextView = view.findViewById(R.id.txtLogTitle)
        val txtUser: TextView = view.findViewById(R.id.txtLogUser)
        val txtMethod: TextView = view.findViewById(R.id.txtLogMethod)
        val txtDate: TextView = view.findViewById(R.id.txtLogDate)
        val txtTime: TextView = view.findViewById(R.id.txtLogTime)
        // txtState가 XML에 없을 경우를 대비해 nullable 처리 (안전장치)
        val txtState: TextView? = view.findViewById(R.id.txtLogState)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]
        val stateUpper = log.state.uppercase()

        // 1. 상태에 따른 UI 변경 (LOCK / UNLOCK)
        // contains를 사용하여 "UNLOCK", "OPEN" 등을 모두 처리
        if (stateUpper.contains("UNLOCK") || stateUpper.contains("OPEN")) {
            // 🔓 열림 상태
            holder.txtTitle.text = "🔓 문이 열렸습니다"
            holder.txtTitle.setTextColor(Color.parseColor("#2563EB")) // 파란색

            // 시스템 아이콘 대신 직관적인 아이콘 사용 권장 (없으면 기본 ic_lock_idle_lock 사용)
            holder.imgIcon.setImageResource(R.drawable.ic_lock_open) // 앞서 만든 open 아이콘
            holder.imgIcon.setColorFilter(Color.parseColor("#2563EB"))

            holder.txtState?.text = "UNLOCK"
            holder.txtState?.setTextColor(Color.parseColor("#10B981")) // 초록색

        } else {
            // 🔒 잠김 상태 (LOCK, CLOSE 등)
            holder.txtTitle.text = "🔒 문이 잠겼습니다"
            holder.txtTitle.setTextColor(Color.parseColor("#DC2626")) // 빨간색

            holder.imgIcon.setImageResource(android.R.drawable.ic_lock_idle_lock)
            holder.imgIcon.setColorFilter(Color.parseColor("#DC2626"))

            holder.txtState?.text = "LOCK"
            holder.txtState?.setTextColor(Color.parseColor("#EF4444")) // 빨간색
        }

        // 2. 사용자 정보
        holder.txtUser.text = if (log.user.isNotEmpty()) log.user else "Unknown"

        // 3. 방법(Method) 배지 스타일링
        holder.txtMethod.text = log.method

        when (log.method.uppercase()) {
            "APP", "APP_WIFI", "APP_REMOTE" -> {
                holder.txtMethod.setTextColor(Color.parseColor("#6366F1")) // 보라색
                holder.txtMethod.setBackgroundColor(Color.parseColor("#EEF2FF"))
            }
            "RFID", "CARD" -> {
                holder.txtMethod.setTextColor(Color.parseColor("#F59E0B")) // 주황색
                holder.txtMethod.setBackgroundColor(Color.parseColor("#FEF3C7"))
            }
            "BLE", "AUTO_BLE" -> {
                holder.txtMethod.setTextColor(Color.parseColor("#10B981")) // 초록색
                holder.txtMethod.setBackgroundColor(Color.parseColor("#D1FAE5"))
            }
            "AUTO_LOCK", "AUTO" -> {
                holder.txtMethod.setTextColor(Color.parseColor("#6B7280")) // 회색
                holder.txtMethod.setBackgroundColor(Color.parseColor("#F3F4F6"))
            }
            else -> {
                holder.txtMethod.setTextColor(Color.parseColor("#6B7280"))
                holder.txtMethod.setBackgroundColor(Color.parseColor("#F3F4F6"))
            }
        }

        // 4. 날짜 및 시간 파싱 (안전한 파싱 로직 적용)
        try {
            // ESP32에서 보내는 날짜 형식: "2023-12-03 14:30:00"
            val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateObj = inputFormat.parse(log.time) ?: Date()

            // 날짜와 시간을 분리하여 포맷팅
            val dateFormat = SimpleDateFormat("MM월 dd일", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            holder.txtDate.text = dateFormat.format(dateObj)
            holder.txtTime.text = timeFormat.format(dateObj)

        } catch (e: Exception) {
            // 파싱 실패 시 원본 문자열을 적절히 잘라서 표시
            holder.txtDate.text = log.time
            holder.txtTime.text = ""
        }
    }

    override fun getItemCount() = logs.size
}