package com.octavio.tinta.canvas

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.util.AttributeSet
import android.view.FrameMetrics
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import com.octavio.tinta.Diag
import com.octavio.tinta.R
import com.octavio.tinta.model.Appearance
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.Clipboard
import com.octavio.tinta.model.Cut
import com.octavio.tinta.model.History
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Graph2D
import com.octavio.tinta.model.Graph3D
import com.octavio.tinta.model.Lasso
import com.octavio.tinta.model.LassoFilter
import com.octavio.tinta.model.Media
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.Pen
import com.octavio.tinta.model.Shapes
import com.octavio.tinta.model.Solid
import com.octavio.tinta.model.Tool
import com.octavio.tinta.model.Transform
import com.octavio.tinta.model.Viewport
import com.octavio.tinta.model.Widget
import com.octavio.tinta.model.Work
import com.octavio.tinta.storage.NoteStore
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lienzo infinito, o de hojas una debajo de otra. El lápiz (y el mouse, útil en el emulador)
 * escribe; los dedos mueven y hacen zoom. Los trazos en curso los dibuja [InProgressStrokesView]
 * con baja latencia; al terminar pasan a [DryLayer], primero como trazos "frescos" dibujados uno por
 * uno y, cuando el lápiz descansa, a las baldosas de [Tiles] (repintarlas cuesta varios ms y no
 * conviene hacerlo entre trazo y trazo).
 *
 * Con el lápiz quieto: al empezar, después de un segundo pasa a borrar; al final de un trazo, el
 * trazo se cambia por la figura que se quiso hacer ([Shapes]), que sigue al lápiz hasta levantarlo.
 *
 * Con el lazo se rodea lo que se quiere elegir; lo elegido se mueve arrastrándolo y se agranda con
 * la manija de la esquina. Mientras se arrastra, lo elegido sale de la nota y se dibuja aparte,
 * grabado una vez en un [RenderNode]; al soltar, se arman las copias movidas en segundo plano.
 */
class NoteCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    enum class Mode { PEN, ERASER, LASSO, LASER }

    interface Listener {
        fun onStrokesAdded(strokes: List<InkStroke>)

        /** Trazos que el borrador ya sacó de [strokes], con su índice en el momento de sacarlos. */
        fun onStrokesErased(removed: List<Pair<Int, InkStroke>>)

        /** Un cambio que el lienzo ya hizo en las listas de la nota (mover o borrar lo elegido). */
        fun onEdited(edit: History.Edit)

        fun onViewportChanged()

        /** Cambió lo elegido con el lazo, o su lugar en la pantalla. */
        fun onSelectionChanged() = Unit

        /** Un clic del botón lateral con el lápiz en el aire, sin tocar la pantalla. */
        fun onStylusButtonClick() = Unit

        /** Tocaron un audio o un video: hay que reproducirlo. */
        fun onMediaTap(media: Media) = Unit

        /** Cambiaron las hojas desde el lienzo (agregar o borrar una, o deshacerlo). */
        fun onPagesChanged(pages: PageLayout) = Unit

        /** Cambió cuántas hojas se muestran (se escribió en la última, se agregó una…). */
        fun onPageCountChanged(count: Int) = Unit

        /** Se prendió, apagó o movió la lupa (para ubicar sus botones). */
        fun onZoomChanged() = Unit

        /**
         * Con el lazo, se mantuvo apretado (lápiz o dedo) en un lugar vacío: (sx, sy) en la
         * pantalla del lienzo y (wx, wy) en el mundo, para ofrecer pegar ahí.
         */
        fun onLassoLongPress(sx: Float, sy: Float, wx: Float, wy: Float) = Unit
    }

    val camera = Camera(resources.displayMetrics.density)
    var listener: Listener? = null

    /** La lista de la nota abierta; el borrador y el lazo la modifican directamente. */
    var strokes: MutableList<InkStroke> = mutableListOf()
        set(value) {
            field = value
            fresh.clear()
            selection = null
            dry.reset()
        }

    /** Imágenes y textos de la nota; el lazo también los mueve. */
    var images: MutableList<NoteImage> = mutableListOf()
        set(value) {
            field = value
            elementsChanged()
        }
    var texts: MutableList<NoteText> = mutableListOf()
        set(value) {
            field = value
            elementsChanged()
        }

    /** Gráficos, figuras, audios y videos de la nota. */
    var widgets: MutableList<Widget> = mutableListOf()
        set(value) {
            field = value
            elementsChanged()
        }

    /** La carpeta de medios de la nota: lo que se pega de otra nota se copia acá. */
    var mediaDir: File? = null

    var paperStyle = Background.GRID
        set(value) {
            field = value
            elementsChanged()
        }

    /** Hojas de la nota; sin tamaño, la nota es infinita. */
    var pages = PageLayout()
        set(value) {
            if (field == value) return
            field = value
            dry.contentChanged()
            elementsChanged()
        }

    /** Hojas que se muestran ahora: hasta la última escrita y una en blanco. */
    val pageCount get() = dry.pageCount

    var dark = false
        set(value) {
            if (field == value) return
            field = value
            dry.darkChanged()
        }

    /** Lápiz elegido en la barra. */
    var pen = Pen.DEFAULTS[0]

    /** Qué hace el lápiz: escribir, borrar o elegir con el lazo. */
    var mode = Mode.PEN
        set(value) {
            if (field == value) return
            if (field == Mode.LASSO) clearSelection()
            if (field == Mode.LASER) laser.clear()
            field = value
            hover.let { setCursor(if (it != null && wantsEraserNow()) PointF(it.x, it.y) else null) }
        }

    /** Radio del borrador en dp de pantalla. */
    var eraserRadius = 12f

    /** El borrador saca sólo la parte del trazo que toca, en vez del trazo entero. */
    var partialErase = false

    /** Mantener el lápiz quieto al apoyarlo pasa a borrar. */
    var holdToErase = true

    /** Mantener el lápiz quieto al final de un trazo lo cambia por la figura más parecida. */
    var holdForShapes = true

    var lassoFilter = LassoFilter()


    private val density = resources.displayMetrics.density
    private val dry = DryLayer(context)
    private val wet = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val scratch = Matrix()
    /** Con qué lápiz se empezó cada trazo en curso (su color real, no el de pantalla). */
    private val strokePens = HashMap<InProgressStrokeId, Pen>()

    /** Trazos terminados que todavía no están en las baldosas: siempre los últimos de [strokes]. */
    private val fresh = ArrayList<InkStroke>()
    private val merge = Runnable { mergeFresh() }

    private var penPointer = NONE
    private var penDownAt = 0L

    /** El dedo que puede ser un toque, con dónde y cuándo se apoyó. */
    private var tapPointer = NONE
    private var tapX = 0f
    private var tapY = 0f
    private var tapAt = 0L
    private var penStroke: InProgressStrokeId? = null
    private var erasing = false
    private val erased = ArrayList<Pair<Int, InkStroke>>()
    private var eraseX = 0f
    private var eraseY = 0f

    /** Línea recta en curso (resaltador recto), en el mundo, con su vista previa. */
    private var lineStart: PointF? = null
    private var linePreview: InkStroke? = null

    /** Dedos apoyados que mueven la vista, con su última posición en pantalla. */
    private val fingers = LinkedHashMap<Int, PointF>()

    /** Última vez que se vio el lápiz apoyado o cerca de la pantalla, para rechazar la palma. */
    private var lastPenSeen = 0L

    /** Dónde está el lápiz sin apoyar, o null si no está cerca. */
    private var hover: PointF? = null

    /** Posición del cursor del borrador en pantalla, o null si no se muestra. */
    private var cursor: PointF? = null

    init {
        addView(dry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                val added = strokes.map { (id, s) ->
                    val p = strokePens.remove(id) ?: pen
                    // En modo oscuro el trazo en curso usa el color de pantalla; se guarda el real.
                    val color = p.tool.storedColor(p.color)
                    InkStroke(p.tool, if (s.brush.colorIntArgb == color) s else s.copy(s.brush.copyWithColorIntArgb(color)), p.sensitivity)
                }
                addFresh(added)
                wet.removeFinishedStrokes(strokes.keys)
            }
        })
    }

    /** Lo que tardó cada cuadro fuera del hilo principal, para ajustar cuánto pintar por cuadro. */
    private val frameMetrics = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        var ns = m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
            m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ns += m.getMetric(FrameMetrics.GPU_DURATION)
        dry.adapt(ns)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wet.eagerInit()
        (context as? Activity)?.window?.addOnFrameMetricsAvailableListener(frameMetrics, handler)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(merge)
        removeCallbacks(hold)
        removeCallbacks(lassoHold)
        removeCallbacks(fingerHold)
        runCatching { (context as? Activity)?.window?.removeOnFrameMetricsAvailableListener(frameMetrics) }
    }

    /**
     * Muestra la vista guardada con la nota. La vista se pudo guardar con otro ancho de ventana
     * (en media pantalla o en la pantalla entera): una hoja que entra a lo ancho se centra, y una
     * que se guardó entera a lo ancho pero ahora no entra, se ajusta al ancho.
     */
    fun restoreViewport(v: Viewport) {
        camera.show(v.x, v.y, v.zoom)
        if (width > 0) recenter(v) else post { recenter(v) }
    }

    /** Si la vista se guardó en una ventana de otro tamaño, se mira el mismo centro. */
    private fun recenter(v: Viewport) {
        if (v.width > 0f && v.height > 0f) {
            camera.set(camera.tx + (width - v.width) / 2f, camera.ty + (height - v.height) / 2f, camera.zoom)
        }
        fitToWindow()
        listener?.onViewportChanged()
    }

    private fun fitToWindow() {
        if (!pages.paged || width == 0) return
        val pageWidth = pages.width * camera.scale
        val left = pages.left * camera.scale + camera.tx
        if (pageWidth <= width) {
            if (abs(left - (width - pageWidth) / 2) > 1f) camera.set((width - pageWidth) / 2 - pages.left * camera.scale, camera.ty, camera.zoom)
        } else if (pageWidth <= resources.displayMetrics.widthPixels * 1.02f) {
            val top = camera.toWorldY(0f)
            val zoom = (width - 24 * density) / (pages.width * density)
            val scale = zoom.coerceIn(Camera.MIN_ZOOM, Camera.MAX_ZOOM) * density
            camera.set((width - pages.width * scale) / 2 - pages.left * scale, -top * scale, zoom)
        }
        dry.invalidate()
    }

    /**
     * Cambió el tamaño (al dividir la pantalla o girar la tablet): con la hoja al ancho, se vuelve
     * a ajustar al ancho; si no, lo que estaba en el centro sigue en el centro.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (zoomOn) post {
            layoutZoom()
            zoomChanged(follow = false)
        }
        if (oldw == 0 || oldh == 0) return
        val wasPageWidth = pages.paged && abs(pages.width * camera.scale + 24 * density - oldw) < 0.02f * oldw
        if (wasPageWidth) {
            val centerY = camera.toWorldY(oldh / 2f)
            val zoom = (w - 24 * density) / (pages.width * density)
            val scale = zoom.coerceIn(Camera.MIN_ZOOM, Camera.MAX_ZOOM) * density
            camera.set((w - pages.width * scale) / 2 - pages.left * scale, h / 2f - centerY * scale, zoom)
        } else {
            camera.set(camera.tx + (w - oldw) / 2f, camera.ty + (h - oldh) / 2f, camera.zoom)
        }
        listener?.onViewportChanged()
        post { listener?.onSelectionChanged() }
    }

    /** Avisa que cambió la lista de trazos desde afuera (deshacer, rehacer, borrar todo). */
    fun refresh() {
        removeCallbacks(merge)
        fresh.clear()
        dry.strokesChanged()
    }

    /** Llegaron mallas armadas en segundo plano. */
    fun meshesChanged() = dry.strokesChanged()

    /** Llegaron las imágenes decodificadas, o cambió el fondo. */
    fun elementsChanged() {
        dry.contentChanged()
        dry.elementsChanged()
    }

    /** Hay que repintar esa zona del mundo (por ejemplo, llegó una imagen nueva). */
    fun damage(r: RectF) = dry.damage(r)

    /** El centro de lo que se ve, en el mundo. */
    fun visibleCenter() = PointF(camera.toWorldX(width / 2f), camera.toWorldY(height / 2f))

    /** Lo que se ve, en el mundo. */
    fun visibleWorld(out: RectF): RectF =
        out.apply { set(camera.toWorldX(0f), camera.toWorldY(0f), camera.toWorldX(width.toFloat()), camera.toWorldY(height.toFloat())) }

    /**
     * Encuadra todo lo escrito, sin acercar más de 2×; sin trazos, vuelve al origen. Con hojas,
     * ajusta al ancho de la hoja, y si ya estaba así, muestra la hoja entera.
     */
    fun fitToContent() {
        if (width == 0 || height == 0) return
        if (pages.paged) {
            fitPage()
        } else {
            val b = contentBounds()
            if (b == null) {
                camera.set(0f, 0f, 1f)
            } else {
                val pad = 24 * density
                val zoom = min((width - 2 * pad) / (b.width() * density), (height - 2 * pad) / (b.height() * density))
                    .coerceIn(Camera.MIN_ZOOM, 2f)
                val scale = zoom * density
                camera.set((width - b.width() * scale) / 2 - b.left * scale, (height - b.height() * scale) / 2 - b.top * scale, zoom)
            }
        }
        dry.invalidate()
        listener?.onViewportChanged()
        listener?.onSelectionChanged()
    }

    private fun fitPage() {
        val pad = 12 * density
        val centerY = camera.toWorldY(height / 2f)
        val k = pages.pageAt(centerY).coerceIn(0, pageCount - 1)
        val widthZoom = (width - 2 * pad) / (pages.width * density)
        val pageZoom = min(widthZoom, (height - 2 * pad) / (pages.height * density))
        if (abs(camera.zoom / widthZoom - 1) > 0.02f || widthZoom <= pageZoom * 1.02f) {
            // Al ancho, sin mover lo que está en el medio de la pantalla.
            val scale = widthZoom.coerceIn(Camera.MIN_ZOOM, Camera.MAX_ZOOM) * density
            val empty = strokes.isEmpty() && images.isEmpty() && texts.isEmpty() && widgets.isEmpty()
            val y = if (empty || pages.pageAt(camera.toWorldY(0f)) < 0) pages.top - pad / scale else centerY - height / 2f / scale
            camera.set((width - pages.width * scale) / 2 - pages.left * scale, -y * scale, widthZoom)
        } else {
            val scale = pageZoom * density
            val top = pages.pageTop(k)
            camera.set(
                (width - pages.width * scale) / 2 - pages.left * scale,
                (height - pages.height * scale) / 2 - top * scale,
                pageZoom,
            )
        }
    }

    private fun contentBounds(): RectF? {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (s in strokes) {
            if (s.left.isNaN()) continue
            l = min(l, s.left)
            t = min(t, s.top)
            r = max(r, s.right)
            b = max(b, s.bottom)
        }
        for (box in images.map(Elements::imageBounds) + texts.map(Elements::textBounds) + widgets.map { RectF(it.left, it.top, it.right, it.bottom) }) {
            l = min(l, box.left)
            t = min(t, box.top)
            r = max(r, box.right)
            b = max(b, box.bottom)
        }
        return if (l > r) null else RectF(l, t, r, b)
    }

    // --- Trazos frescos ---

    private fun addFresh(added: List<InkStroke>) {
        listener?.onStrokesAdded(added) // los agrega al final de la lista de la nota
        fresh.addAll(added)
        dry.freshChanged()
        scheduleMerge()
    }

    private fun scheduleMerge() {
        removeCallbacks(merge)
        if (penPointer != NONE) return
        postDelayed(merge, if (fresh.size >= MAX_FRESH) 0L else MERGE_DELAY_MS)
    }

    /** Pasa los trazos frescos a las baldosas. */
    private fun mergeFresh() {
        removeCallbacks(merge)
        if (fresh.isEmpty()) return
        fresh.clear()
        dry.strokesChanged()
    }

    /** Cuántos trazos de [strokes] ya van en las baldosas. */
    private fun committedCount(): Int {
        val n = strokes.size - fresh.size
        // Si la lista no termina con los frescos (no debería pasar), van todos a las baldosas.
        if (n < 0 || fresh.indices.any { strokes[n + it] !== fresh[it] }) {
            fresh.clear()
            return strokes.size
        }
        return n
    }

    // --- Tacto ---

    // Los hijos sólo dibujan: todo el tacto lo maneja este contenedor.
    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (isPenButtonDevice(e)) {
            penButtonDeviceEvent(e)
            return true
        }
        // Mientras se corren las hojas no se escribe: lo escrito se perdería al terminar.
        if (shifting && e.actionMasked != MotionEvent.ACTION_CANCEL) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pointerDown(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(e, e.actionIndex)
            MotionEvent.ACTION_CANCEL -> cancelAll(e)
        }
        return true
    }

    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        val type = e.getToolType(0)
        if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
            lastPenSeen = e.eventTime
        }
        if (isPen(e, 0)) {
            val near = e.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            hover = if (near) PointF(e.x, e.y) else null
            setCursor(if (near && wantsEraser(e, 0)) PointF(e.x, e.y) else null)
            logButtons(e)
        }
        return super.dispatchHoverEvent(e)
    }

    private fun isPen(e: MotionEvent, i: Int) = when (e.getToolType(i)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_MOUSE -> true
        else -> false
    }

    /** La goma del lápiz (si tiene) o el clic derecho del mouse borran aunque no esté elegido el borrador. */
    private fun wantsEraser(e: MotionEvent, i: Int): Boolean =
        wantsEraserNow() ||
            e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ||
            (e.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE && e.buttonState and MotionEvent.BUTTON_SECONDARY != 0)

    private fun wantsEraserNow() = mode == Mode.ERASER

    private var lastButtons = 0

    /** Cuándo se apretó el botón lateral; 0 si no está apretado. */
    private var buttonDownAt = 0L

    /**
     * Sigue los botones que informa el lápiz en sus propios eventos: los deja en el registro (para
     * ver qué manda cada tablet) y un clic (apretar y soltar rápido) avisa con
     * [Listener.onStylusButtonClick], para volver a la herramienta anterior.
     */
    private fun logButtons(e: MotionEvent) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return // los botones del mouse son otra cosa
        val buttons = e.buttonState
        if (buttons == lastButtons) return
        val pressed = buttons and STYLUS_BUTTONS != 0
        val wasPressed = lastButtons and STYLUS_BUTTONS != 0
        lastButtons = buttons
        Diag.log("botones del lápiz: 0x${Integer.toHexString(buttons)} acción ${MotionEvent.actionToString(e.actionMasked)} herramienta ${e.getToolType(0)}")
        buttonChanged(pressed && !wasPressed, !pressed && wasPressed, e.eventTime)
    }

    private fun buttonChanged(down: Boolean, up: Boolean, time: Long) {
        if (down) buttonDownAt = time
        if (up) {
            if (buttonDownAt != 0L && time - buttonDownAt < CLICK_MS) listener?.onStylusButtonClick()
            buttonDownAt = 0L
        }
    }

    /**
     * Los botones del Lenovo Tab Pen Plus llegan por Bluetooth, como si fueran otro aparato (un
     * mouse y un control multimedia), no en los eventos del lápiz. Sus eventos no dibujan: un clic
     * cuenta como el botón del lápiz.
     */
    private val buttonDevices = HashMap<Int, Boolean>()

    private fun isPenButtonDevice(e: MotionEvent): Boolean = buttonDevices.getOrPut(e.deviceId) {
        val d = InputDevice.getDevice(e.deviceId)
        d != null && d.name.contains("pen", ignoreCase = true) && !d.supportsSource(InputDevice.SOURCE_STYLUS)
    }

    private fun penButtonDeviceEvent(e: MotionEvent) {
        Diag.log("lápiz Bluetooth: ${MotionEvent.actionToString(e.actionMasked)} botones 0x${Integer.toHexString(e.buttonState)} de ${InputDevice.getDevice(e.deviceId)?.name}")
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> buttonChanged(down = true, up = false, time = e.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> buttonChanged(down = false, up = true, time = e.eventTime)
            MotionEvent.ACTION_SCROLL -> listener?.onStylusButtonClick()
        }
    }

    /** Apretar o soltar el botón lateral con el lápiz en el aire llega por acá. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (isPenButtonDevice(e)) {
            penButtonDeviceEvent(e)
            return true
        }
        if (isPen(e, 0) && (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || e.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE)) {
            logButtons(e)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    private fun pointerDown(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        val byPen = isPen(e, i)
        if (gesturePointer != NONE || zoomDragPointer != NONE) return
        // La lupa: en el panel sólo escribe (o borra) el lápiz; un dedo sobre el recuadro lo mueve.
        if (zoomOn && zoomPanel.contains(e.getX(i), e.getY(i))) {
            if (!byPen || penPointer != NONE || mode == Mode.LASSO || mode == Mode.LASER) return
            penInPanel = true
            usePanelInput()
        } else if (penPointer == NONE) {
            penInPanel = false
            useMainInput()
            if (zoomOn && !byPen && fingers.isEmpty() && zoomBoxOnScreen().contains(e.getX(i), e.getY(i))) {
                zoomDragPointer = id
                zoomDragX = e.getX(i)
                zoomDragY = e.getY(i)
                return
            }
        }
        if (penPointer == NONE) panelMaxX = Float.NEGATIVE_INFINITY
        // Con el lazo, lo elegido se arrastra con el lápiz o con un dedo.
        if (mode == Mode.LASSO && penPointer == NONE && fingers.isEmpty() && !wantsEraser(e, i) &&
            (byPen || e.eventTime - lastPenSeen > PALM_GRACE_MS)
        ) {
            val kind = selectionHit(e.getX(i), e.getY(i))
            if (kind != NONE) {
                if (byPen) lastPenSeen = e.eventTime
                startGesture(kind, id, e.getX(i), e.getY(i))
                return
            }
        }
        if (!byPen) {
            // Rechazo de palma: con el lápiz apoyado o recién levantado, los dedos no cuentan.
            if (penPointer == NONE && e.eventTime - lastPenSeen > PALM_GRACE_MS) {
                fingers[id] = PointF(e.getX(i), e.getY(i))
                // Un solo dedo que no se mueve es un toque (por ejemplo, para reproducir un audio).
                removeCallbacks(fingerHold)
                if (fingers.size == 1) {
                    tapPointer = id
                    tapX = e.getX(i)
                    tapY = e.getY(i)
                    tapAt = e.eventTime
                    // Con el lazo, mantener el dedo quieto ofrece pegar.
                    if (mode == Mode.LASSO) postDelayed(fingerHold, LONG_PRESS_MS)
                } else {
                    tapPointer = NONE
                }
            }
            return
        }
        if (penPointer != NONE) return
        removeCallbacks(merge)
        fingers.clear() // el lápiz manda: corta cualquier desplazamiento en curso
        removeCallbacks(fingerHold)
        tapPointer = NONE
        penPointer = id
        lastPenSeen = e.eventTime
        logButtons(e)
        if (wantsEraser(e, i)) {
            startErase(e.getX(i), e.getY(i))
            return
        }
        if (mode == Mode.LASSO) {
            startLasso(e.getX(i), e.getY(i))
            return
        }
        if (mode == Mode.LASER) {
            laser.add(LaserTrail())
            laserPoint(e.getX(i), e.getY(i), e.eventTime)
            return
        }
        penDownAt = e.eventTime
        startHold(e, i)
        if (pen.straight) {
            lineStart = PointF(inX(e.getX(i)), inY(e.getY(i)))
            linePreview = null
            return
        }
        requestUnbufferedDispatch(e)
        predictor.record(e)
        val brush = Brush.createWithColorIntArgb(pen.tool.family(pen.sensitivity), displayColor(), pen.size, epsilon())
        val strokeId = wet.startStroke(e, id, brush, inputToWorld(scratch))
        strokePens[strokeId] = pen
        penStroke = strokeId
    }

    private fun move(e: MotionEvent) {
        if (gesturePointer != NONE) {
            moveGesture(e)
            return
        }
        if (zoomDragPointer != NONE) {
            val i = e.findPointerIndex(zoomDragPointer)
            if (i >= 0) {
                zoomBox.offset((e.getX(i) - zoomDragX) / camera.scale, (e.getY(i) - zoomDragY) / camera.scale)
                zoomDragX = e.getX(i)
                zoomDragY = e.getY(i)
                zoomLineX = zoomBox.left
                zoomChanged(follow = false)
            }
            return
        }
        if (penPointer == NONE) {
            if (fingers.isNotEmpty()) panZoom(e)
            return
        }
        val i = e.findPointerIndex(penPointer)
        if (i < 0) return
        lastPenSeen = e.eventTime
        logButtons(e)
        if (!erasing && wantsEraser(e, i)) {
            // Apretó el botón con el lápiz apoyado: lo escrito queda y sigue borrando.
            removeCallbacks(hold)
            penStroke?.let { wet.finishStroke(e, penPointer, it) }
            penStroke = null
            lineStart?.let { commitLine() }
            if (shape != null) commitShape()
            if (lassoPath != null) {
                lassoPath = null
                dry.invalidate()
            }
            startErase(e.getX(i), e.getY(i))
            return
        }
        if (erasing) {
            for (h in 0 until e.historySize) {
                eraseTo(inX(e.getHistoricalX(i, h)), inY(e.getHistoricalY(i, h)))
            }
            eraseTo(inX(e.getX(i)), inY(e.getY(i)))
            setCursor(PointF(e.getX(i), e.getY(i)))
            return
        }
        if (lassoPath != null) {
            for (h in 0 until e.historySize) addLassoPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
            addLassoPoint(e.getX(i), e.getY(i))
            dry.invalidate()
            return
        }
        if (mode == Mode.LASER) {
            for (h in 0 until e.historySize) laserPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
            laserPoint(e.getX(i), e.getY(i), e.eventTime)
            return
        }
        trackHold(e, i)
        shape?.let { s ->
            shapePreview = shapeStroke(s.follow(shapeFromX, shapeFromY, inX(e.getX(i)), inY(e.getY(i))))
            dry.invalidate()
            return
        }
        lineStart?.let { start ->
            linePreview = straightLine(start, inX(e.getX(i)), inY(e.getY(i)))
            dry.invalidate()
            return
        }
        val strokeId = penStroke ?: return
        predictor.record(e)
        val prediction = predictor.predict()
        wet.addToStroke(e, penPointer, strokeId, prediction)
        prediction?.recycle()
    }

    private fun pointerUp(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        if (id == gesturePointer) {
            endGesture()
            return
        }
        if (id == zoomDragPointer) {
            zoomDragPointer = NONE
            return
        }
        if (id != penPointer) {
            if (id == tapPointer) {
                removeCallbacks(fingerHold)
                tapPointer = NONE
                val still = hypot(e.getX(i) - tapX, e.getY(i) - tapY) < TAP_SLOP * density
                if (still && e.eventTime - tapAt < TAP_MS) mediaAt(camera.toWorldX(e.getX(i)), camera.toWorldY(e.getY(i)))?.let { listener?.onMediaTap(it) }
            }
            // Al soltar los dedos se repinta lo que quedó estirado por el zoom.
            if (fingers.remove(id) != null && fingers.isEmpty()) dry.invalidate()
            return
        }
        lastPenSeen = e.eventTime
        removeCallbacks(hold)
        removeCallbacks(lassoHold)
        val canceled = e.flags and MotionEvent.FLAG_CANCELED != 0
        laser.lastOrNull()?.let { if (it.upAt == Long.MAX_VALUE) it.upAt = e.eventTime }
        // Un toque del lápiz sobre un audio o un video lo reproduce en vez de dejar un punto.
        val tapped = if (mode == Mode.PEN && !canceled && !penMoved && e.eventTime - penDownAt < TAP_MS && shape == null) {
            mediaAt(inX(e.getX(i)), inY(e.getY(i)))
        } else {
            null
        }
        if (tapped != null) {
            penStroke?.let {
                wet.cancelStroke(it, e)
                strokePens.remove(it)
            }
            penStroke = null
            lineStart = null
            linePreview = null
            listener?.onMediaTap(tapped)
        }
        penStroke?.let { strokeId ->
            if (canceled) {
                wet.cancelStroke(strokeId, e)
                strokePens.remove(strokeId)
            } else {
                wet.finishStroke(e, id, strokeId)
            }
        }
        lineStart?.let { start ->
            linePreview = if (canceled) null else straightLine(start, inX(e.getX(i)), inY(e.getY(i)))
            commitLine()
        }
        if (shape != null) {
            if (canceled) shapePreview = null
            commitShape()
        }
        if (lassoPath != null) {
            if (!canceled) finishLasso()
            lassoPath = null
            dry.invalidate()
        }
        if (erasing) endErase()
        penPointer = NONE
        penStroke = null
        scheduleMerge()
        // En la lupa, al terminar cerca del borde derecho del recuadro, el recuadro avanza.
        if (penInPanel && !canceled && panelMaxX > zoomBox.left + zoomBox.width() * ZOOM_ADVANCE_AT) zoomMove(ZOOM_STEP)
        penInPanel = false
        useMainInput()
    }

    private fun cancelAll(e: MotionEvent) {
        zoomDragPointer = NONE
        penInPanel = false
        useMainInput()
        removeCallbacks(hold)
        removeCallbacks(lassoHold)
        removeCallbacks(fingerHold)
        tapPointer = NONE
        penStroke?.let {
            wet.cancelStroke(it, e)
            strokePens.remove(it)
        }
        lineStart = null
        linePreview = null
        shape = null
        shapePreview = null
        lassoPath = null
        if (gesturePointer != NONE) endGesture()
        if (erasing) endErase()
        penPointer = NONE
        penStroke = null
        fingers.clear()
        dry.invalidate()
        scheduleMerge()
    }

    /** Un dedo arrastra; dos o más además hacen zoom alrededor de su centro. */
    private fun panZoom(e: MotionEvent) {
        var n = 0
        var ox = 0f
        var oy = 0f
        var nx = 0f
        var ny = 0f
        for ((id, p) in fingers) {
            val i = e.findPointerIndex(id)
            if (i < 0) continue
            ox += p.x
            oy += p.y
            nx += e.getX(i)
            ny += e.getY(i)
            n++
        }
        if (n == 0) return
        ox /= n
        oy /= n
        nx /= n
        ny /= n
        var factor = 1f
        if (n >= 2) {
            var oldSpan = 0f
            var newSpan = 0f
            for ((id, p) in fingers) {
                val i = e.findPointerIndex(id)
                if (i < 0) continue
                oldSpan += hypot(p.x - ox, p.y - oy)
                newSpan += hypot(e.getX(i) - nx, e.getY(i) - ny)
            }
            if (oldSpan > 1f) factor = newSpan / oldSpan
        }
        if (tapPointer != NONE) {
            val i = e.findPointerIndex(tapPointer)
            if (i < 0 || hypot(e.getX(i) - tapX, e.getY(i) - tapY) >= TAP_SLOP * density) {
                tapPointer = NONE
                removeCallbacks(fingerHold)
            }
        }
        camera.panZoom(nx - ox, ny - oy, factor, nx, ny)
        for ((id, p) in fingers) {
            val i = e.findPointerIndex(id)
            if (i >= 0) p.set(e.getX(i), e.getY(i))
        }
        dry.invalidate()
        listener?.onViewportChanged()
        if (selection != null) listener?.onSelectionChanged()
    }

    // --- Lápiz quieto: borrar o figura ---

    private val hold = Runnable { onHold() }

    /** Dónde se quedó quieto el lápiz por última vez, en pantalla. */
    private var holdX = 0f
    private var holdY = 0f

    /** Si el lápiz se movió desde que se apoyó. */
    private var penMoved = false
    private var penX = 0f
    private var penY = 0f

    /** Los puntos del trazo en curso en el mundo, para reconocer la figura. */
    private val trace = Floats()
    private var pressureSum = 0f
    private var pressureCount = 0

    /** Figura reconocida que sigue al lápiz hasta levantarlo, con su trazo de muestra. */
    private var shape: Shapes.Shape? = null
    private var shapePreview: InkStroke? = null
    private var shapePen: Pen? = null
    private var shapeFromX = 0f
    private var shapeFromY = 0f

    private fun startHold(e: MotionEvent, i: Int) {
        trace.clear()
        pressureSum = 0f
        pressureCount = 0
        penMoved = false
        holdX = e.getX(i)
        holdY = e.getY(i)
        penX = holdX
        penY = holdY
        addTrace(holdX, holdY, e.getPressure(i))
        removeCallbacks(hold)
        if (holdToErase) postDelayed(hold, ERASE_HOLD_MS)
    }

    private fun trackHold(e: MotionEvent, i: Int) {
        for (h in 0 until e.historySize) {
            sample(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h))
        }
        sample(e.getX(i), e.getY(i), e.getPressure(i))
    }

    private fun sample(sx: Float, sy: Float, pressure: Float) {
        penX = sx
        penY = sy
        if (shape == null) addTrace(sx, sy, pressure)
        if (hypot(sx - holdX, sy - holdY) <= HOLD_SLOP * density) return
        // Se movió: el reloj vuelve a empezar desde acá.
        holdX = sx
        holdY = sy
        penMoved = true
        removeCallbacks(hold)
        if (holdForShapes && shape == null && penStroke != null) postDelayed(hold, SHAPE_HOLD_MS)
    }

    private fun addTrace(sx: Float, sy: Float, pressure: Float) {
        trace.add(inX(sx), inY(sy))
        if (penInPanel) panelMaxX = max(panelMaxX, inX(sx))
        pressureSum += pressure
        pressureCount++
    }

    private fun onHold() {
        if (penPointer == NONE || erasing || mode != Mode.PEN) return
        if (!penMoved) {
            if (!holdToErase) return
            // Quieto desde que se apoyó: lo que se empezó a escribir (un punto) no queda.
            penStroke?.let {
                wet.cancelStroke(it)
                strokePens.remove(it)
            }
            penStroke = null
            lineStart = null
            linePreview = null
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startErase(penX, penY)
            return
        }
        if (!holdForShapes || shape != null) return
        val strokeId = penStroke ?: return
        val found = Shapes.recognize(trace.toArray(), density / inScale) ?: return
        shapePen = strokePens.remove(strokeId) ?: pen
        wet.cancelStroke(strokeId)
        penStroke = null
        shape = found
        shapeFromX = inX(penX)
        shapeFromY = inY(penY)
        shapePreview = shapeStroke(found)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dry.invalidate()
    }

    /** El trazo de una figura, con el lápiz con que se dibujó y la presión media del trazo. */
    private fun shapeStroke(s: Shapes.Shape): InkStroke? {
        val p = shapePen ?: pen
        val step = (3f * density / inScale).coerceIn(0.5f, LINE_STEP)
        val pts = s.sample(step)
        if (pts.size < 4) return null
        val pressure = if (pressureCount > 0) (pressureSum / pressureCount).coerceIn(0.15f, 1f) else 0.5f
        val batch = MutableStrokeInputBatch()
        for (k in 0 until pts.size / 2) {
            // Puntos espaciados en el tiempo: así Ink no suaviza las esquinas.
            batch.add(InputToolType.STYLUS, pts[2 * k], pts[2 * k + 1], k * SHAPE_POINT_MS, pressure = pressure)
        }
        return runCatching { InkStroke(p.tool, Stroke(p.tool.brush(p.color, p.size, epsilon(), p.sensitivity), batch), p.sensitivity) }.getOrNull()
    }

    private fun commitShape() {
        val s = shapePreview
        shape = null
        shapePreview = null
        shapePen = null
        if (s != null) addFresh(listOf(s)) else dry.invalidate()
    }

    // --- Línea recta ---

    /**
     * Trazo recto de `start` a (x, y) con el lápiz elegido. Si casi es horizontal o vertical, se
     * endereza: así se resalta un renglón sin que se tuerza.
     */
    private fun straightLine(start: PointF, x: Float, y: Float): InkStroke? {
        var ex = x
        var ey = y
        val dx = ex - start.x
        val dy = ey - start.y
        val length = hypot(dx, dy)
        if (length < 1f) return null
        val angle = Math.toDegrees(atan2(abs(dy), abs(dx)).toDouble())
        if (angle < SNAP_DEGREES) ey = start.y
        if (angle > 90 - SNAP_DEGREES) ex = start.x
        val batch = MutableStrokeInputBatch()
        val n = (length / LINE_STEP).toInt().coerceAtLeast(1)
        for (k in 0..n) {
            val f = k.toFloat() / n
            batch.add(InputToolType.STYLUS, start.x + (ex - start.x) * f, start.y + (ey - start.y) * f, k * 8L, pressure = 0.5f)
        }
        return InkStroke(pen.tool, Stroke(pen.tool.brush(pen.color, pen.size, epsilon(), pen.sensitivity), batch), pen.sensitivity)
    }

    private fun commitLine() {
        val line = linePreview
        lineStart = null
        linePreview = null
        if (line != null) addFresh(listOf(line)) else dry.invalidate()
    }

    // --- Borrador ---

    /** Lo que hizo el borrador parcial en esta pasada, en orden, para deshacerlo de una vez. */
    private val cutEdits = ArrayList<History.Edit>()

    private fun startErase(sx: Float, sy: Float) {
        mergeFresh() // el borrador trabaja sobre la lista entera, ya en las tandas
        erasing = true
        erased.clear()
        cutEdits.clear()
        eraseX = inX(sx)
        eraseY = inY(sy)
        setCursor(PointF(sx, sy))
        eraseTo(eraseX, eraseY)
    }

    /**
     * Saca los trazos que toca el borrador entre la posición anterior y (x, y), en el mundo. El
     * radio está en dp de pantalla, así que borra lo mismo a cualquier zoom.
     */
    private fun eraseTo(x: Float, y: Float) {
        val r = eraserRadius * density / inScale
        val box = ImmutableBox.fromCenterAndDimensions(ImmutableVec(x, y), 2 * r, 2 * r)
        val moved = abs(x - eraseX) + abs(y - eraseY) > 0f
        val segment = if (moved) ImmutableSegment(ImmutableVec(eraseX, eraseY), ImmutableVec(x, y)) else null
        val left = min(x, eraseX) - r
        val right = max(x, eraseX) + r
        val top = min(y, eraseY) - r
        val bottom = max(y, eraseY) + r
        var changed = false
        for (i in strokes.indices.reversed()) {
            val s = strokes[i]
            if (!s.intersects(left, top, right, bottom)) continue
            val shape = s.stroke?.shape ?: continue // sin malla todavía: no está a la vista
            if (box.intersects(shape, AffineTransform.IDENTITY) ||
                segment?.intersects(shape, AffineTransform.IDENTITY) == true
            ) {
                if (partialErase) {
                    // Se cambia el trazo por los pedazos que quedan afuera de la goma.
                    val pieces = cut(s, eraseX, eraseY, x, y, r) ?: continue
                    strokes.removeAt(i)
                    cutEdits.add(History.Remove(strokes, listOf(i to s)))
                    if (pieces.isNotEmpty()) {
                        strokes.addAll(i, pieces)
                        cutEdits.add(History.Insert(strokes, i, pieces))
                    }
                } else {
                    strokes.removeAt(i)
                    erased.add(i to s)
                }
                changed = true
            }
        }
        eraseX = x
        eraseY = y
        if (changed) dry.strokesChanged()
    }

    /**
     * Los pedazos de `s` que quedan fuera de la goma (que pasó de (ax, ay) a (bx, by) con radio
     * `r`), con la misma pluma; null si la goma no llega a tocar ningún punto del trazo.
     */
    private fun cut(s: InkStroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float): List<InkStroke>? {
        val stroke = s.stroke ?: return null
        val inputs = stroke.inputs
        val p = StrokeInput()
        val points = ArrayList<Cut.Point>(inputs.size)
        for (k in 0 until inputs.size) {
            inputs.populate(k, p)
            points.add(Cut.Point(p.x, p.y, p.elapsedTimeMillis.toFloat(), p.pressure, p.tiltRadians, p.orientationRadians))
        }
        // La goma "llega" hasta el borde de la tinta, no sólo hasta el centro de la línea.
        val reach = r + s.size * 0.5f
        val step = (min(r, s.size) / 2f).coerceAtLeast(0.3f)
        val parts = Cut.outside(points, ax, ay, bx, by, reach, step) ?: return null
        val type = inputs.getToolType()
        val unit = if (inputs.hasStrokeUnitLength()) inputs.getStrokeUnitLengthCm() else StrokeInput.NO_STROKE_UNIT_LENGTH
        return parts.mapNotNull { part ->
            runCatching {
                val batch = MutableStrokeInputBatch()
                val t0 = part[0].t
                for (q in part) batch.add(type, q.x, q.y, (q.t - t0).toLong().coerceAtLeast(0L), unit, q.pressure, q.tilt, q.orientation)
                InkStroke(s.tool, Stroke(stroke.brush, batch), s.sensitivity)
            }.getOrNull()
        }
    }

    private fun endErase() {
        erasing = false
        if (erased.isNotEmpty()) listener?.onStrokesErased(ArrayList(erased))
        erased.clear()
        if (cutEdits.isNotEmpty()) listener?.onEdited(History.Group(ArrayList(cutEdits)))
        cutEdits.clear()
        setCursor(hover?.takeIf { wantsEraserNow() }) // si el lápiz sigue cerca, sigue el cursor
    }

    private fun setCursor(p: PointF?) {
        if (p == null && cursor == null) return
        cursor = p
        dry.invalidate()
    }

    // --- Lazo ---

    /** Lo elegido: objetos que están en las listas de la nota. */
    private class Selection(
        val strokes: List<InkStroke>,
        val images: List<NoteImage>,
        val texts: List<NoteText>,
        val widgets: List<Widget>,
    ) {
        val bounds = RectF()

        init {
            var first = true
            fun add(l: Float, t: Float, r: Float, b: Float) {
                if (l.isNaN()) return
                if (first) bounds.set(l, t, r, b) else bounds.union(l, t, r, b)
                first = false
            }
            for (s in strokes) add(s.left, s.top, s.right, s.bottom)
            for (i in images) Elements.imageBounds(i).let { add(it.left, it.top, it.right, it.bottom) }
            for (t in texts) Elements.textBounds(t).let { add(it.left, it.top, it.right, it.bottom) }
            for (w in widgets) add(w.left, w.top, w.right, w.bottom)
        }

        val isEmpty get() = strokes.isEmpty() && images.isEmpty() && texts.isEmpty() && widgets.isEmpty()
        val count get() = strokes.size + images.size + texts.size + widgets.size
    }

    /** Lo elegido mientras se arrastra: fuera de la nota, con la transformación `t`. */
    private class Lifted(val removed: List<History.Edit>, val node: RenderNode, val bounds: RectF) {
        var t = Transform()
    }

    /** Lo que se agrega a la nota cuando terminan de armarse las mallas y de copiarse los medios. */
    private class Added(val strokes: List<InkStroke>, val images: List<NoteImage>, val texts: List<NoteText>, val widgets: List<Widget>)

    /** Copias que se arman en segundo plano; al terminar se agregan con [apply]. */
    private class Pending(val task: FutureTask<Added>, val apply: (Added) -> Unit)

    private var selection: Selection? = null
        set(value) {
            field = value?.takeUnless { it.isEmpty }
            dry.invalidate()
            listener?.onSelectionChanged()
        }
    private var lifted: Lifted? = null
    private var pending: Pending? = null
    private var lassoPath: Floats? = null
    private var lassoLength = 0f
    private var lassoX = 0f
    private var lassoY = 0f

    private var gesture = NONE
    private var gesturePointer = NONE
    private var gestureX = 0f
    private var gestureY = 0f
    private var gestureMoved = false

    val hasSelection get() = selection != null

    /** Lo elegido tiene algo a lo que se le puede cambiar el color. */
    val selectionHasInk
        get() = selection?.let { s -> s.strokes.isNotEmpty() || s.texts.isNotEmpty() || s.widgets.any { it !is Media } } == true

    /** Si lo elegido es una sola imagen, para recortarla. */
    val selectedImage get() = selection?.takeIf { it.count == 1 }?.images?.firstOrNull()

    /** Si lo elegido es un solo widget, para editarlo o reproducirlo. */
    val selectedWidget get() = selection?.takeIf { it.count == 1 }?.widgets?.firstOrNull()

    /** Se está moviendo lo elegido: lo que está en las listas de la nota no está completo. */
    val busy get() = lifted != null || pending != null

    private val corners = FloatArray(8)

    /**
     * Las cuatro esquinas de lo elegido en la pantalla (con su margen), giradas si se está girando:
     * arriba a la izquierda, arriba a la derecha, abajo a la derecha, abajo a la izquierda.
     */
    private fun selectionCorners(out: FloatArray): Boolean {
        val sel = selection ?: return false
        val pad = SELECTION_PAD * density / camera.scale
        val l = sel.bounds.left - pad
        val t = sel.bounds.top - pad
        val r = sel.bounds.right + pad
        val b = sel.bounds.bottom + pad
        val tr = lifted?.t ?: Transform()
        val xs = floatArrayOf(l, r, r, l)
        val ys = floatArrayOf(t, t, b, b)
        for (k in 0..3) {
            out[2 * k] = screenX(tr.x(xs[k], ys[k]))
            out[2 * k + 1] = screenY(tr.y(xs[k], ys[k]))
        }
        return true
    }

    /** Dónde va la manija para girar: arriba del borde superior, hacia afuera. */
    private fun rotateHandle(c: FloatArray, out: PointF) {
        val mx = (c[0] + c[2]) / 2
        val my = (c[1] + c[3]) / 2
        val cx = (c[0] + c[4]) / 2
        val cy = (c[1] + c[5]) / 2
        val d = hypot(mx - cx, my - cy).takeIf { it > 0f } ?: 1f
        val off = ROTATE_OFFSET * density
        out.set(mx + (mx - cx) / d * off, my + (my - cy) / d * off)
    }

    private val handle = PointF()

    /** El rectángulo de lo elegido en la pantalla, con su margen y la manija de girar. */
    fun selectionScreenBounds(out: RectF): Boolean {
        if (!selectionCorners(corners)) return false
        rotateHandle(corners, handle)
        out.set(handle.x, handle.y, handle.x, handle.y)
        for (k in 0..3) out.union(corners[2 * k], corners[2 * k + 1])
        return true
    }

    private fun screenX(x: Float) = x * camera.scale + camera.tx
    private fun screenY(y: Float) = y * camera.scale + camera.ty

    fun clearSelection() {
        finishPending()
        if (selection != null) selection = null
    }

    /** Elige estas cosas, que tienen que estar en la nota (por ejemplo, una imagen recién puesta). */
    fun select(strokes: List<InkStroke>, images: List<NoteImage>, texts: List<NoteText>, widgets: List<Widget> = emptyList()) {
        finishPending()
        selection = Selection(strokes, images, texts, widgets)
    }

    /** Agrega imágenes y widgets a la nota (un paso de deshacer) y los deja elegidos. */
    fun insert(newImages: List<NoteImage>, newWidgets: List<Widget>) {
        finishPending()
        mergeFresh()
        val edits = ArrayList<History.Edit>()
        if (newImages.isNotEmpty()) {
            images.addAll(newImages)
            edits.add(History.Add(images, newImages))
        }
        if (newWidgets.isNotEmpty()) {
            widgets.addAll(newWidgets)
            edits.add(History.Add(widgets, newWidgets))
        }
        if (edits.isEmpty()) return
        listener?.onEdited(History.Group(edits))
        val s = Selection(emptyList(), newImages, emptyList(), newWidgets)
        selection = s
        dry.contentChanged()
        dry.damage(s.bounds)
    }

    /** Cambia la imagen o el widget elegido por otro (recortado, editado), en un paso de deshacer. */
    fun replaceSelected(newImage: NoteImage?, newWidget: Widget?) {
        finishPending()
        val sel = selection ?: return
        val edits = ArrayList<History.Edit>()
        if (newImage != null) {
            removeFrom(images, sel.images)?.let(edits::add)
            images.add(newImage)
            edits.add(History.Add(images, listOf(newImage)))
        }
        if (newWidget != null) {
            removeFrom(widgets, sel.widgets)?.let(edits::add)
            widgets.add(newWidget)
            edits.add(History.Add(widgets, listOf(newWidget)))
        }
        if (edits.isEmpty()) return
        listener?.onEdited(History.Group(edits))
        val s = Selection(emptyList(), listOfNotNull(newImage), emptyList(), listOfNotNull(newWidget))
        dry.damage(sel.bounds)
        dry.damage(s.bounds)
        dry.contentChanged()
        selection = s
    }

    /**
     * Termina lo que esté a medias con lo elegido (arrastrarlo, armar las copias) para que la nota
     * quede completa. Antes de guardar al salir.
     */
    fun finishPending() {
        if (lifted != null && pending == null) {
            gesturePointer = NONE
            gesture = NONE
            commitLift()
        }
        val p = pending ?: return
        runCatching { p.task.get() }
        complete(p)
    }

    fun deleteSelection() {
        finishPending()
        val sel = selection ?: return
        mergeFresh()
        val edits = listOfNotNull(
            removeFrom(strokes, sel.strokes), removeFrom(images, sel.images),
            removeFrom(texts, sel.texts), removeFrom(widgets, sel.widgets),
        )
        selection = null
        if (edits.isNotEmpty()) listener?.onEdited(History.Group(edits))
        dry.strokesChanged()
        dry.damage(sel.bounds)
    }

    /** Cambia el color de los trazos, textos, gráficos y figuras elegidos (`color` como se guarda). */
    fun recolorSelection(color: Int) {
        finishPending()
        val sel = selection ?: return
        mergeFresh()
        val recolored = sel.strokes.map { it.recolored(it.tool.storedColor(color)) }
        val newTexts = sel.texts.map { it.transformed(Transform(), color) }
        val newWidgets = sel.widgets.map { w ->
            when (w) {
                is Graph2D -> w.copy(color = color)
                is Graph3D -> w.copy(color = color)
                is Solid -> w.copy(color = color)
                is Media -> w
            }
        }
        val edits = ArrayList<History.Edit>()
        removeFrom(strokes, sel.strokes)?.let(edits::add)
        removeFrom(texts, sel.texts)?.let(edits::add)
        removeFrom(widgets, sel.widgets)?.let(edits::add)
        strokes.addAll(recolored)
        texts.addAll(newTexts)
        widgets.addAll(newWidgets)
        if (recolored.isNotEmpty()) edits.add(History.Add(strokes, recolored))
        if (newTexts.isNotEmpty()) edits.add(History.Add(texts, newTexts))
        if (newWidgets.isNotEmpty()) edits.add(History.Add(widgets, newWidgets))
        if (edits.isEmpty()) return
        listener?.onEdited(History.Group(edits))
        selection = Selection(recolored, sel.images, newTexts, newWidgets)
        dry.strokesChanged()
        dry.damage(sel.bounds)
    }

    /** Lo elegido, para copiarlo o guardarlo como sticker. */
    fun selectionClip(): Clipboard.Clip? {
        val sel = selection ?: return null
        return Clipboard.Clip(sel.strokes, sel.images, sel.texts, sel.widgets, RectF(sel.bounds))
    }

    fun copySelection(): Boolean {
        Clipboard.clip = selectionClip() ?: return false
        return true
    }

    /** Una copia de lo elegido, un poco corrida, que pasa a ser lo elegido. */
    fun duplicateSelection() {
        finishPending()
        val sel = selection ?: return
        val off = DUPLICATE_OFFSET * density / camera.scale
        addTransformed(sel.strokes, sel.images, sel.texts, sel.widgets, Transform.translation(off, off), emptyList())
    }

    /** Pega lo copiado (o un sticker) centrado en (x, y) del mundo, o en el centro de la pantalla, y lo deja elegido. */
    fun paste(clip: Clipboard.Clip, x: Float? = null, y: Float? = null) {
        finishPending()
        val c = visibleCenter()
        addTransformed(
            clip.strokes, clip.images, clip.texts, clip.widgets,
            Transform.translation((x ?: c.x) - clip.bounds.centerX(), (y ?: c.y) - clip.bounds.centerY()), emptyList(),
        )
    }

    private fun startLasso(sx: Float, sy: Float) {
        clearSelection()
        lassoPath = Floats().apply { add(camera.toWorldX(sx), camera.toWorldY(sy)) }
        lassoLength = 0f
        lassoX = sx
        lassoY = sy
        lassoStartX = sx
        lassoStartY = sy
        removeCallbacks(lassoHold)
        postDelayed(lassoHold, LONG_PRESS_MS)
        dry.invalidate()
    }

    private var lassoStartX = 0f
    private var lassoStartY = 0f

    /** El lápiz quieto con el lazo: no es un lazo, es para pegar. */
    private val lassoHold = Runnable {
        if (lassoPath == null || lassoLength >= TAP_SLOP * density) return@Runnable
        lassoPath = null
        dry.invalidate()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onLassoLongPress(lassoStartX, lassoStartY, camera.toWorldX(lassoStartX), camera.toWorldY(lassoStartY))
    }

    /** Lo mismo con un dedo. */
    private val fingerHold = Runnable {
        if (tapPointer == NONE || mode != Mode.LASSO || fingers.size != 1) return@Runnable
        tapPointer = NONE
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onLassoLongPress(tapX, tapY, camera.toWorldX(tapX), camera.toWorldY(tapY))
    }

    private fun addLassoPoint(sx: Float, sy: Float) {
        lassoLength += hypot(sx - lassoX, sy - lassoY)
        if (lassoLength >= TAP_SLOP * density) removeCallbacks(lassoHold)
        lassoX = sx
        lassoY = sy
        lassoPath?.add(camera.toWorldX(sx), camera.toWorldY(sy))
    }

    private fun finishLasso() {
        val path = lassoPath ?: return
        mergeFresh()
        if (lassoLength < TAP_SLOP * density) {
            selectAt(camera.toWorldX(lassoX), camera.toWorldY(lassoY))
            return
        }
        val lasso = Lasso(path.toArray())
        val f = lassoFilter
        val chosen = strokes.filter { s ->
            f.accepts(s.tool) && !s.left.isNaN() && lasso.intersects(s.left, s.top, s.right, s.bottom) &&
                lasso.containsMostOf(s.points())
        }
        val chosenImages = if (f.images) images.filter { !it.locked && lasso.contains(it.centerX, it.centerY) } else emptyList()
        val chosenTexts = if (f.texts) texts.filter { Elements.textBounds(it).let { b -> lasso.contains(b.centerX(), b.centerY()) } } else emptyList()
        val chosenWidgets = if (f.widgets) widgets.filter { lasso.contains(it.centerX, it.centerY) } else emptyList()
        selection = Selection(chosen, chosenImages, chosenTexts, chosenWidgets)
    }

    /** Un toque con el lazo elige lo que está debajo: un texto, un trazo, un widget o una imagen. */
    private fun selectAt(x: Float, y: Float) {
        val f = lassoFilter
        val r = TAP_SLOP * density / camera.scale
        if (f.texts) texts.lastOrNull { Elements.textBounds(it).contains(x, y) }?.let {
            selection = Selection(emptyList(), emptyList(), listOf(it), emptyList())
            return
        }
        val box = ImmutableBox.fromCenterAndDimensions(ImmutableVec(x, y), 2 * r, 2 * r)
        strokes.lastOrNull { s ->
            f.accepts(s.tool) && s.intersects(x - r, y - r, x + r, y + r) &&
                s.stroke?.shape?.let { box.intersects(it, AffineTransform.IDENTITY) } == true
        }?.let {
            selection = Selection(listOf(it), emptyList(), emptyList(), emptyList())
            return
        }
        if (f.widgets) widgets.lastOrNull { it.contains(x, y) }?.let {
            selection = Selection(emptyList(), emptyList(), emptyList(), listOf(it))
            return
        }
        if (f.images) images.lastOrNull { !it.locked && imageContains(it, x, y) }?.let {
            selection = Selection(emptyList(), listOf(it), emptyList(), emptyList())
            return
        }
        selection = null
    }

    /** Si (x, y) cae dentro de la imagen, girada o no. */
    private fun imageContains(i: NoteImage, x: Float, y: Float): Boolean {
        val back = Transform.rotation(-Math.toRadians(i.rotation.toDouble()).toFloat(), i.centerX, i.centerY)
        val lx = back.x(x, y)
        val ly = back.y(x, y)
        return lx in i.left..i.left + i.width && ly in i.top..i.top + i.height
    }

    /** El audio o video en (x, y) del mundo, si hay. */
    private fun mediaAt(x: Float, y: Float) = widgets.lastOrNull { it is Media && it.contains(x, y) } as Media?

    /** Qué toca un dedo o el lápiz en (sx, sy): una manija, lo elegido o nada. */
    private fun selectionHit(sx: Float, sy: Float): Int {
        if (pending != null || !selectionCorners(corners)) return NONE
        rotateHandle(corners, handle)
        val touch = HANDLE_TOUCH * density
        if (hypot(sx - handle.x, sy - handle.y) < touch) return ROTATE
        if (hypot(sx - corners[4], sy - corners[5]) < touch) return SCALE
        // Adentro del rectángulo (girado), con un poco de margen.
        val lasso = Lasso(corners)
        if (lasso.contains(sx, sy)) return MOVE
        val margin = SELECTION_PAD * density
        for (k in 0..3) {
            val ax = corners[2 * k]
            val ay = corners[2 * k + 1]
            val bx = corners[(2 * k + 2) % 8]
            val by = corners[(2 * k + 3) % 8]
            if (segmentDistance(sx, sy, ax, ay, bx, by) < margin) return MOVE
        }
        return NONE
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun startGesture(kind: Int, id: Int, sx: Float, sy: Float) {
        removeCallbacks(merge)
        gesture = kind
        gesturePointer = id
        gestureX = sx
        gestureY = sy
        gestureMoved = false
    }

    private fun moveGesture(e: MotionEvent) {
        val i = e.findPointerIndex(gesturePointer)
        if (i < 0) return
        val sx = e.getX(i)
        val sy = e.getY(i)
        if (!gestureMoved) {
            if (hypot(sx - gestureX, sy - gestureY) < TOUCH_SLOP * density) return
            gestureMoved = true
            lift()
        }
        val l = lifted ?: return
        val b = l.bounds
        l.t = when (gesture) {
            MOVE -> Transform.translation((sx - gestureX) / camera.scale, (sy - gestureY) / camera.scale)
            SCALE -> {
                // Se agranda desde la esquina opuesta, sin deformar: cuenta cuánto avanzó el lápiz
                // a lo largo de la diagonal.
                val hx = b.width()
                val hy = b.height()
                val px = camera.toWorldX(sx) - b.left
                val py = camera.toWorldY(sy) - b.top
                val startX = camera.toWorldX(gestureX) - b.left
                val startY = camera.toWorldY(gestureY) - b.top
                val base = startX * hx + startY * hy
                if (base <= 0f) return
                val minA = MIN_SELECTION * density / camera.scale / max(max(hx, hy), 1f)
                Transform.scale(((px * hx + py * hy) / base).coerceIn(minA, MAX_SCALE), b.left, b.top)
            }
            else -> {
                // Gira alrededor del centro lo que giró el lápiz; cerca de un ángulo recto se pega.
                val cx = b.centerX()
                val cy = b.centerY()
                val a0 = atan2(camera.toWorldY(gestureY) - cy, camera.toWorldX(gestureX) - cx)
                val a1 = atan2(camera.toWorldY(sy) - cy, camera.toWorldX(sx) - cx)
                var angle = a1 - a0
                val quarter = (Math.PI / 2).toFloat()
                val nearest = Math.round(angle / quarter) * quarter
                if (abs(angle - nearest) < Math.toRadians(ROTATE_SNAP).toFloat()) angle = nearest
                Transform.rotation(angle, cx, cy)
            }
        }
        dry.invalidate()
        listener?.onSelectionChanged()
    }

    private fun endGesture() {
        gesturePointer = NONE
        gesture = NONE
        if (lifted != null) commitLift()
        // Un toque (sin arrastrar) sobre un audio o video elegido lo reproduce.
        if (!gestureMoved) mediaAt(camera.toWorldX(gestureX), camera.toWorldY(gestureY))?.let { listener?.onMediaTap(it) }
    }

    /** Saca lo elegido de la nota y lo graba aparte para arrastrarlo. */
    private fun lift() {
        val sel = selection ?: return
        mergeFresh()
        val removed = listOfNotNull(
            removeFrom(strokes, sel.strokes), removeFrom(images, sel.images),
            removeFrom(texts, sel.texts), removeFrom(widgets, sel.widgets),
        )
        val b = RectF(sel.bounds)
        val node = RenderNode("tinta-seleccion").apply { clipToBounds = false }
        val left = floor(b.left).toInt()
        val top = floor(b.top).toInt()
        node.setPosition(left, top, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
        val rc = node.beginRecording()
        rc.translate(-left.toFloat(), -top.toFloat())
        for (img in sel.images) ImageCache.get(img.file) {}?.let { Elements.drawImage(rc, img, it) }
        for (w in sel.widgets) dry.painter.draw(rc, w, dark) { f -> ImageCache.get(f) {} }
        val m = Matrix().apply { setScale(camera.scale, camera.scale) }
        InkDraw.use { r -> for (s in sel.strokes) r.draw(rc, s.display(dark) ?: continue, m) }
        for (t in sel.texts) Elements.drawText(rc, t, dry.textPaint, Appearance.ink(t.color, Tool.PEN, dark))
        node.endRecording()
        lifted = Lifted(removed, node, b)
        dry.strokesChanged()
        if (sel.images.isNotEmpty() || sel.texts.isNotEmpty() || sel.widgets.isNotEmpty()) dry.damage(b)
    }

    /** Al soltar: si se movió, se arman las copias movidas; si no, todo vuelve a su lugar. */
    private fun commitLift() {
        val l = lifted ?: return
        val sel = selection
        if (sel == null || l.t.isIdentity) {
            for (edit in l.removed.asReversed()) edit.undo()
            endLift()
            dry.strokesChanged()
            dry.damage(l.bounds)
            return
        }
        addTransformed(sel.strokes, sel.images, sel.texts, sel.widgets, l.t, l.removed)
    }

    private fun endLift() {
        lifted?.node?.discardDisplayList()
        lifted = null
        dry.invalidate()
    }

    /**
     * Agrega a la nota copias de esas cosas transformadas con `t` y las deja elegidas. `before`
     * son los cambios ya hechos que van en el mismo paso de deshacer (sacar los originales, al
     * mover). Las mallas se arman y los medios de otra nota se copian a esta en segundo plano;
     * mientras tanto, si se estaba arrastrando, se sigue viendo lo arrastrado.
     */
    private fun addTransformed(
        source: List<InkStroke>,
        sourceImages: List<NoteImage>,
        sourceTexts: List<NoteText>,
        sourceWidgets: List<Widget>,
        t: Transform,
        before: List<History.Edit>,
    ) {
        val dir = mediaDir
        val task = FutureTask(Callable {
            val moved = Work.map(source) { it.transformedBuilt(t) }
            InkDraw.warmNow(moved)
            val movedImages = sourceImages.map { i -> i.transformed(t).let { if (dir != null) it.withFile(NoteStore.copyMedia(it.file, dir)) else it } }
            val movedWidgets = sourceWidgets.map { w -> w.transformed(t).let { if (dir != null) it.withFiles { f -> NoteStore.copyMedia(f, dir) } else it } }
            Added(moved, movedImages, sourceTexts.map { it.transformed(t) }, movedWidgets)
        })
        val oldBounds = lifted?.bounds
        val p = Pending(task) { a ->
            strokes.addAll(a.strokes)
            images.addAll(a.images)
            texts.addAll(a.texts)
            widgets.addAll(a.widgets)
            val edits = ArrayList(before)
            if (a.strokes.isNotEmpty()) edits.add(History.Add(strokes, a.strokes))
            if (a.images.isNotEmpty()) edits.add(History.Add(images, a.images))
            if (a.texts.isNotEmpty()) edits.add(History.Add(texts, a.texts))
            if (a.widgets.isNotEmpty()) edits.add(History.Add(widgets, a.widgets))
            endLift()
            listener?.onEdited(History.Group(edits))
            selection = Selection(a.strokes, a.images, a.texts, a.widgets)
            dry.strokesChanged()
            selection?.let { dry.damage(it.bounds) }
            oldBounds?.let { dry.damage(it) }
        }
        pending = p
        selectionWork.execute {
            task.run()
            post { complete(p) }
        }
    }

    private fun complete(p: Pending) {
        if (pending !== p) return
        pending = null
        val added = runCatching { p.task.get() }.getOrElse {
            shifting = false
            // No debería pasar: si no se pudieron armar, lo sacado vuelve a su lugar.
            lifted?.let { l -> for (edit in l.removed.asReversed()) edit.undo() }
            endLift()
            dry.strokesChanged()
            return
        }
        p.apply(added)
    }

    private fun <T> removeFrom(list: MutableList<T>, items: List<T>): History.Remove<T>? {
        if (items.isEmpty()) return null
        val chosen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(items) }
        return History.removeAt(list, list.indices.filter { list[it] in chosen })
    }

    // --- Hojas ---

    /** Se están corriendo las hojas (en segundo plano). */
    private var shifting = false

    /** La hoja que está en el medio de la pantalla. */
    fun currentPage(): Int = pages.pageAt(camera.toWorldY(height / 2f)).coerceIn(0, pageCount - 1)

    /** Agrega una hoja en blanco al final y la muestra. */
    fun addPageAtEnd() {
        if (!pages.paged) return
        finishPending()
        val k = pageCount
        setPages(pages.copy(minPages = k + 1), pages)
        // Que se vea la hoja nueva, al mismo zoom.
        camera.set(camera.tx, -pages.pageTop(k) * camera.scale + 12 * density, camera.zoom)
        dry.invalidate()
        listener?.onViewportChanged()
    }

    /**
     * Inserta una hoja en blanco en el lugar `k` (lo que estaba de la hoja `k` para abajo baja una
     * hoja), o con `delete` borra la hoja `k` con lo que tiene y sube lo de abajo. Es un solo paso
     * de deshacer. `onDone` se llama al terminar, en el hilo de la pantalla.
     */
    fun shiftPages(k: Int, delete: Boolean, onDone: () -> Unit) {
        if (!pages.paged) return
        val count = pageCount
        if (delete) {
            remapPages(max(1, count - 1), onDone) { p -> if (p < k) p else if (p == k) null else p - 1 }
        } else {
            remapPages(count + 1, onDone) { p -> if (p < k) p else p + 1 }
        }
    }

    /** Lleva la hoja `from` al lugar `to`; las del medio se corren una. Un paso de deshacer. */
    fun movePage(from: Int, to: Int, onDone: () -> Unit) {
        if (!pages.paged || from == to) return
        remapPages(pageCount, onDone) { p ->
            when {
                p == from -> to
                from < to && p in (from + 1)..to -> p - 1
                to < from && p in to until from -> p + 1
                else -> p
            }
        }
    }

    /**
     * Cambia de hoja lo que está escrito: cada cosa (por la hoja donde cae su centro) va a la hoja
     * `newPage(p)`, o se borra si da null. Lo que no cambia de hoja no se toca. Las copias corridas
     * se arman en segundo plano; mientras tanto no se puede escribir.
     */
    private fun remapPages(newCount: Int, onDone: () -> Unit, newPage: (Int) -> Int?) {
        if (shifting) return
        finishPending()
        clearSelection()
        mergeFresh()
        val old = pages
        fun shift(cy: Float): Float? {
            val p = old.pageAt(cy)
            if (p < 0) return 0f
            val q = newPage(p) ?: return null
            return (q - p) * old.step
        }
        val movedStrokes = ArrayList<Pair<InkStroke, Float>>()
        val goneStrokes = ArrayList<InkStroke>()
        for (st in strokes) {
            if (st.top.isNaN()) continue
            val d = shift((st.top + st.bottom) / 2)
            if (d == null) goneStrokes.add(st) else if (d != 0f) movedStrokes.add(st to d)
        }
        fun <T> split(items: List<T>, cy: (T) -> Float): Pair<List<Pair<T, Float>>, List<T>> {
            val moved = ArrayList<Pair<T, Float>>()
            val gone = ArrayList<T>()
            for (it in items) {
                val d = shift(cy(it))
                if (d == null) gone.add(it) else if (d != 0f) moved.add(it to d)
            }
            return moved to gone
        }
        val (movedImages, goneImages) = split(images) { it.centerY }
        val (movedTexts, goneTexts) = split(texts) { Elements.textBounds(it).centerY() }
        val (movedWidgets, goneWidgets) = split(widgets) { it.centerY }
        val new = old.copy(minPages = max(1, newCount))
        shifting = true
        val bulk = movedStrokes.size > WARM_NOW_LIMIT
        val task = FutureTask(Callable {
            val strokesCopy = Work.map(movedStrokes) { (st, d) -> st.transformedBuilt(Transform.translation(0f, d)) }
            // Muchos trazos: las mallas de Android se calientan después, de a poco.
            if (!bulk) InkDraw.warmNow(strokesCopy)
            Added(
                strokesCopy,
                movedImages.map { (i, d) -> i.transformed(Transform.translation(0f, d)) },
                movedTexts.map { (t, d) -> t.transformed(Transform.translation(0f, d)) },
                movedWidgets.map { (w, d) -> w.transformed(Transform.translation(0f, d)) },
            )
        })
        val p = Pending(task) { a ->
            val edits = listOfNotNull(
                removeFrom(strokes, movedStrokes.map { it.first } + goneStrokes),
                removeFrom(images, movedImages.map { it.first } + goneImages),
                removeFrom(texts, movedTexts.map { it.first } + goneTexts),
                removeFrom(widgets, movedWidgets.map { it.first } + goneWidgets),
            ).toMutableList<History.Edit>()
            strokes.addAll(a.strokes)
            images.addAll(a.images)
            texts.addAll(a.texts)
            widgets.addAll(a.widgets)
            if (a.strokes.isNotEmpty()) edits.add(History.Add(strokes, a.strokes))
            if (a.images.isNotEmpty()) edits.add(History.Add(images, a.images))
            if (a.texts.isNotEmpty()) edits.add(History.Add(texts, a.texts))
            if (a.widgets.isNotEmpty()) edits.add(History.Add(widgets, a.widgets))
            edits.add(History.Change({ setPages(old, null) }, { setPages(new, null) }))
            setPages(new, null)
            listener?.onEdited(History.Group(edits))
            shifting = false
            dry.strokesChanged()
            elementsChanged()
            if (bulk) InkDraw.warm(a.strokes) { false }
            onDone()
        }
        pending = p
        selectionWork.execute {
            task.run()
            post { complete(p) }
        }
    }

    /** Lleva la vista a la hoja `k`, con su borde de arriba arriba de todo y el mismo zoom. */
    fun scrollToPage(k: Int) {
        if (!pages.paged) return
        val top = pages.pageTop(k.coerceIn(0, pageCount - 1))
        camera.set(camera.tx, -top * camera.scale + 8 * density, camera.zoom)
        dry.invalidate()
        listener?.onViewportChanged()
        listener?.onSelectionChanged()
    }

    // --- Lo encontrado al buscar ---

    /** Renglones encontrados (cajas del mundo), resaltados; [foundCurrent] es el elegido. */
    private val found = ArrayList<RectF>()
    private var foundCurrent = -1

    /** Resalta lo encontrado y lleva la vista hasta `current` (si es -1, no se mueve). */
    fun showFound(boxes: List<RectF>, current: Int) {
        found.clear()
        found.addAll(boxes)
        foundCurrent = current
        boxes.getOrNull(current)?.let(::focusOn)
        dry.invalidate()
    }

    fun clearFound() {
        if (found.isEmpty()) return
        found.clear()
        foundCurrent = -1
        dry.invalidate()
    }

    /**
     * Deja el rectángulo del mundo `r` a la vista, un poco arriba del centro (abajo puede estar la
     * lupa o el teclado). El zoom cambia si no entra o si se vería tan chico que no se lee; si ya
     * se ve entero y legible, la vista no se mueve.
     */
    private fun focusOn(r: RectF) {
        if (width == 0 || height == 0) return
        val m = FOCUS_MARGIN * density
        val fit = min((width - 2 * m) / r.width().coerceAtLeast(1f), (height * 0.5f) / r.height().coerceAtLeast(1f)) / density
        val readable = min(FOCUS_READABLE_DP / r.height().coerceAtLeast(1f), fit)
        val visible = RectF(camera.toWorldX(m), camera.toWorldY(m), camera.toWorldX(width - m), camera.toWorldY(height * 0.6f))
        if (visible.contains(r) && camera.zoom >= readable * 0.99f) return
        var zoom = camera.zoom.coerceAtLeast(readable)
        if (zoom > fit) zoom = fit
        camera.set(0f, 0f, zoom)
        val s = camera.scale
        // Si el renglón es más ancho que la pantalla, se ve su comienzo.
        val x = if (r.width() * s > width - 2 * m) m - r.left * s else width / 2f - r.centerX() * s
        camera.set(x, height * 0.35f - r.centerY() * s, zoom)
        dry.invalidate()
        listener?.onViewportChanged()
        listener?.onSelectionChanged()
    }

    /** Cambia las hojas y avisa; con `undoFrom`, como un paso de deshacer que vuelve a esas. */
    private fun setPages(new: PageLayout, undoFrom: PageLayout?) {
        pages = new
        listener?.onPagesChanged(new)
        if (undoFrom != null) listener?.onEdited(History.Change({ setPages(undoFrom, null) }, { setPages(new, null) }))
    }

    // --- Entrada del lápiz: la hoja o el panel de la lupa ---

    /** Cómo pasar de la pantalla al mundo para el trazo en curso (la cámara o la lupa). */
    private var inScale = 1f
    private var inTx = 0f
    private var inTy = 0f

    private fun inX(sx: Float) = (sx - inTx) / inScale
    private fun inY(sy: Float) = (sy - inTy) / inScale

    private fun useMainInput() {
        inScale = camera.scale
        inTx = camera.tx
        inTy = camera.ty
    }

    private fun usePanelInput() {
        inScale = zoomScale
        inTx = zoomPanel.left - zoomBox.left * zoomScale
        inTy = zoomPanel.top - zoomBox.top * zoomScale
    }

    private fun inputToWorld(out: Matrix): Matrix = out.apply {
        setTranslate(-inTx, -inTy)
        postScale(1 / inScale, 1 / inScale)
    }

    // --- Lupa ---

    /** La lupa: un recuadro de la hoja que se ve aumentado en un panel abajo, para escribir ahí. */
    var zoomOn = false
        private set

    /** El recuadro, en el mundo. */
    private val zoomBox = RectF()

    /** El panel, en la pantalla. */
    private val zoomPanel = RectF()

    /** Dónde empieza el renglón: ahí vuelve el recuadro con "renglón nuevo". */
    private var zoomLineX = 0f

    /** Alto del recuadro en el mundo: cuanto más chico, más aumento. */
    private var zoomHeight = ZOOM_HEIGHT

    /** px del panel por dp del mundo. */
    private var zoomScale = 1f
    private var penInPanel = false
    private var panelMaxX = Float.NEGATIVE_INFINITY
    private var zoomDragPointer = NONE
    private var zoomDragX = 0f
    private var zoomDragY = 0f

    /** Prende o apaga la lupa. Al prenderla, el recuadro empieza en lo que se está mirando. */
    fun setZoom(on: Boolean) {
        if (zoomOn == on) return
        finishPending()
        zoomOn = on
        if (on) {
            layoutZoom()
            val view = visibleWorld(RectF())
            val bottom = camera.toWorldY(zoomPanel.top)
            var left = view.left + view.width() * 0.08f
            if (pages.paged) left = max(left, pages.left + PageLayout.MARGIN)
            zoomBox.offsetTo(left, view.top + (bottom - view.top) * 0.35f)
            zoomLineX = zoomBox.left
        }
        zoomChanged(follow = on)
    }

    /** El panel ocupa la parte de abajo; el recuadro tiene sus proporciones. */
    private fun layoutZoom() {
        val m = 12 * density
        val h = (height * 0.36f).coerceIn(140 * density, 320 * density)
        zoomPanel.set(m, height - m - h, width - m, height - m)
        zoomScale = zoomPanel.height() / zoomHeight
        val w = zoomPanel.width() / zoomScale
        zoomBox.set(zoomBox.left, zoomBox.top, zoomBox.left + w, zoomBox.top + zoomHeight)
    }

    /** Corre el recuadro `fraction` de su ancho (negativo: para atrás). Al pasar el borde de la hoja, renglón nuevo. */
    fun zoomMove(fraction: Float) {
        if (!zoomOn) return
        zoomBox.offset(zoomBox.width() * fraction, 0f)
        if (pages.paged && zoomBox.left > pages.right - zoomBox.width() * 0.4f) {
            zoomNewLine()
            return
        }
        zoomChanged(follow = true)
    }

    /** El recuadro vuelve al principio del renglón y baja uno. */
    fun zoomNewLine() {
        if (!zoomOn) return
        zoomBox.offsetTo(zoomLineX, zoomBox.top + zoomBox.height())
        zoomChanged(follow = true)
    }

    /** Más (factor < 1) o menos (> 1) aumento. */
    fun zoomResize(factor: Float) {
        if (!zoomOn) return
        zoomHeight = (zoomHeight * factor).coerceIn(ZOOM_MIN_HEIGHT, ZOOM_MAX_HEIGHT)
        layoutZoom()
        zoomChanged(follow = true)
    }

    /** El panel de la lupa en la pantalla, para poner los botones arriba. */
    fun zoomPanelRect(out: RectF): Boolean {
        if (!zoomOn) return false
        out.set(zoomPanel)
        return true
    }

    private fun zoomBoxOnScreen() =
        RectF(screenX(zoomBox.left), screenY(zoomBox.top), screenX(zoomBox.right), screenY(zoomBox.bottom))

    /** Se movió el recuadro: se repinta, y con `follow` la hoja se corre para que se vea. */
    private fun zoomChanged(follow: Boolean) {
        if (zoomOn && follow) {
            val b = zoomBoxOnScreen()
            val top = 8 * density
            val bottom = zoomPanel.top - 8 * density
            var dx = 0f
            var dy = 0f
            if (b.top < top || b.bottom > bottom) dy = (top + (bottom - top) * 0.35f) - b.top
            if (b.left < 0f) dx = 16 * density - b.left
            if (b.right > width) dx = width - 16 * density - b.right
            if (dx != 0f || dy != 0f) {
                camera.panZoom(dx, dy, 1f, 0f, 0f)
                listener?.onViewportChanged()
            }
        }
        dry.zoomDirty = true
        dry.invalidate()
        listener?.onZoomChanged()
    }

    // --- Láser ---

    /** Un trazo del puntero láser: puntos del mundo con su hora, y cuándo se levantó el lápiz. */
    private class LaserTrail {
        val points = Floats()
        val times = ArrayList<Long>()
        var upAt = Long.MAX_VALUE
    }

    private val laser = ArrayList<LaserTrail>()

    private fun laserPoint(sx: Float, sy: Float, time: Long) {
        val trail = laser.lastOrNull()?.takeIf { it.upAt == Long.MAX_VALUE } ?: return
        trail.points.add(camera.toWorldX(sx), camera.toWorldY(sy))
        trail.times.add(time)
        dry.invalidate()
    }

    /** El color con que se ve el trazo en curso (en modo oscuro, el invertido). */
    private fun displayColor() = Appearance.ink(pen.tool.storedColor(pen.color), pen.tool, dark)

    /** Precisión de la malla del trazo: medio píxel al zoom actual, nunca más de 0,1 dp. */
    private fun epsilon() = (0.5f / max(camera.scale, inScale)).coerceAtMost(0.1f)

    private inner class DryLayer(context: Context) : View(context), Tiles.Content {
        private val chunks = StrokeChunks()
        private val tiles = Tiles(density)
        private var stale = true
        private var version = 0
        private val overview = Overview { post { invalidate() } }
        private val paper = Paper()
        val painter = WidgetPainter()
        val textPaint = Elements.newTextPaint()
        private val worldToScreen = Matrix()
        private val view = RectF()
        private val needed = RectF()
        private val damaged = ArrayList<RectF>()
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
        private val accent = context.getColor(R.color.accent)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = accent
            pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = 0xFFFFFFFF.toInt()
        }
        private val lassoScreen = Path()
        private val outlinePath = Path()
        private val laserGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 9 * density
        }
        private val laserCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 3 * density
        }
        private val angleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * density
            textAlign = Paint.Align.CENTER
        }

        /** Las hojas dependen de dónde termina lo escrito: se recalcula cuando cambia. */
        private var contentStale = true
        var pageCount = 1
            private set

        /** El contenido del panel de la lupa hay que volver a grabarlo. */
        var zoomDirty = true

        fun strokesChanged() {
            stale = true
            contentStale = true
            version++
            zoomDirty = true
            invalidate()
        }

        fun contentChanged() {
            contentStale = true
            invalidate()
        }

        fun freshChanged() {
            contentStale = true
            invalidate()
        }

        fun adapt(renderNanos: Long) = tiles.adapt(renderNanos)

        fun elementsChanged() {
            tiles.invalidateAll()
            zoomDirty = true
            invalidate()
        }

        fun damage(r: RectF) {
            damaged.add(RectF(r))
            zoomDirty = true
            invalidate()
        }

        fun darkChanged() {
            zoomDirty = true
            version++
            chunks.dark = dark
            tiles.invalidateAll()
            invalidate()
        }

        fun reset() {
            chunks.clear()
            tiles.clear()
            strokesChanged()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            overview.shutdown()
        }

        private fun updatePageCount() {
            contentStale = false
            if (!pages.paged) {
                pageCount = 1
                return
            }
            var bottom = Float.NEGATIVE_INFINITY
            for (st in strokes) if (st.bottom > bottom) bottom = st.bottom // las sin caja (NaN) no cuentan
            for (i in images) bottom = max(bottom, Elements.imageBounds(i).bottom)
            for (t in texts) bottom = max(bottom, Elements.textBounds(t).bottom)
            for (wd in widgets) bottom = max(bottom, wd.bottom)
            lifted?.let { bottom = max(bottom, it.t.box(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)[3]) }
            if (bottom == Float.NEGATIVE_INFINITY) bottom = Float.NaN
            val count = pages.count(bottom)
            if (count != pageCount) {
                // Apareció o se fue una hoja: se repinta desde la más baja de las dos.
                val from = pages.pageTop(min(count, pageCount) - 1)
                tiles.invalidate(RectF(pages.left - BIG, from, pages.right + BIG, pages.pageTop(max(count, pageCount)) + BIG), urgent = true)
                pageCount = count
                post { listener?.onPageCountChanged(count) }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (contentStale) updatePageCount()
            // Debajo de las baldosas, por si falta alguna.
            paper.draw(canvas, paperStyle, camera.scale, camera.tx, camera.ty, w, h, dark, pages = pages, pageCount = pageCount)
            if (stale) {
                chunks.sync(strokes.subList(0, committedCount()))
                stale = false
            }
            val settled = fingers.isEmpty()
            view.set(camera.toWorldX(0f), camera.toWorldY(0f), camera.toWorldX(w), camera.toWorldY(h))
            val recordScale = tiles.recordScale(camera.scale, settled)
            tiles.neededArea(view, needed)
            val deadline = System.nanoTime() + CHUNK_BUDGET_NS
            var pending = chunks.update(needed.left, needed.top, needed.right, needed.bottom, recordScale, settled, deadline)
            for (r in chunks.urgentDamage) tiles.invalidate(r, urgent = true)
            for (r in chunks.damage) tiles.invalidate(r, urgent = false)
            for (r in damaged) tiles.invalidate(r, urgent = true)
            chunks.urgentDamage.clear()
            chunks.damage.clear()
            damaged.clear()
            // Las imágenes a la vista siguen en uso aunque sus baldosas no se vuelvan a pintar.
            for (img in images) if (RectF.intersects(needed, Elements.imageBounds(img))) ImageCache.touch(img.file)
            for (wd in widgets) if (wd is Media && wd.thumb != null && wd.right >= needed.left && wd.left <= needed.right &&
                wd.bottom >= needed.top && wd.top <= needed.bottom
            ) ImageCache.touch(wd.thumb)

            camera.worldToScreen(worldToScreen)
            var save = canvas.save()
            canvas.concat(worldToScreen)
            chunks.keepAlive(canvas) // antes que las baldosas
            // Con miles de trazos a la vista, mientras se pintan las baldosas se ve la imagen
            // de toda la nota.
            val crowded = chunks.count(view.left, view.top, view.right, view.bottom) > CROWDED
            if (crowded && tiles.incomplete) {
                overview.request(strokes.toList(), version, dark)
                if (camera.scale <= overview.scale * MAX_UPSCALE) overview.draw(canvas)
            }
            canvas.restoreToCount(save)

            pending = tiles.draw(canvas, view, camera.scale, camera.tx, camera.ty, settled, this) || pending

            save = canvas.save()
            canvas.concat(worldToScreen)
            lifted?.let { l ->
                val inner = canvas.save()
                canvas.translate(l.t.dx, l.t.dy)
                canvas.rotate(l.t.degrees)
                canvas.scale(l.t.a, l.t.a)
                canvas.drawRenderNode(l.node)
                canvas.restoreToCount(inner)
            }
            if (fresh.isNotEmpty() || linePreview != null || shapePreview != null) {
                InkDraw.use { r ->
                    for (s in fresh) r.draw(canvas, s.display(dark) ?: continue, worldToScreen)
                    linePreview?.display(dark)?.let { r.draw(canvas, it, worldToScreen) }
                    shapePreview?.display(dark)?.let { r.draw(canvas, it, worldToScreen) }
                }
            }
            canvas.restoreToCount(save)
            if (found.isNotEmpty()) drawFound(canvas, worldToScreen)
            if (zoomOn) drawZoom(canvas, worldToScreen)
            drawLasso(canvas)
            drawSelection(canvas)
            if (drawLaser(canvas)) pending = true
            cursor?.let {
                cursorPaint.color = if (dark) 0x99E0E0E0.toInt() else 0x99404040.toInt()
                canvas.drawCircle(it.x, it.y, eraserRadius * density, cursorPaint)
            }
            // Con todo quieto y pintado, se adelanta trabajo para cuando se aleje la vista.
            if (!pending && settled && penPointer == NONE && fresh.isEmpty()) {
                pending = chunks.prewarm(recordScale, System.nanoTime() + PREWARM_NS)
            }
            if (pending) postInvalidateOnAnimation()
        }

        private val zoomNode = RenderNode("tinta-lupa").apply { clipToBounds = true }
        private val zoomMatrix = Matrix()
        private val zoomFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = accent
        }
        private val zoomShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent and 0x00FFFFFF or 0x1A000000 }
        private val zoomShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val zoomClip = Path()

        /** El recuadro en la hoja y, abajo, el panel con lo que hay en él aumentado. */
        private fun drawZoom(canvas: Canvas, worldToScreen: Matrix) {
            val b = zoomBoxOnScreen()
            canvas.drawRect(b, zoomShade)
            canvas.drawRect(b, zoomFrame)
            val p = zoomPanel
            val r = 14 * density
            canvas.drawRoundRect(p.left, p.top + 2 * density, p.right, p.bottom + 3 * density, r, r, zoomShadow)
            if (zoomDirty) recordZoom()
            val save = canvas.save()
            zoomClip.rewind()
            zoomClip.addRoundRect(p, r, r, Path.Direction.CW)
            canvas.clipPath(zoomClip)
            canvas.drawRenderNode(zoomNode)
            // Lo recién escrito (todavía fuera de las tandas) y las vistas previas, encima.
            zoomMatrix.setScale(zoomScale, zoomScale)
            zoomMatrix.postTranslate(p.left - zoomBox.left * zoomScale, p.top - zoomBox.top * zoomScale)
            canvas.concat(zoomMatrix)
            InkDraw.use { rd ->
                for (st in fresh) if (st.intersects(zoomBox.left, zoomBox.top, zoomBox.right, zoomBox.bottom)) rd.draw(canvas, st.display(dark) ?: continue, zoomMatrix)
                linePreview?.display(dark)?.let { rd.draw(canvas, it, zoomMatrix) }
                shapePreview?.display(dark)?.let { rd.draw(canvas, it, zoomMatrix) }
            }
            canvas.restoreToCount(save)
            canvas.drawRoundRect(p, r, r, zoomFrame)
        }

        /** Graba lo que hay dentro del recuadro, al aumento del panel. */
        private fun recordZoom() {
            zoomDirty = false
            val p = zoomPanel
            zoomNode.setPosition(p.left.toInt(), p.top.toInt(), p.right.toInt(), p.bottom.toInt())
            val rc = zoomNode.beginRecording()
            val w = p.width()
            val h = p.height()
            val ox = p.left - p.left.toInt()
            val oy = p.top - p.top.toInt()
            paper.draw(rc, paperStyle, zoomScale, ox - zoomBox.left * zoomScale, oy - zoomBox.top * zoomScale, w + 2, h + 2, dark, pages = pages, pageCount = pageCount)
            rc.translate(ox, oy)
            rc.scale(zoomScale, zoomScale)
            rc.translate(-zoomBox.left, -zoomBox.top)
            val box = RectF(zoomBox)
            for (img in images) {
                val bounds = Elements.imageBounds(img)
                if (!RectF.intersects(box, bounds)) continue
                ImageCache.get(img.file) { damage(bounds) }?.let { Elements.drawImage(rc, img, it) }
            }
            for (wd in widgets) {
                if (wd.right < box.left || wd.left > box.right || wd.bottom < box.top || wd.top > box.bottom) continue
                val area = RectF(wd.left, wd.top, wd.right, wd.bottom)
                painter.draw(rc, wd, dark) { f -> ImageCache.get(f) { damage(area) } }
            }
            val m = Matrix().apply { setScale(zoomScale, zoomScale) }
            val committed = strokes.subList(0, committedCount())
            InkDraw.use { rd ->
                for (st in committed) if (st.intersects(box.left, box.top, box.right, box.bottom)) rd.draw(rc, st.display(dark) ?: continue, m)
            }
            for (txt in texts) {
                if (!RectF.intersects(box, Elements.textBounds(txt))) continue
                Elements.drawText(rc, txt, textPaint, Appearance.ink(txt.color, Tool.PEN, dark))
            }
            zoomNode.endRecording()
        }

        private fun drawLasso(canvas: Canvas) {
            val path = lassoPath ?: return
            val pts = path.data
            lassoScreen.rewind()
            for (k in 0 until path.size / 2) {
                val x = screenX(pts[2 * k])
                val y = screenY(pts[2 * k + 1])
                if (k == 0) lassoScreen.moveTo(x, y) else lassoScreen.lineTo(x, y)
            }
            canvas.drawPath(lassoScreen, outline)
        }

        /** El borde de lo elegido (girado si se está girando), la manija de escala y la de giro. */
        private fun drawSelection(canvas: Canvas) {
            if (!selectionCorners(corners)) return
            outlinePath.rewind()
            outlinePath.moveTo(corners[0], corners[1])
            for (k in 1..3) outlinePath.lineTo(corners[2 * k], corners[2 * k + 1])
            outlinePath.close()
            canvas.drawPath(outlinePath, outline)
            if (pending != null) return
            val r = HANDLE_RADIUS * density
            canvas.drawCircle(corners[4], corners[5], r, handleFill)
            canvas.drawCircle(corners[4], corners[5], r, handleRing)
            rotateHandle(corners, handle)
            val mx = (corners[0] + corners[2]) / 2
            val my = (corners[1] + corners[3]) / 2
            handleRing.color = accent
            canvas.drawLine(mx, my, handle.x, handle.y, handleRing)
            handleRing.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(handle.x, handle.y, r, handleFill)
            canvas.drawCircle(handle.x, handle.y, r * 0.45f, handleRing)
            lifted?.takeIf { gesture == ROTATE }?.let { l ->
                angleText.color = accent
                val deg = ((Math.round(l.t.degrees) % 360) + 360) % 360
                canvas.drawText("$deg°", handle.x, handle.y - r * 1.8f, angleText)
            }
        }

        private val foundFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFB300 }
        private val foundCurrentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFB300 }
        private val foundEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = 0xFFF29900.toInt()
        }
        private val foundRect = RectF()

        /** Lo encontrado, como resaltado: más fuerte y con borde el elegido. */
        private fun drawFound(canvas: Canvas, worldToScreen: Matrix) {
            val pad = 4 * density
            val r = 6 * density
            for ((k, box) in found.withIndex()) {
                worldToScreen.mapRect(foundRect, box)
                foundRect.inset(-pad, -pad)
                if (foundRect.right < 0 || foundRect.left > width || foundRect.bottom < 0 || foundRect.top > height) continue
                canvas.drawRoundRect(foundRect, r, r, if (k == foundCurrent) foundCurrentFill else foundFill)
                if (k == foundCurrent) canvas.drawRoundRect(foundRect, r, r, foundEdge)
            }
        }

        /** La estela del láser: se borra sola. Devuelve true si todavía queda algo. */
        private fun drawLaser(canvas: Canvas): Boolean {
            if (laser.isEmpty()) return false
            val now = android.os.SystemClock.uptimeMillis()
            laser.removeAll { now - it.upAt > LASER_FADE_MS }
            for (trail in laser) {
                val pts = trail.points.data
                for (k in 1 until trail.times.size) {
                    val age = now - trail.times[k]
                    val tail = (1f - (age - LASER_TAIL_MS).coerceAtLeast(0L) / LASER_FADE_MS.toFloat()).coerceIn(0f, 1f)
                    val after = if (trail.upAt == Long.MAX_VALUE) 1f else (1f - (now - trail.upAt) / LASER_FADE_MS.toFloat()).coerceIn(0f, 1f)
                    val a = min(tail, after)
                    if (a <= 0f) continue
                    val x0 = screenX(pts[2 * k - 2])
                    val y0 = screenY(pts[2 * k - 1])
                    val x1 = screenX(pts[2 * k])
                    val y1 = screenY(pts[2 * k + 1])
                    laserGlow.color = (((0x60 * a).toInt()) shl 24) or 0xFF1A1A
                    laserCore.color = (((0xFF * a).toInt()) shl 24) or 0xFF4A3A
                    canvas.drawLine(x0, y0, x1, y1, laserGlow)
                    canvas.drawLine(x0, y0, x1, y1, laserCore)
                }
            }
            return laser.isNotEmpty()
        }

        // --- Contenido de cada baldosa ---

        override fun record(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, scale: Float) {
            val px = Tiles.SIZE.toFloat()
            paper.draw(canvas, paperStyle, scale, -left * scale, -top * scale, px, px, dark, pages = pages, pageCount = pageCount)
            val save = canvas.save()
            canvas.scale(scale, scale)
            canvas.translate(-left, -top)
            val box = RectF(left, top, right, bottom)
            for (img in images) {
                val bounds = Elements.imageBounds(img)
                if (!RectF.intersects(box, bounds)) continue
                // Si todavía no está decodificada, se pide y se repinta cuando llegue.
                val bitmap = ImageCache.get(img.file) { damage(bounds) } ?: continue
                Elements.drawImage(canvas, img, bitmap)
            }
            // Gráficos y figuras debajo de la tinta, para poder anotarlos.
            for (wd in widgets) {
                if (wd.right < left || wd.left > right || wd.bottom < top || wd.top > bottom) continue
                val area = RectF(wd.left, wd.top, wd.right, wd.bottom)
                painter.draw(canvas, wd, dark) { f -> ImageCache.get(f) { damage(area) } }
            }
            chunks.forEachNode(left, top, right, bottom) { canvas.drawRenderNode(it) }
            for (txt in texts) {
                if (!RectF.intersects(box, Elements.textBounds(txt))) continue
                Elements.drawText(canvas, txt, textPaint, Appearance.ink(txt.color, Tool.PEN, dark))
            }
            canvas.restoreToCount(save)
        }

        override fun cost(left: Float, top: Float, right: Float, bottom: Float) = chunks.count(left, top, right, bottom)
    }

    /** Lista de floats que crece, sin cajas. */
    private class Floats {
        var data = FloatArray(512)
            private set
        var size = 0
            private set

        fun add(x: Float, y: Float) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }

        fun clear() {
            size = 0
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private companion object {
        const val NONE = -1

        // Gestos sobre lo elegido.
        const val MOVE = 1
        const val SCALE = 2
        const val ROTATE = 3

        /** La manija de girar, a esta distancia (dp) del borde de arriba. */
        const val ROTATE_OFFSET = 30f

        /** A menos de estos grados de un ángulo recto, el giro se pega. */
        const val ROTATE_SNAP = 4.0

        // Láser: la cola se apaga después de LASER_TAIL_MS, y todo se va en LASER_FADE_MS.
        const val LASER_TAIL_MS = 900L
        const val LASER_FADE_MS = 700L

        /** Un toque dura menos que esto. */
        const val TAP_MS = 350L

        /** Mantener apretado con el lazo para pegar. */
        const val LONG_PRESS_MS = 500L

        /** Margen (dp) que se deja alrededor de lo encontrado al llevar la vista hasta ahí. */
        const val FOCUS_MARGIN = 48f

        /** Alto mínimo (dp en pantalla) con que se muestra un renglón encontrado. */
        const val FOCUS_READABLE_DP = 26f

        // Lupa: alto del recuadro en dp del mundo (unos 2 renglones), y cuánto avanza.
        const val ZOOM_HEIGHT = 80f
        const val ZOOM_MIN_HEIGHT = 30f
        const val ZOOM_MAX_HEIGHT = 240f
        const val ZOOM_STEP = 0.5f
        const val ZOOM_ADVANCE_AT = 0.72f

        /** Hasta estos trazos corridos, las mallas de Android se arman antes de mostrarlos. */
        const val WARM_NOW_LIMIT = 1500

        /** Trazos a la vista a partir de los cuales conviene la imagen de toda la nota. */
        const val CROWDED = 6000

        /** Cuánto se puede agrandar esa imagen antes de que se note borrosa. */
        const val MAX_UPSCALE = 3f
        const val PALM_GRACE_MS = 400L

        /** Un clic del botón lateral dura menos que esto. */
        const val CLICK_MS = 500L

        /** Tiempo por cuadro para grabar tandas de trazos que no corren prisa. */
        const val CHUNK_BUDGET_NS = 4_000_000L

        /** Tiempo por cuadro para grabar tandas lejanas con la vista quieta. */
        const val PREWARM_NS = 3_000_000L

        /** Los trazos frescos pasan a las baldosas cuando el lápiz descansa este tiempo... */
        const val MERGE_DELAY_MS = 700L

        /** ...o cuando se juntan estos. */
        const val MAX_FRESH = 48

        /** Separación (dp) de los puntos de una línea recta. */
        const val LINE_STEP = 4f
        const val SNAP_DEGREES = 4.0
        const val STYLUS_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

        /** Lápiz quieto al apoyarlo: pasa a borrar. */
        const val ERASE_HOLD_MS = 500L

        /** Lápiz quieto al final de un trazo: figura. */
        const val SHAPE_HOLD_MS = 600L

        /** Cuánto (dp de pantalla) puede temblar el lápiz y seguir contando como quieto. */
        const val HOLD_SLOP = 4f
        const val SHAPE_POINT_MS = 40L

        // Lazo, en dp de pantalla.
        const val TAP_SLOP = 10f
        const val TOUCH_SLOP = 6f
        const val SELECTION_PAD = 8f
        const val HANDLE_RADIUS = 9f
        const val HANDLE_TOUCH = 28f
        const val MIN_SELECTION = 12f
        const val DUPLICATE_OFFSET = 24f
        const val MAX_SCALE = 20f

        /** Margen de sobra al repintar las hojas, en dp del mundo. */
        const val BIG = 4000f

        /** Arma las copias movidas: un hilo para que nunca haya dos a la vez. */
        val selectionWork = Executors.newSingleThreadExecutor { Thread(it, "tinta-seleccion") }
    }
}
