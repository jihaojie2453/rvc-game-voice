package dev.virtualmic;

import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import android.os.Process;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Loopback-only, per-session token authenticated PCM16 mono 48k, length-prefixed blocks. */
final class AppBridge implements Closeable {
    private final ServerSocket server;
    private final String token;
    private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private final short[] queue = new short[48000];
    private final long[] positions = new long[48000];
    private int startDelayMs;
    private int head, queued;
    private long received, trimmed;
    private volatile long suppliedFrames;
    private volatile IOException failure;
    private volatile boolean closed;
    private volatile boolean resetPending;
    private volatile boolean converterLost;
    private final boolean failClosed;
    private final MicRelay.Trace converted;
    /** Streaming state and the current connection are swapped under this lock. */
    private final Object connLock = new Object();
    private volatile boolean connected;
    /**
     * 游戏第一次开麦时听得到「砰」：注入轨从精确的 0 起播，波形是个阶跃。
     * 这里在起播后做一个 10 ms 的淡入，并且给静音段加一个约 −66 dBFS 的噪声底，
     * 让游戏的降噪 / VAD / AGC 有稳定的参考（真麦克风永远有本底噪声）。
     */
    private int playRamp;
    private int noiseState = 0x1234567;

    private short noiseFloor() {
        noiseState ^= noiseState << 13;
        noiseState ^= noiseState >>> 17;
        noiseState ^= noiseState << 5;
        // 低位强制置 1：保证不会取到 0，否则噪声底本身又会往流里塞「精确零」
        return (short) (((noiseState & 30) - 15) | 1);
    }

    AppBridge(String name, int uid, String trace, boolean failClosed) throws IOException {
        token = name;
        this.failClosed = failClosed;
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(failClosed ? 0 : 120000);
        converted = trace == null ? null : new MicRelay.Trace(trace + ".injected.wav");
        if (!failClosed) deadline.schedule(this::close, 120, TimeUnit.SECONDS);
        log("BRIDGE_LISTENING port=" + server.getLocalPort() + " converter_uid=" + uid
                + " fail_closed=" + failClosed);
    }

    void accept() throws IOException {
        connect(server.accept());
        Thread re = new Thread(this::reacceptLoop, "bridge-reaccept");
        re.setDaemon(true);
        re.start();
    }

    /**
     * Handshake on a fresh socket and swap it in. The AudioPolicy and the injected track keep running
     * across app-side restarts, so the target never falls back to the physical microphone.
     */
    private void connect(Socket fresh) throws IOException {
        fresh.setTcpNoDelay(true);
        fresh.setSoTimeout(45000);
        DataInputStream in = new DataInputStream(fresh.getInputStream());
        DataOutputStream out = new DataOutputStream(fresh.getOutputStream());
        if (in.readInt() != 0x564d4932) throw new IOException("Invalid bridge protocol");
        if (!token.equals(in.readUTF())) throw new IOException("Invalid bridge token");
        int delay = in.readInt();
        if (delay < 80 || delay > 1500) throw new IOException("Invalid start delay");
        out.writeInt(0x564d4932);
        out.flush();
        Socket previous;
        synchronized (connLock) {
            previous = socket;
            socket = fresh;
            input = in;
            output = out;
            startDelayMs = delay;
            connected = true;
        }
        synchronized (this) { head = 0; queued = 0; }
        converterLost = false;
        if (previous != null) try { previous.close(); } catch (IOException ignored) {}
        deadline.shutdownNow();
        startReader(fresh, in);
        log("BRIDGE_CONNECTED start_delay_ms=" + delay);
    }

    /** The app went away: drop the socket, keep the policy registered and hold silence until it is back. */
    private void dropConnection() {
        Socket dead;
        synchronized (connLock) {
            connected = false;
            dead = socket;
            socket = null; input = null; output = null;
        }
        if (dead != null) try { dead.close(); } catch (IOException ignored) {}
        if (closed) return;
        if (failClosed) {
            converterLost = true;
            synchronized (this) { head = 0; queued = 0; }
            log("BRIDGE_CONVERTER_LOST: holding silence");
        }
    }

    private void reacceptLoop() {
        while (!closed) {
            boolean need;
            synchronized (connLock) { need = !connected; }
            if (!need) { sleepQuietly(50); continue; }
            try {
                connect(server.accept());
            } catch (IOException error) {
                if (closed) return;
                sleepQuietly(100);
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void startReader(Socket owner, DataInputStream in) {
        Thread reader = new Thread(() -> {
            try {
                while (!closed && connected) {
                    int n = in.readInt();
                    if (n == 0) continue; // Idle heartbeat; no PCM follows.
                    if (n == -1) { // Converter has discarded its old input/context/output.
                        synchronized (this) { head = 0; queued = 0; resetPending = false; }
                        continue;
                    }
                    if (n < 1 || n > 48000) throw new IOException("Invalid converted block length " + n);
                    long sourceStart = in.readLong();
                    if (!BridgeTiming.validPosition(sourceStart, suppliedFrames)) throw new IOException("Invalid source position");
                    byte[] pcm = new byte[n * 2];
                    in.readFully(pcm);
                    synchronized (this) {
                        int drop = Math.max(0, queued + n - queue.length);
                        head = (head + drop) % queue.length;
                        queued -= drop;
                        trimmed += drop;
                        for (int i = 0; i < n; i++) {
                            int at=(head+queued+i)%queue.length;
                            queue[at]=(short) ((pcm[2*i] & 255) | (pcm[2*i+1] << 8));
                            positions[at]=sourceStart+i;
                        }
                        queued += n;
                        received += n;
                    }
                }
            } catch (IOException error) {
                if (closed) return;
                Socket current;
                synchronized (connLock) { current = socket; }
                if (current == owner) dropConnection();
                else if (!failClosed) failure = error;
            }
        }, "converted-pcm");
        reader.setDaemon(true);
        reader.start();
    }

    private boolean writeInt(int value) {
        DataOutputStream out;
        synchronized (connLock) { out = output; }
        if (out == null) return false;
        try {
            out.writeInt(value); out.flush(); return true;
        } catch (IOException error) {
            dropConnection();
            return false;
        }
    }

    private boolean writePcm(byte[] pcm, int frames) {
        DataOutputStream out;
        synchronized (connLock) { out = output; }
        if (out == null) return false;
        try {
            out.writeInt(frames); out.write(pcm, 0, frames * 2); out.flush(); return true;
        } catch (IOException error) {
            dropConnection();
            return false;
        }
    }

    private synchronized int take(short[] block, long[] sourcePositions) {
        int n = Math.min(block.length, queued);
        for (int i = 0; i < n; i++) {
            block[i] = queue[(head + i) % queue.length];
            sourcePositions[i] = positions[(head+i)%queue.length];
        }
        head = (head + n) % queue.length;
        queued -= n;
        Arrays.fill(block, n, block.length, (short) 0);
        Arrays.fill(sourcePositions,n,block.length,-1);
        return n;
    }

    void idleHeartbeat() throws IOException {
        if (failure != null) throw failure;
        if (closed) throw new IOException("Bridge closed");
        if (!writeInt(0)) throw new IOException("Bridge closed");
    }

    void resetCallContext() throws IOException {
        synchronized (this) { resetPending = true; head = 0; queued = 0; }
        writeInt(-1);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (resetPending) {
            idleHeartbeat();
            if (System.nanoTime() >= deadline) throw new IOException("CELLULAR_STALLED");
            try { Thread.sleep(50); } catch (InterruptedException e) { throw new IOException(e); }
        }
    }

    void run(AudioRecord record, AudioTrack track, int seconds, MicRelay.Trace raw,
             AudioManager manager, InputScope scope) throws Exception {
        run(record, track, seconds, raw, manager, scope, null);
    }

    interface CallControl {
        boolean tick() throws Exception;
        void convertedReady() throws Exception;
        void failed();
        void close();
    }

    void run(AudioRecord record, AudioTrack track, int seconds, MicRelay.Trace raw,
             AudioManager manager, InputScope scope, CallControl call) throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        if (seconds > 0) watchdog.schedule(this::close, seconds + 5, TimeUnit.SECONDS);
        short[] mic = new short[480], out = new short[480];
        long[] sourcePositions = new long[480];
        BridgeTiming.Window captureToWrite = new BridgeTiming.Window(4096);
        AudioTimestamp timestamp = new AudioTimestamp();
        byte[] bytes = new byte[960];
        long captured = call == null ? 0 : suppliedFrames, written = 0, gaps = 0, lastReport = 0;
        double energy = 0;
        PlayoutGate gate = new PlayoutGate(startDelayMs, 4800);
        long recoverySilence = 0;
        short lastOutput = 0;
        boolean fadingIn = true;
        int pending = 0, offset = 0, missingTarget = 0;
        Method getUid = AudioRecordingConfiguration.class.getMethod("getClientUid");
        long start = System.nanoTime(), end = BridgeTiming.deadline(start, seconds);
        // 注入轨缓冲 40 ms（1920 帧）。只领先 480 帧（10 ms）时余量太小：实测 5 分钟里 track_underruns
        // 记到 6 次，每次约 130 ms 静音（音乐里的"卡一下"）。这里只放宽领先量、不动缓冲大小
        // （setBufferSizeInFrames 会把 mix 重配，可能让 REMOTE_SUBMIX 采集直接哑掉，见 16:09 会话）。
        final int lead = 1440;
        boolean persistent = seconds == 0 && call == null, capturing = !persistent, targetActive = !persistent;
        long lastConverted = start, lastWritten = start, previousReceived = received;
        long nextCheck = 0, lastHeartbeat = 0, segmentStart = start, segmentWritten = 0, segmentCaptured = captured;
        boolean lostHandled = false;
        if (capturing) { track.play(); playRamp = 480; log("BRIDGE_BUFFERING: waiting for converted audio"); }
        else log("BRIDGE_IDLE: waiting for an app microphone; no start deadline");
        try {
            while (!closed && System.nanoTime() < end) {
                if (failure != null) throw failure;
                if (converterLost && !lostHandled) {
                    synchronized (this) { head = 0; queued = 0; }
                    gate.reset(); pending = offset = 0; lastOutput = 0; fadingIn = true;
                    lostHandled = true;
                    log("BRIDGE_SILENT_HOLD: converter lost; injecting silence");
                } else if (!converterLost) {
                    // 重连之后要清掉，否则第二次掉线不会再重置缓冲
                    lostHandled = false;
                }
                if (call != null && !call.tick()) break;
                long stateNow = System.nanoTime();
                if (call != null) {
                    synchronized (this) {
                        if (received != previousReceived) { previousReceived = received; lastConverted = stateNow; }
                    }
                    long grace = gate.hasPlayed() ? CallLease.TIMEOUT_NS : 20_000_000_000L;
                    if (stateNow - lastConverted > grace || stateNow - lastWritten > grace)
                        throw new IOException("CELLULAR_STALLED");
                }
                if (persistent && stateNow >= nextCheck) {
                    targetActive = false;
                    for (AudioRecordingConfiguration cfg : manager.getActiveRecordingConfigurations()) {
                        if (scope.matches((Integer) getUid.invoke(cfg))) { targetActive = true; break; }
                    }
                    nextCheck = stateNow + 250_000_000L;
                    if (capturing && !targetActive) {
                        record.stop(); track.pause(); track.flush(); capturing = false;
                        pending = offset = 0; gate.reset(); lastOutput = 0; fadingIn = true;
                        resetPending = true;
                        writeInt(-1);
                        log("BRIDGE_IDLE: microphone stopped; waiting for next input");
                    }
                }
                if (persistent && !capturing) {
                    if (targetActive && !resetPending) {
                        synchronized (this) { head = 0; queued = 0; }
                        record.startRecording();
                        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                            throw new IOException("Physical microphone did not start");
                        segmentStart = System.nanoTime(); segmentWritten = written; segmentCaptured = captured;
                        track.play(); playRamp = 480; capturing = true;
                        log("BRIDGE_BUFFERING: waiting for fresh converted audio");
                    } else {
                        if (stateNow - lastHeartbeat >= 1_000_000_000L) {
                            if (writeInt(0)) lastHeartbeat = stateNow;
                        }
                        Thread.sleep(20);
                        continue;
                    }
                }
                int n = record.read(mic, 0, mic.length, AudioRecord.READ_NON_BLOCKING);
                if (n < 0) throw new IOException("Microphone read " + n);
                if (n > 0) {
                    if (raw != null) raw.write(mic, n);
                    for (int i = 0; i < n; i++) {
                        bytes[2*i] = (byte) mic[i]; bytes[2*i+1] = (byte) (mic[i] >> 8);
                        double v = mic[i] / 32768.0; energy += v*v;
                    }
                    suppliedFrames = captured + n;
                    writePcm(bytes, n);
                    captured += n;
                }
                long now = System.nanoTime();
                if (pending == offset && written - segmentWritten < (now - segmentStart) * 48000 / 1_000_000_000L + lead) {
                    boolean wasReady = gate.isReady();
                    boolean play;
                    synchronized (this) { play = gate.canTake(now - segmentStart, queued); }
                    if (play) {
                        int got = take(out,sourcePositions); gaps += out.length - got;
                        if (!wasReady) {
                            log("BRIDGE_RUNNING: converted audio ready startup_ms=" + (now-segmentStart)/1e6);
                            fadingIn = true;
                        }
                        if (fadingIn) {
                            for (int i=0; i<out.length; i++) out[i] = (short) (out[i] * i / out.length);
                            fadingIn = false;
                        }
                        lastOutput = out[out.length-1];
                    } else {
                        if (gate.hasPlayed()) recoverySilence += out.length;
                        Arrays.fill(out, (short) 0); Arrays.fill(sourcePositions,-1);
                        if (wasReady) {
                            for (int i=0; i<out.length; i++) out[i] = (short) (lastOutput * (out.length-1-i) / out.length);
                            lastOutput = 0;
                            log("BRIDGE_BUFFERING: refilling converted audio recovery=" + gate.recoveries());
                        }
                    }
                    // 块成形时处理一次：静音段填噪声底（别送精确零），起播后先淡入 10 ms。
                    // 两者都是为了避免「0 ↔ 有声」的阶跃——游戏第一次开麦那下「砰」来自这里。
                    for (int i = 0; i < out.length; i++) {
                        short v = out[i];
                        if (v == 0) v = noiseFloor();
                        if (playRamp > 0) {
                            // 从 1/481 起步而不是 0：淡入的第一个采样也不要是精确零
                            v = (short) (v * (480 - playRamp + 1) / 481);
                            playRamp--;
                        }
                        out[i] = v;
                    }
                    pending = out.length; offset = 0;
                }
                if (pending > offset) {
                    int accepted = track.write(out, offset, pending - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (accepted < 0) throw new IOException("Injection write " + accepted);
                    if (accepted > 0) {
                        lastWritten = now;
                        if (call != null && sourcePositions[offset + accepted - 1] >= 0) call.convertedReady();
                    }
                    if (accepted > 0 && converted != null) converted.write(Arrays.copyOfRange(out, offset, offset + accepted), accepted);
                    if (accepted > 0 && sourcePositions[offset+accepted-1] >= 0
                            && record.getTimestamp(timestamp,AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS) {
                        long captureTime=timestamp.nanoTime+(sourcePositions[offset+accepted-1]-segmentCaptured-timestamp.framePosition)*1_000_000_000L/48000;
                        double age=(System.nanoTime()-captureTime)/1e6;
                        if(age>=0 && age<10000) captureToWrite.add(age);
                    }
                    offset += accepted; written += accepted;
                }
                if (now - lastReport >= 1_000_000_000L) {
                    boolean active = call != null;
                    String silenced = "not-active";
                    for (AudioRecordingConfiguration cfg : manager.getActiveRecordingConfigurations()) {
                        if (scope.matches((Integer) getUid.invoke(cfg))) { active = true; silenced = "" + cfg.isClientSilenced(); }
                    }
                    missingTarget = active ? 0 : missingTarget + 1;
                    AudioRecordingConfiguration cfg = record.getActiveRecordingConfiguration();
                    synchronized (this) {
                        log("BRIDGE_STREAM elapsed_s=" + (now-start)/1e9 + " capture_frames=" + captured
                                + " state=" + (gate.isReady() ? "RUNNING" : "BUFFERING")
                                + " converted_frames=" + received + " queue_ms=" + queued/48.0
                                + " gap_ms=" + gaps/48.0 + " trimmed_frames=" + trimmed
                                + " recovery_silence_ms=" + recoverySilence/48.0 + " recoveries=" + gate.recoveries()
                                + " mic_silenced=" + (cfg != null && cfg.isClientSilenced())
                                + " target_silenced=" + silenced + " track_underruns=" + track.getUnderrunCount());
                    }
                    lastReport = now;
                    if (!persistent && missingTarget >= 2) { log("TARGET_STOPPED"); break; }
                }
                Thread.sleep(2);
            }
            log("BRIDGE_SUMMARY capture_frames=" + captured + " converted_frames=" + received
                    + " written_frames=" + written + " gap_ms=" + gaps/48.0 + " trimmed_frames=" + trimmed
                    + " recovery_silence_ms=" + recoverySilence/48.0 + " recoveries=" + gate.recoveries()
                    + " raw_rms_dbfs=" + (energy == 0 ? -240 : 10*Math.log10(energy/Math.max(1,captured))));
            if(captureToWrite.size()>0) log("CAPTURE_TO_INJECT_WRITE p50_ms="+captureToWrite.percentile(.50)
                    +" p95_ms="+captureToWrite.percentile(.95)
                    +" window=last_4096_samples scope=source_timestamps_and_SOLA_mapping_excludes_output_track_and_target_buffers");
            if (received == 0 && call == null && !failClosed) throw new IOException("No converted PCM received");
        } catch (Exception e) {
            if (call != null) call.failed();
            throw e;
        } finally { if (call != null) call.close(); else close(); watchdog.shutdownNow(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        if (converted != null) try { converted.close(); } catch (IOException ignored) {}
        deadline.shutdownNow();
    }
    private static void log(String s) { System.out.println("[virtual-mic] " + s); }
}
