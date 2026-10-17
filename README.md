# Xen AI Floating Bot — Android & Gemini Vision Assistant

Aplikasi Android Native modern dengan **Floating Bubble** yang memungkinkan pengguna menganalisis soal atau pertanyaan di layar smartphone secara instan tanpa perlu copy-paste teks.

## Fitur Utama

- **Floating Bubble WindowManager**: Tombol mengambang di atas semua aplikasi dengan gesture drag, auto-snap ke tepi layar, tap untuk analisis, dan long-press untuk menutup.
- **Single-Frame Screen Capture**: Menggunakan Android MediaProjection (kompatibel penuh Android 14 API 34). Tidak merekam secara pasif atau terus-menerus.
- **AI Vision Engine**: Ditenagai model **Google Gemini 3.8 Flash** yang membaca teks, diagram, grafik, dan pilihan ganda.
- **Jawaban Mengambang**: Panel hasil muncul langsung di atas halaman soal dengan penjelasan terstruktur, tombol salin, dan tombol analisis ulang.
- **Antarmuka Jetpack Compose**: Halaman utama bersih bertema putih minimalis dengan aksen ungu & biru.
- **Keamanan Penuh**: API key disimpan di backend, tidak pernah ditanam di file APK.

## Struktur Direktori

```
├── android/                   # Proyek Native Android (Gradle Kotlin DSL)
│   ├── app/
│   │   ├── src/main/java/com/xenai/floatingbot/
│   │   │   ├── MainActivity.kt
│   │   │   ├── api/
│   │   │   ├── data/
│   │   │   ├── service/       # FloatingService & ScreenCaptureService
│   │   │   └── ui/            # Jetpack Compose UI & Theme
│   │   └── src/main/AndroidManifest.xml
│   └── build.gradle.kts
├── backend/                   # Server Node.js Express (CommonJS)
│   ├── server.js              # Endpoint POST /api/analyze & GET /api/health
│   ├── package.json
│   └── .env.example
└── .github/workflows/         # Otomasi build APK via GitHub Actions
    └── android-apk.yml
```

## Cara Menjalankan Backend

1. Buka folder backend: `cd backend`
2. Pasang modul: `npm install`
3. Buat file `.env` dan masukkan `GEMINI_API_KEY` dari Google AI Studio.
4. Jalankan: `npm start`

## Build Debug APK

Push repositori ini ke GitHub, buka tab **Actions**, dan jalankan workflow **Build Android Debug APK**. Artifact APK siap diunduh dan dipasang pada perangkat Android.