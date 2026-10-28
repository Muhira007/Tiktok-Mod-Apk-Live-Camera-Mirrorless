param(
    [string]$Serial,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.ss.android.ugc.trill'
$apkDirectory = Join-Path $PSScriptRoot 'apks'
$checksumFile = Join-Path $PSScriptRoot 'checksums.csv'

if (-not (Test-Path -LiteralPath $apkDirectory -PathType Container)) {
    throw "Folder APK tidak ditemukan: $apkDirectory"
}
if (-not (Test-Path -LiteralPath $checksumFile -PathType Leaf)) {
    throw "Daftar checksum tidak ditemukan: $checksumFile"
}

$apkFiles = @(Get-ChildItem -LiteralPath $apkDirectory -Filter '*.apk' -File | Sort-Object Name)
if ($apkFiles.Count -ne 82) {
    throw "Paket tidak lengkap: ditemukan $($apkFiles.Count) APK, seharusnya 82."
}

$checksums = @(Import-Csv -LiteralPath $checksumFile)
if ($checksums.Count -ne 82) {
    throw "Daftar checksum tidak lengkap: ditemukan $($checksums.Count) entri, seharusnya 82."
}

Write-Host 'Memeriksa 82 APK...' -ForegroundColor Cyan
$filesByName = @{}
foreach ($apk in $apkFiles) { $filesByName[$apk.Name] = $apk }
foreach ($entry in $checksums) {
    if (-not $filesByName.ContainsKey($entry.File)) {
        throw "APK hilang: $($entry.File)"
    }
    $actual = (Get-FileHash -LiteralPath $filesByName[$entry.File].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $entry.SHA256.ToLowerInvariant()) {
        throw "Checksum tidak cocok untuk $($entry.File). Paket mungkin rusak atau berubah."
    }
}
Write-Host 'PASS: paket R17 lengkap dan semua checksum cocok.' -ForegroundColor Green

if ($VerifyOnly) {
    Write-Host 'Verifikasi selesai; tidak ada instalasi yang dilakukan.'
    exit 0
}

$fixedAdb = 'C:\Users\WIN\AppData\Local\Android\Sdk\platform-tools\adb.exe'
if (Test-Path -LiteralPath $fixedAdb -PathType Leaf) {
    $adb = $fixedAdb
} else {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adbCommand) {
        throw 'ADB tidak ditemukan. Instal Android Platform Tools atau letakkan adb pada PATH.'
    }
    $adb = $adbCommand.Source
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $connected = @(& $adb devices | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
    })
    if ($connected.Count -eq 0) {
        throw 'Tidak ada HP ADB yang siap. Sambungkan USB debugging atau jalankan adb connect terlebih dahulu.'
    }
    if ($connected.Count -gt 1) {
        throw 'Lebih dari satu perangkat ADB terhubung. Jalankan skrip dengan -Serial "serial-perangkat".'
    }
    $Serial = $connected[0]
}

Write-Host "Perangkat: $Serial" -ForegroundColor Cyan
& $adb -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Perangkat ADB tidak merespons.' }

$installed = @(& $adb -s $Serial shell pm list packages --user 0 $packageName)
if ($LASTEXITCODE -ne 0) { throw 'Tidak dapat memeriksa paket pada HP.' }
if ($installed -contains "package:$packageName") {
    throw 'TikTok masih terpasang. Skrip sengaja tidak melakukan uninstall atau clear-data. Uninstall sendiri hanya setelah draft penting diamankan.'
}

Write-Host 'Memasang 82 split APK. Jangan cabut kabel atau mematikan Wireless debugging...' -ForegroundColor Cyan
$paths = @($apkFiles | ForEach-Object FullName)
& $adb -s $Serial install-multiple @paths
if ($LASTEXITCODE -ne 0) {
    throw 'Instalasi gagal. Jangan menghapus berkas paket; baca pesan ADB di atas.'
}

$installedAfter = @(& $adb -s $Serial shell pm list packages --user 0 $packageName)
if ($installedAfter -notcontains "package:$packageName") {
    throw 'ADB melaporkan instalasi selesai, tetapi paket TikTok tidak ditemukan sesudahnya.'
}

Write-Host ''
Write-Host 'SELESAI: TikTok Sony LIVE R17 terpasang tanpa root.' -ForegroundColor Green
Write-Host 'Buka TikTok, login kembali, matikan update otomatis, lalu uji preview Sony sebelum LIVE.'
