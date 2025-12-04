package com.example.smartdoorlock

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.smartdoorlock.databinding.ActivityMainBinding
import com.example.smartdoorlock.service.LocationService
import com.example.smartdoorlock.service.NotificationService
import com.example.smartdoorlock.service.UwbServiceManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private lateinit var uwbManager: UwbServiceManager
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    private var authListener: ValueEventListener? = null
    private var authRef: DatabaseReference? = null

    private val REQUEST_ALL_PERMISSIONS = 1001
    private val REQUEST_BACKGROUND_LOCATION = 1002

    // 필수 권한 목록 (백그라운드 위치 제외)
    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Android 12 미만: 구형 블루투스 권한
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // Android 12 이상: 신형 블루투스 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Android 13 이상: 알림 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uwbManager = UwbServiceManager(this)
        uwbManager.init()

        supportActionBar?.let {
            val gradient = ContextCompat.getDrawable(this, R.drawable.gradient_actionbar_background)
            it.setBackgroundDrawable(gradient)
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        navController = navHostFragment.navController

        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_dashboard,
                R.id.navigation_profile,
                R.id.navigation_notifications,
                R.id.navigation_settings
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.navView.setupWithNavController(navController)

        // 탭 전환 시 스택 초기화
        binding.navView.setOnItemSelectedListener { item ->
            if (item.itemId != binding.navView.selectedItemId) {
                val navOptions = NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setPopUpTo(item.itemId, true)
                    .build()

                try {
                    navController.navigate(item.itemId, null, navOptions)
                    return@setOnItemSelectedListener true
                } catch (e: IllegalArgumentException) {
                    return@setOnItemSelectedListener false
                }
            }
            true
        }

        // 화면 전환 시 UI 제어 및 로그인 상태 체크
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.navigation_login,
                R.id.navigation_register,
                R.id.findPasswordFragment -> {
                    binding.navView.visibility = View.GONE
                    supportActionBar?.hide()
                }
                else -> {
                    if (auth.currentUser == null) {
                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.mobile_navigation, true)
                            .build()
                        navController.navigate(R.id.navigation_login, null, navOptions)
                    } else {
                        binding.navView.visibility = View.VISIBLE
                        supportActionBar?.show()
                        observeAuthMethod()
                    }
                }
            }
        }

        // 뒤로가기 처리
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentDestination = navController.currentDestination?.id

                if (currentDestination == R.id.navigation_login ||
                    currentDestination == R.id.navigation_register ||
                    auth.currentUser == null) {
                    finish()
                    return
                }

                if (!navController.popBackStack()) {
                    showExitConfirmationDialog()
                }
            }
        })

        // 권한 체크 및 서비스 시작
        checkAndRequestPermissions()

        // 로그인 상태 확인
        if (auth.currentUser == null) {
            val navOptions = NavOptions.Builder()
                .setPopUpTo(R.id.mobile_navigation, true)
                .build()
            navController.navigate(R.id.navigation_login, null, navOptions)
        }
    }

    // 앱 종료 확인 다이얼로그
    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("앱 종료")
            .setMessage("정말 앱을 종료하시겠습니까?")
            .setPositiveButton("종료") { _, _ ->
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 인증 방식 감시
    private fun observeAuthMethod() {
        if (authListener != null) return
        val uid = auth.currentUser?.uid ?: return
        authRef = database.getReference("users").child(uid).child("authMethod")

        authListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val method = snapshot.getValue(String::class.java) ?: "BLE"
                if (method == "UWB") uwbManager.startRanging()
                else uwbManager.stopRanging()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        authRef?.addValueEventListener(authListener!!)
    }

    // 권한 체크 및 요청
    private fun checkAndRequestPermissions() {
        val missingPermissions = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            // 모든 필수 권한이 있음 -> 서비스 시작
            startAllServices()

            // 백그라운드 위치 권한 체크 (선택적)
            checkBackgroundLocationPermission()
        } else {
            // 부족한 권한 요청
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_ALL_PERMISSIONS
            )
        }
    }

    // 백그라운드 위치 권한 체크 (Android 10 이상)
    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // 백그라운드 위치 권한이 없으면 안내 다이얼로그 표시
                AlertDialog.Builder(this)
                    .setTitle("백그라운드 위치 권한")
                    .setMessage("도어락 자동 잠금/해제 기능을 사용하려면 백그라운드에서도 위치 접근이 필요합니다.\n\n다음 화면에서 '항상 허용'을 선택해주세요.")
                    .setPositiveButton("설정") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            REQUEST_BACKGROUND_LOCATION
                        )
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            }
        }
    }

    // 모든 서비스 시작
    private fun startAllServices() {
        // 1. 위치 추적 서비스 시작
        startLocationService()

        // 2. 알림 서비스 시작
        startNotificationService()
    }

    // 위치 추적 서비스 시작
    private fun startLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // 알림 서비스 시작
    private fun startNotificationService() {
        val serviceIntent = Intent(this, NotificationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_ALL_PERMISSIONS -> {
                // 거부된 권한 찾기
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }

                if (deniedPermissions.isEmpty()) {
                    // 모든 권한 허용됨 -> 서비스 시작
                    startAllServices()

                    // 백그라운드 위치 권한 체크
                    checkBackgroundLocationPermission()
                } else {
                    // 일부 권한 거부됨
                    AlertDialog.Builder(this)
                        .setTitle("권한 필요")
                        .setMessage("앱을 사용하려면 다음 권한이 필요합니다:\n\n${deniedPermissions.joinToString("\n")}\n\n권한을 허용해주세요.")
                        .setPositiveButton("다시 시도") { _, _ ->
                            checkAndRequestPermissions()
                        }
                        .setNegativeButton("나중에", null)
                        .show()
                }
            }

            REQUEST_BACKGROUND_LOCATION -> {
                // 백그라운드 위치 권한 결과
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 백그라운드 권한 허용됨 -> 서비스 재시작
                    startLocationService()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uwbManager.stopRanging()
        if (authListener != null && authRef != null) {
            authRef?.removeEventListener(authListener!!)
            authListener = null
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
