# Struktur kode produksi

`apps/sony-live` berisi delapan kelas bridge yang dipakai patch produksi. Pembaca UVC menyusun MJPEG dari Sony, mailbox menyimpan frame terbaru, renderer menerapkan rotasi/mirror, dan native converter menghasilkan NV21 untuk preview. Jalur LIVE memakai upload RGB ke tekstur GL melalui `LiveNodeTextureBridge`.

`PatchProductionDex` menambahkan empat hook lifecycle serta dua hook frame preview. `PatchLiveDex` mempertahankan hook RGB LIVE dan adapter Lyrax/legacy yang masih direferensikan oleh DEX. Adapter legacy sengaja dipertahankan: bukan dead code selama patcher masih menargetkannya.

Empat metode pemaksaan FRAME/TYPE1 dan field pendukungnya dihapus karena tidak dipanggil oleh patch produksi r17. Kelas meter audio, introspeksi kamera, probe, compile stubs audio, serta pengujian khusus audio dikeluarkan dari pohon sumber aktif dan disimpan dalam arsip sejarah.

`packages/apk-tooling` menyediakan utilitas bersama untuk repack, signing dan perbandingan isi APK. Root `build.py` menjalankan build aplikasi. Seluruh input berasal dari APK resmi dalam `vendor`, sumber Java/C++ dan dua patcher aktif; revisi APK lama tidak diperlukan untuk membangun ulang.

Rilis r17 yang telah dipakai pengguna disimpan terpisah dari hasil build sumber yang dirapikan. Hasil build baru harus melalui pengujian perangkat sebelum dipromosikan menjadi rilis berikutnya. Proses cleanup tidak memperbarui aplikasi pada HP.
