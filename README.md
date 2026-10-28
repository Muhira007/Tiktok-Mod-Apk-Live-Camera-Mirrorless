# Sony USB LIVE monorepo

Integrasi Sony ZV-1 UVC ke TikTok LIVE Android tanpa root. Baseline perangkat yang sudah dicoba pengguna adalah **r17** (TikTok 47.5.3, ARM64, realme RMX3393).

## Struktur

- `apps/sony-live/`: kode bridge Java/native, patcher DEX, pengujian dan installer.
- `packages/apk-tooling/`: utilitas repack, signing dan validasi bersama.
- `docs/`: arsitektur, operasi dan riwayat investigasi.
- `tools/`: dependensi lokal dexlib2/Guava dan utilitas investigasi; lihat `tools/README.md` untuk checksum dependensi.
- `vendor/tiktok-47.5.3/apk/`: 82 split APK resmi sebagai input build (diabaikan Git).
- `artifacts/releases/r17/`: installer lengkap r17 yang sudah diverifikasi, ZIP cadangan dan update APK.
- `artifacts/recovery/r16/`: tiga APK untuk pemulihan baseline r16.
- `artifacts/evidence/`: hasil pengujian/screenshot sebelumnya.
- `archive/experiment-sources-before-cleanup.zip`: sumber eksperimen lama yang sudah dikeluarkan dari kode aktif.
- `secrets/test-signing.p12`: kunci lokal yang harus dicadangkan secara privat (diabaikan Git).

## Build

Jalankan dari root monorepo:

```powershell
python build.py
```

Perlu Python 3.11+, JDK 17+, Android platform 36, build-tools 36.0.0 dan NDK 29.0.14206865. SDK secara default dibaca dari `%LOCALAPPDATA%\Android\Sdk`; gunakan `ANDROID_SDK_ROOT`, `ANDROID_BUILD_TOOLS`, dan `ANDROID_NDK_VERSION` untuk menggantinya. `tools/dexlib2.jar` dan `tools/guava.jar` dibutuhkan patcher.

Build membuat tiga APK di `artifacts/build/sony-live`, menguji antrean frame dan memverifikasi perubahan payload serta tanda tangan. Build bersumber langsung dari APK resmi; tidak bergantung pada hasil build historis. Hasil build baru belum diuji di HP dan tidak menggantikan arsip r17 yang telah dipakai pengguna.

`OTGLIVE_SIGNING_KEY` dan `OTGLIVE_KEY_PASSWORD` dapat digunakan untuk mengatur signing lokal. Kunci harus sama dengan paket terpasang untuk pembaruan tanpa kehilangan data.

## Instalasi ulang r17

Ekstrak `artifacts/releases/r17/TikTok-Sony-R17-Fresh-Install.zip`, baca `PETUNJUK.md`, lalu gunakan `Pasang-R17.bat`. Uninstall menghapus sesi login/draft lokal; installer fresh tidak melakukan uninstall otomatis.

HP tidak diubah oleh proses merapikan monorepo. Folder artefak, vendor, archive dan secrets sengaja diabaikan Git; cadangkan terpisah sebelum memindahkan ke komputer lain.
