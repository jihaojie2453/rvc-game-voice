package com.voicestudio.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Four tabs: 录音 (record the dry voice with a live waveform and a list of takes), 配置 (games -> voice ->
 * profiles, where every profile keeps its own voice and engine settings), 状态 (pick a game, a voice and a
 * profile, then start the virtual microphone) and 通用 (compute device, threads, language, model import).
 *
 * Files live in the app's own folder (Android/data/com.voicestudio.phone/files):
 *   common/hubert.onnx, common/rmvpe.onnx                         shared by every voice
 *   models/<name>/model.enc.onnx, model.dec.onnx, settings.json   one folder per voice ("불러오기" copies them in)
 *   models/<name>/profiles/<profile>.json                         每音色的方案：音色参数 + 高级设置
 *   models/<name>/model_settings.json                             该音色共享的计算设备与解码器
 *   recordings/<name>.wav                                            the 录音 tab's takes
 * settings.json is the 0.5.3 layout and is only read for migration and as the profile editor's seed.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val player = Player()
    private val base by lazy { getExternalFilesDir(null)!! }
    private val commonDir by lazy { File(base, "common") }
    private val modelsDir by lazy { File(base, "models") }
    private val recordingsDir by lazy { File(base, "recordings") }
    private val prefs by lazy { getSharedPreferences("phone", MODE_PRIVATE) }
    private val computePolicy by lazy {
        ComputePolicy.from(
            if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else "",
            android.os.Build.HARDWARE, android.os.Build.BOARD,
            packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL))
    }
    /** The test hooks' extras (`adb shell am start ... --es convert ...`): only when files/debug_hooks exists, since
     *  the launcher activity takes intents from any app. Otherwise an empty intent, so every hook reads its default. */
    private val hooks: Intent by lazy { if (File(base, "debug_hooks").isFile) intent else Intent() }

    private val views = HashMap<Int, View>()
    @Suppress("UNCHECKED_CAST")
    private fun <T : View> f(id: Int): T = views.getOrPut(id) { findViewById(id) } as T

    // record page
    private var recorder: Recorder? = null
    private var take: FloatArray? = null
    private var takeName = ""
    // convert page
    private var models = listOf<File>()
    private var input: FloatArray? = null
    private var inputName = str(R.string.ui_record)
    private var inputTake: FloatArray? = null  // the 녹음 take the input came from; null for a picked file
    private var output: FloatArray? = null
    private var outputName = ""
    private var engine: Rvc? = null
    private var engineKey = ""
    private var ep = "qnn_htp"  // test hook override (--es ep); the app follows the NPU switch
    private var decFile = "model.dec.onnx"
    private var busy = false
    /** The shared models and at least one voice are there. */
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // where the DSP looks for QNN's NPU programs: the app's extracted libraries first
        android.system.Os.setenv("ADSP_LIBRARY_PATH",
            "${applicationInfo.nativeLibraryDir};/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp", true)
        setContentView(R.layout.activity_main)
        f<TextView>(R.id.brand).text = SpannableStringBuilder("voice / studio").apply {
            setSpan(ForegroundColorSpan(getColor(R.color.accent)), 5, 8, 0)
        }
        f<View>(R.id.tab_record).setOnClickListener { showPage(RECORD) }
        f<View>(R.id.tab_config).setOnClickListener { showPage(CONFIG) }
        f<View>(R.id.tab_live).setOnClickListener { showPage(LIVE) }
        f<View>(R.id.tab_setting).setOnClickListener { showPage(SETTINGS) }
        f<View>(R.id.gear).visibility = View.GONE  // 设置页已有页签，齿轮只留空位
        f<Button>(R.id.settings_close).visibility = View.GONE  // 通用已经是页签，不再需要关闭按钮
        setupRecordPage()
        setupConfigPage()
        setupStatusPage()
        setupSettingsPage()
        // 0.5.3 的每音色一份 settings.json 与 voice_live:* 先搬成「默认方案」；失败不阻塞启动
        runCatching { VoiceProfiles.migrateIfNeeded(prefs, modelsDir) }
        f<Button>(R.id.get_common).setOnClickListener { toggleCommonDownload() }
        player.onStateChange = { wave, playing -> playButtonFor(wave)?.text = if (playing) "⏸" else "▶" }
        val saved = prefs.getString("page", null)
        // 0.5.3 存过 "convert"，那一页已经不在页签里，统一落到「配置」
        showPage(if (saved == RECORD || saved == LIVE || saved == SETTINGS) saved else CONFIG)
        refreshModels()
        renderStatusCard()  // 模型列表就绪后重画状态页的音色/方案
        refreshRecordings()
        if (!ready) showPage(SETTINGS)  // first run: models come first
        decFile = hooks.getStringExtra("dec") ?: decFile
        hooks.getStringExtra("virtual_mic_socket")?.let {
            startActivity(Intent(this, VirtualMicActivity::class.java).putExtras(hooks))
        }

        hooks.getStringExtra("stream")?.let { debugStream(File(it)) }
        if (hooks.getBooleanExtra("dspbench", false)) thread { StreamBench.dsp { android.util.Log.i("VoiceStudio", it) } }
        hooks.getStringExtra("bench")?.let { path ->  // test hook: --es bench <dec.onnx> --es ep cpu|qnn_htp|qnn_gpu
            thread {
                val msg = try {
                    val (first, rest) = Rvc.benchDecoder(path, hooks.getStringExtra("ep") ?: ep, hooks.getIntExtra("threads", 4))
                    String.format(Locale.US, "bench %s on %s: first run %.2f s, then %.3f s per run", File(path).name, ep, first, rest)
                } catch (e: Throwable) { "bench failed on $ep: ${e.message}" }
                android.util.Log.i("VoiceStudio", msg)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refreshModels()
        if (recorder == null) refreshRecordings()  // realtime recordings may have been saved from the notification
        ui.removeCallbacks(statusTicker); ui.post(statusTicker)
    }

    override fun onPause() {
        ui.removeCallbacks(statusTicker)
        player.stop()
        super.onPause()
    }

    override fun onDestroy() {
        player.stop()
        engine?.close()
        listenStop()  // 离开时不要留着试听占用麦克风
        if (!LiveSession.running) LiveSession.release()  // a running session goes on (its notification stops it)
        super.onDestroy()
    }

    private var page = RECORD
    /** The tab 설정 returns to. */
    private var lastPage = RECORD

    private fun showPage(which: String) {
        page = which
        if (which != SETTINGS) lastPage = which
        for ((name, pageId, tabId) in listOf(
            Triple(RECORD, R.id.page_record, R.id.tab_record),
            Triple(CONFIG, R.id.page_config, R.id.tab_config),
            Triple(LIVE, R.id.page_live, R.id.tab_live),
            Triple(SETTINGS, R.id.page_settings, R.id.tab_setting),
        )) {
            f<View>(pageId).visibility = if (name == which) View.VISIBLE else View.GONE
            f<View>(tabId).isSelected = name == which
        }
        if (which != SETTINGS) prefs.edit().putString("page", which).apply() else showCompute()
        f<ScrollView>(R.id.scroll).scrollTo(0, 0)
        if (which == CONFIG) fillGameRows()
    }

    private fun playButtonFor(wave: WaveformView?): Button? = when (wave?.id) {
        R.id.rec_wave -> f(R.id.rec_play)
        else -> null
    }

    // ---------------------------------------------------------------- config page (game -> voice -> profile)

    /** 配置页里列出「已添加的游戏」的容器；绑定变化后只重画这一块。 */
    private var configRows: LinearLayout? = null

    private fun setupConfigPage() {
        val page = f<LinearLayout>(R.id.page_config)
        page.removeAllViews()
        val games = card(page)
        games.addView(TextView(this).apply {
            text = str(R.string.ui_game_voice_title); textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(getColor(R.color.text))
        })
        games.addView(TextView(this).apply {
            text = str(R.string.ui_game_voice_help); textSize = 12f
            setTextColor(getColor(R.color.muted)); setPadding(0, dp(6), 0, dp(8))
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        games.addView(rows)
        games.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_add_game)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) }
            setOnClickListener { chooseGameToAdd() }
        })
        configRows = rows
        fillGameRows()
    }

    /** 每个游戏一行：应用名 + 当前音色·方案 + 包名，点进去改。 */
    private fun fillGameRows() {
        val rows = configRows ?: return
        rows.removeAllViews()
        val games = GameBinding.games(prefs)
        if (games.isEmpty()) {
            rows.addView(TextView(this).apply {
                text = str(R.string.ui_no_games_yet); textSize = 12f
                setTextColor(getColor(R.color.muted)); setPadding(0, dp(6), 0, dp(6))
            })
            return
        }
        val pm = packageManager
        for (pkg in games) {
            val bound = GameBinding.get(prefs, pkg)
            val name = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { showGameSetup(pkg) }
            }
            line.addView(TextView(this).apply {
                text = name; textSize = 15f
                setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.text))
            })
            line.addView(TextView(this).apply {
                text = if (bound == null) str(R.string.ui_game_voice_unbound)
                else "${bound.model} · ${bound.profile}"
                textSize = 12f; setTextColor(getColor(R.color.accent_strong))
            })
            line.addView(TextView(this).apply {
                text = pkg; textSize = 10f; setTextColor(getColor(R.color.muted))
            })
            rows.addView(line)
        }
    }

    /** 「添加游戏」：列出所有有启动图标的已安装应用（manifest 里已声明 LAUNCHER 查询）。 */
    private fun chooseGameToAdd() {
        val pm = packageManager
        val found = LinkedHashMap<String, String>()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (info in pm.queryIntentActivities(launcher, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue
            found.getOrPut(pkg) { info.loadLabel(pm).toString() }
        }
        val apps = found.entries.map { it.value to it.key }.sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) { toast(str(R.string.ui_loading_the_app_list_try_again_in)); return }
        val labels = apps.map { "${it.first}　·　${it.second}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(str(R.string.ui_add_game))
            .setItems(labels) { _, which -> addGame(apps[which].second) }
            .setNegativeButton(str(R.string.ui_close), null)
            .show()
    }

    /** 新加的游戏先绑「第一个音色的第一个方案」，随后立刻打开配置页改。 */
    private fun addGame(pkg: String) {
        val model = models.firstOrNull()
        if (model == null) { toast(str(R.string.ui_add_a_model_first)); return }
        val profile = VoiceProfiles.list(model).firstOrNull() ?: VoiceProfiles.DEFAULT
        GameBinding.bind(prefs, pkg, model.name, profile)
        fillGameRows()
        showGameSetup(pkg)
    }

    /** 游戏配置：选音色、管方案（新建 / 重命名 / 删除），保存后这个游戏一直沿用。
     *  音色与方案都改成"显式读写"，不用 Spinner 的选中回调——回调在重建适配器时会把选择改回第一项。 */
    private fun showGameSetup(pkg: String, forcedModel: Int? = null) {
        if (models.isEmpty()) { toast(str(R.string.ui_add_a_model_first)); return }
        val bound = GameBinding.get(prefs, pkg)
        val modelIndex = (forcedModel ?: models.indexOfFirst { it.name == bound?.model }).coerceIn(0, models.size - 1)
        val dir = models[modelIndex]
        val startFrom = if (forcedModel == null && bound?.model == dir.name) bound?.profile else null
        val pm = packageManager
        val appName = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun caption(text: String) = TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(getColor(R.color.muted)); setPadding(0, dp(10), 0, dp(2))
        }
        body.addView(caption(str(R.string.ui_voice_of)))
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        voiceRow.addView(TextView(this).apply {
            text = dir.name; textSize = 16f
            setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        voiceRow.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_change); textSize = 12f
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(str(R.string.ui_voice_of))
                    .setItems(models.map { it.name }.toTypedArray()) { _, which -> showGameSetup(pkg, which) }
                    .setNegativeButton(str(R.string.ui_cancel), null)
                    .show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        body.addView(voiceRow)

        body.addView(caption(str(R.string.ui_profiles)))
        val profileSpinner = Spinner(this)
        fun refresh(preferName: String?) {
            val names = VoiceProfiles.list(dir)
            profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val index = names.indexOf(preferName ?: "")
            profileSpinner.setSelection(if (index >= 0) index else 0)
        }
        fun picked(): String {
            val names = VoiceProfiles.list(dir)
            return names.getOrNull(profileSpinner.selectedItemPosition) ?: names.firstOrNull() ?: VoiceProfiles.DEFAULT
        }
        refresh(startFrom)
        body.addView(profileSpinner)

        val ops = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
        }
        fun op(text: String, action: () -> Unit) = Button(this, null, 0, R.style.SoftButton).apply {
            this.text = text; textSize = 12f
            setOnClickListener { action() }
        }
        // 「默认」方案是只读基线：不能改名、不能删除、参数也不能改；只有自己新建的方案可操作。
        val editBtn = Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_edit_params); textSize = 13f
            setOnClickListener {
                val target = picked()
                if (target == VoiceProfiles.DEFAULT) toast(str(R.string.ui_default_profile_locked))
                else showProfileEditor(dir, target)
            }
        }
        fun syncOps() {
            val readOnly = picked() == VoiceProfiles.DEFAULT
            for (i in 1 until ops.childCount) ops.getChildAt(i).isEnabled = !readOnly
            editBtn.isEnabled = !readOnly
        }
        ops.addView(op(str(R.string.ui_new_profile)) {
            val base = picked()
            askProfileName(str(R.string.ui_new_profile), "") { name ->
                // 新方案以当前方案为起点，一新建就跳回出厂音高会让人以为坏了
                if (VoiceProfiles.create(dir, name, VoiceProfiles.read(dir, base))) { refresh(name); syncOps() }
                else toast(str(R.string.ui_could_not_save, name))
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        ops.addView(op(str(R.string.ui_rename)) {
            val old = picked()
            if (old == VoiceProfiles.DEFAULT) toast(str(R.string.ui_default_profile_locked))
            else askProfileName(str(R.string.ui_rename), old) { name ->
                if (VoiceProfiles.rename(dir, old, name)) { refresh(name); syncOps() }
                else toast(str(R.string.ui_could_not_save, name))
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        ops.addView(op(str(R.string.ui_delete)) {
            val victim = picked()
            if (victim == VoiceProfiles.DEFAULT) toast(str(R.string.ui_default_profile_locked))
            else AlertDialog.Builder(this)
                .setMessage(victim)
                .setPositiveButton(str(R.string.ui_delete)) { _, _ ->
                    if (VoiceProfiles.delete(dir, victim)) { refresh(null); syncOps() }
                    else toast(str(R.string.ui_could_not_save, victim))
                }
                .setNegativeButton(str(R.string.ui_cancel), null)
                .show()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        body.addView(ops)
        body.addView(editBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = syncOps()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        syncOps()

        AlertDialog.Builder(this)
            .setTitle("${str(R.string.ui_game_setup)} · $appName")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(str(R.string.ui_save)) { _, _ ->
                GameBinding.bind(prefs, pkg, dir.name, picked())
                fillGameRows()
            }
            .setNegativeButton(str(R.string.ui_cancel), null)
            .show()
    }
    /**
     * 试听：用方案里的参数把麦克风就地变声，只送耳机/扬声器，不进入任何 App。
     * 编辑页滑杆改动后调用 [LiveSession.engine]?.update(...) 就能立刻听到。
     */
    private fun listenStart(dir: File, settings: Rvc.Settings, onReady: () -> Unit = {}) {
        if (listening || LiveSession.engine != null) return
        if (!File(commonDir, "hubert.onnx").isFile || !File(commonDir, "rmvpe.onnx").isFile) {
            toast(str(R.string.ui_shared_models_hubert_and_rmvpe_are_required)); return
        }
        val mic = android.Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(mic) != PackageManager.PERMISSION_GRANTED) {
            pendingListen = { listenStart(dir, settings, onReady) }
            requestPermissions(arrayOf(mic), LIVE_MIC)
            return
        }
        if (RootMicController.active || VirtualMicSession.isActive) {
            toast(str(R.string.ui_turn_off_the_virtual_microphone_first)); return
        }
        val params = liveParams()
        val devices = liveDevices(dir)
        val threads = prefs.getInt("threads", 4)
        val speaker = prefs.getBoolean("live_speaker", false)
        listening = true
        thread {
            try {
                val key = "${dir.path}|${devices.ep}|${devices.file}|${devices.front}|$threads|${params.block}|${params.tail}|${params.window(null)}"
                if (LiveSession.rvcKey != key) {
                    LiveSession.rvc?.close(); LiveSession.rvc = null; LiveSession.rvcKey = ""
                    LiveSession.rvc = Rvc(commonDir, dir, threads, devices.ep,
                        if (decFile != "model.dec.onnx") decFile else devices.file, devices.front,
                        live = Rvc.LiveShape(params.block, params.tail, windowFrames = params.window(null)),
                        allowFallback = true)
                    LiveSession.rvcKey = key
                }
                val btMic = prefs.getBoolean("mic_bluetooth", false)
                val inputGain = Math.pow(10.0, livePrefs().getInt(
                    if (btMic) "live_input_gain_db_bt" else "live_input_gain_db", if (btMic) 0 else 12
                ).coerceIn(0, 24) / 20.0).toFloat()
                val e = LiveEngine(applicationContext, LiveSession.rvc!!, params, settings, speaker, false, base, inputGain)
                e.setGain(params.gainDb)
                e.warmUp()
                e.start()
                LiveSession.engine = e
                liveMessage = null
                ui.post(onReady)
            } catch (t: Throwable) {
                android.util.Log.e("VoiceStudio", "listen start failed", t)
                listening = false
                LiveSession.stop(applicationContext)
                ui.post { toast(str(R.string.ui_could_not_start, t.message)) }
            }
        }
    }

    private fun listenStop() {
        val e = LiveSession.engine ?: return
        LiveSession.engine = null
        listening = false
        thread { e.stop(); LiveSession.stop(applicationContext) }
    }

    /** 编辑页滑杆松手后调用：正在试听就立刻换参数，下一个 10 ms 步生效。 */
    private fun listenApply(settings: Rvc.Settings) {
        if (listening) LiveSession.engine?.update(settings)
    }
    /** 方案参数：音色参数 / 高级设置 / 模型设置。默认方案在调用处已拦掉，这里再拦一次。 */
    private fun showProfileEditor(dir: File, profile: String) {
        if (profile == VoiceProfiles.DEFAULT) { toast(str(R.string.ui_default_profile_locked)); return }
        val voice = JSONObject(VoiceProfiles.voice(dir, profile).toString())
        val engine = JSONObject(VoiceProfiles.engine(dir, profile).toString())
        val modelSettings = VoiceProfiles.modelSettings(dir)
        // 打开就把缺的键补成「当前有效值」：这个音色 settings.json 里的调音 + 0.5.3 的 voice_live 偏好。
        // 保存后方案自成一体，不再随旧偏好变化。
        val legacyVoice = File(dir, "settings.json").takeIf { it.isFile }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: JSONObject()
        val legacyLive = ModelLivePreferences(prefs, dir.name)
        fun seed(json: JSONObject, key: String, value: Double) { if (!json.has(key)) json.put(key, value) }
        seed(voice, "pitch", legacyVoice.optDouble("pitch", 0.0))
        seed(voice, "formant", legacyVoice.optDouble("formant", 0.0))
        seed(voice, "low_pitch_ratio", legacyVoice.optDouble("low_pitch_ratio", 1.0))
        seed(voice, "low_pitch_knee", legacyVoice.optDouble("low_pitch_knee", 120.0))
        seed(voice, "high_pitch_ratio", legacyVoice.optDouble("high_pitch_ratio", 1.0))
        seed(voice, "high_pitch_knee", legacyVoice.optDouble("high_pitch_knee", 260.0))
        seed(voice, "intonation_ratio", legacyVoice.optDouble("intonation_ratio", 1.15))
        seed(voice, "rms_mix_rate", legacyVoice.optDouble("rms_mix_rate", 0.0))
        seed(voice, "unvoiced_keep", legacyVoice.optDouble("unvoiced_keep", 1.0))
        seed(voice, "intonation_expand", legacyVoice.optDouble("intonation_expand", 0.0))
        seed(engine, "live_block", legacyLive.getInt("live_block", 27).toDouble())
        seed(engine, "live_context", legacyLive.getInt("live_context", 124).toDouble())
        seed(engine, "live_crossfade", legacyLive.getInt("live_crossfade", 8).toDouble())
        seed(engine, "live_cushion_cs", legacyLive.getInt("live_cushion_cs", 6).toDouble())
        seed(engine, "live_threshold_db", legacyLive.getInt("live_threshold_db", -60).toDouble())
        seed(engine, "live_input_gain_db", legacyLive.getInt("live_input_gain_db", 12).toDouble())
        seed(engine, "live_gain_db", legacyLive.getInt("live_gain_db", 12).toDouble())
        seed(engine, "live_split", if (legacyLive.getBoolean("live_split", false)) 1.0 else 0.0)
        seed(engine, "live_auto_block", if (legacyLive.getBoolean("live_auto_block", false)) 1.0 else 0.0)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val preview = Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_listen); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) }
        }
        preview.setOnClickListener {
            if (listening) { listenStop(); preview.text = str(R.string.ui_listen) }
            else {
                listenStart(dir, VoiceProfiles.settings(voice)) { preview.text = str(R.string.ui_listen_stop) }
            }
        }
        body.addView(preview)

        fun heading(text: String, hint: String? = null) {
            body.addView(TextView(this).apply {
                this.text = text; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.text)); setPadding(0, dp(14), 0, dp(2))
            })
            if (hint != null) body.addView(TextView(this).apply {
                this.text = hint; textSize = 11f; setTextColor(getColor(R.color.muted))
            })
        }
        fun numOf(json: JSONObject, key: String, def: Double) = if (json.has(key)) json.optDouble(key, def) else def
        // 显示值 = 存储值 × scale：界面上给客户看得懂的单位（秒 / dB / Hz / 倍），
        // 存储仍然是引擎原始单位（10 ms 帧、厘秒、dB）。
        fun param(json: JSONObject, key: String, label: String, minD: Double, maxD: Double, stepD: Double,
                  defD: Double, scale: Double, fmt: (Double) -> String) {
            fun stored() = numOf(json, key, defD / scale)
            val value = TextView(this).apply {
                textSize = 12f; typeface = Typeface.MONOSPACE
                setTextColor(getColor(R.color.accent_strong))
                text = fmt(stored() * scale)
            }
            val steps = ((maxD - minD) / stepD).roundToInt().coerceAtLeast(1)
            val bar = sliderRow(body, label, value, fmt(minD), fmt(maxD), steps)
            bar.progress = ((stored() * scale - minD) / stepD).roundToInt().coerceIn(0, steps)
            bar.setOnSeekBarChangeListener(slider { p, done ->
                val display = minD + p * stepD
                value.text = fmt(display)
                if (done) { json.put(key, display / scale); listenApply(VoiceProfiles.settings(voice)) }
            })
        }
        fun switchParam(json: JSONObject, key: String, label: String, def: Boolean) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(getColor(R.color.text))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(this).apply { isChecked = numOf(json, key, if (def) 1.0 else 0.0) != 0.0 }
            sw.setOnCheckedChangeListener { _, on -> json.put(key, if (on) 1.0 else 0.0); listenApply(VoiceProfiles.settings(voice)) }
            row.addView(sw)
            body.addView(row)
        }
        fun seconds(v: Double) = str(R.string.ui_s, v)
        fun db(v: Double) = String.format(Locale.US, "%+.0f dB", v)
        fun times(v: Double) = String.format(Locale.US, "%.2f×", v)
        fun hz(v: Double) = String.format(Locale.US, "%.0f Hz", v)
        fun semitones(v: Double) = if (v == 0.0) str(R.string.ui_semitones) else String.format(Locale.US, str(R.string.ui_semitones_2), v.roundToInt())

        heading(str(R.string.ui_voice_params))
        param(voice, "pitch", str(R.string.ui_pitch_relative_to_your_voice), -24.0, 24.0, 1.0, 0.0, 1.0, { semitones(it) })
        param(voice, "formant", str(R.string.ui_formant_voice_texture), -2.0, 2.0, 0.1, 0.0, 1.0,
            { String.format(Locale.US, "%+.1f", it) })
        param(voice, "low_pitch_ratio", str(R.string.ui_low_pitch_protection_strength), 1.0, 3.0, 0.1, 1.0, 1.0, { times(it) })
        param(voice, "low_pitch_knee", str(R.string.ui_low_pitch_protection_threshold), 60.0, 200.0, 1.0, 120.0, 1.0, { hz(it) })
        param(voice, "high_pitch_ratio", str(R.string.ui_high_pitch_protection_strength), 1.0, 3.0, 0.1, 1.0, 1.0, { times(it) })
        param(voice, "high_pitch_knee", str(R.string.ui_high_pitch_protection_threshold), 200.0, 600.0, 5.0, 260.0, 1.0, { hz(it) })
        param(voice, "intonation_ratio", str(R.string.ui_intonation_expansion_strength), 1.0, 1.5, 0.05, 1.15, 1.0, { times(it) })
        param(voice, "rms_mix_rate", str(R.string.ui_volume_envelope), 0.0, 1.0, 0.05, 0.0, 1.0,
            { String.format(Locale.US, "%.2f", it) })
        switchParam(voice, "unvoiced_keep", str(R.string.ui_preserve_breath_and_consonants), true)
        switchParam(voice, "intonation_expand", str(R.string.ui_expand_intonation), false)

        heading(str(R.string.ui_advanced_settings), str(R.string.ui_restart_mic_hint))
        param(engine, "live_block", str(R.string.ui_block_audio_converted_per_step), 0.15, 0.50, 0.01, 0.27, 0.01, { seconds(it) })
        param(engine, "live_context", str(R.string.ui_additional_context), 0.0, 3.0, 0.01, 1.50, 0.01, { seconds(it) })
        param(engine, "live_crossfade", str(R.string.ui_crossfade), 0.04, 0.12, 0.01, 0.08, 0.01, { seconds(it) })
        param(engine, "live_cushion_cs", str(R.string.ui_output_buffer_reduce_dropouts), 0.02, 0.20, 0.01, 0.06, 0.01, { seconds(it) })
        param(engine, "live_threshold_db", str(R.string.ui_silence_threshold), -80.0, -30.0, 1.0, -60.0, 1.0, { db(it) })
        param(engine, "live_input_gain_db", str(R.string.ui_input_gain), 0.0, 24.0, 1.0, 12.0, 1.0, { db(it) })
        param(engine, "live_gain_db", str(R.string.ui_output_gain), -12.0, 24.0, 1.0, 12.0, 1.0, { db(it) })
        switchParam(engine, "live_split", str(R.string.ui_split_decoder_npu_bit_cpu), false)

        heading(str(R.string.ui_model_settings))
        val computeKeys = arrayOf("follow", "cpu", "npu")
        val computeLabels = arrayOf(str(R.string.ui_follow_global), "CPU", "NPU")
        val compute = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, computeLabels.toList())
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(computeKeys.indexOf(modelSettings.optString("compute", "follow")).coerceAtLeast(0))
        }
        body.addView(compute)
        val decoderKeys = arrayOf("auto", "whole", "split")
        val decoderLabels = arrayOf(str(R.string.ui_auto), str(R.string.ui_decoder_whole), str(R.string.ui_decoder_split))
        val decoder = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, decoderLabels.toList())
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(decoderKeys.indexOf(modelSettings.optString("decoder", "auto")).coerceAtLeast(0))
        }
        body.addView(decoder)

        AlertDialog.Builder(this)
            .setTitle("${str(R.string.ui_edit_params)} · $profile")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(str(R.string.ui_save)) { _, _ ->
                VoiceProfiles.write(dir, profile, JSONObject()
                    .put(VoiceProfiles.GROUP_VOICE, voice)
                    .put(VoiceProfiles.GROUP_ENGINE, engine))
                VoiceProfiles.writeModelSettings(dir, JSONObject()
                    .put("compute", computeKeys[compute.selectedItemPosition.coerceIn(0, computeKeys.size - 1)])
                    .put("decoder", decoderKeys[decoder.selectedItemPosition.coerceIn(0, decoderKeys.size - 1)]))
                toast(str(R.string.ui_saved_restart_mic))
            }
            .setNegativeButton(str(R.string.ui_cancel), null)
            .setOnDismissListener { listenStop() }
            .show()
    }

    private fun askProfileName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); setSingleLine(); setSelection(text.length)
            hint = str(R.string.ui_profile_name)
        }
        val pad = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        pad.addView(input)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(pad)
            .setPositiveButton(str(R.string.ui_save)) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(str(R.string.ui_cancel), null)
            .show()
    }

    // ---------------------------------------------------------------- record page

    private fun setupRecordPage() {
        val wave = f<WaveformView>(R.id.rec_wave)
        f<View>(R.id.record).setOnClickListener { toggleRecording() }
        f<Button>(R.id.rec_play).setOnClickListener { togglePlay(wave, take) }
        f<Button>(R.id.rec_rewind).setOnClickListener {
            wave.position = 0
            if (player.isPlaying(wave)) take?.let { player.play(wave, it, 0) }
            showRecTime()
        }
        wave.onSeek = { showRecTime() }
        wave.onSeekDone = { at -> if (player.isPlaying(wave)) take?.let { player.play(wave, it, at) }; showRecTime() }
        ticker()
    }

    /** One loop for the moving parts: live waveform, timers and playheads. */
    private fun ticker() {
        val r = recorder
        if (r != null) {
            f<WaveformView>(R.id.rec_wave).livePeaks = r.peaks()
            f<TextView>(R.id.rec_time).text = clock(r.samples)
        } else if (player.playing) {
            showRecTime()
        }
        ui.postDelayed({ ticker() }, 50)
    }

    private fun showRecTime() {
        val wave = f<WaveformView>(R.id.rec_wave)
        if (recorder == null && take != null) f<TextView>(R.id.rec_time).text = "${clock(wave.position)} / ${clock(wave.samples)}"
    }

    private fun toggleRecording() {
        val r = recorder
        if (r == null && (RootMicController.active || VirtualMicSession.isActive)) { toast(str(R.string.ui_turn_off_the_virtual_microphone_first)); return }
        if (r == null && LiveSession.running) { toast(str(R.string.ui_stop_live_conversion_before_recording)); return }
        if (r == null) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC)
                return
            }
            player.stop()
            val started = Recorder(this).also { it.start() }
            recorder = started
            f<View>(R.id.record_dot).setBackgroundResource(R.drawable.rec_stop)
            f<TextView>(R.id.rec_title).text = str(R.string.ui_recording)
            f<TextView>(R.id.rec_info).text = str(R.string.ui_input_tap_again_to_stop, started.sourceName)
            f<WaveformView>(R.id.rec_wave).livePeaks = FloatArray(0)
            listOf(R.id.rec_play, R.id.rec_rewind).forEach { f<Button>(it).isEnabled = false }
        } else {
            val x = r.stop()
            recorder = null
            f<View>(R.id.record_dot).setBackgroundResource(R.drawable.rec_dot)
            if (x.size < Rvc.SR / 5) {
                f<TextView>(R.id.rec_info).text = str(R.string.ui_the_recording_is_too_short)
                f<WaveformView>(R.id.rec_wave).setAudio(take)
                if (take != null) listOf(R.id.rec_play, R.id.rec_rewind).forEach { f<Button>(it).isEnabled = true }
                return
            }
            recordingsDir.mkdirs()
            val name = str(R.string.ui_recording_2) + SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
            AudioIo.writeWav(File(recordingsDir, "$name.wav"), x)
            loadTake(x, name)
            refreshRecordings()
        }
    }

    private fun loadTake(x: FloatArray, name: String) {
        player.stop()
        take = x
        takeName = name
        f<WaveformView>(R.id.rec_wave).setAudio(x)
        f<TextView>(R.id.rec_title).text = name
        f<TextView>(R.id.rec_info).text = str(R.string.ui_tap_or_drag_the_waveform_to_seek)
        listOf(R.id.rec_play, R.id.rec_rewind).forEach { f<Button>(it).isEnabled = true }
        showRecTime()
    }

    private fun refreshRecordings() {
        val list = f<LinearLayout>(R.id.rec_list)
        list.removeAllViews()
        val files = recordingsDir.listFiles { file -> file.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            list.addView(TextView(this).apply { text = str(R.string.ui_no_recordings_yet); textSize = 12f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(6), 0, dp(6)) })
            return
        }
        for (file in files) {
            val samples = ((file.length() - 44) / 2).toInt()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                setBackgroundResource(R.drawable.btn_soft)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row.addView(TextView(this).apply {
                text = file.nameWithoutExtension; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = clock(samples).substringBefore('.'); textSize = 12f; setPadding(0, 0, dp(8), 0)
                typeface = Typeface.MONOSPACE; setTextColor(getColor(R.color.muted))
            })
            row.setOnClickListener {
                runCatching { loadTake(AudioIo.readWav(file), file.nameWithoutExtension) }.onFailure { toast(str(R.string.ui_could_not_open, it.message)) }
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(file.nameWithoutExtension).setMessage(str(R.string.ui_delete_this_recording))
                    .setPositiveButton(str(R.string.ui_delete)) { _, _ -> file.delete(); refreshRecordings() }
                    .setNegativeButton(str(R.string.ui_cancel), null).show()
                true
            }
            list.addView(row)
        }
    }


    private fun slider(changed: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = changed(p, false)
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) = changed(s.progress, true)
    }

    /** Play [wave]'s clip from its playhead; [partner] (the other waveform) follows the same place. */
    private fun togglePlay(wave: WaveformView, clip: FloatArray?, partner: WaveformView? = null) {
        val x = clip ?: return
        if (player.isPlaying(wave)) { player.stop(); return }
        val from = if (wave.position >= x.size - 1) 0 else wave.position
        player.play(wave, x, from)
        partner?.position = from
    }


    // advanced settings, built from a spec list like the studio's web page
    private data class Spec(val key: String, val label: String, val min: Double, val max: Double, val step: Double,
                            val unit: String, val default: Double, val minLabel: String, val maxLabel: String)
    private val specs = listOf(
        Spec("low_pitch_ratio", str(R.string.ui_low_pitch_protection_strength), 1.0, 3.0, 0.1, "", 1.0, str(R.string.ui_off), "3"),
        Spec("low_pitch_knee", str(R.string.ui_low_pitch_protection_threshold), 60.0, 200.0, 5.0, "Hz", 120.0, "60", "200"),
        Spec("high_pitch_ratio", str(R.string.ui_high_pitch_protection_strength), 1.0, 3.0, 0.1, "", 1.0, str(R.string.ui_off), "3"),
        Spec("high_pitch_knee", str(R.string.ui_high_pitch_protection_threshold), 200.0, 600.0, 10.0, "Hz", 260.0, "200", "600"),
        Spec("intonation_ratio", str(R.string.ui_intonation_expansion_strength), 1.0, 1.5, 0.05, str(R.string.ui_), 1.15, str(R.string.ui__2), str(R.string.ui__3)),
        Spec("rms_mix_rate", str(R.string.ui_volume_envelope), 0.0, 1.0, 0.05, "", 0.0, str(R.string.ui_follow_input), str(R.string.ui_follow_conversion)),
    )
    private val switchSpecs = listOf("unvoiced_keep" to str(R.string.ui_preserve_breath_and_consonants), "intonation_expand" to str(R.string.ui_expand_intonation))
    private val advSliders = HashMap<String, SeekBar>()
    private val advSwitches = HashMap<String, Switch>()
    private var loadingSliders = false


    private fun sliderRow(parent: LinearLayout, label: String, value: TextView, minLabel: String, maxLabel: String, steps: Int): SeekBar {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        top.addView(TextView(this).apply { text = label; textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        top.addView(value)
        val bar = SeekBar(this, null, 0, R.style.Slider).apply {
            max = steps
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
        }
        val scale = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, t) in listOf(minLabel, maxLabel).withIndex()) scale.addView(TextView(this).apply {
            text = t; textSize = 10f; setTextColor(getColor(R.color.muted)); typeface = Typeface.MONOSPACE
            gravity = if (i == 0) Gravity.START else Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        parent.addView(top); parent.addView(bar); parent.addView(scale)
        return bar
    }

    private fun format(x: Double, step: Double) = when {
        step >= 1 -> x.roundToInt().toString()
        step >= 0.1 -> String.format(Locale.US, "%.1f", x)
        else -> String.format(Locale.US, "%.2f", x)
    }

    private fun currentModel(): File? = models.getOrNull(f<Spinner>(R.id.model).selectedItemPosition)

    private fun settingsJson(dir: File): JSONObject =
        File(dir, "settings.json").let { if (it.isFile) runCatching { JSONObject(it.readText()) }.getOrDefault(JSONObject()) else JSONObject() }

    private fun saveSetting(key: String, value: Double) {
        if (loadingSliders) return
        val dir = currentModel() ?: return
        File(dir, "settings.json").writeText(settingsJson(dir).put(key, value).toString(1))
        LiveSession.engine?.update(settingsOf(dir))  // realtime follows from its next step
    }

    /** Sliders and switches from the selected model's settings.json. */

    private fun settingsOf(dir: File): Rvc.Settings {
        val j = settingsJson(dir)
        val d = Rvc.Settings()
        fun num(k: String, def: Double) = if (j.has(k)) j.optDouble(k, def) else def
        return Rvc.Settings(
            pitch = num("pitch", d.pitch), formant = num("formant", d.formant),
            lowRatio = num("low_pitch_ratio", d.lowRatio), lowKnee = num("low_pitch_knee", d.lowKnee),
            highRatio = num("high_pitch_ratio", d.highRatio), highKnee = num("high_pitch_knee", d.highKnee),
            unvoicedKeep = num("unvoiced_keep", 1.0) != 0.0,
            expand = num("intonation_expand", 0.0) != 0.0, expandRatio = num("intonation_ratio", d.expandRatio),
            rmsMixRate = num("rms_mix_rate", d.rmsMixRate),
        )
    }

    private fun refreshModels() {
        commonDir.mkdirs(); modelsDir.mkdirs()
        if (!busy && !LiveSession.running) sortLooseFiles().takeIf { it.isNotEmpty() }?.let { toast(str(R.string.ui_imported) + it.joinToString(", ")) }
        val commonOk = File(commonDir, "hubert.onnx").isFile && File(commonDir, "rmvpe.onnx").isFile
        models = modelsDir.listFiles { file -> file.isDirectory && File(file, "model.enc.onnx").isFile && File(file, "model.dec.onnx").isFile }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val spinner = f<Spinner>(R.id.model)
        val keep = hooks.getStringExtra("model") ?: prefs.getString("model", null)  // test hook: --es model NAME
        spinner.adapter = ArrayAdapter(this, R.layout.spinner_item, models.map { it.name }).apply { setDropDownViewResource(R.layout.spinner_item) }
        models.indexOfFirst { it.name == keep }.takeIf { it >= 0 }?.let { spinner.setSelection(it) }
        ready = commonOk && models.isNotEmpty()
        val status = f<TextView>(R.id.status)
        status.text = if (ready) str(R.string.ui_ready) else str(R.string.ui_models_needed)
        status.setBackgroundResource(if (ready) R.drawable.chip else R.drawable.chip_warn)
        status.setTextColor(getColor(if (ready) R.color.green else R.color.rec))
        // also offered when only the NPU's HuBERT is missing (realtime is ~90 ms a step faster with it)
        val missing = CommonModels.items.filter { !File(commonDir, it.name).isFile }
        f<View>(R.id.common_row).visibility = if (missing.isEmpty() && !downloading) View.GONE else View.VISIBLE
        f<TextView>(R.id.common_text).text = if (commonOk) String.format(Locale.US,
            str(R.string.ui_hubert_for_npu_faster_live_conversion_mb), missing.sumOf { it.bytes } / 1e6)
            else String.format(Locale.US, str(R.string.ui_shared_models_hubert_and_rmvpe_one_time), missing.sumOf { it.bytes } / 1e6)
        f<TextView>(R.id.help).text = if (ready) str(R.string.ui_to_add_a_voice_use_import_to) else buildString {
            append(str(R.string.ui_use_import_to_select_a_model_folder))
            if (!commonOk) append(str(R.string.ui_shared_models_hubert_onnx_and_rmvpe_onnx))
            if (models.isEmpty()) append(str(R.string.ui_voice_model_name_enc_onnx_name_dec))
        }
    }

    @Volatile private var downloadStop = false
    private var downloading = false

    /** The shared models from the release page; a second press stops. Voice models stay the user's own. */
    private fun toggleCommonDownload() {
        if (downloading) {
            downloadStop = true
            return
        }
        downloading = true
        downloadStop = false
        val button = f<Button>(R.id.get_common)
        val bar = f<ProgressBar>(R.id.common_progress)
        button.text = str(R.string.ui_stop_2)
        bar.progress = 0
        bar.visibility = View.VISIBLE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        thread {
            var shown = -1
            val error = try {
                CommonModels.download(commonDir, { p ->
                    val step = (p * 1000).toInt()
                    if (step != shown) {
                        shown = step
                        runOnUiThread { bar.progress = step }
                    }
                }, { downloadStop })
                null
            } catch (e: Throwable) {
                e
            }
            runOnUiThread {
                downloading = false
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                button.text = str(R.string.ui_download)
                bar.visibility = View.GONE
                refreshModels()
                when {
                    error == null -> toast(str(R.string.ui_shared_models_downloaded))
                    downloadStop -> toast(str(R.string.ui_download_stopped_tap_again_to_download_the))
                    else -> toast(str(R.string.ui_could_not_download_shared_models_copy_hubert, error.message, commonDir.path) +
                        str(R.string.ui_you_can_create_these_files_with_the))
                }
            }
        }
    }





    // ---------------------------------------------------------------- model import

    private fun chooseImport() {
        AlertDialog.Builder(this).setTitle(str(R.string.ui_import_model))
            .setItems(arrayOf(str(R.string.ui_choose_folder), str(R.string.ui_choose_zip_file))) { _, which ->
                if (which == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_ZIP)
            }.show()
    }

    /** Where an imported file goes, by its name: <name>.enc.onnx or model.enc.onnx and the like. */
    private fun importTarget(name: String, model: File?): File? {
        val n = name.lowercase()
        return when {
            n == "hubert.onnx" || n == "rmvpe.onnx" || n == "hubert.htp.onnx" -> File(commonDir, n)
            model == null -> null
            n == "model.json" || n.endsWith(".model.json") -> File(model, "model.json")
            n.endsWith(".enc.onnx") -> File(model, "model.enc.onnx")
            n.endsWith(".dec.w8a16.onnx") -> File(model, W8A16)  // the NPU's integer decoder (converter, calib/)
            n.endsWith(".dec.onnx") -> File(model, "model.dec.onnx")
            n.endsWith(".dec.ncnn.param") -> File(model, "model.dec.ncnn.param")
            n.endsWith(".dec.ncnn.bin") -> File(model, "model.dec.ncnn.bin")
            n.endsWith(".dec_a.onnx") -> File(model, "model.dec_a.onnx")  // the split decoder (iPhone for now)
            n.endsWith(".dec_b.onnx") -> File(model, "model.dec_b.onnx")
            n == "settings.json" || n.endsWith(".settings.json") || n == "realtime_settings.json" -> File(model, "settings.json")
            else -> null
        }
    }

    private class Source(val name: String, val open: () -> InputStream)

    /** Model files put loose into the app folder (adb push, the PC converter's 폰으로 보내기): <voice>.dec.w8a16.onnx and
     *  the other parts move into models/<voice>/ (a new voice when its enc and dec came together; its <voice>.settings.json
     *  seeds the new folder only), hubert.htp.onnx and the other shared ones into common/,
     *  as the iPhone app sorts what lands in its Documents. adb can write the app folder but not the voice folders
     *  the app made. A replaced part takes the NPU's compiled copies with it. Returns what moved, for a message. */
    private fun sortLooseFiles(): List<String> {
        val moved = ArrayList<String>()
        val voices = (modelsDir.listFiles { f -> f.isDirectory }?.associateBy { it.name.lowercase() } ?: emptyMap()).toMutableMap()
        val loose = base.listFiles { f -> f.isFile && (f.name.endsWith(".onnx") || ".ncnn." in f.name || f.name.endsWith(".settings.json") || f.name.endsWith(".model.json")) }
            ?: return moved
        // a voice the phone does not have yet: both of its main parts arrived
        val names = loose.map { it.name }.toSet()
        for (f in loose) {
            val voice = f.name.substringBefore('.')  // as the parts are matched below (no dots in loose voice names)
            if (f.name == "$voice.enc.onnx" && "$voice.dec.onnx" in names && voice.lowercase() !in voices && voice.isNotBlank())
                voices[voice.lowercase()] = File(modelsDir, voice).apply { mkdirs() }
        }
        for ((voice, dir) in voices) {
            val incoming = loose.filter { it.name.substringBefore('.').lowercase() == voice }
                .mapNotNull { importTarget(it.name, dir)?.name }.toSet()
            ModelSpec.obsoleteParts(incoming).forEach { File(dir, it).delete() }
        }
        for (file in loose) {
            val shared = file.name.lowercase() in setOf("hubert.onnx", "rmvpe.onnx", "hubert.htp.onnx")
            val dir = if (shared) commonDir else voices[file.name.substringBefore('.').lowercase()] ?: continue
            val target = importTarget(file.name, dir) ?: continue
            if (target.name == "settings.json" && target.isFile) { file.delete(); continue }  // never over a voice's tuned settings
            val stem = target.name.removeSuffix(".onnx")
            dir.listFiles { f -> f.name.startsWith("$stem.") && ".qnn_" in f.name }?.forEach { it.delete() }
            target.delete()
            if (!file.renameTo(target)) {
                runCatching { file.copyTo(target, overwrite = true); file.delete() }.onFailure { continue }
            }
            moved += if (shared) target.name else "${dir.name} ← ${file.name.substringAfter('.')}"
        }
        if (moved.isNotEmpty()) { engine?.close(); engine = null; engineKey = ""; LiveSession.release() }
        return moved
    }

    private fun importFolder(tree: Uri) {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val folderName = query(DocumentsContract.buildDocumentUriUsingTree(tree, treeId)) ?: str(R.string.ui_model)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val sources = ArrayList<Source>()
        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                sources.add(Source(c.getString(1)) { contentResolver.openInputStream(uri)!! })
            }
        }
        askNameAndCopy(folderName, sources)
    }

    private fun importZip(uri: Uri) {
        val zipName = (query(uri) ?: str(R.string.ui_model)).removeSuffix(".zip").removeSuffix(".vsmodel")
        val names = ArrayList<String>()
        ZipInputStream(contentResolver.openInputStream(uri)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) names.add(e.name.substringAfterLast('/'))
            }
        }
        // a fresh pass per file keeps memory flat (the shared models are hundreds of MB)
        val sources = names.map { wanted ->
            Source(wanted) {
                val z = ZipInputStream(contentResolver.openInputStream(uri))
                while (true) {
                    val e = z.nextEntry ?: throw IllegalStateException(str(R.string.ui_not_found, wanted))
                    if (!e.isDirectory && e.name.substringAfterLast('/') == wanted) break
                }
                z
            }
        }
        askNameAndCopy(zipName, sources)
    }

    private fun query(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    private fun askNameAndCopy(suggested: String, sources: List<Source>) {
        val hasModel = sources.any { importTarget(it.name, modelsDir) != null && importTarget(it.name, null) == null }
        val hasCommon = sources.any { importTarget(it.name, null) != null }
        if (!hasModel && !hasCommon) {
            toast(str(R.string.ui_no_voice_model_enc_onnx_dec_onnx))
            return
        }
        if (!hasModel) { copyIn(null, sources); return }
        val field = EditText(this).apply { setText(suggested.replace(Regex("[\\\\/:*?\"<>|]+"), "_")); setSingleLine() }
        AlertDialog.Builder(this).setTitle(str(R.string.ui_model_name)).setView(field)
            .setPositiveButton(str(R.string.ui_import)) { _, _ ->
                val name = field.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_").ifEmpty { str(R.string.ui_model) }
                copyIn(File(modelsDir, name), sources)
            }.setNegativeButton(str(R.string.ui_cancel), null).show()
    }

    private fun copyIn(model: File?, sources: List<Source>) {
        val help = f<TextView>(R.id.help)
        busy = true
        thread {
            try {
                model?.mkdirs()
                // a new decoder makes the NPU's compiled copy stale
                // (the GPU decoder's files only when new ones come: importing just the NPU decoder keeps them)
                val newNcnn = sources.any { it.name.lowercase().contains(".dec.ncnn.") }
                model?.listFiles { file -> ".qnn_" in file.name || (newNcnn && file.name.startsWith("model.dec.ncnn")) }?.forEach { it.delete() }
                val todo = sources.mapNotNull { s -> importTarget(s.name, model)?.let { s to it } }
                if (model != null) ModelSpec.obsoleteParts(todo.map { it.second.name }.toSet())
                    .forEach { File(model, it).delete() }
                for ((i, pair) in todo.withIndex()) {
                    val (source, target) = pair
                    ui.post { help.text = str(R.string.ui_importing, i + 1, todo.size, source.name) }
                    val tmp = File(target.path + ".part")
                    source.open().use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
                    target.delete()
                    tmp.renameTo(target)
                }
                if (model != null && !File(model, "model.enc.onnx").isFile) throw IllegalStateException(str(R.string.ui_model_enc_onnx_is_missing))
                if (model != null) ModelSpec.load(model)
                ui.post {
                    if (model != null) prefs.edit().putString("model", model.name).apply()
                    if (model != null && engineKey.startsWith(model.path)) { engine?.close(); engine = null; engineKey = "" }
                    toast(if (model != null) str(R.string.ui_imported_model, model.name) else str(R.string.ui_shared_models_imported))
                }
            } catch (e: Exception) {
                ui.post { toast(str(R.string.ui_import_failed, e.message)) }
            } finally {
                ui.post { busy = false; refreshModels() }
            }
        }
    }

    // ---------------------------------------------------------------- settings

    /** Shared file/live preference. Device hints avoid unsupported accelerators; Rvc verifies by running them. */
    private class Devices(val front: String, val ep: String, val file: String, val label: String)

    private fun fileDevices(dir: File): Devices {
        val hasInt = File(dir, W8A16).isFile
        val hasGpu = computePolicy.tryGpu && File(dir, "model.dec.ncnn.param").isFile && File(dir, "model.dec.ncnn.bin").isFile
        val (requestedFront, requestedDec) = when (prefs.getString("compute", "recommended")) {
            "full32" -> "cpu" to "gpu"
            "custom" -> prefs.getString("custom_front", "npu")!! to prefs.getString("custom_dec", "npu_int")!!
            else -> (if (computePolicy.tryNpu) "npu" else "cpu") to computePolicy.decoder(hasInt, hasGpu)
        }
        val front = if (requestedFront == "npu" && !computePolicy.tryNpu) "cpu" else requestedFront
        val dec = when {
            requestedDec.startsWith("npu") && !computePolicy.tryNpu -> if (hasGpu) "gpu" else "cpu"
            requestedDec == "npu_int" && !hasInt -> if (hasGpu) "gpu" else "cpu"
            requestedDec == "gpu" && !hasGpu -> "cpu"
            else -> requestedDec
        }
        val f = if (front == "npu") "qnn_htp" else "cpu"
        val frontLabel = str(R.string.ui_front_end) + if (front == "npu") "NPU" else "CPU"
        return when {
            dec == "npu_int" && hasInt -> Devices(f, "qnn_htp", W8A16, str(R.string.ui_integer_npu_decoder_bit, frontLabel))
            dec == "npu16" -> Devices(f, "qnn_htp", "model.dec.onnx", str(R.string.ui_bit_npu_decoder_may_sound_rough, frontLabel))
            dec == "cpu" -> Devices(f, "cpu", "model.dec.onnx", str(R.string.ui_bit_cpu_decoder, frontLabel))
            else -> Devices(f, "ncnn_gpu", "model.dec.onnx", str(R.string.ui_bit_gpu_decoder, frontLabel) +
                if (dec == "npu_int") str(R.string.ui_no_integer_npu_decoder_file_for_this) else "")
        }
    }

    private fun liveDevices(dir: File): Devices {
        val selected = fileDevices(dir)
        return if (ModelLivePreferences(prefs, dir.name).getBoolean("live_split", false) && computePolicy.tryNpu && selected.front == "qnn_htp")
            Devices(selected.front, "split", "model.dec.onnx", str(R.string.ui_front_end_npu_decoder_npu_cpu))
        else selected
    }

    private val computeViews = HashMap<String, View>()

    /** A row of choices that looks like the page tabs. */
    private fun segmented(parent: LinearLayout, items: List<Pair<String, String>>, selected: () -> String, pick: (String) -> Unit): List<TextView> {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.tabbar)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(10) }
        }
        val views = items.map { (key, label) ->
            TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColorStateList(R.color.tab_text)); setBackgroundResource(R.drawable.tab)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                tag = key
                setOnClickListener { pick(key); showCompute() }
            }.also { bar.addView(it) }
        }
        parent.addView(bar)
        computeViews["seg_" + items.joinToString { it.first }] = bar
        return views.onEach { v -> v.isSelected = v.tag == selected() }
    }

    private val segments = ArrayList<Pair<List<TextView>, () -> String>>()

    private fun setupSettingsPage() {
        val page = f<LinearLayout>(R.id.page_settings)
        val guide = card(page)
        guide.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.usage_title); setOnClickListener { showUsage() }
        })
        guide.addView(TextView(this).apply {
            text = str(R.string.language_system_hint); textSize = 12f
            setTextColor(getColor(R.color.muted)); setPadding(0, dp(10), 0, dp(6))
        })
        if (android.os.Build.VERSION.SDK_INT >= 33) guide.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.app_language)
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        // where the models run
        val compute = card(page)
        compute.addView(TextView(this).apply { text = str(R.string.ui_compute_devices_app_wide_settings); textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val presets = listOf("recommended" to str(R.string.ui_recommended), "full32" to str(R.string.ui_all_bit), "custom" to str(R.string.ui_custom))
        val preset = { prefs.getString("compute", "recommended")!! }
        segments += segmented(compute, presets, preset) { prefs.edit().putString("compute", it).apply() } to preset
        val hint = TextView(this).apply { textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(8), 0, 0) }
        compute.addView(hint)
        computeViews["hint"] = hint
        val custom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        compute.addView(custom)
        computeViews["custom"] = custom
        custom.addView(TextView(this).apply { text = str(R.string.ui_front_end_hubert_pitch_encoder); textSize = 12f; setPadding(0, dp(10), 0, 0) })
        val front = { prefs.getString("custom_front", "npu")!! }
        segments += segmented(custom, listOf("cpu" to "CPU", "npu" to "NPU"), front) { prefs.edit().putString("custom_front", it).apply() } to front
        custom.addView(TextView(this).apply { text = str(R.string.ui_decoder_generates_audio); textSize = 12f; setPadding(0, dp(10), 0, 0) })
        val dec = { prefs.getString("custom_dec", "npu_int")!! }
        segments += segmented(custom, listOf("cpu" to "CPU", "gpu" to "GPU", "npu_int" to str(R.string.ui_integer_npu), "npu16" to str(R.string.ui_bit_npu)), dec) {
            prefs.edit().putString("custom_dec", it).apply()
        } to dec
        val now = TextView(this).apply { textSize = 12f; setTextColor(getColor(R.color.accent_strong)); setPadding(0, dp(10), 0, 0) }
        compute.addView(now)
        computeViews["now"] = now
        val threadsValue = valueText()
        val threadsBar = sliderRow(compute, str(R.string.ui_cpu_threads), threadsValue, "2", "8", 6)
        threadsBar.setOnSeekBarChangeListener(slider { p, done ->
            threadsValue.text = "${p + 2}"
            if (done) prefs.edit().putInt("threads", p + 2).apply()
        })
        threadsBar.progress = hooks.getIntExtra("threads", prefs.getInt("threads", 4)) - 2
        threadsValue.text = "${threadsBar.progress + 2}"
        compute.addView(TextView(this).apply {
            text = str(R.string.ui_device_settings_apply_to_the_next_file)
            textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(10), 0, 0)
        })
        // records
        val records = card(page)
        records.addView(TextView(this).apply { text = str(R.string.ui_diagnostics); textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        row.addView(TextView(this).apply {
            text = str(R.string.ui_save_live_diagnostics_and_the_latest_live)
            textSize = 11f; setTextColor(getColor(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_export_diagnostics)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(10) }
            setOnClickListener { exportLive() }
        })
        records.addView(row)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        records.addView(TextView(this).apply {
            text = str(R.string.ui_app_folder_version, base.path, version)
            textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(8), 0, 0)
        })
    }

    /** The compute card's state: which choice is lit, its hint, and what the selected voice will actually run on. */
    private fun showCompute() {
        for ((views, selected) in segments) views.forEach { it.isSelected = it.tag == selected() }
        val preset = prefs.getString("compute", "recommended")
        (computeViews["hint"] as? TextView)?.text = when (preset) {
            "full32" -> str(R.string.ui_uses_the_cpu_for_the_front_end)
            "custom" -> str(R.string.ui_choose_devices_for_file_and_live_conversion)
            else -> str(R.string.ui_uses_npu_gpu_or_cpu_based_on)
        }
        computeViews["custom"]?.visibility = if (preset == "custom") View.VISIBLE else View.GONE
        (computeViews["now"] as? TextView)?.text = currentModel()?.let { str(R.string.ui_current_voice, it.name, fileDevices(it).label) } ?: ""
    }

    // ---------------------------------------------------------------- live page

    /** 试听时用：没有本地监听时给出可读的提示。 */
    @Volatile private var livePreparing = false
    @Volatile private var liveMessage: String? = null
    private var listening = false
    /** 试听需要麦克风权限时，授予后继续启动。 */
    private var pendingListen: (() -> Unit)? = null

    /** Timing is saved per model and applies at the next start. */
    private fun livePrefs() = ModelLivePreferences(prefs, currentModel()?.name)
    private fun liveParams() = LivePreferences.readWithMic(false, livePrefs()::getInt, prefs.getBoolean("mic_bluetooth", false))

    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        parent.addView(this)
    }

    private fun valueText() = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(getColor(R.color.accent_strong)) }




    // ---------------------------------------------------------------- status page (game -> voice -> profile -> start)

    private var statusBody: LinearLayout? = null
    private var statusButton: Button? = null
    private var statusRoot: TextView? = null
    private var statusHeadline: TextView? = null
    private var statusDetail: TextView? = null
    private var statusTimer: TextView? = null
    private var statusInBar: ProgressBar? = null
    private var statusOutBar: ProgressBar? = null
    private var statusInValue: TextView? = null
    private var statusOutValue: TextView? = null
    private var statusMetrics: TextView? = null
    @Volatile private var rootReady = false
    @Volatile private var rootChecked = false
    private var rootChecking = false
    private val statusTicker = object : Runnable {
        override fun run() { renderStatusState(); ui.postDelayed(this, 500) }
    }

    /** 状态页顶部那张卡：选游戏、音色、方案，然后直接开麦。旧实时变声卡片暂留在这张卡下面。 */
    private fun setupStatusPage() {
        val page = f<LinearLayout>(R.id.page_live)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = str(R.string.ui_status); textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(getColor(R.color.text))
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(body)
        statusBody = body
        page.addView(card, 0)
        renderStatusCard()
    }

    private fun renderStatusCard() {
        val body = statusBody ?: return
        body.removeAllViews()
        fun caption(text: String) = TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(getColor(R.color.muted)); setPadding(0, dp(10), 0, dp(2))
        }
        fun row(text: String, action: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                this.text = text; textSize = 16f
                setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.text))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity, null, 0, R.style.SoftButton).apply {
                this.text = str(R.string.ui_change); textSize = 12f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        }

        val games = GameBinding.games(prefs)
        if (games.isEmpty()) {
            body.addView(TextView(this).apply {
                text = str(R.string.ui_no_games_yet); textSize = 12f
                setTextColor(getColor(R.color.muted)); setPadding(0, dp(6), 0, dp(6))
            })
            body.addView(Button(this, null, 0, R.style.SoftButton).apply {
                text = str(R.string.ui_add_game)
                layoutParams = LinearLayout.LayoutParams(-1, dp(46))
                setOnClickListener { showPage(CONFIG) }
            })
            statusButton = null
            return
        }
        val pkg = prefs.getString("status_game", null)?.takeIf { it in games } ?: games.first()
        prefs.edit().putString("status_game", pkg).apply()
        val pm = packageManager
        val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
        val bound = GameBinding.get(prefs, pkg)
        val modelDir = models.firstOrNull { it.name == bound?.model } ?: models.firstOrNull()
        val profiles = modelDir?.let { VoiceProfiles.list(it) } ?: emptyList()

        body.addView(caption(str(R.string.ui_game_setup)))
        body.addView(row(label) {
            val names = games.map { p ->
                runCatching { pm.getApplicationInfo(p, 0).loadLabel(pm).toString() }.getOrDefault(p)
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle(str(R.string.ui_game_setup))
                .setItems(names) { _, which ->
                    prefs.edit().putString("status_game", games[which]).apply(); renderStatusCard()
                }
                .setNegativeButton(str(R.string.ui_cancel), null).show()
        })
        body.addView(caption(str(R.string.ui_voice_of)))
        body.addView(row(modelDir?.name ?: str(R.string.ui_add_a_model_first)) {
            AlertDialog.Builder(this).setTitle(str(R.string.ui_voice_of))
                .setItems(models.map { it.name }.toTypedArray()) { _, which ->
                    val dir = models[which]
                    val first = VoiceProfiles.list(dir).firstOrNull() ?: VoiceProfiles.DEFAULT
                    GameBinding.bind(prefs, pkg, dir.name, first)
                    renderStatusCard()
                }
                .setNegativeButton(str(R.string.ui_cancel), null).show()
        })

        body.addView(caption(str(R.string.ui_profiles)))
        val profileSpinner = Spinner(this).apply { isEnabled = profiles.isNotEmpty() }
        profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profiles)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        profileSpinner.setSelection(profiles.indexOf(bound?.profile ?: "").let { if (it >= 0) it else 0 })
        body.addView(profileSpinner)

        val button = Button(this, null, 0, R.style.SoftButton).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) }
            setOnClickListener {
                if (RootMicController.active || VirtualMicSession.isActive) {
                    RootMicController.stop()
                    stopService(Intent(this@MainActivity, LiveService::class.java))
                    renderStatusState()
                    return@setOnClickListener
                }
                val dir = modelDir
                if (dir == null) { toast(str(R.string.ui_add_a_model_first)); return@setOnClickListener }
                val chosen = profiles.getOrNull(profileSpinner.selectedItemPosition) ?: VoiceProfiles.DEFAULT
                GameBinding.bind(prefs, pkg, dir.name, chosen)
                prefs.edit().putString("virtual_mic_target", pkg).putString("virtual_mic_model", dir.name).apply()
                startStatusSession(pkg, dir.name)
            }
        }
        body.addView(button)
        statusButton = button

        // 就绪状态（搬自原来的「管理游戏音色」页）
        statusRoot = TextView(this).apply {
            textSize = 12f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(12), 0, 0)
        }
        body.addView(statusRoot)
        statusHeadline = TextView(this).apply {
            textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text)); setPadding(0, dp(4), 0, 0)
        }
        body.addView(statusHeadline)
        statusDetail = TextView(this).apply {
            textSize = 11f; setTextColor(getColor(R.color.muted)); setLineSpacing(dp(2).toFloat(), 1f)
        }
        body.addView(statusDetail)
        statusTimer = TextView(this).apply {
            textSize = 13f; typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.accent_strong)); setPadding(0, dp(6), 0, 0)
        }
        body.addView(statusTimer)

        // 实时参数（电平 + 处理耗时）
        fun levelRow(label: String, onBar: (ProgressBar) -> Unit, onValue: (TextView) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = label; textSize = 12f; setTextColor(getColor(R.color.muted))
            }, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            row.addView(bar, LinearLayout.LayoutParams(0, dp(12), 1f).apply { marginEnd = dp(10) })
            val value = TextView(this).apply {
                text = "—"; textSize = 12f; typeface = Typeface.MONOSPACE
                setTextColor(getColor(R.color.text)); gravity = Gravity.END
            }
            row.addView(value, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(row)
            onBar(bar); onValue(value)
        }
        levelRow(str(R.string.ui_mic_input), { statusInBar = it }, { statusInValue = it })
        levelRow(str(R.string.ui_converted_output), { statusOutBar = it }, { statusOutValue = it })
        statusMetrics = TextView(this).apply {
            textSize = 11f; setTextColor(getColor(R.color.muted))
            setPadding(0, dp(8), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        }
        body.addView(statusMetrics)
        renderStatusState()
        checkRootStatus()
    }

    /** 后台探一次 root（su -c id 最长 6 秒），结果给状态页用。 */
    private fun checkRootStatus() {
        if (rootChecking) return
        rootChecking = true
        thread {
            val ok = RootMicController.checkRoot(force = true)
            ui.post {
                rootReady = ok; rootChecked = true; rootChecking = false
                renderStatusState()
            }
        }
    }

    /** 状态页：就绪状态 + 阶段 + 实时电平与处理耗时（原「管理游戏音色」页的内容）。 */
    private fun renderStatusState() {
        val running = RootMicController.active || VirtualMicSession.isActive
        statusButton?.text = str(if (running) R.string.ui_stop_virtual_mic else R.string.ui_start_virtual_mic)
        statusRoot?.text = when {
            rootReady -> str(R.string.ui_root_granted)
            rootChecked -> str(R.string.ui_root_required)
            else -> str(R.string.ui_root_checking)
        }
        statusRoot?.setTextColor(getColor(if (rootReady) R.color.green else R.color.accent_strong))
        val phase = RootMicController.phase
        val stream = phase == MicPhase.RUNNING
        val failure = RootMicController.error
        statusHeadline?.text = when {
            failure != null -> str(R.string.ui_check_connection)
            phase == MicPhase.SILENT -> str(R.string.ui_model_failed_silent_title)
            phase == MicPhase.STOPPING -> str(R.string.ui_stopping_the_microphone)
            phase == MicPhase.BUFFERING -> str(R.string.ui_preparing_audio)
            stream -> str(R.string.ui_sending_your_voice)
            phase == MicPhase.WAITING -> str(R.string.ui_waiting_for_microphone_input)
            running -> str(R.string.ui_preparing_your_voice)
            rootChecked && !rootReady -> str(R.string.ui_root_required)
            else -> str(R.string.ui_virtual_microphone_is_off)
        }
        statusDetail?.text = when {
            failure != null -> failure.takeLast(200)
            phase == MicPhase.SILENT -> str(R.string.ui_model_failed_silent_detail)
            phase == MicPhase.STOPPING -> str(R.string.ui_restoring_the_original_microphone_2)
            phase == MicPhase.BUFFERING -> str(R.string.ui_audio_resumes_when_converted_sound_is_ready)
            stream -> str(R.string.ui_sending_converted_input_to_apps_using_the)
            phase == MicPhase.WAITING -> str(R.string.ui_waiting_without_a_time_limit_start_a)
            running -> str(R.string.ui_loading_the_selected_voice_please_wait)
            rootChecked && !rootReady -> str(R.string.ui_root_required_detail)
            else -> str(R.string.ui_choose_an_app_and_voice_then_turn)
        }
        val seconds = if (RootMicController.active)
            (android.os.SystemClock.elapsedRealtime() - RootMicController.startedAt) / 1000 else 0L
        statusTimer?.text = if (RootMicController.active) String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60) else ""
        statusTimer?.visibility = if (RootMicController.active) View.VISIBLE else View.GONE
        val meter = if (stream) VirtualMicSession.meter else VirtualMicSession.Meter()
        fun showLevel(value: Float, bar: ProgressBar?, label: TextView?) {
            val db = if (value > 0) (20 * Math.log10(value.toDouble())).coerceIn(-60.0, 0.0) else -60.0
            bar?.progress = ((db + 60) / 60 * 100).toInt()
            label?.text = if (value > 0) "${db.toInt()} dB" else "—"
        }
        showLevel(meter.input, statusInBar, statusInValue)
        showLevel(meter.output, statusOutBar, statusOutValue)
        statusMetrics?.text = str(R.string.ui_processing_ms_ms_block_skipped_inputs_capture, meter.computeMs.toInt(), meter.drops)
        statusMetrics?.visibility = if (stream) View.VISIBLE else View.GONE
    }

    /** 和虚拟麦页面相同的启动顺序：先拿权限，再起前台服务，最后交给 root 控制器。 */
    private fun startStatusSession(pkg: String, modelName: String) {
        val mic = android.Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(mic) != PackageManager.PERMISSION_GRANTED) {
            val ask = mutableListOf(mic)
            if (android.os.Build.VERSION.SDK_INT >= 33) ask += android.Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(ask.toTypedArray(), STATUS_MIC)
            return
        }
        if (LiveSession.running) { toast(str(R.string.ui_stop_the_existing_live_conversion_first)); return }
        try {
            startForegroundService(Intent(this, LiveService::class.java).putExtra("virtual_mic", true))
            RootMicController.start(applicationContext, pkg, modelName)
        } catch (e: Throwable) {
            toast(e.message ?: str(R.string.ui_could_not_start_2))
            stopService(Intent(this, LiveService::class.java))
        }
        renderStatusState()
    }


    /** The live page's pitch and texture from the selected voice's settings.json. */




    /** The realtime diagnosis (logs, block timings) and the newest realtime recording into Download/VoiceStudio/live_<time>,
     *  where a PC can reach them (the app's own folder is closed to other apps and to adb for files it made). */
    private fun exportLive() {
        thread {
            val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
            val files = listOf("live_log.txt", "live_log_prev.txt", "live_blocks.csv", "live_blocks_prev.csv").map { File(base, it) }.filter { it.isFile } +
                (recordingsDir.listFiles { f -> f.name.startsWith("실시간_") }?.sortedByDescending { it.lastModified() }?.take(2) ?: emptyList())
            var done = 0
            for (file in files) runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/VoiceStudio/live_$stamp")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                done++
            }
            ui.post { toast(str(R.string.ui_saved_files_to_downloads_voicestudio_live, stamp, done)) }
        }
    }

    private val thermalNames = listOf(str(R.string.ui_normal), str(R.string.ui_slightly_warm), str(R.string.ui_warm), str(R.string.ui_hot_may_slow_down), str(R.string.ui_very_hot), str(R.string.ui_critical), str(R.string.ui_near_shutdown))

    /** The live page: buttons, the two scrolling waveforms and the numbers that tell whether the phone keeps up. */
    // ---------------------------------------------------------------- misc

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        val mic = permissions.indexOf(android.Manifest.permission.RECORD_AUDIO).let { results.getOrNull(it) } == PackageManager.PERMISSION_GRANTED
        if (code == MIC && mic) toggleRecording()
        else if (code == MIC) toast(str(R.string.ui_microphone_permission_is_required_to_record))
        else if (code == LIVE_MIC && mic) { pendingListen?.invoke(); pendingListen = null }
        else if (code == LIVE_MIC) toast(str(R.string.ui_microphone_permission_is_required_for_live_conversion))
        else if (code == STATUS_MIC && mic) {
            val pkg = prefs.getString("status_game", null) ?: return
            val model = GameBinding.get(prefs, pkg)?.model ?: models.firstOrNull()?.name ?: return
            startStatusSession(pkg, model)
        } else if (code == STATUS_MIC) toast(str(R.string.ui_microphone_permission_is_required_for_live_conversion))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (code) {
            PICK_FOLDER -> runCatching { importFolder(uri) }.onFailure { toast(str(R.string.ui_could_not_read_folder, it.message)) }
            PICK_ZIP -> runCatching { importZip(uri) }.onFailure { toast(str(R.string.ui_could_not_read_zip, it.message)) }
        }
    }

    /** Test hook: realtime steps over a file, without the audio devices (StreamBench). `am start -n
     *  com.voicestudio.phone/.MainActivity --es stream <wav> [--es front cpu|qnn_htp --es ep cpu|ncnn_gpu|qnn_htp
     *  --ei threads N --ei block 20 --ei context 100 --ei crossfade 8 --ei search 1 --ef cushion 0.06 --ez paced true
     *  --ef seconds 30 --ef gain_db 0 --ef threshold_db -120]` writes files/stream_output.wav and stream_steps.csv
     *  and logs a summary line starting with "stream". */
    private fun debugStream(file: File) {
        val dir = models.firstOrNull { it.name == (hooks.getStringExtra("model") ?: prefs.getString("model", null)) }
            ?: currentModel() ?: return
        val params = LiveParams(hooks.getIntExtra("block", 20), hooks.getIntExtra("context", 100),
            hooks.getIntExtra("crossfade", 8), hooks.getIntExtra("search", 1),
            hooks.getFloatExtra("cushion", 0.06f).toDouble(), hooks.getFloatExtra("threshold_db", -120f).toDouble(),
            hooks.getFloatExtra("gain_db", 0f).toDouble())
        val front = hooks.getStringExtra("front") ?: "cpu"
        val ep = hooks.getStringExtra("ep") ?: "ncnn_gpu"
        val threads = hooks.getIntExtra("threads", 4)
        val log = { m: String -> android.util.Log.i("VoiceStudio", m); Unit }
        thread {
            try {
                val x = AudioIo.decode(this, Uri.fromFile(file))
                val t0 = System.nanoTime()
                Rvc(commonDir, dir, threads, ep, decFile, front, seed = hooks.getIntExtra("seed", 0).toLong(),
                    live = Rvc.LiveShape(params.block, params.tail, windowFrames = params.window(null)),
                    cpuFixedWindows = hooks.getBooleanExtra("fixed_windows", false),
                    profileDir = if (hooks.getBooleanExtra("profile", false)) File(base, "profiles").apply { mkdirs() } else null).use { rvc ->
                    log(String.format(Locale.US, "stream engine ready in %.1f s: front %s, decoder %s (%s)",
                        (System.nanoTime() - t0) / 1e9, front, ep, rvc.decoderDevice))
                    val r = StreamBench.run(rvc, params, settingsOf(dir), x, hooks.getBooleanExtra("paced", true),
                        hooks.getFloatExtra("seconds", (x.size / 48000f)).toDouble(), log = log)
                    AudioIo.writeWav(File(base, "stream_output.wav"), r.output)
                    File(base, "stream_steps.csv").writeText(r.csv)
                    log(r.summary + " | front $front, decoder $ep, threads $threads, model ${dir.name}")
                }
            } catch (e: Throwable) {
                android.util.Log.e("VoiceStudio", "stream failed", e)
            }
        }
    }

    private fun clock(samples: Int): String {
        val s = samples.toDouble() / Rvc.SR
        return String.format(Locale.US, "%d:%04.1f", (s / 60).toInt(), s % 60)
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).roundToInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val MIC = 2
        private const val PICK_FOLDER = 3
        private const val PICK_ZIP = 4
        private const val LIVE_MIC = 5
        private const val STATUS_MIC = 6
        private const val W8A16 = "model.dec.w8a16.onnx"
        private const val RECORD = "record"
        private const val CONFIG = "config"
        private const val LIVE = "live"
        private const val SETTINGS = "settings"
    }
}
