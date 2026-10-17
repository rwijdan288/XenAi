package com.xenai.floatingbot.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xenai.floatingbot.api.HistoryItem

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xen_ai_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val KEY_BACKEND_URL = "key_backend_url"
        const val KEY_CUSTOM_INSTRUCTION = "key_custom_instruction"
        const val KEY_AUTO_COLLAPSE = "key_auto_collapse"
        const val KEY_HISTORY = "key_history"
        const val DEFAULT_BACKEND_URL = "http://10.0.2.2:3000"
        const val DEFAULT_INSTRUCTION =
            "Analisis teks dan gambar yang terlihat di layar. Jika terdapat pertanyaan atau pilihan ganda, berikan jawaban terbaik dan jelaskan alasannya dalam Bahasa Indonesia. Jika gambar tidak jelas atau informasi kurang, katakan dengan jujur dan jangan mengarang."
    }

    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value.trim()).apply()

    var customInstruction: String
        get() = prefs.getString(KEY_CUSTOM_INSTRUCTION, DEFAULT_INSTRUCTION) ?: DEFAULT_INSTRUCTION
        set(value) = prefs.edit().putString(KEY_CUSTOM_INSTRUCTION, value.trim()).apply()

    fun getHistory(): List<HistoryItem> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addHistoryItem(item: HistoryItem) {
        val current = getHistory().toMutableList()
        current.add(0, item)
        if (current.size > 30) current.removeAt(current.size - 1)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(current)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
}