package com.example.smartdoorlock.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smartdoorlock.R
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class NotificationService : Service() {

    private val database = FirebaseDatabase.getInstance()

    // 리스너 관리를 위한 맵 (중복 등록 방지 및 해제용)
    private val listeners = HashMap<String, ChildEventListener>()
    private val dbRefs = HashMap<String, DatabaseReference>()

    private val CHANNEL_ID_FOREGROUND = "doorlock_monitor_channel"
    private val CHANNEL_ID_ALERT = "doorlock_alert_channel"
    private val NOTI_ID_FOREGROUND = 2 // LocationService와 겹치지 않게 2번 사용

    // 서비스 시작 시간 (이 시간 이후에 생긴 로그만 알림을 띄움)
    private var serviceStartTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        serviceStartTime = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. 포그라운드 서비스 시작 (죽지 않게 상단바 고정)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle("도어락 모니터링")
            .setContentText("도어락 상태를 실시간으로 확인 중입니다.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 아이콘 확인 필요 (없으면 android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 데이터 동기화 타입으로 서비스 실행
            startForeground(NOTI_ID_FOREGROUND, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID_FOREGROUND, notification)
        }

        // 2. 감시 시작
        startMonitoring()

        return START_STICKY
    }

    // [수정] 랜덤 도어락 ID 방식으로 변경
    private fun startMonitoring() {
        val prefs = getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("saved_id", null)

        if (username == null) {
            Log.w("NotiService", "사용자 아이디 없음. 감시 중단.")
            return
        }

        // [수정] 내 도어락 목록 가져오기 (랜덤 ID 방식)
        val myDoorlocksRef = database.getReference("users").child(username).child("my_doorlocks")

        // 도어락이 추가될 때마다 해당 도어락 감시 시작
        myDoorlocksRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val doorlockId = snapshot.key ?: return // 랜덤 ID (예: "abc123xyz")
                Log.d("NotiService", "도어락 감시 시작: $doorlockId")
                monitorDoorlockLogs(doorlockId)
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val doorlockId = snapshot.key ?: return
                removeMonitor(doorlockId)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("NotiService", "감시 시작 실패: ${error.message}")
            }
        })
    }

    // [핵심] 특정 도어락의 로그 감시 (공용 DB: doorlocks/{doorlockId}/logs)
    private fun monitorDoorlockLogs(doorlockId: String) {
        if (listeners.containsKey(doorlockId)) return // 이미 감시 중이면 패스

        Log.d("NotiService", "로그 감시 시작: doorlocks/$doorlockId/logs")

        val logsRef = database.getReference("doorlocks").child(doorlockId).child("logs")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                // 로그 데이터 파싱
                val method = snapshot.child("method").getValue(String::class.java) ?: "알 수 없음"
                val state = snapshot.child("state").getValue(String::class.java) ?: ""
                val timeStr = snapshot.child("time").getValue(String::class.java) ?: ""
                val user = snapshot.child("user").getValue(String::class.java) ?: "알 수 없음"

                Log.d("NotiService", "로그 감지: $state by $method at $timeStr")

                // 서비스 시작 이후에 발생한 로그인지 확인 (과거 로그 알림 방지)
                if (isNewLog(timeStr)) {
                    val message = when(state) {
                        "UNLOCK" -> "문이 열렸습니다 ($method)"
                        "LOCK" -> "문이 잠겼습니다 ($method)"
                        else -> "상태 변경: $state"
                    }
                    showNotification("도어락 알림", message)
                    Log.d("NotiService", "알림 표시: $message")
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("NotiService", "로그 감시 실패: ${error.message}")
            }
        }

        // 리스너 등록 (limitToLast(1)로 최신 것부터 감시)
        logsRef.limitToLast(1).addChildEventListener(listener)

        // 관리용 맵에 저장 (나중에 해제하기 위함)
        listeners[doorlockId] = listener
        dbRefs[doorlockId] = logsRef
    }

    private fun removeMonitor(doorlockId: String) {
        if (listeners.containsKey(doorlockId)) {
            dbRefs[doorlockId]?.removeEventListener(listeners[doorlockId]!!)
            listeners.remove(doorlockId)
            dbRefs.remove(doorlockId)
            Log.d("NotiService", "감시 종료: $doorlockId")
        }
    }

    private fun isNewLog(timeStr: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val logDate = sdf.parse(timeStr) ?: return false
            logDate.time > serviceStartTime
        } catch (e: Exception) {
            false
        }
    }

    private fun showNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 알림 아이콘 (없으면 기본값)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // 헤드업 알림 (화면 상단에 뜸)
            .setDefaults(NotificationCompat.DEFAULT_ALL)   // 소리/진동
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 1. 백그라운드 유지용 채널 (조용함)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "모니터링 서비스",
                NotificationManager.IMPORTANCE_LOW
            )

            // 2. 실제 알림용 채널 (소리/진동)
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "도어락 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "도어락 상태 변경 알림"
                enableVibration(true)
            }

            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 모든 리스너 정리
        for ((doorlockId, listener) in listeners) {
            dbRefs[doorlockId]?.removeEventListener(listener)
        }
        listeners.clear()
        dbRefs.clear()
        Log.d("NotiService", "서비스 종료")
    }
}