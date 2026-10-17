package com.xenai.floatingbot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xenai.floatingbot.api.HistoryItem
import com.xenai.floatingbot.ui.theme.*

// Jetpack Compose Home Screen
// Menyajikan UI minimalis putih dengan aksen ungu & biru (#6C5CE7, #0984E3)
// 1. Logo & Nama Xen AI
// 2. Status Koneksi AI Backend
// 3. Tombol Toggle Floating Bubble
// 4. Status Izin Overlay & Screen Capture
// 5. Pengaturan Backend & Custom Instruction
// 6. Riwayat Analisis Terbaru
// 7. Informasi Privasi & Keamanan