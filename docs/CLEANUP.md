# Cleanup dan migrasi monorepo

Dilakukan 28 Oktober 2026. Proyek awal belum memiliki repositori Git lokal; sumber aktif sekarang disatukan di `apps/sony-live` dan `packages/apk-tooling`.

## Yang dibersihkan

- Hasil dekompilasi, DEX sementara, kelas Java hasil kompilasi, APK unsigned/aligned sementara, dan cache Python lama.
- APK percobaan r2-r15 serta duplikasi paket split generasi awal.
- Skrip build revisi lama, patcher diagnostik, kelas diagnostik audio/kamera dan probe dari pohon kode aktif.
- Metode pemaksaan FRAME/TYPE1, state pendukung, dan field timestamp yang tidak dipakai bridge produksi.

Sumber eksperimen diarsipkan dahulu di `archive/experiment-sources-before-cleanup.zip`. Artefak build lama yang dihapus harus dibangun ulang jika kelak diperlukan; arsip sumber bukan cadangan seluruh biner historis.

## Yang dipertahankan

- Rilis r17 lengkap, termasuk ZIP installer, 82 APK dan checksum.
- Tiga APK pemulihan r16.
- Seluruh 82 input APK resmi dan kunci penandatanganan lokal.
- Bukti pengujian/screenshot serta catatan sesi historis, hanya di penyimpanan lokal.

## Validasi

- `python build.py` sukses dari input resmi tanpa memakai APK r12/r16.
- Semua payload patch DEX (`classes4.dex`, `classes5.dex`, `classes25.dex`) identik byte demi byte dengan r17 yang sudah dirilis.
- Pustaka native hasil build identik byte demi byte dengan rilis r17.
- Perubahan payload APK dibatasi pada bridge, tiga DEX patch dan pustaka native yang ditambahkan; tanda tangan diverifikasi.
- Uji mailbox lulus: producer nonblocking, frame terbaru, urutan dan shutdown.
- Paket fresh r17 setelah dipindah tetap lolos pemeriksaan 82 checksum; hash ZIP tetap sama.
- HP tidak diperbarui selama cleanup; build yang dirapikan menunggu uji perangkat sebelum dijadikan rilis baru.

Pemakaian disk turun dari sekitar 11,65 GiB menjadi sekitar 2,6 GiB, termasuk hasil build verifikasi baru. Artefak, kunci, APK pihak ketiga, bukti lokal dan catatan sesi pribadi dikecualikan dari Git.
