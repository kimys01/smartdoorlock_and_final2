package com.example.smartdoorlock.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    // [중요 설정] 서버 주소
    // 1. 안드로이드 에뮬레이터 사용 시: "http://10.0.2.2/smartdoorlock/"
    // 2. 실제 스마트폰 사용 시: 실행 중인 PC의 로컬 IP 주소 입력 (예: "http://192.168.0.15/smartdoorlock/")
    // 주의: 주소 끝에 반드시 '/'가 있어야 합니다.
    private const val BASE_URL = "http://10.0.2.2/smartdoorlock/"

    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(ApiService::class.java)
    }
}