package com.xenai.floatingbot.api

import com.google.gson.annotations.SerializedName

data class AnalyzeRequest(
    @SerializedName("image") val image: String,
    @SerializedName("instruction") val instruction: String? = null
)

data class AnalyzeResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("answer") val answer: String? = null,
    @SerializedName("model") val model: String? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("error") val error: String? = null
)

data class HealthResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("status") val status: String? = null,
    @SerializedName("service") val service: String? = null,
    @SerializedName("model") val model: String? = null,
    @SerializedName("apiKeyConfigured") val apiKeyConfigured: Boolean = false,
    @SerializedName("timestamp") val timestamp: String? = null
)

data class HistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val promptSnippet: String,
    val answer: String,
    val isSuccess: Boolean
)