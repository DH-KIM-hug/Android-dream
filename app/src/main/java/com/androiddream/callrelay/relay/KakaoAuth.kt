package com.androiddream.callrelay.relay

import android.net.Uri
import com.androiddream.callrelay.data.Settings
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * 카카오 OAuth (REST API 방식, SDK 없이).
 *  1) authorizeUrl() 을 WebView 로 열어 로그인/동의
 *  2) redirect_uri 로 돌아온 code 를 exchangeCode() 로 토큰 교환
 *  3) 전송 시 validAccessToken() 이 만료 전 자동 갱신
 */
object KakaoAuth {
    private const val TOKEN_URL = "https://kauth.kakao.com/oauth/token"

    fun authorizeUrl(s: Settings): String = Uri.parse("https://kauth.kakao.com/oauth/authorize").buildUpon()
        .appendQueryParameter("client_id", s.kakaoRestKey.trim())
        .appendQueryParameter("redirect_uri", s.kakaoRedirectUri.trim())
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("scope", "talk_message")
        .build().toString()

    fun exchangeCode(s: Settings, code: String) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", s.kakaoRestKey.trim())
            .add("redirect_uri", s.kakaoRedirectUri.trim())
            .add("code", code)
        if (s.kakaoClientSecret.isNotBlank()) form.add("client_secret", s.kakaoClientSecret.trim())
        save(s, post(form.build()))
    }

    @Synchronized
    fun validAccessToken(s: Settings): String {
        if (s.kakaoRefreshToken.isBlank()) throw IllegalStateException("카카오 로그인이 필요합니다 (설정 > 카카오톡 > 로그인)")
        val margin = 5 * 60 * 1000L
        if (s.kakaoAccessToken.isNotBlank() && System.currentTimeMillis() + margin < s.kakaoAccessExpiresAt) {
            return s.kakaoAccessToken
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", s.kakaoRestKey.trim())
            .add("refresh_token", s.kakaoRefreshToken)
        if (s.kakaoClientSecret.isNotBlank()) form.add("client_secret", s.kakaoClientSecret.trim())
        save(s, post(form.build()))
        return s.kakaoAccessToken
    }

    fun logout(s: Settings) {
        s.kakaoAccessToken = ""
        s.kakaoRefreshToken = ""
        s.kakaoAccessExpiresAt = 0L
    }

    private fun post(body: FormBody): JSONObject {
        val req = Request.Builder().url(TOKEN_URL)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .post(body).build()
        return JSONObject(Http.execute(req))
    }

    private fun save(s: Settings, json: JSONObject) {
        val at = json.optString("access_token")
        if (at.isBlank()) throw IllegalStateException("카카오 토큰 발급 실패: $json")
        s.kakaoAccessToken = at
        s.kakaoAccessExpiresAt = System.currentTimeMillis() + json.optLong("expires_in", 0L) * 1000
        // refresh_token 은 만료가 가까울 때만 새로 내려온다
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { s.kakaoRefreshToken = it }
    }
}
