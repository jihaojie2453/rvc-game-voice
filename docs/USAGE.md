# Voice Studio usage

[한국어](USAGE.ko.md) · [Model converter walkthrough](../converter/USAGE.md)

Voice Studio replaces the microphone input of one game with a converted voice. Everything runs
on the phone; nothing is uploaded.

**Root is required.** The bridge runs as the `shell` user through KernelSU or SukiSU, so the app
must be whitelisted in your root manager. Shizuku is no longer used (removed in 0.4.5), and the
web-call and file-conversion modes are gone (removed in 0.6.0).

## Before the first session

1. **Root** — install KernelSU or SukiSU, then allow Voice Studio in its app list. The status
   tab shows "Root 已授权" when it works.
2. **Overlay permission** — "显示在其他应用上层". Without it the app stays in the foreground
   instead of leaving the floating button.
3. **Shared models** — HuBERT (360 MB), its NPU export (360 MB) and RMVPE (350 MB). Download
   them in 通用 (General) → 音色模型, or copy them into
   `Android/data/com.voicestudio.phone/files/common/`.
4. **A voice model** — use the PC converter to turn an RVC v2 `.pth` into a folder with
   `model.enc.onnx` and `model.dec.onnx` (optionally `model.dec.w8a16.onnx`,
   `model.dec_a/b.onnx` and `model.json`), then import the folder or ZIP in 通用 → 音色模型.

## Bind a game

1. 配置 (Games) → 添加游戏, pick the installed game.
2. Choose a voice and a profile. A new game starts on the default profile, which is read-only:
   create your own with 新建方案 and edit that.
3. 编辑参数 opens three groups:
   - **音色参数** — pitch, formant, low/high-pitch protection, intonation, loudness envelope,
     breath;
   - **高级设置** — block size, additional context, crossfade, output buffer, silence threshold,
     input/output gain, split decoder;
   - **模型设置** — compute device and decoder.
4. Repeat for every game you play. Each game keeps its own voice + profile binding.

## Start the virtual microphone

1. 状态 (Status) → 启动虚拟麦克风.
2. The app starts a foreground service and the root bridge, then moves to the background — it
   does not appear in recents. A capsule stays on screen.
3. Open the game and start its voice chat or recording. The capsule goes **green** when you
   speak, red when you are silent ("已连接 · 等你说话") or when nothing is recording ("等待
   麦克风").
4. Tap the capsule for the panel: current game, voice, profile, five live parameters, 重开会话
   and 退出应用.

The red state is safe: the bridge runs with `--fail-closed`, so the game hears silence, never
your raw microphone, until conversion is ready.

While nobody speaks, the app stops running the models and returns silence. That is what keeps
the phone cool during long sessions.

## Which parameters need what

| Parameter | Preview | Virtual microphone |
| --- | --- | --- |
| Pitch, protection, intonation, envelope, breath | immediate | immediate (500 ms poll) |
| Silence threshold, input gain, output gain | immediate | immediate |
| Formant | immediate | **restart the session** |
| Block, context, crossfade, output buffer | next start | **restart the session** |
| Compute device, decoder | next start | **restart the session** |

The 重开会话 button in the panel is grey until something that needs a restart has changed.

## Troubleshooting

- **"等待麦克风"** — no app is recording. Start the game's voice chat. After reinstalling the
  app the game may have lost its recording session; restart the game.
- **Red although the game has its microphone on** — nobody is speaking. The capsule turns green
  on speech, not on "connected".
- **Green while you are silent** — the input is above your silence threshold. Raise 静音阈值
  (try -30 dB) or lower 输入增益.
- **Nothing reaches the game** — check that root is granted, that the model finished loading,
  and that the game really holds its microphone. The log in
  `Android/data/com.voicestudio.phone/files/virtual_mic/<epoch>/session.log` shows the bridge
  state (`BRIDGE_IDLE`, `BRIDGE_STREAM`, `BRIDGE_SILENT_HOLD`).
- **The phone gets hot** — put the tablet on a hard surface: on a blanket the microphone picks
  up friction noise that keeps the input above the gate, so the models keep running. A headset
  microphone close to the mouth also helps, but the Bluetooth one switches the link to SCO and
  makes game audio narrowband.
- **Timings** — `blocks.csv` in the same folder has one line per step: compute time, per-stage
  time, input peak, whether the step was skipped.

## Limits

- Only one session at a time: preview, virtual microphone and a restart exclude each other.
- Changing the formant or the engine shape requires a restart; a session cannot change its
  decoder shape.
- A game that keeps its microphone open while you are silent still costs one gate evaluation per
  block, and the models run whenever the input exceeds the threshold.
- The app is written around Snapdragon QNN HTP; other SoCs are untested for the virtual
  microphone.
