package com.voicestudio.phone

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * 悬浮窗：虚拟麦跑起来以后，App 退到后台，只剩这颗红/绿按钮。
 *
 * - 绿 = 游戏真的在实时变声（[MicPhase.RUNNING]）；其它任何阶段（加载模型、等待麦克风、
 *   缓冲、静音保持）都是红，此时桥接是 fail-closed，麦克风里不会有原声漏出。
 * - 点一下展开面板：当前游戏、音色（可切）、方案（可切）、以及五个常用参数。
 * - 切换音色要重建引擎，期间保持红色；切换方案只换参数文件，500 ms 内热生效。
 */
class OverlayService : Service() {

    companion object {
        /** 只有拿到悬浮窗权限、并且真的有会话在跑时才显示。 */
        @Volatile var visible = false
            private set

        fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            if (!canDraw(ctx)) return
            runCatching { ctx.startService(Intent(ctx, OverlayService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
        }
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var collapsed: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var dot: View? = null
    private var stateText: TextView? = null
    private var gameView: TextView? = null
    private var voiceSpinner: Spinner? = null
    private var profileSpinner: Spinner? = null
    private var switching: TextView? = null
    private var expanded = false
    private var syncing = false
    private var lastTrace = 0L
    private var restartButton: Button? = null
    private var idleSince = 0L
    @Volatile private var restarting = false

    // ---- 音频专属悬浮窗（第二个窗口）----
    private var audioRoot: LinearLayout? = null
    private var audioPanel: LinearLayout? = null
    private var audioIcon: TextView? = null
    private var audioSpinner: Spinner? = null
    private var audioList: LinearLayout? = null
    private var audioState: TextView? = null
    private var audioToggle: Button? = null
    private var audioExpanded = false
    private var audioSyncing = false
    private var audioCategories: List<AudioLibrary.Category> = emptyList()
    private val audioRows = ArrayList<Pair<String, View>>()
    private var audioHighlight: String? = null
    private var rowOn: GradientDrawable? = null

    // ---- 「首页」：网络音乐（热门 / 最近 / 搜索）----
    private var audioHomeBox: LinearLayout? = null
    private var audioLocalBox: LinearLayout? = null
    private var audioSearchRow: LinearLayout? = null
    private var audioSearchInput: android.widget.EditText? = null
    private var audioHomeNote: TextView? = null
    private var audioModeHome: TextView? = null
    private var audioModeLocal: TextView? = null
    private var audioTabHot: TextView? = null
    private var audioTabLiked: TextView? = null
    private var audioTabSearch: TextView? = null
    /** 当前面板在「首页」还是「本地」；首页三个页签：0 热门 1 最近 2 搜索。 */
    private var homeMode = false
    private var homeTab = 0
    /** 当前显示的那一列。 */
    private var homeSongs: List<LxMusic.Song> = emptyList()
    /** 热门自己的缓存——必须和搜索结果分开，否则搜完再回热门会显示搜索结果。 */
    private var hotSongs: List<LxMusic.Song> = emptyList()
    private var searchSongs: List<LxMusic.Song> = emptyList()
    private var homeBusy = false
    private var lastHotTap = 0L
    private var audioModeBtn: TextView? = null
    private var audioQualityBtn: TextView? = null

    // ---- 闭麦专属悬浮窗（第三个窗口）----
    private var muteRoot: LinearLayout? = null
    private var muteBadge: MicBadgeView? = null
    /** 返音开着、又是在外放时显示的那行提醒。 */
    private var monitorHint: TextView? = null
    private var speakerAt = 0L
    private var speakerOut = false

    private val prefs by lazy { getSharedPreferences("phone", Context.MODE_PRIVATE) }
    private val modelsDir by lazy { File(getExternalFilesDir(null), "models") }

    private val red = Color.parseColor("#E5484D")
    private val green = Color.parseColor("#30A46C")
    private val amber = Color.parseColor("#FFB020")

    private val tick = object : Runnable {
        override fun run() {
            renderAudio()
            renderMute()
            renderState()
            ui.postDelayed(this, 300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 音频那几个开关是持久化的；服务先起来时也要把它们放回 AudioDeck（setMonitor 是幂等的）
        AudioDeck.setMonitorGain(prefs.getFloat("audio_monitor_gain_db", -6f))
        AudioDeck.setMonitor(prefs.getBoolean("audio_monitor", false))
        // 播放模式：老版本只有 audio_loop 布尔，读不到就用默认的单曲循环
        AudioDeck.setMode(
            runCatching { AudioDeck.Mode.valueOf(prefs.getString("audio_mode", "LOOP_ONE")!!) }
                .getOrDefault(AudioDeck.Mode.LOOP_ONE),
        )
        addOverlay()
        ui.post(tick)
        visible = true

        // 网络音源：WebView 起得慢，服务一起来就预热，别等用户点开首页才开始加载 31 个脚本
        LxMusic.init(this) { ok ->
            ui.post {
                audioHomeNote?.text =
                    if (ok) str(R.string.ui_audio_sources_ok, LxMusic.scriptCount())
                    else str(R.string.ui_audio_sources_failed)
            }
        }
    }

    override fun onDestroy() {
        visible = false
        ui.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        audioRoot?.let { runCatching { wm.removeView(it) } }
        audioRoot = null
        muteRoot?.let { runCatching { wm.removeView(it) } }
        muteRoot = null
        LxMusic.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 视图

    private fun circle(color: Int, size: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(size, size) }

    private fun addOverlay() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 收起态：一颗圆点 + 一行状态字
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(14), dp(6))
        }
        val view = View(this).apply { background = circle(red, dp(20)) }
        dot = view
        head.addView(view, LinearLayout.LayoutParams(dp(20), dp(20)))
        val label = TextView(this).apply {
            text = str(R.string.ui_overlay_waiting)
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        }
        head.addView(label)
        val capsule = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(Color.parseColor("#CC1B1B1F"))
        }
        head.background = capsule
        drag(head, { root }) { toggle() }
        column.addView(head, LinearLayout.LayoutParams(-2, -2))

        // 展开态：面板
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F7141418"))
            }
            visibility = View.GONE
        }
        body.setPadding(dp(14), dp(12), dp(14), dp(12))

        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val game = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); maxLines = 1
        }
        title.addView(game, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(this).apply {
            text = "×"; textSize = 20f; setTextColor(Color.parseColor("#9A9AA5")); setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { toggle() }
        }
        title.addView(close)
        body.addView(title)
        gameView = game

        // 音色 / 方案 并排放一行：竖着堆会把面板拉太长，横屏装不下
        val heads = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val voiceCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceCol.addView(rowLabel(str(R.string.ui_overlay_voice)))
        voiceSpinner = Spinner(this).also { voiceCol.addView(it) }
        heads.addView(voiceCol, LinearLayout.LayoutParams(0, -2, 1f))
        val profileCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profileCol.addView(rowLabel(str(R.string.ui_overlay_profile)))
        profileSpinner = Spinner(this).also { profileCol.addView(it) }
        heads.addView(profileCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        body.addView(heads)

        switching = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#FFB020")); setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        body.addView(switching)

        // 左栏滑杆、右栏按钮：面板加宽、纵向压到一半
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 五个常用参数
        slider(left, str(R.string.ui_output_gain), -12.0, 24.0, 1.0, "live_gain_db") { v ->
            setParam(VoiceProfiles.GROUP_ENGINE, "live_gain_db", v)
        }
        slider(left, str(R.string.ui_input_gain), 0.0, 24.0, 1.0, "live_input_gain_db") { v ->
            setParam(VoiceProfiles.GROUP_ENGINE, "live_input_gain_db", v)
        }
        slider(left, str(R.string.ui_high_pitch_protection_strength), 1.0, 3.0, 0.1, "high_pitch_ratio") { v ->
            setParam(VoiceProfiles.GROUP_VOICE, "high_pitch_ratio", v)
        }
        slider(left, str(R.string.ui_formant_voice_texture), -2.0, 2.0, 0.1, "formant") { v ->
            setParam(VoiceProfiles.GROUP_VOICE, "formant", v)
        }
        slider(left, str(R.string.ui_silence_threshold), -80.0, -30.0, 1.0, "live_threshold_db") { v ->
            setParam(VoiceProfiles.GROUP_ENGINE, "live_threshold_db", v)
        }

        grid.addView(left, LinearLayout.LayoutParams(0, -2, 1.5f))

        // 四个按钮竖着码在右栏，不再占一整行的高度
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val restart = Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_overlay_restart); textSize = 12f
            setOnClickListener { restartSession() }
        }
        restartButton = restart
        right.addView(restart, LinearLayout.LayoutParams(-1, dp(42)))
        val mute = Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_overlay_mute); textSize = 12f
            setOnClickListener { toggleMuteWindow() }
        }
        right.addView(mute, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        val audio = Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_audio); textSize = 12f
            setOnClickListener { showAudioOverlay() }
        }
        right.addView(audio, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        val quit = Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_overlay_quit); textSize = 12f
            setOnClickListener { quitEverything() }
        }
        right.addView(quit, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        grid.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        body.addView(grid)
        drag(title, { root })

        column.addView(body, LinearLayout.LayoutParams(dp(420), -2).apply { topMargin = dp(8) })
        panel = body
        collapsed = head
        root = column

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(120)
        }
        runCatching { wm.addView(column, lp) }.onFailure { root = null }

        // 初始数据
        ui.post { reloadChoices() }
        // 上次开着闭麦按钮的话接着开着：打游戏时少点两下
        if (prefs.getBoolean("overlay_mute", false)) ui.post { setMuteWindowVisible(true) }
    }

    private fun rowLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.parseColor("#9A9AA5"))
        setPadding(0, dp(8), 0, 0)
    }

    private fun slider(
        parent: LinearLayout,
        label: String,
        min: Double,
        max: Double,
        step: Double,
        key: String,
        onDone: (Double) -> Unit,
    ) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        head.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(Color.parseColor("#C9C9D1"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply {
            textSize = 11f; setTextColor(Color.WHITE)
        }
        head.addView(value)
        parent.addView(head)
        val bar = SeekBar(this).apply {
            this.max = ((max - min) / step).roundToInt()
            progress = (((current(key) ?: min) - min) / step).roundToInt().coerceIn(0, this.max)
            setPadding(dp(2), 0, dp(2), 0)
        }
        value.text = format(current(key) ?: min)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = format(min + p * step)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (syncing) return
                onDone(min + (sb?.progress ?: 0) * step)
            }
        })
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(28)))
    }

    private fun format(v: Double): String =
        if (v == v.roundToInt().toDouble()) v.roundToInt().toString()
        else String.format(Locale.US, "%.1f", v)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    private fun str(id: Int, vararg args: Any): String =
        if (args.isEmpty()) getString(id) else getString(id, *args)

    /** 拖动移动悬浮窗；手指没动就当作点击（否则触摸监听会把点击吞掉）。 */
    private fun drag(handle: View, win: () -> View?, onClick: (() -> Unit)? = null) {
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        var moved = false
        handle.setOnTouchListener { v, e ->
            val lp = win()?.layoutParams as? WindowManager.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp?.x ?: 0; startY = lp?.y ?: 0
                    downX = e.rawX; downY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true
                    val view = win()
                    if (moved && lp != null && view != null) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { v.performClick(); onClick?.invoke() }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggle() {
        expanded = !expanded
        panel?.visibility = if (expanded) View.VISIBLE else View.GONE
        reloadChoices()
    }

    // ---------------------------------------------------------------- 状态

    private fun renderState() {
        val running = RootMicController.active || VirtualMicSession.isActive
        if (!running) {
            // 重启/切换音色时会出现一瞬间“没有会话”，不能在这时自杀（否则 MIUI 立刻冻结进程，重启就没了）。
            // 真的停了（超过 5 秒）才退场。
            if (restarting) {
                idleSince = 0L
                dot?.background = circle(red, dp(20))
                (collapsed?.getChildAt(1) as? TextView)?.text = str(R.string.ui_overlay_switching)
                return
            }
            if (idleSince == 0L) idleSince = android.os.SystemClock.elapsedRealtime()
            if (android.os.SystemClock.elapsedRealtime() - idleSince > 5000) {
                stopSelf()
            }
            return
        }
        idleSince = 0L
        val phase = RootMicController.phase
        val muted = VirtualMicSession.muted
        // 绿 = 最近 2.5 秒里真的有人在说话。phase == RUNNING 只代表桥接通道已连接：游戏占着麦克风时
        // 一直静音也会是 RUNNING，拿它兜底就会「没说话也一直显示变声中」。
        val streaming = VirtualMicSession.lastAudioAt > 0 &&
            android.os.SystemClock.elapsedRealtime() - VirtualMicSession.lastAudioAt < 2500
        val live = streaming && !muted
        if (android.os.SystemClock.elapsedRealtime() - lastTrace > 2000) {
            lastTrace = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("VoiceStudio", "OVERLAY live=$live streaming=$streaming phase=$phase muted=$muted lastAudio=${VirtualMicSession.lastAudioAt} inPeak=${VirtualMicSession.meter.input} inRms=${VirtualMicSession.lastRms} active=${RootMicController.active}")
        }
        dot?.background = circle(if (live) green else red, dp(20))
        // 只有「改了必须重启才生效」的参数时才点亮重开会话，否则灰着
        val canRestart = VirtualMicSession.needsRestart && !restarting
        restartButton?.isEnabled = canRestart
        restartButton?.alpha = if (canRestart) 1f else 0.38f
        (collapsed?.getChildAt(1) as? TextView)?.text = when {
            switching?.visibility == View.VISIBLE -> str(R.string.ui_overlay_switching)
            muted -> str(R.string.ui_overlay_muted)
            live -> str(R.string.ui_overlay_live)
            phase == MicPhase.WAITING -> str(R.string.ui_overlay_waiting)
            phase == MicPhase.BUFFERING -> str(R.string.ui_preparing_audio)
            phase == MicPhase.RUNNING -> str(R.string.ui_overlay_connected)
            phase == MicPhase.SILENT -> str(R.string.ui_model_failed_silent_title)
            RootMicController.error != null -> str(R.string.ui_check_connection)
            else -> str(R.string.ui_preparing_your_voice)
        }
        if (expanded && !syncing) {
            val pkg = prefs.getString("status_game", "") ?: ""
            gameView?.text = gameLabel(pkg)
        }
    }

    private fun gameLabel(pkg: String): String {
        if (pkg.isEmpty()) return str(R.string.ui_no_games_yet)
        val pm = packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    // ---------------------------------------------------------------- 数据

    private val bound get() = prefs.getString("status_game", "")?.let { GameBinding.get(prefs, it) }
    private val modelDir: File?
        get() {
            val m = bound?.model ?: prefs.getString("model", null) ?: return null
            return File(modelsDir, m).takeIf { it.isDirectory }
        }
    private val profileName: String get() = bound?.profile ?: VoiceProfiles.DEFAULT

    private fun current(key: String): Double? {
        val dir = modelDir ?: return null
        return runCatching {
            val g = if (key.startsWith("live_")) VoiceProfiles.engine(dir, profileName) else VoiceProfiles.voice(dir, profileName)
            if (g.has(key)) g.optDouble(key, 0.0) else null
        }.getOrNull()
    }

    private fun setParam(group: String, key: String, value: Double) {
        val dir = modelDir ?: return
        runCatching {
            val data = VoiceProfiles.read(dir, profileName)
            data.optJSONObject(group)?.put(key, value)
            // 引擎参数要热更新到跑着的会话里
            if (group == VoiceProfiles.GROUP_ENGINE) { VirtualMicSession.core?.applyEngine(key, value) }
            VoiceProfiles.write(dir, profileName, data)
        }
    }

    private fun reloadChoices() {
        if (!expanded) return
        val dir = modelDir ?: return
        syncing = true
        try {
            val models = (modelsDir.listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .map { it.name }
                .sorted()
            val voicePair = simple(models, bound?.model)
            val voices = (voiceSpinner ?: return).apply {
                adapter = voicePair.first
                setSelection(voicePair.second, false)
            }
            val profiles = VoiceProfiles.list(dir)
            val profilePair = simple(profiles, profileName)
            val prof = (profileSpinner ?: return).apply {
                adapter = profilePair.first
                setSelection(profilePair.second, false)
            }
            voices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (syncing) return
                    val picked = models.getOrNull(pos) ?: return
                    if (picked != bound?.model) switchVoice(picked)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            prof.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (syncing) return
                    val picked = profiles.getOrNull(pos) ?: return
                    if (picked != profileName) switchProfile(picked)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } finally {
            syncing = false
        }
    }

    /** 深色面板里的下拉框：收起态白字，展开列表黑字（系统默认是深色字，看不清）。 */
    private fun simple(items: List<String>, selected: String?): Pair<ArrayAdapter<String>, Int> {
        val list = items.ifEmpty { listOf("—") }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.textSize = 13f
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(Color.BLACK)
                view.textSize = 13f
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val index = selected?.let { list.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        return adapter to index
    }

    private fun switchVoice(model: String) {
        val pkg = prefs.getString("status_game", null) ?: return
        val profile = if (VoiceProfiles.exists(File(modelsDir, model), profileName)) profileName else VoiceProfiles.DEFAULT
        GameBinding.bind(prefs, pkg, model, profile)
        prefs.edit().putString("model", model).apply()
        // 换音色必须重建引擎：走同一套「等拆完再起」的流程
        restartWith(pkg, model)
    }

    /** 改完共振峰/高级参数后点一下：重建会话（期间保持红色）。 */
    private fun restartSession() {
        val pkg = prefs.getString("status_game", null) ?: return
        val model = bound?.model ?: prefs.getString("model", null) ?: return
        restartWith(pkg, model)
    }

    /**
     * 停旧会话 → 等它真的拆干净（RootMicController.start 有 check(!active)）→ 再起新的。
     * 之前固定等 1.5 秒，拆不干净时 start 抛异常，直接把进程带崩，表现就是「拉不起来」。
     */
    private fun restartWith(pkg: String, model: String) {
        if (restarting) return   // 连点两次不要开两个重启流程
        restarting = true
        idleSince = 0L
        showSwitching(str(R.string.ui_overlay_switching))
        RootMicController.stop()
        VirtualMicSession.stop()
        awaitIdleThenStart(pkg, model, 0)
    }

    private fun awaitIdleThenStart(pkg: String, model: String, attempt: Int) {
        val busy = RootMicController.active || VirtualMicSession.isActive || LiveSession.running
        if (busy) {
            if (attempt >= 48) {  // ~12 秒还没拆完
                restarting = false
                showSwitching(str(R.string.ui_could_not_start_2))
                return
            }
            ui.postDelayed({ awaitIdleThenStart(pkg, model, attempt + 1) }, 250)
            return
        }
        val result = runCatching {
            runCatching { startForegroundService(Intent(this, LiveService::class.java).putExtra("virtual_mic", true)) }
            RootMicController.start(applicationContext, pkg, model)
        }
        restarting = false
        if (result.isFailure) {
            showSwitching(str(R.string.ui_could_not_start, result.exceptionOrNull()?.message ?: ""))
        } else {
            ui.postDelayed({ switching?.visibility = View.GONE }, 1200)
        }
    }

    private fun showSwitching(text: String) {
        switching?.apply { this.text = text; visibility = View.VISIBLE }
    }

    private fun switchProfile(profile: String) {
        val pkg = prefs.getString("status_game", null) ?: return
        val model = bound?.model ?: return
        GameBinding.bind(prefs, pkg, model, profile)
        switching?.apply { text = str(R.string.ui_overlay_switching); visibility = View.VISIBLE }
        ui.postDelayed({ switching?.visibility = View.GONE }, 800)
    }

    /** 退出：停会话、停服务、把整个进程带走（MIUI 上单靠 killProcess 不一定生效）。 */
    private fun quitEverything() {
        runCatching { RootMicController.stop() }
        runCatching { VirtualMicSession.stop() }
        runCatching { stopService(Intent(this, LiveService::class.java)) }
        runCatching { stopService(Intent(this, OverlayService::class.java)) }
        stopSelf()
        // 注意：onDestroy 会 removeCallbacksAndMessages，延迟任务要放在独立线程里
        thread {
            Thread.sleep(400)
            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            runCatching { kotlin.system.exitProcess(0) }
        }
    }

    // ---------------------------------------------------------------- 闭麦悬浮按钮（第三个窗口）

    /** 主面板的「闭麦」按钮：把闭麦按钮挂到屏幕上，再点一次收起。 */
    private fun toggleMuteWindow() = setMuteWindowVisible(muteRoot?.visibility != View.VISIBLE)

    private fun setMuteWindowVisible(on: Boolean) {
        if (on) {
            if (muteRoot == null) createMuteWindow()
            muteRoot?.visibility = View.VISIBLE
        } else {
            // 收起图标必须同时解掉闭麦：否则界面上再没有入口能把它开回来
            if (VirtualMicSession.muted) VirtualMicSession.setMuted(false)
            muteRoot?.visibility = View.GONE
        }
        prefs.edit().putBoolean("overlay_mute", on).apply()
        renderMute()
    }

    /** 一颗会变色的麦克风按钮：点一下闭麦、再点一下开麦；按住可以拖位置。 */
    private fun createMuteWindow() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val badge = MicBadgeView(this).apply {
            tint = if (VirtualMicSession.muted) red else green
            slashed = VirtualMicSession.muted
        }
        column.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        drag(badge, { muteRoot }) {
            VirtualMicSession.setMuted(!VirtualMicSession.muted)
            renderMute()
            renderState()
        }
        muteBadge = badge
        muteRoot = column
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(300)
        }
        runCatching { wm.addView(column, lp) }.onFailure { muteRoot = null }
    }

    /**
     * 闭麦按钮的颜色：红 = 闭麦；黄 = 已经点了开麦、但音频还没真的送出去；绿 = 送出去了。
     * 「送出去」看最近一次写进 socket 的时间，所以游戏没在录音时它会一直黄着——那正是真话。
     */
    private fun renderMute() {
        val badge = muteBadge ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val muted = VirtualMicSession.muted
        val flowing = VirtualMicSession.lastBlockAt > 0 &&
            now - VirtualMicSession.lastBlockAt < 1200
        // 开麦几乎是瞬间完成的；给黄色一个最短露脸时间，否则根本看不见它
        val settled = now - VirtualMicSession.muteChangedAt >= 500
        badge.tint = when {
            muted -> red
            flowing && settled -> green
            else -> amber
        }
        badge.slashed = muted
    }

    // ---------------------------------------------------------------- 音频悬浮窗（第二个窗口）

    /**
     * 主面板上的「音频」按钮：没建过就建；已经在屏幕上就整体收掉（素材继续放）。
     * 面板里的 × 只是把面板收回 ♪ 图标，不关功能。
     */
    /** 主面板上的「音频」按钮：第一次点建窗口，之后只是重新露出来（退出走面板里的 ×）。 */
    private fun showAudioOverlay() {
        if (audioRoot == null) createAudioWindow()
        audioRoot?.visibility = View.VISIBLE
        audioExpanded = true
        audioPanel?.visibility = View.VISIBLE
        refreshAudioPanel()
    }

    /**
     * × = **退出音频功能**：窗口收起，同时把本地素材和在线流都停掉。
     * 用户要的就是「关掉就是不要了」，别留在后台偷偷放。
     */
    private fun hideAudioOverlay() {
        audioExpanded = false
        audioPanel?.visibility = View.GONE
        audioRoot?.visibility = View.GONE
        hideKeyboard()
        setAudioFocusable(false)
        runCatching { LxMusic.stopStream() }
        AudioDeck.release()
        audioHomeNote?.text = null
    }

    /** 一颗 ♪ 图标 + 面板：图标可拖、可点开，面板里选分类、点音频直接发。 */
    private fun createAudioWindow() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val icon = TextView(this).apply {
            text = "♪"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61B1B1F"))
                setStroke(dp(1), Color.parseColor("#8030A46C"))
            }
        }
        column.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F7141418"))
            }
            visibility = View.GONE
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(this).apply {
            text = str(R.string.ui_audio); textSize = 13f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(TextView(this).apply {
            text = "×"; textSize = 20f; setTextColor(Color.parseColor("#9A9AA5"))
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { hideAudioOverlay() }
        })
        body.addView(title)

        // ---- 首页 / 本地 切换 ----
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnHome = audioModeButton(str(R.string.ui_audio_home))
        val btnLocal = audioModeButton(str(R.string.ui_audio_local))
        modeRow.addView(btnHome, LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(6) })
        modeRow.addView(btnLocal, LinearLayout.LayoutParams(0, dp(30), 1f))
        body.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---- 首页：热门 / 最近 / 搜索 ----
        val homeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabHot = audioModeButton(str(R.string.ui_audio_hot))
        val tabLiked = audioModeButton(str(R.string.ui_audio_liked))
        val tabSearch = audioModeButton(str(R.string.ui_audio_search))
        tabs.addView(tabHot, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginEnd = dp(4) })
        tabs.addView(tabLiked, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginEnd = dp(4) })
        tabs.addView(tabSearch, LinearLayout.LayoutParams(0, dp(28), 1f))
        homeBox.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val searchInput = android.widget.EditText(this).apply {
            hint = str(R.string.ui_audio_search_hint)
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#70707A"))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#26FFFFFF"))
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    runSearch()
                    true
                } else {
                    false
                }
            }
            // 打完字就把窗口还给系统：一直获焦会抢返回键
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) setAudioFocusable(false) }
        }
        val searchGo = TextView(this).apply {
            text = str(R.string.ui_audio_search_action)
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#8030A46C"))
            }
            setOnClickListener { runSearch() }
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        searchRow.addView(searchGo, LinearLayout.LayoutParams(-2, -2))
        homeBox.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val homeNote = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#9A9AA5"))
            setPadding(0, dp(4), 0, 0)
            maxLines = 2
        }
        homeBox.addView(homeNote)
        body.addView(homeBox)

        // ---- 本地：分类下拉 ----
        val localBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        localBox.addView(rowLabel(str(R.string.ui_audio_category)))
        val spinner = Spinner(this)
        localBox.addView(spinner)
        body.addView(localBox)

        audioModeHome = btnHome
        audioModeLocal = btnLocal
        audioHomeBox = homeBox
        audioLocalBox = localBox
        audioSearchRow = searchRow
        audioSearchInput = searchInput
        audioHomeNote = homeNote
        audioTabHot = tabHot
        audioTabLiked = tabLiked
        audioTabSearch = tabSearch
        btnHome.setOnClickListener { setAudioMode(true) }
        btnLocal.setOnClickListener { setAudioMode(false) }
        tabHot.setOnClickListener { onHomeTab(0) }
        tabLiked.setOnClickListener { onHomeTab(1) }
        tabSearch.setOnClickListener { onHomeTab(2) }
        renderModeButton()

        // 左栏曲目列表、右栏播放控制：加宽压短，别把按钮顶到屏幕外
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        left.addView(scroll, LinearLayout.LayoutParams(-1, dp(238)).apply { topMargin = dp(6) })

        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9A9AA5"))
            setPadding(0, dp(6), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        left.addView(status)
        grid.addView(left, LinearLayout.LayoutParams(0, -2, 1.45f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toggle = Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_audio_stop)
            textSize = 12f
            isEnabled = false
            alpha = 0.38f
            setOnClickListener { AudioDeck.toggle() }
        }
        right.addView(toggle, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })

        // 播放模式：顺序 / 循环 / 随机 三态，点一下换一个
        val modeBtn = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#C9C9D1"))
            setPadding(0, dp(8), 0, dp(6))
            setOnClickListener { cyclePlayMode() }
        }
        audioModeBtn = modeBtn
        right.addView(modeBtn)

        // 音质：自动 / 最高 / 稳定，点一下换一档（在线音源解析时按这个挑码率）
        val qualityBtn = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#C9C9D1"))
            setPadding(0, 0, 0, dp(6))
            setOnClickListener { cycleQuality() }
        }
        audioQualityBtn = qualityBtn
        right.addView(qualityBtn)

        // 返音：素材除了送进麦克风，也从本机出声
        right.addView(Switch(this).apply {
            text = str(R.string.ui_audio_monitor)
            textSize = 12f
            setTextColor(Color.WHITE)
            isChecked = AudioDeck.monitor
            setPadding(0, dp(6), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                AudioDeck.setMonitor(checked)
                prefs.edit().putBoolean("audio_monitor", checked).apply()
                if (checked) warnMonitorOnce()
            }
        })
        val hint = TextView(this).apply {
            text = str(R.string.ui_audio_monitor_speaker)
            textSize = 10f
            setTextColor(amber)
            setPadding(0, dp(4), 0, 0)
            maxLines = 3
            visibility = View.GONE
        }
        monitorHint = hint
        right.addView(hint)

        audioVolume(right)
        monitorVolume(right)
        grid.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        body.addView(grid)

        drag(title, { audioRoot })
        drag(icon, { audioRoot }) {
            audioExpanded = !audioExpanded
            audioPanel?.visibility = if (audioExpanded) View.VISIBLE else View.GONE
            if (audioExpanded) {
                refreshAudioPanel()
            } else {
                hideKeyboard()
                setAudioFocusable(false)
            }
        }

        column.addView(body, LinearLayout.LayoutParams(dp(480), -2).apply { topMargin = dp(8) })

        audioIcon = icon
        audioPanel = body
        audioSpinner = spinner
        audioList = list
        audioState = status
        audioToggle = toggle
        audioRoot = column

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_TOUCH_MODAL 必须显式写上：打字时要临时去掉 NOT_FOCUSABLE，
            // 少了它窗口立刻变成"模态"，整块屏幕的触摸全被它吃掉（2026-10-31 用户报的）。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(210)
        }
        runCatching { wm.addView(column, lp) }.onFailure { audioRoot = null }
    }

    private fun populateAudio() {
        val list = audioList ?: return
        val spin = audioSpinner ?: return
        if (!AudioLibrary.scanned) {
            list.removeAllViews()
            list.addView(hintRow(str(R.string.ui_audio_loading)))
            thread {
                AudioLibrary.scan()
                ui.post { populateAudio() }
            }
            return
        }
        audioCategories = AudioLibrary.categories
        if (audioCategories.isEmpty()) {
            list.removeAllViews()
            list.addView(hintRow(when (AudioLibrary.problem) {
                AudioLibrary.Problem.PERMISSION -> str(R.string.ui_audio_need_permission)
                AudioLibrary.Problem.MISSING -> str(R.string.ui_audio_no_folder, AudioLibrary.root.path)
                else -> str(R.string.ui_audio_none)
            }))
            return
        }
        val saved = prefs.getString("audio_category", null)
        audioSyncing = true
        try {
            val (adapter, index) = simple(audioCategories.map { it.name }, saved)
            spin.adapter = adapter
            spin.setSelection(index, false)
            spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (audioSyncing) return
                    val picked = audioCategories.getOrNull(position) ?: return
                    if (picked.name != prefs.getString("audio_category", null)) {
                        prefs.edit().putString("audio_category", picked.name).apply()
                        AudioDeck.pause()   // 换分类就停下，免得出现「在播但看不到高亮」
                    }
                    fillAudio(picked)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            fillAudio(audioCategories.getOrNull(index))
        } finally {
            audioSyncing = false
        }
    }

    private fun fillAudio(category: AudioLibrary.Category?) {
        val list = audioList ?: return
        val tracks = if (category == null) emptyList() else AudioLibrary.tracks(category)
        audioRows.clear()
        audioHighlight = null
        list.removeAllViews()
        if (tracks.isEmpty()) {
            list.addView(hintRow(str(R.string.ui_audio_none)))
            return
        }
        for (track in tracks) {
            val row = TextView(this).apply {
                text = track.name
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener {
                    if (!VirtualMicSession.isActive) {
                        Toast.makeText(this@OverlayService, str(R.string.ui_audio_need_session), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    AudioDeck.playList(track, tracks)
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
            audioRows.add(track.id to row)
        }
    }

    private fun hintRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.parseColor("#9A9AA5"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    /** 每 300 ms：按钮文案、状态行、当前音频的高亮。 */
    private fun renderAudio() {
        if (audioRoot == null) return
        monitorHint?.visibility =
            if (AudioDeck.monitor && MonitorRoute.onSpeaker(this)) View.VISIBLE else View.GONE
        val state = AudioDeck.state
        val name = AudioDeck.track?.name ?: ""
        audioToggle?.text = if (state == AudioDeck.State.PAUSED) str(R.string.ui_audio_continue)
        else str(R.string.ui_audio_stop)
        val usable = state == AudioDeck.State.PLAYING || state == AudioDeck.State.PAUSED
        audioToggle?.isEnabled = usable
        audioToggle?.alpha = if (usable) 1f else 0.38f
        audioState?.text = when {
            AudioDeck.failure == AudioDeck.Failure.NO_SESSION -> str(R.string.ui_audio_need_session)
            AudioDeck.failure == AudioDeck.Failure.DECODE -> str(R.string.ui_audio_failed, AudioDeck.failureDetail ?: name)
            state == AudioDeck.State.LOADING -> str(R.string.ui_audio_loading)
            state == AudioDeck.State.PLAYING && RootMicController.phase == MicPhase.WAITING -> str(R.string.ui_audio_waiting_mic)
            state == AudioDeck.State.PLAYING -> str(
                R.string.ui_audio_sending, name,
            ) + "  " + clock(AudioDeck.positionMs) + " / " + clock(AudioDeck.durationMs)
            state == AudioDeck.State.PAUSED -> str(R.string.ui_audio_paused, name)
            else -> ""
        }
        // 首页的行按「歌名 - 歌手」高亮（网络曲目没有本地文件 id）；本地按 file id
        val want = if (state == AudioDeck.State.IDLE) null
        else if (homeMode) AudioDeck.track?.name else AudioDeck.track?.id
        if (want != audioHighlight) {
            audioHighlight = want
            val on = rowOn ?: GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#332FA46C"))
            }.also { rowOn = it }
            for ((id, view) in audioRows) view.background = if (id == want) on else null
        }
    }

    private fun audioVolume(parent: LinearLayout) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        head.addView(TextView(this).apply {
            text = str(R.string.ui_audio_volume); textSize = 11f; setTextColor(Color.parseColor("#C9C9D1"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply { textSize = 11f; setTextColor(Color.WHITE) }
        head.addView(value)
        parent.addView(head)
        val min = -24.0
        val max = 12.0
        fun label(v: Float) = String.format(Locale.US, "%+.0f dB", v)
        val bar = SeekBar(this).apply {
            this.max = (max - min).toInt()
            progress = (AudioDeck.gainDb - min).roundToInt().coerceIn(0, this.max)
            setPadding(dp(2), 0, dp(2), 0)
            value.text = label(AudioDeck.gainDb)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = label((min + p).toFloat())
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val db = (min + (sb?.progress ?: 0)).toFloat()
                    AudioDeck.setGain(db)
                    prefs.edit().putFloat("audio_gain_db", db).apply()
                }
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(28)))
    }

    /** 返音音量：只影响自己听到的那一路，和发给队友的音量分开。 */
    private fun monitorVolume(parent: LinearLayout) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        head.addView(TextView(this).apply {
            text = str(R.string.ui_audio_monitor_volume); textSize = 11f; setTextColor(Color.parseColor("#C9C9D1"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply { textSize = 11f; setTextColor(Color.WHITE) }
        head.addView(value)
        parent.addView(head)
        val min = -24.0
        val max = 12.0
        fun label(v: Float) = String.format(Locale.US, "%+.0f dB", v)
        val bar = SeekBar(this).apply {
            this.max = (max - min).toInt()
            progress = (AudioDeck.monitorGainDb - min).roundToInt().coerceIn(0, this.max)
            setPadding(dp(2), 0, dp(2), 0)
            value.text = label(AudioDeck.monitorGainDb)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = label((min + p).toFloat())
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val db = (min + (sb?.progress ?: 0)).toFloat()
                    AudioDeck.setMonitorGain(db)
                    prefs.edit().putFloat("audio_monitor_gain_db", db).apply()
                }
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(28)))
    }

    /** 外放时提醒一次：音乐会从麦克风串回游戏。戴耳机不提醒。 */
    private fun warnMonitorOnce() {
        if (!MonitorRoute.onSpeaker(this)) return
        if (prefs.getBoolean(MonitorRoute.WARNED, false)) return
        prefs.edit().putBoolean(MonitorRoute.WARNED, true).apply()
        Toast.makeText(this, str(R.string.ui_audio_monitor_speaker), Toast.LENGTH_LONG).show()
    }

    /* ---------------------------------------------------------------- 首页（网络音乐） */

    private fun audioModeButton(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#C9C9D1"))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#26FFFFFF"))
        }
    }

    private fun paintMode() {
        val on = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#8030A46C"))
        }
        val off = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#26FFFFFF"))
        }
        audioModeHome?.background = if (homeMode) on else off
        audioLocalBox?.visibility = if (homeMode) View.GONE else View.VISIBLE
        audioHomeBox?.visibility = if (homeMode) View.VISIBLE else View.GONE
        val list = listOf(audioTabHot, audioTabLiked, audioTabSearch)
        for ((i, t) in list.withIndex()) {
            t?.background = if (i == homeTab) on else off
            t?.setTextColor(if (i == homeTab) Color.WHITE else Color.parseColor("#C9C9D1"))
        }
    }

    private fun refreshAudioPanel() {
        paintMode()
        // 这两行原来只在构造时调过一次（那时按钮还没创建），所以打开面板时按钮是空文本
        renderModeButton()
        renderQualityButton()
        // 音源数量在 init 回调里就绪，但那时面板可能还没建，这里补一次
        if (audioHomeNote?.text.isNullOrBlank()) {
            audioHomeNote?.text =
                if (LxMusic.isReady) str(R.string.ui_audio_sources_ok, LxMusic.scriptCount())
                else str(R.string.ui_audio_sources_failed)
        }
        if (homeMode) loadHome() else populateAudio()
    }

    private fun setAudioMode(home: Boolean) {
        if (homeMode == home) { refreshAudioPanel(); return }
        homeMode = home
        if (!home) {
            // 离开搜索页/首页时把焦点还给游戏
            hideKeyboard()
            setAudioFocusable(false)
        }
        refreshAudioPanel()
    }

    /** 点页签：热门连点两次 = 刷新。 */
    private fun onHomeTab(tab: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val refresh = tab == 0 && homeTab == 0 && now - lastHotTap < 400
        lastHotTap = if (tab == 0) now else 0L
        setHomeTab(tab, refresh)
    }

    private fun setHomeTab(tab: Int, forceRefresh: Boolean = false) {
        homeTab = tab
        audioSearchRow?.visibility = if (tab == 2) View.VISIBLE else View.GONE
        paintMode()
        if (tab == 2) {
            // 只有打字这一幕需要键盘：临时去掉 FLAG_NOT_FOCUSABLE，
            // 否则系统不会把软键盘接到悬浮窗上（原来「打不了字」就是这个原因）。
            setAudioFocusable(true)
            audioSearchInput?.post { showKeyboard(audioSearchInput) }
        } else {
            hideKeyboard()
            setAudioFocusable(false)
            loadHome(forceRefresh)
        }
    }

    private fun loadHome(forceRefresh: Boolean = false) {
        val list = audioList ?: return
        if (!LxMusic.isReady) {
            list.removeAllViews()
            list.addView(hintRow(str(R.string.ui_audio_sources_failed)))
            return
        }
        when (homeTab) {
            1 -> {
                homeSongs = LxMusic.liked()
                fillHomeList(homeSongs, str(R.string.ui_audio_liked_empty))
            }
            2 -> {
                // 搜索页：显示上一次的搜索结果，等用户再点「搜」
                if (searchSongs.isNotEmpty()) {
                    homeSongs = searchSongs
                    fillHomeList(searchSongs, str(R.string.ui_audio_search_empty))
                } else {
                    list.removeAllViews()
                    list.addView(hintRow(str(R.string.ui_audio_search_hint)))
                }
            }
            else -> {
                if (forceRefresh) hotSongs = emptyList()
                if (hotSongs.isNotEmpty() && !homeBusy) {
                    homeSongs = hotSongs
                    fillHomeList(hotSongs, null)
                    return
                }
                if (homeBusy) return
                homeBusy = true
                list.removeAllViews()
                list.addView(hintRow(str(R.string.ui_audio_hot_loading)))
                LxMusic.hot(forceRefresh) { songs ->
                    homeBusy = false
                    hotSongs = songs
                    ui.post {
                        if (homeTab == 0) {
                            homeSongs = songs
                            fillHomeList(songs, str(R.string.ui_audio_search_empty))
                        }
                    }
                }
            }
        }
    }

    private fun modeNameRes(m: AudioDeck.Mode): Int = when (m) {
        AudioDeck.Mode.SEQUENCE -> R.string.ui_audio_mode_sequence
        AudioDeck.Mode.LOOP_ONE -> R.string.ui_audio_mode_loop
        AudioDeck.Mode.SHUFFLE -> R.string.ui_audio_mode_shuffle
    }

    private fun renderModeButton() {
        audioModeBtn?.text = str(R.string.ui_audio_mode, str(modeNameRes(AudioDeck.mode)))
    }

    /** 顺序 → 循环 → 随机 → 顺序。 */
    private fun cyclePlayMode() {
        val next = when (AudioDeck.mode) {
            AudioDeck.Mode.LOOP_ONE -> AudioDeck.Mode.SHUFFLE
            AudioDeck.Mode.SHUFFLE -> AudioDeck.Mode.SEQUENCE
            AudioDeck.Mode.SEQUENCE -> AudioDeck.Mode.LOOP_ONE
        }
        AudioDeck.setMode(next)
        prefs.edit().putString("audio_mode", next.name).apply()
        renderModeButton()
    }

    /** 音质三档：自动 → 最高 → 稳定 → 自动。下一首解析时生效。 */
    private fun cycleQuality() {
        val next = when (LxMusic.qualityMode) {
            LxMusic.QualityMode.AUTO -> LxMusic.QualityMode.HIGHEST
            LxMusic.QualityMode.HIGHEST -> LxMusic.QualityMode.STABLE
            LxMusic.QualityMode.STABLE -> LxMusic.QualityMode.AUTO
        }
        LxMusic.setQualityMode(next)
        renderQualityButton()
    }

    private fun renderQualityButton() {
        audioQualityBtn?.text = str(R.string.ui_audio_quality, str(qualityNameRes(LxMusic.qualityMode)))
    }

    private fun qualityNameRes(q: LxMusic.QualityMode): Int = when (q) {
        LxMusic.QualityMode.AUTO -> R.string.ui_audio_quality_auto
        LxMusic.QualityMode.HIGHEST -> R.string.ui_audio_quality_highest
        LxMusic.QualityMode.STABLE -> R.string.ui_audio_quality_stable
    }

    private fun runSearch() {
        val kw = audioSearchInput?.text?.toString()?.trim().orEmpty()
        val list = audioList ?: return
        if (kw.isEmpty()) return
        if (!LxMusic.isReady) {
            list.removeAllViews()
            list.addView(hintRow(str(R.string.ui_audio_sources_failed)))
            return
        }
        list.removeAllViews()
        list.addView(hintRow(str(R.string.ui_audio_searching)))
        hideKeyboard()
        LxMusic.search(kw) { songs ->
            searchSongs = songs
            ui.post {
                if (homeTab == 2) {
                    homeSongs = songs
                    fillHomeList(songs, str(R.string.ui_audio_search_empty))
                }
            }
        }
    }

    private fun fillHomeList(songs: List<LxMusic.Song>, empty: String?) {
        val list = audioList ?: return
        audioRows.clear()
        audioHighlight = null
        list.removeAllViews()
        if (songs.isEmpty()) {
            list.addView(hintRow(empty ?: str(R.string.ui_audio_none)))
            return
        }
        for ((i, song) in songs.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = song.display
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener {
                    if (!VirtualMicSession.isActive) {
                        Toast.makeText(
                            this@OverlayService,
                            str(R.string.ui_audio_need_session),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnClickListener
                    }
                    audioHomeNote?.text = str(R.string.ui_audio_resolving)
                    // 搜索页：播完就停，不循环也不随机
                    val oneShot = homeTab == 2
                    LxMusic.play(song, songs, i, oneShot) { msg ->
                        ui.post { audioHomeNote?.text = msg }
                    }
                }
            }
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

            // 爱心：点亮就加进「喜欢」，再点取消
            val heart = TextView(this).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(12), dp(6))
            }
            heart.setOnClickListener {
                val on = LxMusic.toggleLike(song)
                paintHeart(heart, on)
                // 在「喜欢」页上取消点亮，直接把这行移出列表
                if (homeTab == 1 && !on) loadHome()
            }
            paintHeart(heart, LxMusic.isLiked(song))
            row.addView(heart, LinearLayout.LayoutParams(-2, -2))

            list.addView(row, LinearLayout.LayoutParams(-1, -2))
            audioRows.add(song.display to row)
        }
    }

    /** 点亮 = 红色实心 ♥；未点亮 = 灰色空心 ♡。 */
    private fun paintHeart(v: TextView, on: Boolean) {
        v.text = if (on) "♥" else "♡"
        v.setTextColor(if (on) Color.parseColor("#E5484D") else Color.parseColor("#70707A"))
    }

    /** 悬浮窗默认 FLAG_NOT_FOCUSABLE，系统不会接软键盘；打字时才临时允许获焦。 */
    private fun setAudioFocusable(on: Boolean) {
        val root = audioRoot ?: return
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val want = if (on) lp.flags and flag.inv() else lp.flags or flag
        if (want == lp.flags) return
        lp.flags = want
        runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun showKeyboard(v: View?) {
        if (v == null) return
        v.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val v = audioSearchInput ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun clock(ms: Long): String =
        String.format(Locale.US, "%d:%02d", ms / 60_000, (ms / 1000) % 60)
}
