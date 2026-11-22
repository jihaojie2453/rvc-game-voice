# RNNoise 降噪接入 —— 交接文档

> 目标读者：接手实现的人（或另一个 AI 对话）。读完这一份就能动手，不需要再问前面的上下文。

> **状态：已接入（0.6.7 / versionCode 26）**。降噪开关是「高级设置 → 降噪（RNNoise）」，默认关。
> 实际怎么落地的见 §11「实施记录」——**§5 原来的写法有三处会让你白做，先看 §11 再看 §5**。

---

## 0. 一句话目标

在**虚拟麦 / 试听的 48 kHz 采集流**上加一级 RNNoise 降噪，让"轻声说话"和"摩擦噪声"能被**频谱结构**区分开，而不是只靠能量门限。

**验收**：
- xiao 模型（现在几乎不降噪，摩擦衰减仅约 13 dB）提到 **≥20 dB**
- paimeng 现有水平（**26.5 dB**）不退化
- 说话不吞字、尾音平滑、无断流、负载 < 80%
- `live_denoise = 0` 时行为与当前版本完全一致

---

## 1. 为什么必须做这一层

现在的降噪**完全依赖能量门限**（`Live.kt` 里的 `gate`）：低于阈值就压到静音。这导致一个绕不过去的矛盾：

| 阈值调低（如 −50）| 阈值调高（如 −30）|
|---|---|
| 轻声能过，但摩擦噪声也过 | 摩擦被压，但轻声/尾音被吃掉 |

而摩擦声的能量峰值是 **−5 ～ −11 dBFS**，比任何合理的阈值都高 **30 dB 以上**——**门限对它无效**。

paimeng 之所以能压到 26.5 dB，是**模型权重本身**把噪声映射成了接近静音；xiao 的权重不具备这个特性，所以参数怎么调都没用（已实测：8 位/16 位解码器、阈值 −30/−42/−50、rms_mix 0/0.25 全部无效）。

RNNoise 判断的是**频谱结构**：人声有谐波峰谷，噪声是宽带平坦的。所以它能做到"轻声照常通过、噪声照常压掉"。

---

## 2. 仓库与环境

```
仓库     E:\项目\VoiceStudio-v0.5.3\source\rvc-voice-studio-mobile
远程     https://github.com/jihaojie2453/rvc-game-voice.git   （私有）
分支     main
版本     0.6.6（android/app/build.gradle.kts:20）
minSdk   29    targetSdk 35
ABI      仅 arm64-v8a（build.gradle.kts:21）
```

### 构建

```powershell
$env:JAVA_HOME          = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME       = 'E:\VoiceStudio-v0.5.3\toolchain\android-sdk'
$env:GRADLE_USER_HOME   = 'E:\VoiceStudio-v0.5.3\gradle-home'
$env:VS_KEYSTORE        = 'E:\VoiceStudio-v0.5.3\keystore\voicestudio-release.jks'
$env:VS_KEYSTORE_PASSWORD = 'voicestudio'
$env:VS_KEY_ALIAS       = 'voicestudio'

# ★ 必须用 ASCII 的 junction 路径。直接 cd 到 E:\项目\... 会因为中文路径被 AGP 拒掉：
#   "Your project path contains non-ASCII characters"
#   E:\VoiceStudio-v0.5.3\source 是一个指向 E:\项目\VoiceStudio-v0.5.3\source 的 junction。
cd E:\VoiceStudio-v0.5.3\source\rvc-voice-studio-mobile\android
E:\VoiceStudio-v0.5.3\toolchain\gradle-8.13\bin\gradle.bat --no-daemon --offline --console=plain assembleRelease
```

产物：`android/app/build/outputs/apk/release/app-release.apk`

> **坑 1**：如果报 `Unresolved reference 'str'` 之类的大批假错误，是 Kotlin 增量缓存坏了。删掉
> `android/app/build/kotlin`、`android/app/build/tmp`、`android/app/build/generated` 再编即可。
> 换过项目路径（比如从中文路径换到 junction）之后一定会遇到这一次。
>
> **坑 2**：`--offline` 能过（依赖都在 `E:\VoiceStudio-v0.5.3\gradle-home`），别让它去连网。

### 安装到平板

```powershell
$adb = 'E:\VoiceStudio-v0.5.3\toolchain\android-sdk\platform-tools\adb.exe'
& $adb pair 192.168.1.161:<配对端口> <六位配对码>     # 无线调试重启后需要重新配对
& $adb connect 192.168.1.161:<连接端口>
& $adb -s <serial> install -r -g android\app\build\outputs\apk\release\app-release.apk
```

- 设备：小米平板 8 Pro（`25091RP04C`），已 root（SukiSU）
- 无线 adb 的端口**每次重启都会变**，mDNS 名字 `adb-1f3e899b-akoQVG._adb-tls-connect._tcp`
- 读 App 私有日志需要 `su`；SukiSU 里要给 **shell** 授权 root

---

## 3. 现有音频链路（**必读，插入点选错就白做**）

### 3.1 两条采集路径

```
【游戏/虚拟麦】
桥接进程 PCM16@48k --socket--> VirtualMicActivity.reader 线程
                              (VirtualMicActivity.kt:278-292)
                              → FloatArray → 输入增益 → softClip
                              → ring.write(pcm)        ← ① 插入点
                              → 转换线程 VirtualMicActivity.kt:341
                                 core.step(ring.copy(...w48), settings)

【试听/返音】
AudioRecord float@48k → LiveEngine.captureLoop (LiveEngine.kt:296)
                      → ring → LiveEngine.kt:427  core.step(x48, settings)
                      ↑ 同样在 ring 写入前插一层
```

两条路共用 `LiveCore`（`Live.kt:72`），所以**降噪要插在各自的 ring 写入之前**，各持有一份降噪状态。

### 3.2 `LiveCore.step` 内部（`Live.kt:188`）

```
1. 跳过判定：blockIsQuiet(x48, w48, b48, threshold) → 整块输出数字静音
   （且 holdLeft > 0 时不许跳过，否则尾音被硬切）
2. x16 = fit(downsampler.process(x48, b48), window*160)
3. y = rvc.streamWindow(x16, st, tail, b, state)     ← 模型推理
4. SOLA 对齐 + 交叉淡化
5. 逐 10 ms 帧的门限（gate）→ softClip → 输出 b48 个采样
```

### 3.3 ⚠️ 关键陷阱：16 kHz 数据是**重叠窗口**，不能在那降噪

`Dsp.WindowResampler.process(x, advance)`（`Rvc.kt:733`）每次返回的是**整窗** 16 kHz 数据，
内部只增量重算尾部（`previous`/`converted` 缓存）。也就是说：

- 每次 `step()` 拿到的 `x16` 与上一次**大面积重叠**
- RNNoise 是**有状态的流式**算法，同一段音频喂两次会让 GRU 状态错乱 → 声音破碎

**所以降噪必须插在 48 kHz 采集侧、音频进入 `ring` 之前**，只处理"新到达"的采样。

---

## 4. RNNoise 接口事实（别按 16 kHz 理解）

| 项 | 值 |
|---|---|
| 采样率 | **48 kHz**（正好等于本项目采集率）|
| 帧长 | **480 采样 / 帧（10 ms）**，`rnnoise_get_frame_size()` 可查 |
| API | `rnnoise_create(NULL)` 用内置模型 / `rnnoise_process_frame(st, out, in)` 返回浊音概率 / `rnnoise_destroy(st)` |
| **输入量程** | **16 位整数量程（±32768），不是 −1..1**。官方 demo 把 short 直接赋给 float 不缩放；`denoise.c` 判静音的阈值是 `E < 0.04`，喂 ±1 的信号会一直被当成静音，等于没接。`rnnoise_jni.c` 里做了 ×32768 / ÷32768 换算 |
| 状态 | `DenoiseState` 跨帧保持，**必须按顺序喂**，不能跳帧、不能重复 |
| **算法延迟** | 自己就滞后一帧（`frame_synthesis` 用的是上一帧的 `delayed_X`），所以 ≥10 ms；再加帧化最多 10 ms，**实际 10～20 ms** |
| 权重 | 不在仓库里。`src/rnnoise_data.c`（78 MB 文本，编译出 14 MB 数据）要从 `https://media.xiph.org/rnnoise/models/rnnoise_data-<model_version>.tar.gz` 下载，`model_version` 是仓库里那个文件的内容（当前 `0a8755f8…b37`，sha256 一致才算对）|
| 体积/开销 | 数据 14 MB（float 权重，`_little` 变体更小）、CPU 约 0.5～1% |
| 许可 | BSD（xiph/rnnoise），随源码带 `COPYING` |

---

## 5. 接入方案

> ⚠️ **这一节是原始设想，实现时有两处没照它做，原因见 §11。别直接照抄。**
>
> 1. **不要改 `CMakeLists.txt` 指望 APK 带上降噪** —— Gradle 根本不会编译原生代码
>    （`app/build.gradle.kts` 里没有 `externalNativeBuild`，`libvsdecoder.so` 是预编译入库的）。
>    实际做法是**单独编一个 `librnnoise.so`** 放进 `jniLibs`。
> 2. **不要往 `libvsdecoder.so` 里加** —— 那是 ncnn 的解码库，重编它需要 NDK + ncnn + Vulkan
>    全套，且会连带影响 NPU/GPU 那条已经调好的路径。

### 5.1 原生代码

1. 取 [xiph/rnnoise](https://github.com/xiph/rnnoise) 源码，把 `src/*.c`、`src/*.h`
   以及 `include/rnnoise.h` 放进 `android/app/src/main/cpp/rnnoise/`
   （注意 `src/rnn_data.c` 是权重表，体积不大但必须带）
2. 修改 `android/app/src/main/cpp/CMakeLists.txt`：

```cmake
file(GLOB RNNOISE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/*.c)
add_library(vsdecoder SHARED ncnn_decoder.cpp rnnoise_jni.cpp ${RNNOISE_SOURCES})
target_include_directories(vsdecoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)
target_link_libraries(vsdecoder ncnn android log)
set_source_files_properties(${RNNOISE_SOURCES} PROPERTIES COMPILE_OPTIONS "-O3")
```

3. 现有 CMake 已用 FetchContent 拉 ncnn，勿动那部分。ABI 只有一个（arm64-v8a），不用管别的。

### 5.2 JNI 封装

`rnnoise_jni.cpp`（新文件，放 `cpp/` 下）：

```cpp
JNIEXPORT jlong JNICALL Java_com_voicestudio_phone_Denoise_nativeCreate(JNIEnv*, jclass);
JNIEXPORT void  JNICALL Java_com_voicestudio_phone_Denoise_nativeProcess(
        JNIEnv* env, jclass, jlong handle, jfloatArray frame);   // 长度必须是 480
JNIEXPORT void  JNICALL Java_com_voicestudio_phone_Denoise_nativeDestroy(JNIEnv*, jclass, jlong handle);
```

Kotlin 侧 `Denoise.kt`：

```kotlin
object Denoise {
    const val FRAME = 480                 // 10 ms @ 48 kHz
    external fun nativeCreate(): Long
    external fun nativeProcess(handle: Long, frame: FloatArray)
    external fun nativeDestroy(handle: Long)
}
```

`System.loadLibrary("vsdecoder")` 已经在现有代码里加载（解码器用的同一个库）。

### 5.3 插入点（精确位置）

**两个采集循环里，`ring.write(...)` 之前**，加一个帧化缓冲：

```kotlin
// 伪代码，两条路径各一份实例
private val pending = ArrayList<Float>(480)
private var denoise = if (enabled) Denoise.nativeCreate() else 0L

fun denoiseInto(pcm: FloatArray) {         // pcm 是 48 kHz float
    if (denoise == 0L) return
    // 攒够 480 的整倍数，逐帧原地处理
    ...
}
```

- 游戏：`VirtualMicActivity.kt` 第 291 行 `ring.write(pcm)` 之前
- 试听：`LiveEngine.kt` `captureLoop` 里 `rec.read(...)` 之后、写入 ring 之前

**状态重置**：会话开始、`IDLE_RESET`（`VirtualMicActivity.kt:317`）、`MUTE_ENTER`（:336）时
`nativeDestroy` 再 `nativeCreate`，否则残留状态会让声音发闷或断续。

### 5.4 配置与开关

- `VoiceProfiles` 的 `GROUP_ENGINE` 增加 `live_denoise`（0/1），可选 `live_denoise_mix`（干湿比）
- `LiveParams`（`Live.kt:22` 附近）读取该字段
- 面板：`LiveSettingsUi.kt` 里有现成的 `slider(...)` / `switchParam(...)` 模板，照抄即可
- **默认关闭**，方便 A/B

---

## 6. 接线后必须一起复调的项

| 项 | 说明 |
|---|---|
| **静音阈值** | RNNoise 会把底噪压掉 15～20 dB，现在 −42 的含义会变。要在降噪后再标定一次（否则要么漏噪、要么吞字）|
| **跳过逻辑** | 降噪后更安静 → `blockIsQuiet` 更容易命中 → 更省电。但**保留 `holdLeft > 0` 不跳过**的保护，否则尾音又会被硬切 |
| **输入增益** | 现在用户设的是 0；RNNoise 不改变电平，无需联动 |
| **延迟** | 帧化最多 +10 ms，可忽略；但别在 `ring` 之后再插，否则会叠加缓冲 |
| **CPU** | +1% 左右，远小于模型本身 |

---

## 7. 验证方法

### 7.1 客观指标（改动前后各测一次）

| 指标 | 来源 | 判定 |
|---|---|---|
| 摩擦衰减 | `blocks.csv` 里摩擦段的 `input_peak` − `output_peak` | 当前 paimeng **26.5 dB**（中位），xiao 约 13 dB，目标 xiao ≥20 |
| 尾音 | `convert → skip` 切换前 3 步的 `output_peak` 应递减 | 出现 `−32 → −45 → −75 → 静音` 即正常 |
| 断流 | `session.log` 的 `gap_ms` / `trimmed_frames` / `track_underruns` | 必须全为 0 |
| 负载 | `session.log` 的 `compute_ms`，或 `live_log.txt` 的 `load` | 中位 < 0.8 × block |
| 跳过占比 | `blocks.csv` 的 `quiet_skip` | 35～50% 属正常 |

### 7.2 日志位置

```
试听   files/live_log.txt                        （每次会话结束时写 summary）
游戏   files/virtual_mic/<epoch>/session.log     （CONFIG / STREAM / BRIDGE_*）
       files/virtual_mic/<epoch>/blocks.csv      （逐块指标）
       files/virtual_mic/root-<epoch>.log        （桥接侧统计）
```

`files` 指 `/sdcard/Android/data/com.voicestudio.phone/files`。读取需要 root：

```sh
su -c 'cp /sdcard/Android/data/com.voicestudio.phone/files/live_log.txt /data/local/tmp/测试/'
```

`blocks.csv` 的列格式见 `Live.kt:390` 的格式串。

### 7.3 抓"注入到游戏的音频"（听得见的证据）

```sh
su -c 'touch /sdcard/Android/data/com.voicestudio.phone/files/debug_trace'
# ★ 必须完全关掉虚拟麦再重开，让桥接进程重启，它只在启动时读这个开关
```

之后会话结束会留下：
- `/data/local/tmp/root-<epoch>`          原始麦克风输入（48k PCM16）
- `/data/local/tmp/root-<epoch>.injected.wav`  注入给游戏的数据

> 这两个文件的 WAV 头里长度字段是 0（边写边录没回填），用 Python `wave` 会报
> "not a WAVE file"。要自己找 `data` 块、从其后直接按 `<i2` 读到底。

---

## 8. 已知坑（都踩过了，别重复）

1. **Kotlin 假编译错误** → 删 `app/build/kotlin` + `app/build/tmp/kotlin-classes`
2. **`live_cushion_cs` 单位是 10 ms**（写 15 = 150 ms，写 100 = 1 秒）
3. **重装 App / 恢复备份会清空 `files/models/`**，4 个模型要重新推
   （paimeng / xiao 的源文件在 `E:\rvc-build\out\{paimeng,xiao}`，推送时改名为
   `model.enc.onnx` / `model.dec.onnx` / `model.dec_a.onnx` / `model.dec_b.onnx` /
   `model.dec.w8a16.onnx` / `model.dec.ncnn.{param,bin}` / `model.json` / `settings.json`）
4. **profile 文件的 owner 是 App uid**（重装会变 u0_a297→u0_a298…），命令行写入后要
   `chmod 666`；目录 `chmod 777`，否则 App 保存失败
5. **无线 adb 端口每次都变**，且重启无线调试后需要重新 `adb pair`
6. **中文路径引号**：`adb shell "su -c '...'"` 里套引号很容易过不去，
   复杂命令写成 `.sh` 推到 `/data/local/tmp/测试/` 再执行

---

## 9. 当前基线（用于对比，别弄丢）

**设备已装版本**包含以下修复，**不要回退**：

| 修复 | 内容 |
|---|---|
| 40k 模型支持 | `ModelSpec` 允许 40000；`ncnn_decoder.cpp` 接受 hop 400 |
| 噪声底 | `AppBridge.noiseFloor()` 保持 **±15**（11-15 14:44 那版的行为，改成 ±1 会明显变差）|
| 块级跳过 | `Live.kt` 的 `blockIsQuiet(...)`：只看最新一块，省电且不吞起音 |
| 尾音保护 | 跳过判定加 `holdLeft <= 0`，门限保持期间不许跳过 |
| 输出缓冲 | 150 ms（`live_cushion_cs=15`），吸收计算尖峰，避免"咯痰"断流 |
| 输出音量下限 | −12 → **−24 dB**（4 个文件里的范围常量）|

**paimeng / 少女**（用户当前主力方案）：

```
pitch 11, formant -2, intonation_expand 1, rms_mix 0, unvoiced_keep 1
block 240 ms, context 1510 ms, crossfade 80 ms, cushion 150 ms
threshold -42 dB, input_gain 0 dB, output_gain -24 dB
```

---

## 10. 回滚

设计上"只加不改"：

- 新增：`cpp/rnnoise/*`、`cpp/rnnoise_jni.cpp`、`Denoise.kt`
- 修改：`CMakeLists.txt`、两个采集循环各十几行、`LiveParams`/`VoiceProfiles` 各一行、面板一个滑杆
- `live_denoise = 0` 时整条路径旁路，行为与现在完全一致

出问题时把 `live_denoise` 置 0 即可，不必回滚代码。

---

## 11. 实施记录（0.6.7，已落地）

### 11.1 和原方案的三处不同

| | 原方案（§5） | 实际做法 | 为什么 |
|---|---|---|---|
| 编到哪 | 加进 `libvsdecoder.so` | **独立 `librnnoise.so`** | `libvsdecoder.so` 是预编译入库的（Gradle 不编原生），改 CMake 对 APK 无影响；重编它还要 ncnn + Vulkan + NDK 全套，风险大 |
| JNI 语言 | `rnnoise_jni.cpp` | **`rnnoise_jni.c`（纯 C）** | APK 里没有 `libc++_shared.so`，C++ 目标会多一个运行时依赖，`dlopen` 直接失败 |
| 权重来源 | “`rnn_data.c` 体积不大但必须带” | **`rnnoise_data.c` 78 MB，单独下载校验** | 仓库里没有权重，`download_model.sh` 从 `media.xiph.org` 拉，按 `model_version` 的 sha256 校验 |

### 11.2 新增/改动的文件

```
android/app/src/main/cpp/rnnoise/           源码（denoise.c kiss_fft.c pitch.c celt_lpc.c rnn.c
                                            nnet.c nnet_default.c parse_lpcnet_weights.c
                                            rnnoise_tables.c + 头文件 + x86/ 头 + COPYING）
android/app/src/main/cpp/rnnoise_jni.c      JNI shim（±1 ⇄ ±32768 换算、帧长校验、VAD 概率）
android/app/src/main/cpp/build-rnnoise-so.ps1  重编 .so 的脚本（不需要 ncnn）
android/app/src/main/cpp/CMakeLists.txt     加了可选的 rnnoise 目标（BUILD_RNNOISE=OFF 默认不编）
android/app/src/main/jniLibs/arm64-v8a/librnnoise.so   14.12 MB 产物
android/app/src/main/java/com/voicestudio/phone/Denoise.kt   原生声明 + 帧化缓冲 Denoiser
```

改动点（都是“只加不改”）：
- `Live.kt`：`LiveParams.denoise`、`readWithMic` 读 `live_denoise`
- `VirtualMicActivity.kt`：reader 线程里 `Denoiser.create(params.denoise)`、`ring.write(pcm, k)`、
  `IDLE_RESET`/`MUTE_ENTER` 时 `reset()`、`CONFIG` 行加 `denoise=`、`shapeChanged` 加这一项
- `LiveEngine.kt`：`captureLoop` 里同样一份实例（输入增益之后、写 ring 之前）
- `LiveSettingsUi.kt`（状态页/虚拟麦对话框）、`MainActivity.kt`（配置页高级设置）各一个开关
- `VoiceProfiles.kt`：`ENGINE_KEYS` 与迁移默认值加 `live_denoise`
- `strings.xml`（en / ko / zh-rCN）：`ui_denoise`、`help_denoise`

### 11.3 重编 .so

```powershell
# 一次性的：NDK + 权重
#   NDK            https://dl.google.com/android/repository/android-ndk-r27c-windows.zip （745 MB）
#   权重           https://media.xiph.org/rnnoise/models/rnnoise_data-<model_version>.tar.gz （56 MB）
#   解出来的 src\rnnoise_data.c / .h 放到 E:\rvc-build\rnnoise-model\src\

cd E:\VoiceStudio-v0.5.3\source\rvc-voice-studio-mobile\android\app\src\main\cpp
powershell -NoProfile -ExecutionPolicy Bypass -File build-rnnoise-so.ps1 `
    -Ndk E:\rvc-build\ndk\android-ndk-r27c -ModelSrc E:\rvc-build\rnnoise-model\src
# 参数可省，默认就是这两个路径。产物直接覆盖 jniLibs\arm64-v8a\librnnoise.so，然后照常 assembleRelease
```

（`-ExecutionPolicy Bypass` 是必要的：这台机器默认禁止运行 .ps1。）

编译参数（脚本里就是这些）：`-O3 -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden`，
链接 `-shared -Wl,--no-undefined -Wl,--gc-sections -Wl,-s`，`--strip-unneeded`。编出来的 `.so` 只依赖
`libm/libdl/libc`（**不能有 `libc++_shared.so`**，APK 里没这个文件）。

> ⚠️ **`-Wl,--no-undefined` 不能省**。踩过一次：`linear_init` / `conv2d_init` 的实现在
> `src/parse_lpcnet_weights.c` 里（名字像工具文件，其实属于库；它的 `main()` 在 `#if 0` 里）。
> 漏掉这个文件时，`-shared` **不会报错**——共享库默认允许未定义符号——`.so` 照样产出 14 MB，
> 但装到设备上 `System.loadLibrary` 会失败，而 `Denoise.available` 把它当"没这功能"静默旁路，
> 表现就是**开关打开了但一点效果都没有**。加上 `--no-undefined` 就会当场链接失败。
> 验证：`llvm-nm -D -u librnnoise.so` 只应看到 `LIBC`/`LIBM` 的符号。

### 11.4 上线前必须做的验证（还没做）

1. **开关打开后听得见效果吗** —— 装 0.6.7，配置页高级设置里打开「降噪（RNNoise）」，重启虚拟麦，
   对着麦克风摩擦衣袖。日志 `session.log` 的 `CONFIG` 行应出现 `denoise=true`。
2. **不吞字、尾音不硬切** —— 看 `blocks.csv`：`convert → skip` 前 3 步 `output_peak` 应递减；
   `gap_ms / trimmed_frames / track_underruns` 必须全 0（降噪会让块更容易被判静音，跳过率会升高，
   这是预期的省电，但 `holdLeft > 0` 的保护必须还在）。
3. **降噪会把阈值含义改掉** —— 底噪被压掉 15～20 dB 之后，`live_threshold_db = −42` 的作用点变了，
   要重新标定一次（先别动 −42，等数据）。

> ⚠️ **测摩擦衰减时注意指标口径变了**：`blocks.csv` 的 `input_peak` 现在记的是**降噪之后**的峰值
> （降噪在 ring 之前）。所以「摩擦衰减 = input_peak − output_peak」在开关打开时会凭空变小，
> 不代表变差。要比就比**同一段素材、开/关两次的 `output_peak`**，或者用 §7.3 的注入 WAV 直接量。

### 11.5 实测：纯环境噪声（2026-11-22）

电脑上没有 C 编译器，所以把同一套源码编成 **arm64 命令行程序**推到平板跑（`/data/local/tmp/测试/`）：
工具在 `E:\rvc-build\denoise-test\rnnoise_test.c`，编译方式同上（多链 `rnnoise_test.c`，不链 `rnnoise_jni.c`）。
它读 48 kHz 单声道 16 位 WAV，逐 480 帧调 `rnnoise_process_frame`，按秒打印输入/输出电平、增益和 VAD。

素材：用户录的 40.31 秒纯环境噪声（48 kHz，RMS −24.7 dBFS，无任何人声）。

```
gain: median −34.82 dB   p10 −48.83 dB   p90 −24.73 dB   min −65.30   max −16.07
vad : 帧数 low(<0.2) 3485   mid 494   high(>=0.8) 52   mean 0.099
```

**结论：库在真机上可用，模型正常加载，纯噪声压掉 ~35 dB（中位）**，VAD 正确判为"几乎无人声"
（4031 帧里只有 52 帧像人声）。

**但这只证明了一半**：没有人声素材，就不知道**轻声起音会不会被吃掉**、尾音会不会被削。
RNNoise 在纯噪声上会收敛到"全压"，数字天然漂亮；真实场景是"人声夹着噪声"，那时增益会小很多。
下一步需要一段**带噪声的说话**素材，用同一个工具跑，看两个数：

| 指标 | 期待 |
|---|---|
| 语音段增益 | 接近 0 dB（不损伤）|
| 语音间隙/噪声段增益 | ≤ −15 dB |


