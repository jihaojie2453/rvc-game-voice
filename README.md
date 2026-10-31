# Timbre Forge (Android) · 音色工坊

Maintained by [@jihaojie2453](https://github.com/jihaojie2453).

Real-time voice conversion for mobile games, running entirely on the phone. Pick a game, bind a
voice and a profile, start the virtual microphone, and the game receives the converted voice
instead of the physical microphone. The app then moves to the background and leaves a small
capsule on screen; mute, material and playback are all driven from the overlay from then on.

Built on the [RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI) framework:
the conversion engine and its model exports come from there, and are credited in `NOTICE.md`.
Everything else — the app, the bridge integration and the features below — was written for this
project, starting from 0.4.4:

- **The Android app and its overlay.** Five screens (录音 / 配置 / 状态 / 音频 / 通用), per-game
  voice binding, profiles, the live parameter set; the floating capsule, the two-column panel and
  the audio window.
- **闭麦 (mute).** One tap stops inference and mutes the model while the game keeps receiving
  silence — the microphone route stays alive, so no device switch and no re-negotiation.
- **返音 (monitor).** The material being sent into the microphone is also played locally, with its
  own volume; it keeps running on its own clock while the game is not recording.
- **Local material and online music.** Audio files from the device are mixed into the virtual
  microphone (no conversion), and online songs — through user-supplied Lx source scripts — share
  the same injection path: hot / liked / search lists, likes, play mode and quality.
- **The audio deck.** Block scheduling, mixing, monitor clock and streaming playback, all in
  `AudioDeck`.

Models, recordings and converted audio never leave the device.

**Root is required** (KernelSU / SukiSU). The bridge process runs as the `shell` user so it can
hold `MODIFY_AUDIO_ROUTING` and register an `AudioPolicy` mix that replaces the target app's
microphone input. Shizuku was used up to 0.4.4 and was removed in 0.4.5. There is no web-call
or file-conversion mode any more; both were removed in 0.6.0.

## What it does

Five tabs:

| Tab | Contents |
| --- | --- |
| **录音** Record | record, waveform, takes, playback, save — your own voice, no conversion |
| **配置** Config | add a game, bind a voice + a profile, create / rename / delete profiles, edit parameters |
| **状态** Status | start / stop the virtual microphone, phase, timer, input and output levels, compute time |
| **音频** Audio | the material folder: pick a directory, grant access, browse the categories and audio files on the device, and send one into the microphone |
| **通用** General | voice models (import / download), compute device, CPU threads, app language |

## The overlay

While a session runs the app has no task in recents; only a capsule stays on top.

- **Green** — someone is talking and the voice is being converted right now.
- **Red** — "waiting for the microphone" (nothing is recording) or "connected · waiting for your
  voice" (the game holds the microphone but nobody is speaking).

Tap the capsule for the panel. It is laid out in two columns:

- **Left** — the game's voice and profile, then five live sliders: output gain, input gain,
  high-pitch protection, formant, silence threshold.
- **Right** — 重开会话 (restart session), 闭麦 (mute), 音频 (audio window) and 退出应用 (quit).
  The restart button only lights up when a parameter that needs a new session has been changed.

**闭麦** adds a second, round badge next to the capsule. The badge reports the session itself:
green while inference is running, amber while it is still starting, red and slashed once the
microphone is muted. Tapping it mutes and unmutes; the game keeps its route the whole time.

**音频** opens a ♪ window with the material:

- **本地** — audio files on the device. Picking one sends it into the microphone, unconverted.
- **首页** — online songs from the Lx source scripts you supply, in three lists: 热门 / 喜欢 /
  搜索. Every row carries a ♥; 喜欢 is that liked list, kept across restarts.
- **播放模式** (in order / repeat one / shuffle) and **音质** (auto / highest / stable) cycle by
  tapping. 音质 applies to the next song.
- **返音** also plays the material on the phone, with its own volume. The × closes the window and
  stops playback.

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
  They are downloaded from the upstream `common-v1` release; see `NOTICE.md`.
- `SYSTEM_ALERT_WINDOW` for the overlay, and all-files access for the material folder.
- Online music needs the Lx source scripts, which are not bundled — drop `.js` files into
  `files/lx_sources/` (see `LxSources.USER_DIR`).

## Known limitations

- The formant, the block size, the context, the crossfade, the output buffer and the model
  settings are baked into the session shape: changing them needs 重开会话 (restart session).
- Only one session at a time; the preview, the virtual microphone and a restart exclude each
  other.
- A game that keeps its microphone open while you are not talking still makes the app convert;
  the silence skip only helps once the input falls below the gate. A tablet resting on a soft
  surface picks up friction noise that can be louder than speech — put it on something hard.
- Using the Bluetooth headset microphone switches the link from A2DP to SCO: game audio becomes
  narrowband and gains latency.
- On the tablet speaker, 返音 is picked up by the microphone and comes back through the game as a
  second, converted copy. Use headphones.
- `README.ko.md` and `docs/*.ko.md` are outdated; the English files are authoritative.

## Credits

Developed with [DeepSeek](https://github.com/deepseek) as the coding agent. The overlay system
(capsule, panel, mute badge, audio window), the audio deck, 闭麦 / 返音, local material playback and
the online-music integration were written with it, and most commit messages here come from it too.
The conversion engine and the model exports are RVC's — see [NOTICE.md](NOTICE.md).

## License

MIT, see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
