package com.voicestudio.phone

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 返音现在从哪一路出声。
 *
 * 桥接的采集端是真的在拾音（见 `MicRelay.java`：录音设备钉在 TYPE_BUILTIN_MIC 上），
 * 所以开返音又走扬声器时，平板麦克风会把音乐一起收进去、被当成麦克风输入送进变声链路，
 * 游戏里就会在干净的音乐之上再叠一层「变声过的音乐」。耳机（有线/蓝牙/USB/助听器）没有这个问题。
 *
 * 产品决定：不自动关返音，只提醒一次。
 */
object MonitorRoute {

    /** 提醒过一次的标记，免得每次打开都弹。 */
    const val WARNED = "audio_monitor_warned"

    @Volatile private var checkedAt = 0L
    @Volatile private var speaker = false

    /** 3 秒内复用上次的结论，别每 300 ms 就查一次设备表。 */
    fun onSpeaker(context: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - checkedAt < 3000) return speaker
        checkedAt = now
        speaker = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { isHeadset(it.type) }
        }.getOrDefault(false)
        return speaker
    }

    private fun isHeadset(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> true
        else -> false
    }
}
