package com.androiddream.callrelay.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import com.androiddream.callrelay.data.Settings
import com.androiddream.callrelay.relay.KakaoAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 카카오 로그인/동의 화면을 WebView 로 띄우고 redirect_uri 로 돌아오는 code 를 가로챈다. */
class KakaoLoginActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        if (settings.kakaoRestKey.isBlank()) {
            Toast.makeText(this, "REST API 키를 먼저 입력하세요", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                intercept(request.url)
        }
        setContentView(web)
        web.loadUrl(KakaoAuth.authorizeUrl(settings))
    }

    private fun intercept(url: Uri): Boolean {
        val redirect = Uri.parse(settings.kakaoRedirectUri.trim())
        val matches = url.scheme == redirect.scheme && url.host == redirect.host && url.path == redirect.path
        if (!matches || handled) return false
        handled = true
        val code = url.getQueryParameter("code")
        val error = url.getQueryParameter("error_description") ?: url.getQueryParameter("error")
        if (code == null) {
            done(false, "카카오 로그인 취소/실패: ${error ?: "code 없음"}")
            return true
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { KakaoAuth.exchangeCode(settings, code) } }
            done(r.isSuccess, if (r.isSuccess) "카카오 로그인 완료" else "토큰 발급 실패: ${r.exceptionOrNull()?.message}")
        }
        return true
    }

    private fun done(ok: Boolean, msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }
}
