# Dependensi build lokal

Tempatkan dua dependensi patcher di folder ini. Binari tidak dimasukkan ke Git; versi lokal yang sudah lulus build dapat dikenali dari SHA-256:

| Berkas | SHA-256 |
| --- | --- |
| `dexlib2.jar` | `5a5c8982d8bd7d6e3bb1a0713049e3c78b719ec32b20f6b619885cec30a0dd61` |
| `guava.jar` | `686404f2d1d4d221911f96bd627ff60dac2226a5dfa6fb8ba517073eb97ec0ef` |

Android SDK, NDK dan JDK dibaca dari instalasi lokal sebagaimana dijelaskan di README root. `apktool.jar` dan folder `jadx` yang mungkin masih tersedia lokal adalah alat investigasi opsional; build produksi tidak memakainya. Referensi C UVC lama disimpan di `archive/references`.
