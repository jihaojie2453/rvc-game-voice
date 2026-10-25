# Voice Studio (Android)

Real-time voice conversion for mobile games, running entirely on the phone. Pick a game, a
voice and a profile, start the virtual microphone, and the game receives the converted voice
instead of the physical microphone. The app then moves to the background and leaves a small
red/green floating button on screen.

Based on [RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI). Models,
recordings and converted audio never leave the device.

**Root is required** (KernelSU / SukiSU). The bridge process runs as the `shell` user so it can
hold `MODIFY_AUDIO_ROUTING` and register an `AudioPolicy` mix that replaces the target app's
microphone input. Shizuku was used up to 0.4.4 and was removed in 0.4.5. There is no web-call
or file-conversion mode any more; both were removed in 0.6.0.

## What it does

Four tabs:

| Tab | Contents |
| --- | --- |
| **Record** | record, waveform, takes, playback, save — the original voice, no conversion |
| **Games** | add a game, bind a voice + profile, create/rename/delete profiles, edit parameters |
| **Status** | start/stop the virtual microphone, phase, timer, input/output levels, compute time |
| **General** | voice models (import / download), compute device, CPU threads, app language |

## Overlay

While a session runs the app has no task in recents; only a capsule stays on top.

- **Green** — someone is talking and the voice is being converted right now.
- **Red** — "waiting for the microphone" (nothing is recording) or "connected · waiting for your
  voice" (the game holds the microphone but nobody is speaking).

Tap the capsule for the panel: current game, voice, profile, five live parameters (output gain,
input gain, high-pitch protection, formant, silence threshold), "restart session" and "quit".
The restart button only lights up when a parameter that needs a new session has been changed.

## How it works

1. **Start** creates a foreground service and starts the root bridge:
   `app_process` as `shell` (`dev.virtualmic.Main`), `--fail-closed`.
2. The bridge registers an `AudioPolicy` mix for the target UID
   (`ROUTE_FLAG_LOOP_BACK`, `MIX_ROLE_INJECTOR`) and reads the physical microphone.
3. PCM goes over a loopback socket to the app, which runs HuBERT + RMVPE + encoder + decoder on
   the QNN HTP NPU, and sends converted PCM back.
4. The bridge writes it into the injected track. Until conversion is ready it injects silence,
   so the raw microphone never leaks into the game.

While nobody is speaking the app skips inference entirely and returns silence — the gate is
closed anyway, so the output is unchanged.

Per-session diagnostics land in
`Android/data/com.voicestudio.phone/files/virtual_mic/<epoch>/` (`session.log`, `blocks.csv`,
per-step timings).

## Performance (measured)

Xiaomi Pad 8 Pro (Snapdragon 8 Elite), release build, block 240 ms, 1.84 s window, 42 decoder
frames, all four models on the NPU:

| Stage | ms / step | Share |
| --- | --- | --- |
| Decoder (NSF-HiFiGAN) | 44.1 | 35% |
| HuBERT | 43.3 | 34% |
| Noise, NSF excitation, resampling | 14.9 | 12% |
| RMVPE | 8.6 | 7% |
| Encoder | 8.5 | 7% |
| Gate, RMS, soft clip | 3.9 | 3% |
| 48 → 16 kHz | 3.4 | 3% |
| **Total** | **126.8** | 53% of the block |

With the silence skip, 65–88% of blocks never run the models. Over a 70-minute session the NPU
duty cycle fell from 53% to about 14%, the device went from thermal status 3 to 0, and the
battery from 48 °C to 36 °C.

## Building

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export VS_KEYSTORE=/path/to/release.jks VS_KEYSTORE_PASSWORD=... VS_KEY_ALIAS=...
cd android && ./gradlew assembleRelease
```

The Gradle wrapper is not vendored here; a Gradle 8.13 install works with
`gradle -p android assembleRelease --no-daemon`. Without a keystore the release APK is signed
with the debug key.

## Requirements

- arm64 Android 10+ (API 29). Tested on Android 17.
- Root through KernelSU or SukiSU; the app must be whitelisted in the root manager.
- Snapdragon SoC for the NPU path. The virtual microphone is written around QNN HTP and has no
  tested CPU fallback.
- About 1.1 GB for the shared models (HuBERT, its NPU export, RMVPE) plus the voice models.
- `SYSTEM_ALERT_WINDOW` for the overlay.

## Known limitations

- The formant, the block size, the context, the crossfade, the output buffer and the model
  settings are baked into the session shape: changing them needs "restart session".
- Only one session at a time; the preview, the virtual microphone and a restart exclude each
  other.
- A game that keeps its microphone open while you are not talking still makes the app convert;
  the silence skip only helps once the input falls below the gate. A tablet resting on a soft
  surface picks up friction noise that can be louder than speech — put it on something hard.
- Using the Bluetooth headset microphone switches the link from A2DP to SCO: game audio becomes
  narrowband and gains latency.
- `README.ko.md` and `docs/*.ko.md` are outdated; the English files are authoritative.

## License

MIT, see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
