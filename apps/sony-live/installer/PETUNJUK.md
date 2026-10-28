# Instalasi Mandiri TikTok Sony LIVE R17

Paket ini berisi **82 split APK** untuk instalasi bersih TikTok 47.5.3 Sony LIVE revisi produksi minimal 17 pada realme RMX3393/ARM64.

## Peringatan sebelum uninstall

- Uninstall TikTok menghapus sesi login, pengaturan, dan draft lokal aplikasi.
- Amankan draft ke galeri atau unggah sebagai privat terlebih dahulu.
- Setelah instalasi bersih, akun TikTok harus login kembali. Tanpa root, sesi login lama tidak dapat dipulihkan dari paket APK.
- Installer tidak pernah melakukan uninstall, clear-data, root, atau membuka bootloader.

## Simpan paket ini

Salin seluruh folder `R17-FRESH-INSTALL`, termasuk subfolder `apks`, ke penyimpanan yang aman. Jangan hanya menyimpan `base.apk`; seluruh 82 APK diperlukan.

Untuk memeriksa apakah salinan masih utuh, klik dua kali `Periksa-Paket.bat`. Hasil yang benar adalah:

```text
PASS: paket R17 lengkap dan semua checksum cocok.
```

## Instalasi paling mudah melalui kabel USB

1. Amankan draft dan pastikan Anda mengetahui metode login akun.
2. Uninstall TikTok secara manual dari HP.
3. Aktifkan **Opsi developer > USB debugging**.
4. Sambungkan HP ke laptop menggunakan kabel data dan setujui dialog izin debugging pada HP.
5. Pastikan hanya satu HP ADB yang tersambung.
6. Klik dua kali `Pasang-R17.bat` dan tunggu sampai tertulis `SELESAI`.
7. Buka TikTok dan login kembali.
8. Di Play Store, buka halaman TikTok dan nonaktifkan **Update otomatis**.
9. Lepas kabel laptop, sambungkan Sony ZV-1 melalui OTG, izinkan akses USB, dan uji preview LIVE.

## Instalasi melalui Wireless debugging

1. Aktifkan **Opsi developer > Wireless debugging** dan sambungkan HP serta laptop ke Wi-Fi yang sama.
2. Jika laptop belum dipasangkan, pilih **Pair device with pairing code**, lalu di PowerShell jalankan:

   ```powershell
   adb pair ALAMAT_IP:PORT_PAIRING
   ```

3. Masukkan kode pairing dari HP.
4. Kembali ke halaman utama Wireless debugging dan catat alamat IP serta port koneksi, lalu jalankan:

   ```powershell
   adb connect ALAMAT_IP:PORT_KONEKSI
   adb devices
   ```

5. Setelah status perangkat menunjukkan `device`, klik dua kali `Pasang-R17.bat`.

Alamat port Wireless debugging dapat berubah setiap kali fitur dimatikan atau HP direstart.

## Jika installer berhenti

- **TikTok masih terpasang:** installer sengaja menolak. Pastikan draft sudah aman, kemudian uninstall secara manual.
- **Tidak ada HP ADB:** periksa izin debugging, kabel data, atau jalankan kembali `adb connect`.
- **Lebih dari satu perangkat:** buka PowerShell di folder ini dan jalankan:

  ```powershell
  .\Install-R17-Fresh.ps1 -Serial "SERIAL_DARI_ADB_DEVICES"
  ```

- **Checksum tidak cocok:** jangan instal. Salin ulang paket dari cadangan yang sehat.

## Isi revisi

- Base dan hook LIVE: revisi produksi minimal 17.
- Konversi gambar ARM64: revisi 15.
- Sony ZV-1 UVC 1280×720, portrait, mirror, dan optimasi preview/LIVE dipertahankan.
- `debuggable`, meter audio pasif, introspeksi kamera mendalam, dan kelas pengujian tidak disertakan.

APK memakai sertifikat lokal proyek, bukan sertifikat resmi TikTok. Paket ini tidak membuat aplikasi menjadi versi resmi dan tidak menghilangkan seluruh risiko penggunaan APK modifikasi.
