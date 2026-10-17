package com.xenai.floatingbot.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun normalizeUrl(baseUrl: String, path: String): String {
        val trimmed = baseUrl.trim().removeSuffix("/")
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "$trimmed$path"
        } else {
            "http://$trimmed$path"
        }
    }

    suspend fun checkHealth(baseUrl: String): Result<HealthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = normalizeUrl(baseUrl, "/api/health")
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success(gson.fromJson(bodyStr, HealthResponse::class.java))
                } else {
                    Result.failure(IOException("Server error HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun analyzeScreen(
        baseUrl: String,
        imageBase64: String,
        instruction: String
    ): Result<AnalyzeResponse> = withContext(Dispatchers.IO) {
        try {
            val url = normalizeUrl(baseUrl, "/api/analyze")
            val payload = AnalyzeRequest(image = imageBase64, instruction = instruction)
            val jsonPayload = gson.toJson(payload)

            val requestBody = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(requestBody).build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val parsed = gson.fromJson(bodyStr, AnalyzeResponse::class.java)
                    if (parsed.success) Result.success(parsed)
                    else Result.failure(IOException(parsed.error ?: "Gagal analisis."))
                } else {
                    Result.failure(IOException("HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}