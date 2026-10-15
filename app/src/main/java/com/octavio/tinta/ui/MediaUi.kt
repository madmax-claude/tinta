package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.octavio.tinta.R
import com.octavio.tinta.canvas.WidgetPainter
import com.octavio.tinta.dp
import com.octavio.tinta.tip
import java.io.File

/**
 * Barrita que flota arriba de la hoja mientras se graba una nota de voz: punto rojo, tiempo,
 * terminar y descartar. Graba AAC en `file`; al terminar llama a `onDone` con la duración.
 */
@SuppressLint("ViewConstructor")
class RecorderBar(context: Context) : LinearLayout(context) {
    var onDone: ((File, Long) -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val time = TextView(context)
    private val tick = object : Runnable {
        override fun run() {
            time.text = WidgetPainter.duration(SystemClock.uptimeMillis() - startedAt)
            postDelayed(this, 250)
        }
    }

    val recording get() = recorder != null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.getDrawable(R.drawable.bg_toolbar)
        elevation = context.dp(8f)
        val p = context.dp(6f).toInt()
        setPadding(p * 2, p, p, p)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFFD93025.toInt())
        }, LayoutParams(context.dp(22f).toInt(), context.dp(22f).toInt()))
        addView(TextView(context).apply {
            text = "Grabando"
            setTextColor(context.getColor(R.color.ink))
            textSize = 15f
            setPadding(p, 0, p, 0)
        })
        time.setTextColor(context.getColor(R.color.muted))
        time.textSize = 15f
        addView(time)
        addView(button(R.drawable.ic_stop, "Terminar y pegar") { stop() })
        addView(button(R.drawable.ic_close, "Descartar") { cancel() })
        visibility = GONE
    }

    private fun button(icon: Int, description: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        background = context.getDrawable(R.drawable.bg_tool)
        contentDescription = description
        tip(description)
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(context.dp(44f).toInt(), context.dp(44f).toInt()).apply { marginStart = context.dp(6f).toInt() }
    }

    /** Empieza a grabar en `target`. Devuelve false si el micrófono no anduvo. */
    fun start(target: File): Boolean {
        if (recorder != null) return false
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(target.path)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = SystemClock.uptimeMillis()
            visibility = VISIBLE
            post(tick)
            true
        }.getOrElse {
            r.release()
            target.delete()
            false
        }
    }

    /** Termina; si duró casi nada (o falló) se descarta. */
    fun stop() {
        val r = recorder ?: return
        val f = file
        val ms = SystemClock.uptimeMillis() - startedAt
        val ok = runCatching { r.stop() }.isSuccess
        finish(r)
        if (ok && f != null && ms > 400) onDone?.invoke(f, ms) else f?.delete()
    }

    fun cancel() {
        val r = recorder ?: return
        runCatching { r.stop() }
        finish(r)
        file?.delete()
    }

    private fun finish(r: MediaRecorder) {
        r.release()
        recorder = null
        removeCallbacks(tick)
        visibility = GONE
    }
}

/** Reproductor de una nota de voz, abajo de la hoja: reproducir o pausar, barra y cerrar. */
@SuppressLint("ViewConstructor")
class AudioPlayerBar(context: Context) : LinearLayout(context) {
    private var player: MediaPlayer? = null
    private val play = ImageButton(context)
    private val seek = SeekBar(context)
    private val time = TextView(context)
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                seek.progress = p.currentPosition
                time.text = "${WidgetPainter.duration(p.currentPosition.toLong())} / ${WidgetPainter.duration(p.duration.toLong())}"
            }
            postDelayed(this, 200)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.getDrawable(R.drawable.bg_toolbar)
        elevation = context.dp(8f)
        val p = context.dp(6f).toInt()
        setPadding(p, p, p, p)
        play.background = context.getDrawable(R.drawable.bg_tool)
        play.setOnClickListener { toggle() }
        addView(play, LayoutParams(context.dp(44f).toInt(), context.dp(44f).toInt()))
        addView(seek, LayoutParams(context.dp(220f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        time.setTextColor(context.getColor(R.color.muted))
        time.textSize = 13f
        addView(time)
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            background = context.getDrawable(R.drawable.bg_tool)
            contentDescription = "Cerrar"
            setOnClickListener { close() }
        }, LayoutParams(context.dp(44f).toInt(), context.dp(44f).toInt()))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        visibility = GONE
    }

    fun play(file: File) {
        close()
        val p = runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
            }
        }.getOrNull() ?: return
        player = p
        seek.max = p.duration
        seek.progress = 0
        time.text = "0:00 / ${WidgetPainter.duration(p.duration.toLong())}"
        p.setOnCompletionListener {
            play.setImageResource(R.drawable.ic_play)
            seek.progress = 0
        }
        visibility = VISIBLE
        p.start()
        play.setImageResource(R.drawable.ic_pause)
        post(tick)
    }

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            play.setImageResource(R.drawable.ic_play)
        } else {
            p.start()
            play.setImageResource(R.drawable.ic_pause)
        }
    }

    fun close() {
        player?.release()
        player = null
        removeCallbacks(tick)
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        close()
    }
}

/** Un video a pantalla casi completa, con los controles de Android. */
object VideoPlayer {
    fun show(activity: Activity, file: File) {
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val box = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val video = VideoView(activity)
        box.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        box.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            background = ColorDrawable(0x66000000)
            contentDescription = "Cerrar"
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(activity.dp(48f).toInt(), activity.dp(48f).toInt(), Gravity.TOP or Gravity.END).apply {
            setMargins(0, activity.dp(24f).toInt(), activity.dp(16f).toInt(), 0)
        })
        dialog.setContentView(box)
        val controls = MediaController(activity)
        controls.setAnchorView(video)
        video.setMediaController(controls)
        video.setVideoPath(file.path)
        video.setOnPreparedListener { video.start() }
        dialog.setOnDismissListener { video.stopPlayback() }
        dialog.show()
    }
}
