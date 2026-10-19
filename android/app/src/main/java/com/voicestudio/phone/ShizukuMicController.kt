package com.voicestudio.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** App-side lifetime owner. Uses Shizuku when available, otherwise a Root shell. */
object ShizukuMicController {
    private enum class Mode { NONE, SHIZUKU, ROOT }

    @Volatile var active = false; private set
    @Volatile var status = str(R.string.ui_virtual_microphone_is_off); private set
    @Volatile var shellLog = ""; private set
    @Volatile var targetPackage = ""; private set
    @Volatile var modelName = ""; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var error: String? = null; private set
    @Volatile var otherProfile = false; private set
    @Volatile var allApps = false; private set
    @Volatile var cellular = false; private set
    @Volatile private var cancelled = AtomicBoolean(false)
    @Volatile private var userStopped = AtomicBoolean(false)
    @Volatile private var remote: IVirtualMicService? = null
    @Volatile private var mode = Mode.NONE
    @Volatile private var rootProcess: Process? = null
    @Volatile private var rootReader: Thread? = null
    @Volatile private var rootToken = ""
    @Volatile private var rootPort = 0
    private val rootLog = StringBuilder()
    @Volatile private var rootCheckedAt = 0L
    @Volatile private var rootOk = false

    val phase: MicPhase get() = MicPhase.resolve(active, cancelled.get(), error, shellLog, VirtualMicSession.silentHold)

    /** Runs `su -c id` off the UI thread. Cached briefly so repeated screens do not prompt. */
    fun checkRoot(force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && rootCheckedAt != 0L && now - rootCheckedAt < 30_000) return rootOk
        val ok = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching false }
            p.inputStream.bufferedReader().readText().contains("uid=0")
        }.getOrDefault(false)
        rootOk = ok; rootCheckedAt = now
        return ok
    }

    @Synchronized fun start(context: Context, target: String, model: String, useOtherProfile: Boolean = false, useAllApps: Boolean = false, useCellular: Boolean = false) {
        check(!active && !VirtualMicSession.isActive && !LiveSession.running)
        val app = context.applicationContext
        val shizukuReady = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0 && Shizuku.getUid() == 2000
        }.getOrDefault(false)
        if (shizukuReady) startShizuku(app, target, model, useOtherProfile, useAllApps, useCellular)
        else startRoot(app, target, model, useOtherProfile, useAllApps, useCellular)
    }

    private fun beginSession(target: String, model: String, useOtherProfile: Boolean, useAllApps: Boolean, useCellular: Boolean): AtomicBoolean {
        active = true
        targetPackage = target
        modelName = model
        otherProfile = useOtherProfile
        allApps = useAllApps
        cellular = useCellular
        startedAt = SystemClock.elapsedRealtime()
        error = null
        val cancel = AtomicBoolean(false)
        cancelled = cancel
        userStopped = AtomicBoolean(false)
        shellLog = ""
        rootPort = 0
        rootToken = ""
        synchronized(rootLog) { rootLog.setLength(0) }
        return cancel
    }

    private fun startShizuku(app: Context, target: String, model: String, useOtherProfile: Boolean, useAllApps: Boolean, useCellular: Boolean) {
        mode = Mode.SHIZUKU
        val cancel = beginSession(target, model, useOtherProfile, useAllApps, useCellular)
        status = str(R.string.ui_preparing_the_microphone_with_shizuku)
        val ready = CountDownLatch(1)
        val owner = Binder()
        val args = Shizuku.UserServiceArgs(ComponentName(app, VirtualMicUserService::class.java))
            .daemon(false).processNameSuffix("virtual_mic_service").debuggable(false).version(5)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null) remote = IVirtualMicService.Stub.asInterface(binder)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                if (active) { status = str(R.string.ui_shizuku_disconnected); cancel.set(true); VirtualMicSession.stop() }
                ready.countDown()
            }
        }
        try { Shizuku.bindUserService(args, connection) }
        catch (e: Throwable) { active = false; mode = Mode.NONE; throw e }
        thread(name = "shizuku-mic-controller") {
            var converterStarted = false
            val log = File(app.getExternalFilesDir(null), "virtual_mic/shizuku-${System.currentTimeMillis()}.log")
            log.parentFile?.mkdirs()
            try {
                check(ready.await(20, TimeUnit.SECONDS)) { str(R.string.ui_shizuku_service_connection_timed_out) }
                if (cancel.get()) return@thread
                val service = remote ?: error(str(R.string.ui_could_not_connect_to_the_shizuku_service))
                val state = JSONObject(service.startMic(target, 0, useOtherProfile, useAllApps, useCellular, owner))
                if (state.has("error")) error(state.getString("error"))
                shellLog = state.getString("log")
                if (cancel.get()) return@thread
                val intent = Intent().putExtra("virtual_mic_socket", state.getString("token"))
                    .putExtra("virtual_mic_port", state.getInt("port")).putExtra("model", model)
                    .putExtra("bounded_logs", true).putExtra("persistent_mic", true)
                VirtualMicSession.start(app, intent)
                converterStarted = true
                while (!cancel.get() && VirtualMicSession.isActive) {
                    val next = JSONObject(service.snapshot())
                    shellLog = next.getString("log")
                    status = VirtualMicSession.status
                    if (!next.getBoolean("running")) {
                        if (useCellular && !cancel.get() && next.optInt("exitCode", 0) != 0)
                            error = str(R.string.cellular_stalled)
                        VirtualMicSession.stop()
                    }
                    Thread.sleep(500)
                }
                status = if (cancel.get()) str(R.string.ui_virtual_microphone_is_off_apps_return_to)
                    else VirtualMicSession.status + str(R.string.ui_apps_return_to_their_original_microphone)
            } catch (e: Throwable) {
                if (!cancel.get()) error = readableError(e.message ?: str(R.string.ui_check_the_connection))
                status = if (cancel.get()) str(R.string.ui_virtual_microphone_is_off_2) else str(R.string.ui_could_not_start_3, e.message)
                android.util.Log.e("ShizukuMic", status, e)
            } finally {
                runCatching { remote?.stopMic(userStopped.get() && VirtualMicSession.failureMessage == null) }
                VirtualMicSession.stop()
                runCatching { remote?.snapshot()?.let {
                    val last = JSONObject(it)
                    shellLog = last.getString("log")
                    if (useCellular && !cancel.get() && last.optInt("exitCode", 0) != 0)
                        error = str(R.string.cellular_stalled)
                } }
                if (!cancel.get()) shellLog.lineSequence().lastOrNull { "FAILED stage=" in it }?.let {
                    error = readableError(it.substringAfter("message="))
                }
                if (converterStarted) VirtualMicSession.failureMessage?.let { error = it }
                if ("CALL_RESTORE_FAILED" in shellLog) error = str(R.string.cellular_restore_failed)
                if ("CALL_HANGUP_FAILED" in shellLog) error = str(R.string.cellular_hangup_failed)
                runCatching { log.writeText(shellLog + "\nAPP_STATUS: " + status) }
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                remote = null
                app.stopService(Intent(app, LiveService::class.java))
                active = false; mode = Mode.NONE
            }
        }
    }

    private fun startRoot(app: Context, target: String, model: String, useOtherProfile: Boolean, useAllApps: Boolean, useCellular: Boolean) {
        mode = Mode.ROOT
        val cancel = beginSession(target, model, useOtherProfile, useAllApps, useCellular)
        status = str(R.string.ui_starting_with_root)
        if (useCellular) {
            error = str(R.string.ui_root_cellular_unsupported)
            active = false; mode = Mode.NONE
            app.stopService(Intent(app, LiveService::class.java))
            return
        }
        val tag = "root-${System.currentTimeMillis()}"
        thread(name = "root-mic-controller") {
            var converterStarted = false
            val log = File(app.getExternalFilesDir(null), "virtual_mic/$tag.log")
            log.parentFile?.mkdirs()
            try {
                check(checkRoot(force = true)) { str(R.string.ui_root_or_shizuku_required) }
                val token = "virtual-mic-${UUID.randomUUID()}"
                rootToken = token
                val apk = app.applicationInfo.sourceDir.replace("'", "'\\''")
                val cmd = buildString {
                    append("CLASSPATH='").append(apk).append("' exec /system/bin/app_process /system/bin --nice-name=virtual_mic dev.virtualmic.Main")
                    append(" --package '").append(target).append("' --mic --seconds 0 --bridge '").append(token).append("' --watch-parent --fail-closed")
                    if (useOtherProfile) append(" --other-profile")
                    if (useAllApps) append(" --all-apps")
                }
                val p = ProcessBuilder("su", "-Z", "u:r:shell:s0", "2000", "-c", cmd)
                    .redirectErrorStream(true).start()
                rootProcess = p
                val ready = CountDownLatch(1)
                rootReader = thread(name = "root-mic-log") {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            appendRootLog(line)
                            Regex("BRIDGE_LISTENING port=(\\d+)").find(line)?.let {
                                rootPort = it.groupValues[1].toInt()
                                ready.countDown()
                            }
                        }
                    }
                }
                if (!ready.await(25, TimeUnit.SECONDS) || cancel.get()) {
                    if (!cancel.get()) error = str(R.string.ui_could_not_start_3, str(R.string.ui_check_the_connection))
                    return@thread
                }
                val intent = Intent().putExtra("virtual_mic_socket", token)
                    .putExtra("virtual_mic_port", rootPort).putExtra("model", model)
                    .putExtra("bounded_logs", true).putExtra("persistent_mic", true)
                VirtualMicSession.start(app, intent)
                converterStarted = true
                while (!cancel.get() && VirtualMicSession.isActive) {
                    status = VirtualMicSession.status
                    if (!p.isAlive) {
                        if (!cancel.get()) error = readableError(
                            shellLog.lineSequence().lastOrNull { "FAILED stage=" in it }?.substringAfter("message=")
                                ?: str(R.string.ui_check_the_connection))
                        VirtualMicSession.stop()
                        break
                    }
                    Thread.sleep(500)
                }
                status = if (cancel.get()) str(R.string.ui_virtual_microphone_is_off_apps_return_to)
                    else VirtualMicSession.status + str(R.string.ui_apps_return_to_their_original_microphone)
            } catch (e: Throwable) {
                if (!cancel.get()) error = readableError(e.message ?: str(R.string.ui_check_the_connection))
                status = if (cancel.get()) str(R.string.ui_virtual_microphone_is_off_2) else str(R.string.ui_could_not_start_3, e.message)
                android.util.Log.e("RootMic", status, e)
            } finally {
                val p = rootProcess
                runCatching { p?.outputStream?.write(2); p?.outputStream?.flush() }
                runCatching { p?.outputStream?.close() }
                if (p != null && !p.waitFor(4, TimeUnit.SECONDS)) {
                    p.destroy()
                    if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
                }
                rootReader?.join(1000)
                VirtualMicSession.stop()
                if (!cancel.get() && converterStarted) VirtualMicSession.failureMessage?.let { error = it }
                runCatching { log.writeText(shellLog + "\nAPP_STATUS: " + status) }
                runCatching { app.stopService(Intent(app, LiveService::class.java)) }
                rootProcess = null; rootReader = null; active = false; mode = Mode.NONE
            }
        }
    }

    private fun appendRootLog(line: String) {
        synchronized(rootLog) {
            rootLog.append(line).append('\n')
            if (rootLog.length > 160_000) rootLog.delete(0, rootLog.length - 80_000)
            shellLog = rootLog.toString()
        }
    }

    fun stop() {
        userStopped.set(true)
        cancelled.set(true)
        if (mode == Mode.ROOT) runCatching { rootProcess?.outputStream?.write(2); rootProcess?.outputStream?.flush() }
        if (!active) VirtualMicSession.stop()
    }

    private fun readableError(message: String): String = when {
        "CELLULAR_NO_CALL" in message -> str(R.string.cellular_no_call)
        "CELLULAR_ROUTE" in message -> str(R.string.cellular_route)
        "CELLULAR_UNSUPPORTED" in message -> str(R.string.cellular_unsupported)
        "CELLULAR_MUTED" in message -> str(R.string.cellular_muted)
        "CELLULAR_BUSY" in message -> str(R.string.cellular_busy)
        "CELLULAR_GUARD" in message || "CELLULAR_STALLED" in message -> str(R.string.cellular_stalled)
        "CELLULAR_HANGUP_FAILED" in message -> str(R.string.cellular_hangup_failed)
        else -> message
    }
}
