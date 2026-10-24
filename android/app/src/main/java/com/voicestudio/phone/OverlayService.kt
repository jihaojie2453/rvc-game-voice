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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
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

    private val prefs by lazy { getSharedPreferences("phone", Context.MODE_PRIVATE) }
    private val modelsDir by lazy { File(getExternalFilesDir(null), "models") }

    private val red = Color.parseColor("#E5484D")
    private val green = Color.parseColor("#30A46C")

    private val tick = object : Runnable {
        override fun run() {
            renderState()
            ui.postDelayed(this, 300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addOverlay()
        ui.post(tick)
        visible = true
    }

    override fun onDestroy() {
        visible = false
        ui.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
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
        drag(head) { toggle() }
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

        body.addView(rowLabel(str(R.string.ui_overlay_voice)))
        voiceSpinner = Spinner(this).also { body.addView(it) }
        body.addView(rowLabel(str(R.string.ui_overlay_profile)))
        profileSpinner = Spinner(this).also { body.addView(it) }

        switching = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#FFB020")); setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        body.addView(switching)

        // 五个常用参数
        slider(body, str(R.string.ui_output_gain), -12.0, 24.0, 1.0, "live_gain_db") { v ->
            setParam(VoiceProfiles.GROUP_ENGINE, "live_gain_db", v)
        }
        slider(body, str(R.string.ui_input_gain), 0.0, 24.0, 1.0, "live_input_gain_db") { v ->
            setParam(VoiceProfiles.GROUP_ENGINE, "live_input_gain_db", v)
        }
        slider(body, str(R.string.ui_high_pitch_protection_strength), 1.0, 3.0, 0.1, "high_pitch_ratio") { v ->
            setParam(VoiceProfiles.GROUP_VOICE, "high_pitch_ratio", v)
        }
        slider(body, str(R.string.ui_formant_voice_texture), -2.0, 2.0, 0.1, "formant") { v ->
            setParam(VoiceProfiles.GROUP_VOICE, "formant", v)
        }
        slider(body, str(R.string.ui_silence_threshold), -80.0, -30.0, 1.0, "live_threshold_db") { v ->
            setParam(VoiceProfiles.GROUP_ENGINE, "live_threshold_db", v)
        }

        val quit = Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_overlay_quit); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) }
            setOnClickListener { quitEverything() }
        }
        body.addView(quit)
        drag(title)

        column.addView(body, LinearLayout.LayoutParams(dp(268), -2).apply { topMargin = dp(8) })
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
    private fun drag(handle: View, onClick: (() -> Unit)? = null) {
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        var moved = false
        handle.setOnTouchListener { v, e ->
            val lp = root?.layoutParams as? WindowManager.LayoutParams
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
                    if (moved && lp != null) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(root, lp) }
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
            // 会话已经结束：悬浮窗自己退场
            stopSelf()
            return
        }
        val phase = RootMicController.phase
        val live = phase == MicPhase.RUNNING
        dot?.background = circle(if (live) green else red, dp(20))
        (collapsed?.getChildAt(1) as? TextView)?.text = when {
            switching?.visibility == View.VISIBLE -> str(R.string.ui_overlay_switching)
            live -> str(R.string.ui_overlay_live)
            phase == MicPhase.WAITING -> str(R.string.ui_overlay_waiting)
            phase == MicPhase.BUFFERING -> str(R.string.ui_preparing_audio)
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
        switching?.apply { text = str(R.string.ui_overlay_switching); visibility = View.VISIBLE }
        // 换音色必须重建引擎：先停会话，等桥接退出后再用新模型拉起
        RootMicController.stop()
        ui.postDelayed({
            RootMicController.start(applicationContext, pkg, model)
            ui.postDelayed({ switching?.visibility = View.GONE }, 1200)
        }, 1500)
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
}
