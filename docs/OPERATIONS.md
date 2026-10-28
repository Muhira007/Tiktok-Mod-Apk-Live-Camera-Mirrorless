# Operasi proyek

## Instalasi ulang r17

Paket mandiri berpindah ke `artifacts/releases/r17`. ZIP tetap memiliki checksum SHA-256:

```text
af51e0658fb723f1f1580a4769abdae6b3049fdcecd1cb02ecabd7f7679eda99
```

Ekstrak ZIP dan ikuti `PETUNJUK.md`. Berkas installer sumber tersedia di `apps/sony-live/installer`; checksum APK ada di paket rilis lengkap.

## Pembaruan tanpa menghapus data

Gunakan updater hanya ketika memang hendak memperbarui aplikasi:

```powershell
python apps/sony-live/Update-NoRoot.py --serial IP:PORT
```

Default menunjuk APK update r17 yang sudah dirilis. Untuk artefak build baru, tentukan direktori dan ketiga split secara eksplisit setelah validasi:

```powershell
python apps/sony-live/Update-NoRoot.py --serial IP:PORT --directory artifacts/build/sony-live --files base.apk split_df_a_dex.apk split_config.arm64_v8a.apk
```

## Pemulihan r16

Tiga APK pemulihan baseline r16 disimpan di `artifacts/recovery/r16`. Gunakan updater dengan direktori tersebut dan tiga nama split yang sama. Perangkat harus memakai paket serta sertifikat proyek yang sama. Tidak ada tindakan pemulihan yang dijalankan oleh cleanup.

## Cadangan

- Cadangkan `secrets/test-signing.p12` secara privat; kunci diperlukan untuk revisi in-place berikutnya.
- Cadangkan rilis r17, folder `vendor` dan `archive` terpisah dari Git karena semuanya diabaikan Git.
- Dokumen `docs/history` adalah catatan historis: alamat ADB dan jalur berkas di dalamnya mungkin sudah lama. Gunakan README dan dokumen operasi ini untuk jalur terkini.
