package com.example.smartdoorlock.helper
import com.google.firebase.auth.FirebaseAuth
import android.util.Log
import com.example.smartdoorlock.data.DoorLockLog
import com.google.firebase.database.FirebaseDatabase

object FirebaseHelper {

    fun saveDoorLockLog(log: DoorLockLog) {
        val db = FirebaseDatabase.getInstance()
        val ref = db.getReference("doorlock_logs")

        // 고유 키 생성 (예: kimys_20250619_031022)
        val key = "${log.user_id}_${log.timestamp.replace(":", "").replace(" ", "_")}"

        ref.child(key).setValue(log)
            .addOnSuccessListener {
                Log.d("Firebase", "로그 저장 성공")
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "저장 실패: ${e.message}")
            }
    }
    
    /**
     * 애플리케이션 이벤트를 'users/{userId}/app_logs' 경로에 저장합니다.
     * @param eventDescription 로그에 기록될 이벤트 내용 (예: "원격 문열림 시도")
     */
    fun addAppLog(eventDescription: String) {
        // 현재 로그인한 사용자의 ID 가져오기
        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid

        if (userId == null) {
            Log.e("FirebaseHelper", "로그인한 사용자가 없어 앱 로그를 저장할 수 없습니다.")
            return
        }

        val db = FirebaseDatabase.getInstance()
        // 앱 로그는 'users/사용자ID/app_logs' 경로에 저장합니다.
        val ref = db.getReference("users").child(userId).child("app_logs")

        // 현재 시간(타임스탬프)
        val timestamp = System.currentTimeMillis()

        // 로그 데이터를 Map 객체로 생성
        val logEntry = mapOf(
            "event" to eventDescription,
            "timestamp" to timestamp
        )

        // .push()를 사용해 고유한 키로 데이터를 저장합니다.
        ref.push().setValue(logEntry)
            .addOnSuccessListener {
                Log.d("FirebaseHelper", "앱 로그 저장 성공: $eventDescription")
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseHelper", "앱 로그 저장 실패: ${e.message}")
            }
    }
    // ▲▲▲ [여기까지 추가] ▲▲▲
}
