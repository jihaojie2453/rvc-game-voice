# Builds the standalone librnnoise.so that ships in src/main/jniLibs.
#
# The Gradle build does NOT compile native code (app/build.gradle.kts has no externalNativeBuild):
# libvsdecoder.so and librnnoise.so are checked-in artifacts. Run this after changing the rnnoise
# sources or the model, then rebuild the APK.
#
# Needs:
#   NDK           an Android NDK; r27c is what the current .so was built with
#   -ModelSrc     directory holding the generated rnnoise_data.c / rnnoise_data.h, from
#                 https://media.xiph.org/rnnoise/models/rnnoise_data-<model_version>.tar.gz
#                 (model_version in the rnnoise repository holds the sha256)
#
# Usage:
#   .\build-rnnoise-so.ps1
#   .\build-rnnoise-so.ps1 -Ndk 'D:\ndk\android-ndk-r27c' -ModelSrc 'E:\rvc-build\rnnoise-model\src'

param(
    [string] $Ndk = 'E:\rvc-build\ndk\android-ndk-r27c',
    [string] $ModelSrc = 'E:\rvc-build\rnnoise-model\src'
)

$ErrorActionPreference = 'Stop'

$bin = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$cc  = Join-Path $bin 'aarch64-linux-android29-clang.cmd'
if (-not (Test-Path $cc)) { throw "NDK not found at $Ndk" }
if (-not (Test-Path (Join-Path $ModelSrc 'rnnoise_data.c'))) { throw "rnnoise_data.c not found in $ModelSrc" }

$here = $PSScriptRoot
$work = Join-Path $env:TEMP 'rnnoise-build'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null

# Everything compiles in one directory: the rnnoise sources, the JNI shim, and the generated weights.
$stage = Join-Path $work 'src'
Copy-Item (Join-Path $here 'rnnoise') $stage -Recurse -Force
Copy-Item (Join-Path $here 'rnnoise_jni.c') $stage -Force
Copy-Item (Join-Path $ModelSrc 'rnnoise_data.c') $stage -Force
Copy-Item (Join-Path $ModelSrc 'rnnoise_data.h') $stage -Force

Push-Location $stage
try {
    $cflags = @('-c', '-O3', '-fPIC', '-ffunction-sections', '-fdata-sections', '-fvisibility=hidden', '-I.')
    # parse_lpcnet_weights.c holds linear_init/conv2d_init, which rnnoise_data.c needs; its main() is
    # inside "#if 0". Leaving it out links a .so with an undefined rnn_linear_init, because -shared does
    # not require every symbol to resolve -- it only fails later, at load time on the device.
    $sources = @('denoise.c', 'kiss_fft.c', 'pitch.c', 'celt_lpc.c', 'rnn.c', 'nnet.c',
                 'nnet_default.c', 'parse_lpcnet_weights.c', 'rnnoise_tables.c', 'rnnoise_data.c',
                 'rnnoise_jni.c')

    Write-Host 'Compiling the denoiser...'
    & $cc @cflags @sources
    if ($LASTEXITCODE -ne 0) { throw "compile failed ($LASTEXITCODE)" }

    Write-Host 'Linking...'
    $objects = $sources | ForEach-Object { [IO.Path]::ChangeExtension($_, '.o') }
    # --no-undefined: a missing symbol must break the build here, not the app on the device.
    $ldflags = @('-shared', '-Wl,--no-undefined', '-Wl,--gc-sections', '-Wl,-s', '-o', 'librnnoise.so') + $objects + @('-lm')
    & $cc @ldflags
    if ($LASTEXITCODE -ne 0) { throw "link failed ($LASTEXITCODE)" }

    & (Join-Path $bin 'llvm-strip.exe') --strip-unneeded librnnoise.so

    $out = Join-Path $here '..\jniLibs\arm64-v8a'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $out = Join-Path $out 'librnnoise.so'
    Copy-Item 'librnnoise.so' $out -Force
    $mb = [math]::Round((Get-Item $out).Length / 1MB, 2)
    Write-Host "Wrote $out ($mb MB)"
} finally {
    Pop-Location
}
