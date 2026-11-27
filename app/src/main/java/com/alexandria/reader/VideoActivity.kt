package com.alexandria.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView

/**
 * Player das abas de filmes e cursos: o vídeo toca enquanto é baixado do grupo do Telegram (por
 * partes, pelo motor da nuvem), com o player do próprio Android. O ponto onde parou vai para o grupo
 * da aba, para continuar no computador ou em outro aparelho.
 */
class VideoActivity : Activity(), SurfaceHolder.Callback {
    private var player: MediaPlayer? = null
    private var holder: SurfaceHolder? = null
    private var prepared = false
    private var closed = false
    private var chat: CloudChat? = null
    private var tabId = AppTabs.MAIN
    private var messageId = 0
    private var info: TelegramCloud.VideoInfo? = null
    private var resumeSeconds = -1L
    private var lastSaved = -1L

    private lateinit var surface: SurfaceView
    private lateinit var controls: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var title: TextView
    private lateinit var playButton: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var message: TextView
    private lateinit var snack: TextView

    private val main = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { showControls(false) }
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            save(false)
            main.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        tabId = intent.getStringExtra(EXTRA_TAB) ?: AppTabs.MAIN
        messageId = intent.getIntExtra(EXTRA_MESSAGE, 0)
        chat = AppTabs.byId(this, tabId).chat

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        surface.holder.addCallback(this)
        root.setOnClickListener { showControls(controls.visibility != View.VISIBLE) }

        spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        message = text(15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            visibility = View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        top.addView(text(22f).apply {
            text = "←"
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "Voltar"
            setOnClickListener { finish() }
        })
        title = text(16f, bold = true).apply {
            text = intent.getStringExtra(EXTRA_NAME)?.substringBeforeLast('.') ?: "Vídeo"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(16), dp(10))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        playButton = text(24f).apply {
            text = "❚❚"
            gravity = Gravity.CENTER
            minWidth = dp(52)
            minHeight = dp(48)
            contentDescription = "Pausar"
            setOnClickListener { togglePlay() }
        }
        controls.addView(playButton)
        elapsed = text(12f).apply { text = "0:00" }
        controls.addView(elapsed)
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = VideoProgress.clock(value / 1000L)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = main.removeCallbacks(hideControls)
                override fun onStopTrackingTouch(bar: SeekBar) {
                    player?.takeIf { prepared }?.seekTo(bar.progress)
                    spinner.visibility = View.VISIBLE
                    scheduleHide()
                }
            })
        }
        controls.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f))
        total = text(12f).apply { text = "--:--" }
        controls.addView(total)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        snack = text(14f).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(230, 24, 28, 34))
            }
            visibility = View.GONE
            setOnClickListener {
                player?.takeIf { prepared }?.seekTo(0)
                visibility = View.GONE
            }
        }
        root.addView(snack, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16)
            bottomMargin = dp(84)
        })
        setContentView(root)

        val group = chat
        if (group == null || messageId == 0) return fail("Esta aba não tem grupo.")
        TelegramCloud.videoOpen(this, group, messageId) { result ->
            if (closed) return@videoOpen
            result.onSuccess { opened ->
                info = opened
                title.text = opened.name.substringBeforeLast('.')
                // Onde parou (no grupo da aba); o player espera essa resposta só um pouco.
                TelegramCloud.pullPosition(this, group, SyncData.key(opened.keyName, opened.size)) { entry ->
                    if (closed) return@pullPosition
                    resumeSeconds = entry?.position?.toLong() ?: 0L
                    maybeResume()
                }
                main.postDelayed({ if (resumeSeconds < 0) resumeSeconds = 0 }, 8000)
                start(group, opened)
            }.onFailure { fail(it.message ?: "Não foi possível abrir o vídeo.") }
        }
    }

    private fun start(group: CloudChat, opened: TelegramCloud.VideoInfo) {
        val cache = BlockCache(opened.size, { index, done ->
            TelegramCloud.videoRange(applicationContext, group, messageId, index.toLong() * BlockCache.BLOCK, BlockCache.BLOCK, done)
        })
        val mp = MediaPlayer()
        player = mp
        mp.setOnPreparedListener {
            prepared = true
            spinner.visibility = View.GONE
            seek.max = mp.duration
            total.text = VideoProgress.clock(mp.duration / 1000L)
            fitSurface(mp.videoWidth, mp.videoHeight)
            mp.start()
            playButton.text = "❚❚"
            main.post(tick)
            scheduleHide()
            maybeResume()
        }
        mp.setOnSeekCompleteListener { spinner.visibility = View.GONE }
        mp.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> spinner.visibility = View.VISIBLE
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> spinner.visibility = View.GONE
            }
            false
        }
        mp.setOnVideoSizeChangedListener { _, w, h -> fitSurface(w, h) }
        mp.setOnCompletionListener {
            playButton.text = "▶"
            save(true)
            showControls(true)
        }
        mp.setOnErrorListener { _, _, _ ->
            fail("Este aparelho não conseguiu tocar este vídeo (formato ${opened.mime.removePrefix("video/").uppercase().ifEmpty { "desconhecido" }}). Tente enviar o vídeo em MP4.")
            true
        }
        try {
            mp.setDataSource(TelegramDataSource(cache))
            holder?.let { mp.setDisplay(it) }
            mp.prepareAsync()
        } catch (e: Exception) {
            fail("Não foi possível abrir o vídeo: ${e.message}")
        }
    }

    /** Continua de onde parou, quando o player e a posição salva estiverem prontos. */
    private fun maybeResume() {
        val mp = player ?: return
        if (!prepared || resumeSeconds < 0) return
        val at = VideoProgress.resumeAt(resumeSeconds, mp.duration / 1000L)
        resumeSeconds = -2L
        if (at <= 0) return
        mp.seekTo((at * 1000).toInt())
        lastSaved = at
        snack.text = "Continuando de ${VideoProgress.clock(at)}  ·  VOLTAR AO INÍCIO"
        snack.visibility = View.VISIBLE
        main.postDelayed({ snack.visibility = View.GONE }, 8000)
    }

    private fun fitSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val root = surface.parent as View
        root.post {
            val w = root.width.toFloat()
            val h = root.height.toFloat()
            val scale = minOf(w / width, h / height)
            surface.layoutParams = FrameLayout.LayoutParams((width * scale).toInt(), (height * scale).toInt(), Gravity.CENTER)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        player?.takeIf { prepared }?.let { fitSurface(it.videoWidth, it.videoHeight) }
    }

    private fun togglePlay() {
        val mp = player?.takeIf { prepared } ?: return
        if (mp.isPlaying) {
            mp.pause()
            playButton.text = "▶"
            playButton.contentDescription = "Tocar"
            save(true)
        } else {
            mp.start()
            playButton.text = "❚❚"
            playButton.contentDescription = "Pausar"
        }
        scheduleHide()
    }

    private fun updateTime() {
        val mp = player?.takeIf { prepared } ?: return
        seek.progress = mp.currentPosition
        elapsed.text = VideoProgress.clock(mp.currentPosition / 1000L)
    }

    /** Grava onde parou (a cada 15 s tocando, ao pausar, ao sair e no fim). */
    private fun save(force: Boolean) {
        val mp = player?.takeIf { prepared } ?: return
        val group = chat ?: return
        val opened = info ?: return
        val at = mp.currentPosition / 1000L
        if (at == lastSaved || (!force && kotlin.math.abs(at - lastSaved) < 15)) return
        lastSaved = at
        val entry = SyncEntry("video", at.toInt(), mp.currentPosition.toLong(), mp.duration / 1000, System.currentTimeMillis(), PositionSync.device())
        TelegramCloud.pushPosition(this, group, SyncData.key(opened.keyName, opened.size), entry)
    }

    private fun showControls(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        controls.visibility = v
        top.visibility = v
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideControls)
        if (player?.isPlaying == true) main.postDelayed(hideControls, 4000)
    }

    private fun fail(text: String) {
        spinner.visibility = View.GONE
        message.text = text
        message.visibility = View.VISIBLE
        showControls(true)
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        holder = h
        player?.setDisplay(h)
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(h: SurfaceHolder) {
        holder = null
        player?.setDisplay(null)
    }

    override fun onPause() {
        super.onPause()
        save(true)
        player?.takeIf { prepared && it.isPlaying }?.let {
            it.pause()
            playButton.text = "▶"
        }
    }

    override fun onDestroy() {
        closed = true
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_NAME = "name"

        fun open(context: Context, tab: String, video: CloudVideo) {
            context.startActivity(
                Intent(context, VideoActivity::class.java).putExtra(EXTRA_TAB, tab).putExtra(EXTRA_MESSAGE, video.messageId).putExtra(EXTRA_NAME, video.name)
            )
        }
    }
}
