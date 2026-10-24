package com.voicestudio.phone

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * 一个音色（模型）下面可以有多个「方案」，每个方案的参数互相独立：
 *
 *   files/models/<音色>/profiles/<方案>.json
 *     { "voice":  { pitch, formant, low_pitch_ratio, low_pitch_knee, high_pitch_ratio,
 *                   high_pitch_knee, intonation_ratio, intonation_expand, rms_mix_rate, unvoiced_keep },
 *       "engine": { live_block, live_context, live_crossfade, live_cushion_cs, live_threshold_db,
 *                   live_threshold_db_bt, live_input_gain_db, live_input_gain_db_bt, live_gain_db,
 *                   live_split, live_auto_block } }
 *
 *   files/models/<音色>/model_settings.json
 *     { "compute": "follow" | "cpu" | "npu", "decoder": "auto" | "whole" | "split" }
 *     —— 模型设置作用于该音色的**所有**方案（同一音色换解码器会让缓存重建，没必要按方案存）
 *
 * 0.5.3 及以前每个音色只有一份 settings.json 和一套 voice_live:* 偏好。首次启动时
 * [migrateIfNeeded] 会把它们搬进名为 [DEFAULT] 的方案里，老用户原来调好的参数不会丢。
 */
object VoiceProfiles {
    /** 老数据迁移后生成的方案名，界面不允许删除最后一个方案。 */
    const val DEFAULT = "默认"

    const val GROUP_VOICE = "voice"
    const val GROUP_ENGINE = "engine"

    /** 「音色参数」组：名字沿用 settings.json 一直使用的键，方便和转换器产物互通。 */
    val VOICE_KEYS = arrayOf(
        "pitch", "formant",
        "low_pitch_ratio", "low_pitch_knee", "high_pitch_ratio", "high_pitch_knee",
        "intonation_ratio", "intonation_expand", "rms_mix_rate", "unvoiced_keep",
    )

    /** 「高级设置」组：名字沿用实时引擎一直在读的 live_* 键（需要重启虚拟麦才生效）。 */
    val ENGINE_KEYS = arrayOf(
        "live_block", "live_context", "live_crossfade", "live_cushion_cs",
        "live_threshold_db", "live_threshold_db_bt",
        "live_input_gain_db", "live_input_gain_db_bt", "live_gain_db",
        "live_split", "live_auto_block",
    )

    /** 只有迁移和「重置参数」需要知道：[Rvc.Settings] 的出厂值。 */
    private val RVC_DEFAULT = Rvc.Settings()

    // ---------------------------------------------------------------- 目录与读写

    fun profilesDir(modelDir: File): File = File(modelDir, "profiles")

    fun profileFile(modelDir: File, name: String): File = File(profilesDir(modelDir), safeName(name) + ".json")

    fun exists(modelDir: File, name: String): Boolean = profileFile(modelDir, name).isFile

    /** 该音色的方案名，默认方案排在最前，其余按名字排序。 */
    fun list(modelDir: File): List<String> =
        (profilesDir(modelDir).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .map { it.name.removeSuffix(".json") }
            .sortedWith(compareBy({ it != DEFAULT }, { it }))

    /** 读一个方案；不存在或损坏时返回空对象，调用方按默认值处理。 */
    fun read(modelDir: File, name: String): JSONObject {
        val file = profileFile(modelDir, name)
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    /** 原子写入：先写 .tmp 再改名，避免虚拟麦读到写了一半的方案。 */
    fun write(modelDir: File, name: String, data: JSONObject) {
        val dir = profilesDir(modelDir).apply { mkdirs() }
        val tmp = File(dir, safeName(name) + ".json.tmp")
        val target = File(dir, safeName(name) + ".json")
        val text = data.toString(1)
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {          // 某些 FUSE 路径不支持直接改名
            target.writeText(text)
            tmp.delete()
        }
    }

    /** 新建方案。[from] 不为空时以它为模板复制，否则从出厂值开始。 */
    fun create(modelDir: File, name: String, from: JSONObject? = null): Boolean {
        if (name.isBlank() || exists(modelDir, name)) return false
        write(modelDir, name, from?.let { JSONObject(it.toString()) } ?: JSONObject())
        return true
    }

    fun duplicate(modelDir: File, name: String, from: String): Boolean =
        if (!exists(modelDir, from)) false else create(modelDir, name, read(modelDir, from))

    /** 改名：写新文件成功后才删旧文件。 */
    fun rename(modelDir: File, old: String, new: String): Boolean {
        if (!exists(modelDir, old) || new.isBlank() || exists(modelDir, new)) return false
        write(modelDir, new, read(modelDir, old))
        profileFile(modelDir, old).delete()
        return true
    }

    /** 删除方案；最后一个方案不允许删（否则音色没有可用的参数集）。 */
    fun delete(modelDir: File, name: String): Boolean {
        if (!exists(modelDir, name) || list(modelDir).size <= 1) return false
        return profileFile(modelDir, name).delete()
    }

    // ---------------------------------------------------------------- 分组读取

    fun group(modelDir: File, name: String, group: String): JSONObject =
        read(modelDir, name).optJSONObject(group) ?: JSONObject()

    /** 「音色参数」：pitch / formant / 高低音保护 / 语调 / 包络 / 气声。 */
    fun voice(modelDir: File, name: String): JSONObject = group(modelDir, name, GROUP_VOICE)

    /** 「高级设置」：音频块 / 上下文 / 淡入淡出 / 缓冲 / 门限 / 增益（需重启虚拟麦）。 */
    fun engine(modelDir: File, name: String): JSONObject = group(modelDir, name, GROUP_ENGINE)

    /** 把方案里的「音色参数」变成引擎认识的结构；缺键时按出厂值补齐。 */
    fun settings(voice: JSONObject): Rvc.Settings {
        fun n(k: String, def: Double) = if (voice.has(k)) voice.optDouble(k, def) else def
        return Rvc.Settings(
            pitch = n("pitch", RVC_DEFAULT.pitch),
            formant = n("formant", RVC_DEFAULT.formant),
            lowRatio = n("low_pitch_ratio", RVC_DEFAULT.lowRatio),
            lowKnee = n("low_pitch_knee", RVC_DEFAULT.lowKnee),
            highRatio = n("high_pitch_ratio", RVC_DEFAULT.highRatio),
            highKnee = n("high_pitch_knee", RVC_DEFAULT.highKnee),
            unvoicedKeep = n("unvoiced_keep", 1.0) != 0.0,
            expand = n("intonation_expand", 0.0) != 0.0,
            expandRatio = n("intonation_ratio", RVC_DEFAULT.expandRatio),
            rmsMixRate = n("rms_mix_rate", RVC_DEFAULT.rmsMixRate),
        )
    }

    // ---------------------------------------------------------------- 模型设置（该音色共享）

    fun modelSettingsFile(modelDir: File): File = File(modelDir, "model_settings.json")

    fun modelSettings(modelDir: File): JSONObject {
        val file = modelSettingsFile(modelDir)
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    fun writeModelSettings(modelDir: File, data: JSONObject) {
        val file = modelSettingsFile(modelDir)
        val tmp = File(modelDir, "model_settings.json.tmp")
        tmp.writeText(data.toString(1))
        if (!tmp.renameTo(file)) { file.writeText(data.toString(1)); tmp.delete() }
    }

    // ---------------------------------------------------------------- 老数据迁移

    /**
     * 把 0.5.3 的「每音色一份 settings.json + 一套 voice_live:*」搬进 [DEFAULT] 方案，
     * 并把 game_voice:<包名> 换成新的游戏绑定。只做一次，结果用 profiles_v1 记住。
     *
     * @return 新建的方案个数（只用于日志/自检）
     */
    fun migrateIfNeeded(prefs: SharedPreferences, modelsDir: File): Int {
        if (prefs.getBoolean("profiles_v1", false)) return 0
        var created = 0
        val models = modelsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (modelDir in models) {
            if (list(modelDir).isEmpty()) {
                val voice = JSONObject()
                // 老参数就在模型目录的 settings.json 里
                val legacy = File(modelDir, "settings.json")
                if (legacy.isFile && legacy.length() in 1..16384) {
                    runCatching { JSONObject(legacy.readText()) }.getOrNull()?.let { j ->
                        for (k in VOICE_KEYS) if (j.has(k)) voice.put(k, j.optDouble(k, 0.0))
                    }
                }
                // 老引擎参数按模型存在 voice_live:<长度>:<模型>:live_*
                val live = ModelLivePreferences(prefs, modelDir.name)
                val block = live.getInt("live_block", 27).coerceIn(15, 50)
                val fade = live.getInt("live_crossfade", 8).coerceIn(4, 12)
                val engine = JSONObject()
                    .put("live_block", block)
                    .put("live_context", live.getInt("live_context", LiveParams.defaultContext(block, fade)))
                    .put("live_crossfade", fade)
                    .put("live_cushion_cs", live.getInt("live_cushion_cs", 6))
                    .put("live_threshold_db", live.getInt("live_threshold_db", -60))
                    .put("live_threshold_db_bt", live.getInt("live_threshold_db_bt", -45))
                    .put("live_input_gain_db", live.getInt("live_input_gain_db", 12))
                    .put("live_input_gain_db_bt", live.getInt("live_input_gain_db_bt", 0))
                    .put("live_gain_db", live.getInt("live_gain_db", 12))
                    .put("live_split", live.getBoolean("live_split", false))
                    .put("live_auto_block", live.getBoolean("live_auto_block", false))
                write(modelDir, DEFAULT, JSONObject()
                    .put(GROUP_VOICE, voice)
                    .put(GROUP_ENGINE, engine))
                created++
            }
        }
        GameBinding.migrateLegacy(prefs)
        prefs.edit().putBoolean("profiles_v1", true).apply()
        return created
    }

    /** 方案名要当文件名用：去掉路径分隔符和 Windows 保留字符（中文、空格、括号保留）。 */
    private fun safeName(name: String): String {
        val kept = "-_ ()（）·"
        val cleaned = name.trim().map { c -> if (c.isLetterOrDigit() || kept.indexOf(c) >= 0) c else '_' }.joinToString("")
        return cleaned.ifBlank { "profile" }
    }
}
