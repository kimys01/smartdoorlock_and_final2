package com.example.smartdoorlock.api

import com.example.smartdoorlock.data.AccessLog
import com.example.smartdoorlock.data.LoginResponse
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // [수정] 맨 앞의 '/' 제거 (상대 경로 사용)
    // 출입 기록 저장
    @POST("logs")
    suspend fun sendLog(@Body log: AccessLog): Response<Void>

    // [수정] 맨 앞의 '/' 제거
    // 출입 기록 불러오기
    @GET("logs")
    suspend fun getLogs(): List<AccessLog>

    // 로그인 요청
    @FormUrlEncoded
    @POST("login.php")
    fun loginUser(
        @Field("name") name: String,
        @Field("password") password: String
    ): Call<LoginResponse>

    // 회원가입
    @FormUrlEncoded
    @POST("register.php")
    fun registerUser(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("name") name: String
    ): Call<LoginResponse>

    // [수정] 맨 앞의 '/' 제거
    // 이름 수정
    @FormUrlEncoded
    @POST("update_user.php")
    fun updateUserName(
        @Field("username") username: String,
        @Field("new_name") newName: String
    ): Call<LoginResponse>

    // ✅ 위치 전송 (정상)
    @FormUrlEncoded
    @POST("save_location.php")
    fun sendLocation(
        @Field("username") username: String,
        @Field("latitude") latitude: Double,
        @Field("longitude") longitude: Double,
        @Field("timestamp") timestamp: String
    ): Call<LoginResponse>

}