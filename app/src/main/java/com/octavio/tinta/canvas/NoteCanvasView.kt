package com.octavio.tinta.canvas

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
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
import com.octavio.tinta.storage.VistaCache
import com.octavio.tinta.model.Clipboard
import com.octavio.tinta.model.Cut
import com.octavio.tinta.model.Dash
import com.octavio.tinta.model.History
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.paintInk
import com.octavio.tinta.model.Figure2D
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
 * con baja latencia. El resaltador va debajo de la pluma y del marcador: su trazo en curso está
 * entre el papel y la capa de tinta. La pluma, al terminar, queda primero como trazo fresco y,
 * cuando descansa, en su capa; el resaltador pasa directo a las baldosas.
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

    enum class Mode { PEN, ERASER, LASSO, LASER, CAPTURE }

    interface Listener {
        fun onStrokesAdded(strokes: List<InkStroke>)

        /** Trazos que el borrador ya sacó de [strokes], con su índice en el momento de sacarlos. */
        fun onStrokesErased(removed: List<Pair<Int, InkStroke>>)

        /** Un cambio que el lienzo ya hizo en las listas de la nota (mover o borrar lo elegido). */
        fun onEdited(edit: History.Edit)

        fun onViewportChanged()

        /** Cambió lo elegido con el lazo, o su lugar en la pantalla. */
        fun onSelectionChanged() = Unit

        /** Un toque del botón lateral: al borrador, o de vuelta al primer lápiz. */
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

        /** Se eligió con la herramienta de captura el rectángulo `world` (del mundo). */
        fun onCaptureArea(world: RectF) = Unit

        /** Empezó otra captura o se borró la elegida: sacar sus opciones. */
        fun onCaptureCleared() = Unit

        /** Se dibujó el recuadro donde va a escribirse con el teclado. */
        fun onTextFrame(world: RectF) = Unit

        /** Dos toques sobre un texto: editarlo. */
        fun onTextDoubleTap(text: NoteText) = Unit
    }

    /** Con la herramienta de texto: el próximo trazo dibuja el recuadro, no tinta. */
    var placingText = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) cancelTextFrame()
        }

    val camera = Camera(resources.displayMetrics.density)
    var listener: Listener? = null

    /** La foto guardada no alcanza este zoom: hay que armar las mallas. */
    var onNeedMeshes: (() -> Unit)? = null

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

    /** Color de la hoja de la nota (null: el de siempre). */
    var paperColor: Int? = null
        set(value) {
            if (field == value) return
            field = value
            dry.setPaperColor(value)
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
            if (field == Mode.CAPTURE) clearCapture()
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
    /** Resaltador en curso: debajo de la pluma ya escrita. */
    private val wetUnder = InProgressStrokesView(context)
    /** Pluma y marcador ya terminados, encima del resaltador. */
    private val ink = InkLayer(context)
    /** Pluma o marcador en curso: encima de todo lo escrito. */
    private val wetPen = InProgressStrokesView(context)
    /** Capa que tiene el trazo que se está haciendo ahora. */
    private var strokeWet: InProgressStrokesView? = null
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
        val fill = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(dry, fill)
        addView(wetUnder, LayoutParams(fill))
        addView(ink, LayoutParams(fill))
        addView(wetPen, LayoutParams(fill))
        watchFinished(wetUnder)
        watchFinished(wetPen)
    }

    /** Al soltar, el trazo en curso pasa a la nota. Cada capa avisa de los suyos. */
    private fun watchFinished(view: InProgressStrokesView) {
        view.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                val added = strokes.flatMap { (id, s) ->
                    val p = strokePens.remove(id) ?: pen
                    // En modo oscuro el trazo en curso usa el color de pantalla; se guarda el real.
                    val color = p.tool.storedColor(p.color, p.opacity)
                    styled(InkStroke(p.tool, if (s.brush.colorIntArgb == color) s else s.copy(s.brush.copyWithColorIntArgb(color)), p.sensitivity), p.dash)
                }
                addFresh(added)
                view.removeFinishedStrokes(strokes.keys)
                if (penStroke == null) {
                    strokeWet = null
                    endWritingClip()
                }
            }
        })
    }

    private fun activeWet(): InProgressStrokesView =
        strokeWet?.takeIf { penStroke != null } ?: if (pen.tool == Tool.HIGHLIGHTER) wetUnder else wetPen

    /** Lo que tardó cada cuadro fuera del hilo principal, para ajustar cuánto pintar por cuadro. */
    private val frameMetrics = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        var ns = m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
            m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ns += m.getMetric(FrameMetrics.GPU_DURATION)
        dry.adapt(ns)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wetUnder.eagerInit()
        wetPen.eagerInit()
        (context as? Activity)?.window?.addOnFrameMetricsAvailableListener(frameMetrics, handler)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(merge)
        removeCallbacks(hold)
        removeCallbacks(eraserHold)
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
        val anchor = sheetLeft(width / 2f, height / 2f)
        val left = anchor * camera.scale + camera.tx
        if (pageWidth <= width) {
            if (abs(left - (width - pageWidth) / 2) > 1f) camera.set((width - pageWidth) / 2 - anchor * camera.scale, camera.ty, camera.zoom)
        } else if (pageWidth <= resources.displayMetrics.widthPixels * 1.02f) {
            val top = camera.toWorldY(0f)
            val zoom = (width - 24 * density) / (pages.width * density)
            val scale = zoom.coerceIn(Camera.MIN_ZOOM, Camera.MAX_ZOOM) * density
            camera.set((width - pages.width * scale) / 2 - anchor * scale, -top * scale, zoom)
        }
        dry.invalidate()
    }

    /** Borde izquierdo de la hoja que se está mirando. En vertical todas comparten el mismo. */
    private fun sheetLeft(screenX: Float, screenY: Float): Float {
        if (!pages.horizontal) return pages.left
        val k = pages.pageOf(camera.toWorldX(screenX), camera.toWorldY(screenY)).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        return pages.pageLeft(k)
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
            val anchor = sheetLeft(oldw / 2f, oldh / 2f)
            val centerY = camera.toWorldY(oldh / 2f)
            val zoom = (w - 24 * density) / (pages.width * density)
            val scale = zoom.coerceIn(Camera.MIN_ZOOM, Camera.MAX_ZOOM) * density
            camera.set((w - pages.width * scale) / 2 - anchor * scale, h / 2f - centerY * scale, zoom)
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

    /** Llegaron mallas armadas en segundo plano. No rearma la foto de la pluma: eso espera a que estén todas. */
    fun meshesChanged() = dry.meshesArrived()

    /** Anota con qué nota coincide la foto que se guarda al lado. */
    fun armVista(file: File, modified: Long, count: Int, dark: Boolean) = dry.armVista(file, modified, count, dark)

    /** Mientras se lee la foto: papel e imágenes, sin grabar trazos provisorios. */
    fun deferMeshes() {
        dry.vistaMode = VistaMode.WAIT
        dry.invalidate()
    }

    /** La foto cubre este zoom: se muestra ella y las mallas esperan. */
    fun holdVista() {
        dry.vistaMode = VistaMode.HOLD
        dry.invalidate()
    }

    /** Se dibuja la nota completa. */
    fun resumeLive() {
        if (dry.vistaMode == VistaMode.LIVE) return
        dry.vistaMode = VistaMode.LIVE
        dry.invalidate()
    }

    /** La foto de la nota, agrandada hasta [MAX_UPSCALE], sigue nítida a este zoom. */
    fun vistaCovers() = dry.vistaCovers()

    /** Hay foto en pantalla y este zoom ya pide las mallas. */
    fun vistaZoomNeedsMeshes() = dry.vistaZoomNeedsMeshes()

    /** Pone en la nota las franjas leídas del disco. */
    fun adoptVista(parts: List<Pair<Bitmap, RectF>>, scale: Float, source: List<InkStroke>) = dry.adoptVista(parts, scale, source)

    /** A partir de esta cantidad de trazos se guarda y se usa la foto de la nota. */
    fun crowdedNote(count: Int) = count >= CROWDED

    private enum class VistaMode { LIVE, WAIT, HOLD }

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
        val centerX = camera.toWorldX(width / 2f)
        val centerY = camera.toWorldY(height / 2f)
        val k = pages.pageOf(centerX, centerY).coerceIn(0, pageCount - 1)
        val widthZoom = (width - 2 * pad) / (pages.width * density)
        val pageZoom = min(widthZoom, (height - 2 * pad) / (pages.height * density))
        if (abs(camera.zoom / widthZoom - 1) > 0.02f || widthZoom <= pageZoom * 1.02f) {
            // Al ancho, sin mover lo que está en el medio de la pantalla.
            val scale = widthZoom.coerceIn(Camera.MIN_ZOOM, Camera.MAX_ZOOM) * density
            val empty = strokes.isEmpty() && images.isEmpty() && texts.isEmpty() && widgets.isEmpty()
            val above = camera.toWorldY(0f) < pages.top
            val y = if (empty || above) pages.top - pad / scale else centerY - height / 2f / scale
            camera.set((width - pages.width * scale) / 2 - pages.pageLeft(k) * scale, -y * scale, widthZoom)
        } else {
            val scale = pageZoom * density
            camera.set(
                (width - pages.width * scale) / 2 - pages.pageLeft(k) * scale,
                (height - pages.height * scale) / 2 - pages.pageTop(k) * scale,
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

    private fun addFresh(written: List<InkStroke>) {
        val added = if (pages.paged) written.flatMap(::clipToPage) else written
        if (added.isEmpty()) {
            dry.invalidate()
            return
        }
        listener?.onStrokesAdded(added) // los agrega al final de la lista de la nota
        if (added.first().tool == Tool.HIGHLIGHTER) {
            // Directo a las baldosas, debajo de la pluma. No queda un rato encima.
            dry.strokesChanged()
        } else {
            fresh.addAll(added)
            dry.freshChanged()
            scheduleMerge()
        }
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

    /** El último flanco del botón, para no contarlo dos veces (tecla y evento a la vez). */
    private var lastButtonAt = Long.MIN_VALUE

    /**
     * Sigue los botones que informa el lápiz en sus propios eventos: los deja en el registro (para
     * ver qué manda cada tablet). El botón sólo manda el flanco, no cuánto está apretado: cada
     * toque avisa con [Listener.onStylusButtonClick].
     */
    private fun logButtons(e: MotionEvent) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return // los botones del mouse son otra cosa
        val buttons = e.buttonState
        if (buttons == lastButtons) return
        val pressed = buttons and STYLUS_BUTTONS != 0
        val wasPressed = lastButtons and STYLUS_BUTTONS != 0
        lastButtons = buttons
        Diag.log("botones del lápiz: 0x${Integer.toHexString(buttons)} acción ${MotionEvent.actionToString(e.actionMasked)} herramienta ${e.getToolType(0)}")
        if ((pressed && !wasPressed) || (!pressed && wasPressed)) stylusButton(e.eventTime)
    }

    /** Un toque del botón lateral. El mismo clic puede llegar por dos caminos: cuenta una sola vez. */
    private fun stylusButton(time: Long) {
        if (time - lastButtonAt < 350L) return
        lastButtonAt = time
        listener?.onStylusButtonClick()
    }

    /** El botón lateral cuando llega como tecla (el lápiz o su control por Bluetooth). */
    fun penButton(down: Boolean, time: Long) = stylusButton(time)

    /** Dónde se apoyó la punta, para pasar al borrador si se queda quieta. */
    private var eraserFromX = 0f
    private var eraserFromY = 0f

    /** Medio segundo quieto en el mismo punto: borra hasta soltar, y al soltar sigue el lápiz. Si escribió, no. */
    private val eraserHold = Runnable { pickEraserFromHold() }

    private fun pickEraserFromHold() {
        if (penPointer == NONE || erasing || mode != Mode.PEN || penMoved || !holdToErase) return
        if (hypot(penX - eraserFromX, penY - eraserFromY) > HOLD_SLOP * density) return
        // El punto que alcanzó a empezar no queda. No cambia la herramienta: al levantar la punta se escribe de nuevo.
        penStroke?.let {
            activeWet().cancelStroke(it)
            strokePens.remove(it)
        }
        penStroke = null
        lineStart = null
        linePreview = null
        penMoved = true
        removeCallbacks(hold)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startErase(penX, penY)
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
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.ACTION_SCROLL,
            -> stylusButton(e.eventTime)
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
        if (framePointer != NONE) return
        val id = e.getPointerId(i)
        val byPen = isPen(e, i)
        if (gesturePointer != NONE || zoomDragPointer != NONE) return
        // La lupa: en el panel sólo escribe (o borra) el lápiz; un dedo sobre el recuadro lo mueve.
        if (zoomOn && zoomPanel.contains(e.getX(i), e.getY(i))) {
            if (!byPen || penPointer != NONE || mode == Mode.LASSO || mode == Mode.LASER || mode == Mode.CAPTURE) return
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
        if (placingText && framePointer == NONE && penPointer == NONE && fingers.isEmpty() && gesturePointer == NONE && zoomDragPointer == NONE) {
            if (byPen) lastPenSeen = e.eventTime
            startTextFrame(e.getX(i), e.getY(i), id)
            return
        }
        // Con el lazo, lo elegido se arrastra con el lápiz o con un dedo. El recuadro de un texto
        // también se cambia de tamaño con el lápiz, aunque la herramienta sea escribir.
        if (penPointer == NONE && fingers.isEmpty() && !wantsEraser(e, i) &&
            (byPen || e.eventTime - lastPenSeen > PALM_GRACE_MS) &&
            (mode == Mode.LASSO || selectedText != null)
        ) {
            val kind = selectionHit(e.getX(i), e.getY(i))
            if (kind != NONE && (mode == Mode.LASSO || kind == RESIZE)) {
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
                    // Un dedo corta el pase de hoja: si no, la animación y el arrastre pelean la cámara.
                    cancelFlipAnim()
                    tapPointer = id
                    tapX = e.getX(i)
                    tapY = e.getY(i)
                    tapAt = e.eventTime
                    // Con el lazo, mantener el dedo quieto ofrece pegar.
                    if (mode == Mode.LASSO || mode == Mode.CAPTURE) postDelayed(fingerHold, LONG_PRESS_MS)
                } else {
                    tapPointer = NONE
                    if (fingers.size == 2 && pages.paged) beginFlip()
                }
            }
            return
        }
        if (penPointer != NONE) return
        removeCallbacks(merge)
        fingers.clear() // el lápiz manda: corta cualquier desplazamiento en curso
        flip = null
        suppressPan = false
        cancelFlipAnim()
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
        if (mode == Mode.CAPTURE) {
            startCapture(e.getX(i), e.getY(i))
            return
        }
        if (mode == Mode.LASER) {
            laser.add(LaserTrail())
            laserPoint(e.getX(i), e.getY(i), e.eventTime)
            return
        }
        penDownAt = e.eventTime
        startWritingClip(inX(e.getX(i)), inY(e.getY(i)))
        startHold(e, i)
        if (pen.straight) {
            lineStart = PointF(inX(e.getX(i)), inY(e.getY(i)))
            linePreview = null
            return
        }
        requestUnbufferedDispatch(e)
        predictor.record(e)
        val brush = Brush.createWithColorIntArgb(pen.tool.family(pen.sensitivity), displayColor(), pen.size, epsilon())
        val layer = if (pen.tool == Tool.HIGHLIGHTER) wetUnder else wetPen
        strokeWet = layer
        val strokeId = layer.startStroke(e, id, brush, inputToWorld(scratch))
        strokePens[strokeId] = pen
        penStroke = strokeId
    }

    private fun move(e: MotionEvent) {
        if (framePointer != NONE) {
            val i = e.findPointerIndex(framePointer)
            if (i >= 0) moveTextFrame(e.getX(i), e.getY(i))
            return
        }
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
            penStroke?.let { activeWet().finishStroke(e, penPointer, it) }
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
        if (capturing) {
            moveCapture(e.getX(i), e.getY(i))
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
        activeWet().addToStroke(e, penPointer, strokeId, prediction)
        prediction?.recycle()
    }

    private fun pointerUp(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        if (id == framePointer) {
            endTextFrame(e.flags and MotionEvent.FLAG_CANCELED != 0)
            return
        }
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
                if (still && e.eventTime - tapAt < TAP_MS) {
                    val wx = camera.toWorldX(e.getX(i))
                    val wy = camera.toWorldY(e.getY(i))
                    val hit = if (placingText) null else textAt(wx, wy)
                    if (hit != null && hit === lastTextTap && e.eventTime - lastTextTapAt < DOUBLE_TAP_MS) {
                        lastTextTap = null
                        listener?.onTextDoubleTap(hit)
                    } else {
                        if (hit != null) {
                            lastTextTap = hit
                            lastTextTapAt = e.eventTime
                        }
                        mediaAt(wx, wy)?.let { listener?.onMediaTap(it) }
                    }
                }
            }
            val turning = flip != null && fingers.size >= 2
            fingers.remove(id)
            if (turning && fingers.size < 2) {
                val locked = flip?.decided == true && flip?.pinch != true
                finishFlip()
                if (locked && fingers.isNotEmpty()) suppressPan = true
            }
            if (fingers.isEmpty()) {
                suppressPan = false
                dry.invalidate()
            }
            return
        }
        lastPenSeen = e.eventTime
        removeCallbacks(hold)
        removeCallbacks(eraserHold)
        removeCallbacks(lassoHold)
        removeCallbacks(captureHold)
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
                activeWet().cancelStroke(it, e)
                strokePens.remove(it)
            }
            penStroke = null
            lineStart = null
            linePreview = null
            listener?.onMediaTap(tapped)
        }
        val textHit = textTapHit(e, i, canceled)
        if (textHit != null) {
            penStroke?.let {
                activeWet().cancelStroke(it, e)
                strokePens.remove(it)
            }
            penStroke = null
            lineStart = null
            linePreview = null
            if (textHit === lastTextTap && e.eventTime - lastTextTapAt < DOUBLE_TAP_MS) {
                lastTextTap = null
                listener?.onTextDoubleTap(textHit)
            } else {
                lastTextTap = textHit
                lastTextTapAt = e.eventTime
                select(emptyList(), emptyList(), listOf(textHit))
                listener?.onSelectionChanged()
            }
        } else {
            if (!canceled && !penMoved && e.eventTime - penDownAt < TAP_MS) lastTextTap = null
            penStroke?.let { strokeId ->
                if (canceled) {
                    activeWet().cancelStroke(strokeId, e)
                    strokePens.remove(strokeId)
                } else {
                    activeWet().finishStroke(e, id, strokeId)
                }
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
        if (capturing) endCapture(canceled)
        penPointer = NONE
        penStroke = null
        scheduleMerge()
        // Si el trazo pausó las baldosas, este cuadro las retoma.
        dry.invalidate()
        // En la lupa, al terminar cerca del borde derecho del recuadro, el recuadro avanza.
        if (penInPanel && !canceled && panelMaxX > zoomBox.left + zoomBox.width() * ZOOM_ADVANCE_AT) zoomMove(ZOOM_STEP)
        penInPanel = false
        useMainInput()
    }

    private fun cancelAll(e: MotionEvent) {
        zoomDragPointer = NONE
        cancelTextFrame()
        penInPanel = false
        useMainInput()
        removeCallbacks(hold)
        removeCallbacks(eraserHold)
        removeCallbacks(lassoHold)
        removeCallbacks(captureHold)
        removeCallbacks(fingerHold)
        tapPointer = NONE
        penStroke?.let {
            activeWet().cancelStroke(it, e)
            strokePens.remove(it)
        }
        lineStart = null
        linePreview = null
        shape = null
        shapePreview = null
        lassoPath = null
        endWritingClip()
        if (capturing) clearCapture()
        if (gesturePointer != NONE) endGesture()
        if (erasing) endErase()
        penPointer = NONE
        penStroke = null
        fingers.clear()
        flip = null
        suppressPan = false
        cancelFlipAnim()
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
        var span = 0f
        if (n >= 2) {
            var oldSpan = 0f
            for ((id, p) in fingers) {
                val i = e.findPointerIndex(id)
                if (i < 0) continue
                oldSpan += hypot(p.x - ox, p.y - oy)
                span += hypot(e.getX(i) - nx, e.getY(i) - ny)
            }
            if (oldSpan > 1f) factor = span / oldSpan
        }
        if (n >= 2 && trackFlip(n, nx, ny, span)) {
            for ((id, p) in fingers) {
                val i = e.findPointerIndex(id)
                if (i >= 0) p.set(e.getX(i), e.getY(i))
            }
            dry.invalidate()
            return
        }
        if (suppressPan) {
            for ((id, p) in fingers) {
                val i = e.findPointerIndex(id)
                if (i >= 0) p.set(e.getX(i), e.getY(i))
            }
            return
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
        // Las barras de lo elegido y de la captura siguen al recuadro.
        if (selection != null || captureRect != null) listener?.onSelectionChanged()
    }

    // --- Lápiz quieto: borrador al apoyarlo, o figura al final de un trazo ---

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
        eraserFromX = holdX
        eraserFromY = holdY
        removeCallbacks(eraserHold)
        if (holdToErase) postDelayed(eraserHold, ERASER_HOLD_MS)
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
        // Apenas sale del punto donde se apoyó, está escribiendo: este trazo no pasa al borrador.
        if (penMoved || hypot(sx - eraserFromX, sy - eraserFromY) > HOLD_SLOP * density) removeCallbacks(eraserHold)
        if (hypot(sx - holdX, sy - holdY) <= HOLD_SLOP * density) return
        // Se movió: el reloj de la figura vuelve a empezar desde acá. El del borrador, no.
        holdX = sx
        holdY = sy
        if (!penMoved && selection != null && mode == Mode.PEN) {
            selection = null
            listener?.onSelectionChanged()
        }
        penMoved = true
        removeCallbacks(eraserHold)
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
        if (!penMoved) return
        if (!holdForShapes || shape != null) return
        val strokeId = penStroke ?: return
        val found = Shapes.recognize(trace.toArray(), density / inScale) ?: return
        shapePen = strokePens.remove(strokeId) ?: pen
        activeWet().cancelStroke(strokeId)
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
        return runCatching { InkStroke(p.tool, Stroke(p.tool.brush(p.color, p.size, epsilon(), p.sensitivity, p.opacity), batch), p.sensitivity) }.getOrNull()
    }

    private fun commitShape() {
        val s = shapePreview
        val dash = (shapePen ?: pen).dash
        shape = null
        shapePreview = null
        shapePen = null
        if (s != null) addFresh(styled(s, dash)) else dry.invalidate()
        if (penStroke == null) endWritingClip()
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
        return InkStroke(pen.tool, Stroke(pen.tool.brush(pen.color, pen.size, epsilon(), pen.sensitivity, pen.opacity), batch), pen.sensitivity)
    }

    private fun commitLine() {
        val line = linePreview
        lineStart = null
        linePreview = null
        if (line != null) addFresh(styled(line, pen.dash)) else dry.invalidate()
        endWritingClip()
    }

    // --- Escribir sólo en la hoja ---

    /**
     * En una nota con hojas (todas menos la infinita) no se escribe afuera: el trazo queda en la
     * hoja donde empezó (o la más cercana) y lo que sale de ella no se ve ni se guarda. Así el PDF
     * tiene todo lo escrito.
     */
    private var writingClip: RectF? = null

    private fun pageRect(k: Int): RectF {
        val l = pages.pageLeft(k)
        val t = pages.pageTop(k)
        return RectF(l, t, l + pages.width, t + pages.height)
    }

    private fun pageForWriting(x: Float, y: Float) = pageRect(pages.pageOf(x, y).coerceIn(0, (pageCount - 1).coerceAtLeast(0)))

    private fun startWritingClip(worldX: Float, worldY: Float) {
        if (!pages.paged) {
            endWritingClip()
            return
        }
        val r = pageForWriting(worldX, worldY)
        writingClip = r
        // El trazo en curso lo dibuja la capa de Ink, en la pantalla (o en el panel de la lupa).
        val clip = android.graphics.Rect(
            floor(r.left * inScale + inTx).toInt(), floor(r.top * inScale + inTy).toInt(),
            ceil(r.right * inScale + inTx).toInt(), ceil(r.bottom * inScale + inTy).toInt(),
        )
        wetUnder.clipBounds = clip
        wetPen.clipBounds = android.graphics.Rect(clip)
    }

    private fun endWritingClip() {
        writingClip = null
        wetUnder.clipBounds = null
        wetPen.clipBounds = null
    }

    /** Lo que queda del trazo dentro de la hoja donde empezó (ver [writingClip]). */
    private fun clipToPage(s: InkStroke): List<InkStroke> {
        val stroke = s.stroke ?: return listOf(s)
        val inputs = stroke.inputs
        if (inputs.size == 0) return listOf(s)
        val p = StrokeInput()
        inputs.populate(0, p)
        val page = pageForWriting(p.x, p.y)
        // La tinta entera adentro: queda como está (casi siempre).
        if (s.left >= page.left && s.right <= page.right && s.top >= page.top && s.bottom <= page.bottom) return listOf(s)
        // Medio grosor adentro del borde, para que la tinta no se asome fuera de la hoja.
        val pad = (s.size / 2).coerceAtMost(min(page.width(), page.height()) / 4)
        val points = ArrayList<Cut.Point>(inputs.size)
        for (k in 0 until inputs.size) {
            inputs.populate(k, p)
            points.add(Cut.Point(p.x, p.y, p.elapsedTimeMillis.toFloat(), p.pressure, p.tiltRadians, p.orientationRadians))
        }
        val parts = Cut.inside(points, page.left + pad, page.top + pad, page.right - pad, page.bottom - pad, (s.size / 2).coerceAtLeast(0.5f))
            ?: return listOf(s)
        return rebuild(s, stroke, parts)
    }

    /**
     * Un trazo de un lápiz a rayas o de puntos: se parte en los tramos con tinta (cada uno es un
     * trazo). Mientras se escribe se ve continuo; al levantar el lápiz queda con su patrón.
     */
    private fun styled(s: InkStroke, dash: Dash): List<InkStroke> {
        val lengths = dash.lengths(s.size) ?: return listOf(s)
        val stroke = s.stroke ?: return listOf(s)
        val inputs = stroke.inputs
        if (inputs.size < 2) return listOf(s)
        val p = StrokeInput()
        val points = ArrayList<Cut.Point>(inputs.size)
        for (k in 0 until inputs.size) {
            inputs.populate(k, p)
            points.add(Cut.Point(p.x, p.y, p.elapsedTimeMillis.toFloat(), p.pressure, p.tiltRadians, p.orientationRadians))
        }
        val pieces = rebuild(s, stroke, Dash.split(points, lengths))
        if (pieces.isEmpty()) return listOf(s)
        val g = InkStroke.newGroup()
        for (piece in pieces) piece.group = g
        return pieces
    }

    /** Arma un trazo por cada parte (puntos de [Cut]) con el pincel del original. */
    private fun rebuild(s: InkStroke, stroke: Stroke, parts: List<List<Cut.Point>>): List<InkStroke> {
        val inputs = stroke.inputs
        val type = inputs.getToolType()
        val unit = if (inputs.hasStrokeUnitLength()) inputs.getStrokeUnitLengthCm() else StrokeInput.NO_STROKE_UNIT_LENGTH
        return parts.mapNotNull { part ->
            runCatching {
                val batch = MutableStrokeInputBatch()
                val t0 = part[0].t
                for (q in part) batch.add(type, q.x, q.y, (q.t - t0).toLong().coerceAtLeast(0L), unit, q.pressure, q.tilt, q.orientation)
                InkStroke(s.tool, Stroke(stroke.brush, batch), s.sensitivity, s.group)
            }.getOrNull()
        }
    }

    // --- Borrador ---

    /** Lo que hizo el borrador parcial en esta pasada, en orden, para deshacerlo de una vez. */
    private val cutEdits = ArrayList<History.Edit>()

    /** Las líneas a rayas que el borrador parcial cortó en esta pasada. */
    private val cutGroups = HashSet<Long>()

    // --- Líneas a rayas que quedan partidas ---

    /**
     * Cambia el número de línea de algunos trazos (un paso de deshacer): `changes` son los trazos
     * y su número nuevo. Null si no hay nada que cambiar.
     */
    private fun regroup(changes: List<Pair<InkStroke, Long>>): History.Edit? {
        if (changes.isEmpty()) return null
        val old = changes.map { it.first to it.first.group }
        for ((st, g) in changes) st.group = g
        return History.Change({ for ((st, g) in old) st.group = g }, { for ((st, g) in changes) st.group = g })
    }

    /**
     * Las líneas `groups` que quedaron con un hueco más grande que el de su patrón (cortadas por el
     * borrador, o separadas al insertar una hoja) se parten: cada tramo seguido es una línea. Las
     * rayas están en la lista en el orden en que se dibujaron.
     */
    private fun splitBrokenLines(groups: Set<Long>): History.Edit? {
        val changes = ArrayList<Pair<InkStroke, Long>>()
        for (g in groups) {
            val members = strokes.filter { it.group == g }
            if (members.size < 2) continue
            var current = g
            var last = members[0].points()
            for (k in 1 until members.size) {
                val st = members[k]
                val pts = st.points()
                if (last.size >= 2 && pts.size >= 2) {
                    val gap = hypot(pts[0] - last[last.size - 2], pts[1] - last[last.size - 1])
                    if (gap > maxDashGap(st.size)) current = InkStroke.newGroup()
                }
                if (current != g) changes.add(st to current)
                last = pts
            }
        }
        return regroup(changes)
    }

    /** El hueco más grande que puede haber entre dos rayas seguidas de una línea de grosor `size`. */
    private fun maxDashGap(size: Float) = Dash.entries.maxOf { d -> d.lengths(size)?.filterIndexed { i, _ -> i % 2 == 1 }?.maxOrNull() ?: 0f } * 1.6f + 1f

    /**
     * Al mover una parte de una línea a rayas (el lazo agarró algunas rayas), la parte movida pasa
     * a ser otra línea: borrarla no tiene que borrar lo que quedó en su lugar.
     */
    private fun separateMoved(moved: List<InkStroke>): History.Edit? {
        val groups = moved.mapNotNullTo(HashSet()) { it.group.takeIf { g -> g != 0L } }
        if (groups.isEmpty()) return null
        val movedSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InkStroke, Boolean>()).apply { addAll(moved) }
        val stayed = strokes.filter { it.group in groups && it !in movedSet }.mapTo(HashSet()) { it.group }
        if (stayed.isEmpty()) return null
        val renamed = HashMap<Long, Long>()
        return regroup(moved.filter { it.group in stayed }.map { it to renamed.getOrPut(it.group) { InkStroke.newGroup() } })
    }

    private fun startErase(sx: Float, sy: Float) {
        // Lo elegido con el lazo podría borrarse (una raya de su misma línea, por ejemplo): se suelta.
        if (selection != null) clearSelection()
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
        var groups: HashSet<Long>? = null
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
                    if (s.group != 0L) cutGroups.add(s.group)
                    strokes.removeAt(i)
                    cutEdits.add(History.Remove(strokes, listOf(i to s)))
                    if (pieces.isNotEmpty()) {
                        strokes.addAll(i, pieces)
                        cutEdits.add(History.Insert(strokes, i, pieces))
                    }
                } else {
                    strokes.removeAt(i)
                    erased.add(i to s)
                    if (s.group != 0L) (groups ?: HashSet<Long>().also { groups = it }).add(s.group)
                }
                changed = true
            }
        }
        // Una raya de una línea a rayas o de puntos: se va la línea entera.
        groups?.let { g ->
            for (j in strokes.indices.reversed()) {
                val s = strokes[j]
                if (s.group != 0L && s.group in g) {
                    strokes.removeAt(j)
                    erased.add(j to s)
                }
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
        return rebuild(s, stroke, parts)
    }

    private fun endErase() {
        erasing = false
        if (erased.isNotEmpty()) listener?.onStrokesErased(ArrayList(erased))
        erased.clear()
        // Una línea a rayas cortada por la mitad queda en dos líneas.
        if (cutGroups.isNotEmpty()) splitBrokenLines(cutGroups)?.let(cutEdits::add)
        cutGroups.clear()
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
            for (t in texts) {
                // Un texto solo se elige por su recuadro: los bordes cambian ese tamaño, no la letra.
                if (texts.size == 1 && strokes.isEmpty() && images.isEmpty() && widgets.isEmpty()) {
                    add(t.left, t.top, t.left + t.width, t.top + t.height)
                } else {
                    Elements.textBounds(t).let { add(it.left, it.top, it.right, it.bottom) }
                }
            }
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

    /** Borde del recuadro de texto que se está arrastrando: 1 izquierda, 2 derecha, 4 arriba, 8 abajo. */
    private var hitEdge = 0
    private var resizeEdge = 0
    private var resizeText: NoteText? = null
    private val resizeBox = RectF()

    val hasSelection get() = selection != null

    /** Lo elegido tiene algo a lo que se le puede cambiar el color. */
    val selectionHasInk
        get() = selection?.let { s -> s.strokes.isNotEmpty() || s.texts.isNotEmpty() || s.widgets.any { it !is Media } } == true

    /** Si lo elegido es una sola imagen, para recortarla. */
    val selectedImage get() = selection?.takeIf { it.count == 1 }?.images?.firstOrNull()

    /** Si lo elegido es un solo widget, para editarlo o reproducirlo. */
    val selectedWidget get() = selection?.takeIf { it.count == 1 }?.widgets?.firstOrNull()

    /** Si lo elegido es un solo texto, para cambiar lo escrito. */
    val selectedText get() = selection?.takeIf { it.count == 1 }?.texts?.firstOrNull()

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
        val box = if (resizeText != null) resizeBox else sel.bounds
        val l = box.left - pad
        val t = box.top - pad
        val r = box.right + pad
        val b = box.bottom + pad
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
        if (gesture == RESIZE) {
            gesturePointer = NONE
            gesture = NONE
            resizeText = null
        }
        finishPending()
        if (selection != null) selection = null
    }

    /** Elige estas cosas, que tienen que estar en la nota (por ejemplo, una imagen recién puesta). */
    fun select(strokes: List<InkStroke>, images: List<NoteImage>, texts: List<NoteText>, widgets: List<Widget> = emptyList()) {
        finishPending()
        selection = Selection(strokes, images, texts, widgets)
    }

    /** Agrega imágenes, widgets o textos a la nota (un paso de deshacer) y los deja elegidos. */
    fun insert(newImages: List<NoteImage>, newWidgets: List<Widget>, newTexts: List<NoteText> = emptyList()) {
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
        if (newTexts.isNotEmpty()) {
            texts.addAll(newTexts)
            edits.add(History.Add(texts, newTexts))
        }
        if (edits.isEmpty()) return
        listener?.onEdited(History.Group(edits))
        val s = Selection(emptyList(), newImages, newTexts, newWidgets)
        selection = s
        dry.contentChanged()
        dry.damage(s.bounds)
    }

    /** Cambia el texto elegido por otro (un paso de deshacer) y lo deja elegido. */
    fun replaceSelectedText(updated: NoteText) {
        finishPending()
        val sel = selection ?: return
        if (sel.texts.isEmpty()) return
        val edits = ArrayList<History.Edit>()
        removeFrom(texts, sel.texts)?.let(edits::add)
        texts.add(updated)
        edits.add(History.Add(texts, listOf(updated)))
        listener?.onEdited(History.Group(edits))
        val s = Selection(emptyList(), emptyList(), listOf(updated), emptyList())
        dry.damage(sel.bounds)
        dry.damage(s.bounds)
        dry.contentChanged()
        selection = s
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
                is Figure2D -> w.copy(color = color)
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
        addTransformed(sel.strokes, sel.images, sel.texts, sel.widgets, Transform.translation(off, off), emptyList(), copies = true)
    }

    /** Pega lo copiado (o un sticker) centrado en (x, y) del mundo, o en el centro de la pantalla, y lo deja elegido. */
    fun paste(clip: Clipboard.Clip, x: Float? = null, y: Float? = null) {
        finishPending()
        val c = visibleCenter()
        addTransformed(
            clip.strokes, clip.images, clip.texts, clip.widgets,
            Transform.translation((x ?: c.x) - clip.bounds.centerX(), (y ?: c.y) - clip.bounds.centerY()), emptyList(), copies = true,
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
        if (tapPointer == NONE || (mode != Mode.LASSO && mode != Mode.CAPTURE) || fingers.size != 1) return@Runnable
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
        val edge = textResizeEdge(sx, sy)
        if (edge != 0) {
            hitEdge = edge
            return RESIZE
        }
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

    /**
     * Si (sx, sy) cae en un borde o una esquina del recuadro de un texto elegido, qué bordes mueve
     * (1 izquierda, 2 derecha, 4 arriba, 8 abajo). 0 si no es un texto solo o no tocó un borde.
     */
    private fun textResizeEdge(sx: Float, sy: Float): Int {
        val t = selectedText ?: return 0
        if (selection?.count != 1) return 0
        val box = if (resizeText === t) resizeBox else RectF(t.left, t.top, t.left + t.width, t.top + t.height)
        val l = screenX(box.left)
        val top = screenY(box.top)
        val r = screenX(box.right)
        val b = screenY(box.bottom)
        val touch = HANDLE_TOUCH * density
        val spots = arrayOf(
            floatArrayOf(l, top, 5f),
            floatArrayOf((l + r) / 2f, top, 4f),
            floatArrayOf(r, top, 6f),
            floatArrayOf(r, (top + b) / 2f, 2f),
            floatArrayOf(r, b, 10f),
            floatArrayOf((l + r) / 2f, b, 8f),
            floatArrayOf(l, b, 9f),
            floatArrayOf(l, (top + b) / 2f, 1f),
        )
        var best = 0
        var bestD = touch
        for (s in spots) {
            val d = hypot(sx - s[0], sy - s[1])
            if (d < bestD) {
                bestD = d
                best = s[2].toInt()
            }
        }
        return best
    }

    /** El texto tal como se está viendo: con el recuadro nuevo mientras se arrastra un borde. */
    private fun shownText(t: NoteText): NoteText {
        val live = resizeText
        if (live !== t) return t
        return NoteText(resizeBox.left, resizeBox.top, resizeBox.width().coerceAtLeast(1f), resizeBox.height().coerceAtLeast(1f), t.text, t.size, t.color, t.font, t.clip)
    }

    private fun moveTextResize(sx: Float, sy: Float) {
        val t = resizeText ?: return
        if (!gestureMoved) {
            if (hypot(sx - gestureX, sy - gestureY) < TOUCH_SLOP * density) return
            gestureMoved = true
        }
        val x = camera.toWorldX(sx)
        val y = camera.toWorldY(sy)
        val minW = max(t.size * 2f, 48f)
        val minH = max(t.size * 1.3f, 28f)
        var l = t.left
        var r = t.left + t.width
        var top = t.top
        var b = t.top + t.height
        if (resizeEdge and 1 != 0) l = min(x, r - minW)
        if (resizeEdge and 2 != 0) r = max(x, l + minW)
        if (resizeEdge and 4 != 0) top = min(y, b - minH)
        if (resizeEdge and 8 != 0) b = max(y, top + minH)
        resizeBox.set(l, top, r, b)
        dry.zoomDirty = true
        dry.invalidate()
        listener?.onSelectionChanged()
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
        if (kind == RESIZE) {
            val t = selectedText ?: return
            resizeText = t
            resizeBox.set(t.left, t.top, t.left + t.width, t.top + t.height)
            resizeEdge = hitEdge
        }
    }

    private fun moveGesture(e: MotionEvent) {
        val i = e.findPointerIndex(gesturePointer)
        if (i < 0) return
        val sx = e.getX(i)
        val sy = e.getY(i)
        if (gesture == RESIZE) {
            moveTextResize(sx, sy)
            return
        }
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
        if (gesture == RESIZE) {
            val moved = gestureMoved
            val t = resizeText
            gesturePointer = NONE
            gesture = NONE
            resizeText = null
            if (t != null && moved) {
                val w = resizeBox.width().coerceAtLeast(1f)
                val h = resizeBox.height().coerceAtLeast(1f)
                replaceSelectedText(NoteText(resizeBox.left, resizeBox.top, w, h, t.text, t.size, t.color, t.font, t.clip))
            } else {
                dry.invalidate()
            }
            return
        }
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
        InkDraw.use { r ->
            paintInk(sel.strokes) { s -> r.draw(rc, s.display(dark) ?: return@paintInk, m) }
        }
        for (t in sel.texts) Elements.drawText(rc, t, dry.textPaint, Appearance.ink(t.color, Tool.PEN, dark))
        node.endRecording()
        lifted = Lifted(removed, node, b)
        dry.strokesChanged()
        // También si sólo hay tinta: si no, la baldosa sigue mostrando el original hasta soltar.
        dry.damage(b)
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
        copies: Boolean = false,
    ) {
        val dir = mediaDir
        val task = FutureTask(Callable {
            val moved = Work.map(source) { it.transformedBuilt(t) }
            // Una copia no es la misma línea: borrar la copia no tiene que borrar el original.
            if (copies) {
                val renamed = HashMap<Long, Long>()
                for (s in moved) if (s.group != 0L) s.group = renamed.getOrPut(s.group) { InkStroke.newGroup() }
            }
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
            if (!copies) separateMoved(a.strokes)?.let(edits::add)
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

    // --- Pasar de hoja con dos dedos ---

    /** Gesto de dos dedos que puede terminar en un pase de hoja. */
    private class Flip(
        val page: Int,
        val tx: Float,
        val ty: Float,
        val zoom: Float,
        val x0: Float,
        val y0: Float,
        val span0: Float,
        var along: Float = 0f,
        var decided: Boolean = false,
        var pinch: Boolean = false,
        var lastNs: Long = System.nanoTime(),
        var velocity: Float = 0f,
    )

    private class FlipAnim(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val t0: Long)

    private var flip: Flip? = null
    private var flipAnim: FlipAnim? = null

    /** Quedó un dedo después de pasar la hoja: no arrastra la vista hasta que se levanta. */
    private var suppressPan = false

    private val flipShade = Paint().apply { color = 0x28000000 }

    private val flipTick: Runnable = Runnable {
        val a = flipAnim ?: return@Runnable
        val t = ((System.nanoTime() - a.t0) / FLIP_NS.toFloat()).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t)
        camera.set(a.x0 + (a.x1 - a.x0) * e, a.y0 + (a.y1 - a.y0) * e, camera.zoom)
        dry.invalidate()
        if (t < 1f) {
            postOnAnimation(flipTick)
        } else {
            flipAnim = null
            listener?.onViewportChanged()
            listener?.onSelectionChanged()
        }
    }

    private fun cancelFlipAnim() {
        flipAnim = null
        removeCallbacks(flipTick)
    }

    private fun beginFlip() {
        if (!pages.paged || fingers.size < 2) return
        cancelFlipAnim()
        var sx = 0f
        var sy = 0f
        for (p in fingers.values) {
            sx += p.x
            sy += p.y
        }
        val n = fingers.size
        val cx = sx / n
        val cy = sy / n
        var span = 0f
        for (p in fingers.values) span += hypot(p.x - cx, p.y - cy)
        flip = Flip(currentPage(), camera.tx, camera.ty, camera.zoom, cx, cy, span.coerceAtLeast(1f))
    }

    /**
     * Dos dedos en el sentido de las hojas: la vista sigue el dedo y, al soltar, pasa a la de al
     * lado. Un pellizco sigue siendo zoom. Devuelve true si este movimiento ya es un pase de hoja.
     */
    private fun trackFlip(n: Int, nx: Float, ny: Float, span: Float): Boolean {
        val f = flip ?: return false
        if (n >= 3) {
            f.pinch = true
            return false
        }
        if (f.pinch) return false
        val along = if (pages.horizontal) nx - f.x0 else ny - f.y0
        val across = if (pages.horizontal) ny - f.y0 else nx - f.x0
        val now = System.nanoTime()
        val dt = (now - f.lastNs) / 1e9f
        if (dt > 0.004f) {
            f.velocity = (along - f.along) / dt
            f.lastNs = now
        }
        f.along = along
        val ratio = span / f.span0
        val pinching = ratio > 1.07f || ratio < 0.93f
        if (!f.decided) {
            if (pinching) {
                f.pinch = true
                return false
            }
            if (abs(along) > FLIP_ARM_DP * density && abs(along) > abs(across) * 1.2f) f.decided = true
            else return false
        }
        val pagePx = pages.step * camera.scale
        val pull = along.coerceIn(-pagePx * FLIP_FOLLOW, pagePx * FLIP_FOLLOW)
        if (pages.horizontal) camera.set(f.tx + pull, f.ty, f.zoom) else camera.set(f.tx, f.ty + pull, f.zoom)
        return true
    }

    private fun finishFlip() {
        val f = flip ?: return
        flip = null
        if (!f.decided || f.pinch || !pages.paged) return
        val pagePx = pages.step * camera.scale
        val threshold = min(FLIP_COMMIT_DP * density, pagePx * 0.18f)
        val fling = abs(f.velocity) > 1400f
        val next = f.along < -threshold || (fling && f.velocity < -700f && f.along < 0f)
        val prev = f.along > threshold || (fling && f.velocity > 700f && f.along > 0f)
        val target = when {
            next -> (f.page + 1).coerceAtMost(pageCount - 1)
            prev -> (f.page - 1).coerceAtLeast(0)
            else -> f.page
        }
        if (target != f.page) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val delta = (pages.pageStart(f.page) - pages.pageStart(target)) * (f.zoom * density)
        val x1 = if (pages.horizontal) f.tx + delta else f.tx
        val y1 = if (pages.horizontal) f.ty else f.ty + delta
        startFlipAnim(camera.tx, camera.ty, x1, y1)
    }

    private fun startFlipAnim(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (x0 == x1 && y0 == y1) {
            flipAnim = null
            listener?.onViewportChanged()
            dry.invalidate()
            return
        }
        flipAnim = FlipAnim(x0, y0, x1, y1, System.nanoTime())
        postOnAnimation(flipTick)
    }

    /** Una franja suave en el borde por donde entra la hoja. Una sola recta por cuadro. */
    private fun drawFlipShade(canvas: Canvas) {
        if (!pages.paged) return
        val drag = flip?.takeIf { it.decided && !it.pinch }
        val anim = flipAnim
        if (drag == null && anim == null) return
        val pagePx = (pages.step * camera.scale).coerceAtLeast(1f)
        val along = if (anim != null) {
            val axis = if (pages.horizontal) camera.tx else camera.ty
            val target = if (pages.horizontal) anim.x1 else anim.y1
            axis - target
        } else {
            drag?.along ?: 0f
        }
        val forward = if (anim != null) {
            if (pages.horizontal) anim.x1 < anim.x0 else anim.y1 < anim.y0
        } else {
            along < 0f
        }
        val mag = (abs(along) / pagePx).coerceIn(0f, FLIP_FOLLOW) / FLIP_FOLLOW
        if (mag < 0.02f) return
        val depth = 28f * density * mag
        flipShade.color = ((48 * mag).toInt().coerceIn(0, 48) shl 24)
        val next = forward
        val w = width.toFloat()
        val h = height.toFloat()
        if (pages.horizontal) {
            if (next) canvas.drawRect(w - depth, 0f, w, h, flipShade) else canvas.drawRect(0f, 0f, depth, h, flipShade)
        } else if (next) {
            canvas.drawRect(0f, h - depth, w, h, flipShade)
        } else {
            canvas.drawRect(0f, 0f, w, depth, flipShade)
        }
    }

    // --- Hojas ---

    /** Se están corriendo las hojas (en segundo plano). */
    private var shifting = false

    /** La hoja que está en el medio de la pantalla. */
    fun currentPage(): Int = pages.pageOf(camera.toWorldX(width / 2f), camera.toWorldY(height / 2f)).coerceIn(0, pageCount - 1)

    /** Agrega una hoja en blanco al final y la muestra. */
    fun addPageAtEnd() {
        if (!pages.paged) return
        finishPending()
        val k = pageCount
        setPages(pages.copy(minPages = k + 1), pages)
        // Que se vea la hoja nueva, al mismo zoom.
        val pad = 12 * density
        if (pages.horizontal) camera.set(-pages.pageLeft(k) * camera.scale + pad, camera.ty, camera.zoom)
        else camera.set(camera.tx, -pages.pageTop(k) * camera.scale + pad, camera.zoom)
        dry.invalidate()
        listener?.onViewportChanged()
    }

    /**
     * Pasa las hojas a una debajo de la otra o de costado. Lo escrito se queda en la misma hoja.
     * `onDone` se llama al terminar, en el hilo de la pantalla.
     */
    fun setDirection(horizontal: Boolean, onDone: () -> Unit = {}) {
        if (!pages.paged || shifting || pages.horizontal == horizontal) {
            onDone()
            return
        }
        finishPending()
        clearSelection()
        mergeFresh()
        cancelFlipAnim()
        flip = null
        val old = pages
        val next = old.copy(horizontal = horizontal)
        val here = currentPage()
        fun delta(x: Float, y: Float): Pair<Float, Float> {
            val p = old.pageOf(x, y).coerceAtLeast(0)
            return (next.pageLeft(p) - old.pageLeft(p)) to (next.pageTop(p) - old.pageTop(p))
        }
        val movedStrokes = ArrayList<Triple<InkStroke, Float, Float>>()
        for (st in strokes) {
            if (st.left.isNaN()) continue
            val (dx, dy) = delta((st.left + st.right) / 2, (st.top + st.bottom) / 2)
            if (dx != 0f || dy != 0f) movedStrokes.add(Triple(st, dx, dy))
        }
        fun <T> moved(items: List<T>, x: (T) -> Float, y: (T) -> Float): List<Triple<T, Float, Float>> {
            val out = ArrayList<Triple<T, Float, Float>>()
            for (it in items) {
                val (dx, dy) = delta(x(it), y(it))
                if (dx != 0f || dy != 0f) out.add(Triple(it, dx, dy))
            }
            return out
        }
        val movedImages = moved(images, { it.centerX }, { it.centerY })
        val movedTexts = moved(texts, { Elements.textBounds(it).centerX() }, { Elements.textBounds(it).centerY() })
        val movedWidgets = moved(widgets, { it.centerX }, { it.centerY })
        if (movedStrokes.isEmpty() && movedImages.isEmpty() && movedTexts.isEmpty() && movedWidgets.isEmpty()) {
            setPages(next, old)
            scrollToPage(here)
            onDone()
            return
        }
        shifting = true
        val bulk = movedStrokes.size > WARM_NOW_LIMIT
        val task = FutureTask(Callable {
            val strokesCopy = Work.map(movedStrokes) { (st, dx, dy) -> st.transformedBuilt(Transform.translation(dx, dy)) }
            if (!bulk) InkDraw.warmNow(strokesCopy)
            Added(
                strokesCopy,
                movedImages.map { (i, dx, dy) -> i.transformed(Transform.translation(dx, dy)) },
                movedTexts.map { (t, dx, dy) -> t.transformed(Transform.translation(dx, dy)) },
                movedWidgets.map { (w, dx, dy) -> w.transformed(Transform.translation(dx, dy)) },
            )
        })
        val p = Pending(task) { a ->
            val edits = listOfNotNull(
                removeFrom(strokes, movedStrokes.map { it.first }),
                removeFrom(images, movedImages.map { it.first }),
                removeFrom(texts, movedTexts.map { it.first }),
                removeFrom(widgets, movedWidgets.map { it.first }),
            ).toMutableList<History.Edit>()
            strokes.addAll(a.strokes)
            images.addAll(a.images)
            texts.addAll(a.texts)
            widgets.addAll(a.widgets)
            if (a.strokes.isNotEmpty()) edits.add(History.Add(strokes, a.strokes))
            if (a.images.isNotEmpty()) edits.add(History.Add(images, a.images))
            if (a.texts.isNotEmpty()) edits.add(History.Add(texts, a.texts))
            if (a.widgets.isNotEmpty()) edits.add(History.Add(widgets, a.widgets))
            edits.add(History.Change({ setPages(old, null) }, { setPages(next, null) }))
            setPages(next, null)
            listener?.onEdited(History.Group(edits))
            shifting = false
            dry.strokesChanged()
            elementsChanged()
            scrollToPage(here)
            if (bulk) InkDraw.warm(a.strokes) { false }
            onDone()
        }
        pending = p
        selectionWork.execute {
            task.run()
            post { complete(p) }
        }
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

    /**
     * Inserta en `at` una hoja con el fondo `paper` (el de la nota si es el mismo). Lo que estaba
     * ahí y más abajo baja una hoja. Un paso de deshacer.
     */
    fun insertSheet(at: Int, paper: Background, onDone: () -> Unit) {
        if (!pages.paged || shifting) {
            onDone()
            return
        }
        val index = at.coerceIn(0, pageCount)
        val id = if (paper == paperStyle) -1 else paper.id
        remapPages(pageCount + 1, onDone, fillPaper = index to id) { p -> if (p < index) p else p + 1 }
    }

    /** Una copia de la hoja `k` justo después, con su fondo y su nombre. Un paso de deshacer. */
    fun duplicatePage(k: Int, onDone: () -> Unit) {
        if (!pages.paged || shifting || k !in 0 until pageCount) {
            onDone()
            return
        }
        remapPages(pageCount + 1, onDone, copyFrom = setOf(k)) { p -> if (p < k) p else p + 1 }
    }

    /** Le pone nombre a la hoja `k` (vacío se lo saca). Un paso de deshacer. */
    fun renamePage(k: Int, name: String) {
        if (!pages.paged || k < 0) return
        val next = pages.withName(k, name)
        if (next == pages) return
        setPages(next, pages)
    }

    /** El fondo de la hoja `k`. Si es el de la nota, la hoja vuelve a seguirlo. */
    fun setPagePaper(k: Int, paper: Background) {
        if (!pages.paged || k !in 0 until pageCount) return
        val next = pages.withPaper(k, if (paper == paperStyle) null else paper)
        if (next == pages) return
        setPages(next, pages)
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
     * `newPage(p)`, o se borra si da null. Lo que no cambia de hoja no se toca. Con `copyFrom`, lo
     * de esas hojas se queda y además se copia a `newPage`. Las copias corridas se arman en segundo
     * plano; mientras tanto no se puede escribir.
     */
    private fun remapPages(
        newCount: Int,
        onDone: () -> Unit,
        copyFrom: Set<Int> = emptySet(),
        fillPaper: Pair<Int, Int>? = null,
        newPage: (Int) -> Int?,
    ) {
        if (shifting) return
        finishPending()
        clearSelection()
        mergeFresh()
        val old = pages
        val sideways = old.horizontal
        fun shift(x: Float, y: Float): Float? {
            val p = old.pageOf(x, y)
            if (p < 0 || p in copyFrom) return 0f
            val q = newPage(p) ?: return null
            return (q - p) * old.step
        }
        fun copyOf(x: Float, y: Float): Float? {
            val p = old.pageOf(x, y)
            if (p !in copyFrom || p < 0) return null
            val q = newPage(p) ?: return null
            val d = (q - p) * old.step
            return if (d == 0f) null else d
        }
        val movedStrokes = ArrayList<Pair<InkStroke, Float>>()
        val goneStrokes = ArrayList<InkStroke>()
        val copiedStrokes = ArrayList<Pair<InkStroke, Float>>()
        for (st in strokes) {
            if (st.left.isNaN()) continue
            val midX = (st.left + st.right) / 2
            val midY = (st.top + st.bottom) / 2
            val d = shift(midX, midY)
            if (d == null) goneStrokes.add(st) else if (d != 0f) movedStrokes.add(st to d)
            copyOf(midX, midY)?.let { copiedStrokes.add(st to it) }
        }
        fun <T> split(items: List<T>, x: (T) -> Float, y: (T) -> Float): Triple<List<Pair<T, Float>>, List<T>, List<Pair<T, Float>>> {
            val moved = ArrayList<Pair<T, Float>>()
            val gone = ArrayList<T>()
            val copied = ArrayList<Pair<T, Float>>()
            for (it in items) {
                val d = shift(x(it), y(it))
                if (d == null) gone.add(it) else if (d != 0f) moved.add(it to d)
                val copy = copyOf(x(it), y(it))
                if (copy != null) copied.add(it to copy)
            }
            return Triple(moved, gone, copied)
        }
        val (movedImages, goneImages, copiedImages) = split(images, { it.centerX }, { it.centerY })
        val (movedTexts, goneTexts, copiedTexts) = split(texts, { Elements.textBounds(it).centerX() }, { Elements.textBounds(it).centerY() })
        val (movedWidgets, goneWidgets, copiedWidgets) = split(widgets, { it.centerX }, { it.centerY })
        val new = old.remapSheets(max(1, newCount), newPage, copyFrom, fillPaper)
        shifting = true
        val bulk = movedStrokes.size + copiedStrokes.size > WARM_NOW_LIMIT
        fun slide(d: Float) = Transform.translation(if (sideways) d else 0f, if (sideways) 0f else d)
        val task = FutureTask(Callable {
            val strokesCopy = Work.map(movedStrokes) { (st, d) -> st.transformedBuilt(slide(d)) }
            val strokesDup = Work.map(copiedStrokes) { (st, d) -> st.transformedBuilt(slide(d)) }
            // Una copia no es la misma línea: borrar una no tiene que borrar la otra.
            val renamed = HashMap<Long, Long>()
            for (s in strokesDup) if (s.group != 0L) s.group = renamed.getOrPut(s.group) { InkStroke.newGroup() }
            val built = strokesCopy + strokesDup
            // Muchos trazos: las mallas de Android se calientan después, de a poco.
            if (!bulk) InkDraw.warmNow(built)
            Added(
                built,
                movedImages.map { (i, d) -> i.transformed(slide(d)) } + copiedImages.map { (i, d) -> i.transformed(slide(d)) },
                movedTexts.map { (t, d) -> t.transformed(slide(d)) } + copiedTexts.map { (t, d) -> t.transformed(slide(d)) },
                movedWidgets.map { (w, d) -> w.transformed(slide(d)) } + copiedWidgets.map { (w, d) -> w.transformed(slide(d)) },
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
            // Una línea a rayas que cruzaba el lugar de la hoja nueva queda en dos.
            a.strokes.mapNotNullTo(HashSet()) { it.group.takeIf { g -> g != 0L } }.takeIf { it.isNotEmpty() }?.let { g ->
                splitBrokenLines(g)?.let(edits::add)
            }
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

    /** Lleva la vista a la hoja `k`, con su borde de arriba (o de la izquierda) al borde, y el mismo zoom. */
    fun scrollToPage(k: Int) {
        if (!pages.paged) return
        cancelFlipAnim()
        val i = k.coerceIn(0, pageCount - 1)
        val pad = 8 * density
        if (pages.horizontal) camera.set(-pages.pageLeft(i) * camera.scale + pad, camera.ty, camera.zoom)
        else camera.set(camera.tx, -pages.pageTop(i) * camera.scale + pad, camera.zoom)
        dry.invalidate()
        listener?.onViewportChanged()
        listener?.onSelectionChanged()
    }

    /** Pasa un rectángulo del mundo a la pantalla del lienzo. */
    fun mapToScreen(world: RectF, out: RectF) {
        camera.worldToScreen(Matrix()).mapRect(out, world)
    }

    fun textAt(x: Float, y: Float): NoteText? = texts.lastOrNull { Elements.textBounds(it).contains(x, y) }

    private var framePointer = NONE
    private var textFrame: RectF? = null
    private var frameFromX = 0f
    private var frameFromY = 0f
    private var lastTextTap: NoteText? = null
    private var lastTextTapAt = 0L

    private fun textTapHit(e: MotionEvent, i: Int, canceled: Boolean): NoteText? {
        if (canceled || placingText || mode != Mode.PEN || penMoved || shape != null) return null
        if (e.eventTime - penDownAt >= TAP_MS) return null
        return textAt(inX(e.getX(i)), inY(e.getY(i)))
    }

    private fun startTextFrame(sx: Float, sy: Float, pointer: Int) {
        framePointer = pointer
        frameFromX = camera.toWorldX(sx)
        frameFromY = camera.toWorldY(sy)
        textFrame = RectF(frameFromX, frameFromY, frameFromX, frameFromY)
        dry.invalidate()
    }

    private fun moveTextFrame(sx: Float, sy: Float) {
        val x = camera.toWorldX(sx)
        val y = camera.toWorldY(sy)
        textFrame?.set(min(frameFromX, x), min(frameFromY, y), max(frameFromX, x), max(frameFromY, y))
        dry.invalidate()
    }

    private fun endTextFrame(canceled: Boolean) {
        framePointer = NONE
        val r = textFrame
        textFrame = null
        dry.invalidate()
        if (canceled || r == null || !placingText) return
        val minWorld = 28f * density / camera.scale.coerceAtLeast(0.01f)
        val box = RectF(r)
        if (box.width() < minWorld || box.height() < minWorld) {
            val cx = box.centerX()
            val cy = box.centerY()
            box.set(cx - 110f, cy - 40f, cx + 110f, cy + 40f)
        } else {
            if (box.height() < 36f) {
                val cy = box.centerY()
                box.top = cy - 18f
                box.bottom = cy + 18f
            }
            if (box.width() < 48f) {
                val cx = box.centerX()
                box.left = cx - 24f
                box.right = cx + 24f
            }
        }
        listener?.onTextFrame(box)
    }

    private fun cancelTextFrame() {
        if (framePointer == NONE && textFrame == null) return
        framePointer = NONE
        textFrame = null
        dry.invalidate()
    }

    // --- Captura ---

    /** El rectángulo de la captura (del mundo): mientras se arrastra y mientras se eligen las opciones. */
    private var captureRect: RectF? = null
    private var capturing = false
    private var captureFromX = 0f
    private var captureFromY = 0f

    val hasCapture get() = captureRect != null && !capturing

    private var captureStartX = 0f
    private var captureStartY = 0f

    private fun startCapture(sx: Float, sy: Float) {
        captureFromX = camera.toWorldX(sx)
        captureFromY = camera.toWorldY(sy)
        captureStartX = sx
        captureStartY = sy
        captureRect = RectF(captureFromX, captureFromY, captureFromX, captureFromY)
        capturing = true
        listener?.onCaptureCleared()
        removeCallbacks(captureHold)
        postDelayed(captureHold, LONG_PRESS_MS)
        dry.invalidate()
    }

    /** El lápiz quieto con la captura, como con el lazo: no es un recuadro, es para pegar. */
    private val captureHold = Runnable {
        if (!capturing) return@Runnable
        capturing = false
        captureRect = null
        dry.invalidate()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onLassoLongPress(captureStartX, captureStartY, camera.toWorldX(captureStartX), camera.toWorldY(captureStartY))
    }

    private fun moveCapture(sx: Float, sy: Float) {
        if (hypot(sx - captureStartX, sy - captureStartY) >= TAP_SLOP * density) removeCallbacks(captureHold)
        val x = camera.toWorldX(sx)
        val y = camera.toWorldY(sy)
        captureRect?.set(min(captureFromX, x), min(captureFromY, y), max(captureFromX, x), max(captureFromY, y))
        dry.invalidate()
    }

    private fun endCapture(canceled: Boolean) {
        capturing = false
        val r = captureRect ?: return
        val min = CAPTURE_MIN_DP * density
        // Un toque o un rectángulo muy finito no es una captura.
        if (canceled || r.width() * camera.scale < min || r.height() * camera.scale < min) {
            clearCapture()
            return
        }
        dry.invalidate()
        listener?.onCaptureArea(RectF(r))
    }

    fun clearCapture() {
        if (captureRect == null && !capturing) return
        captureRect = null
        capturing = false
        dry.invalidate()
        listener?.onCaptureCleared()
    }

    /** Elige como captura todo lo que se ve. */
    fun captureVisible() {
        val r = visibleWorld(RectF())
        captureRect = r
        capturing = false
        dry.invalidate()
        listener?.onCaptureArea(RectF(r))
    }

    /** Dónde está la captura en la pantalla (para ubicar sus opciones); false si no hay. */
    fun captureOnScreen(out: RectF): Boolean {
        val r = captureRect ?: return false
        camera.worldToScreen(Matrix()).mapRect(out, r)
        return true
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
            if (pages.paged) {
                val k = pages.pageOf(left, view.top).coerceAtLeast(0)
                left = max(left, pages.pageLeft(k) + PageLayout.MARGIN)
            }
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
        if (pages.paged) {
            val k = pages.pageOf(zoomBox.left, zoomBox.top).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val edge = pages.pageLeft(k) + pages.width
            if (zoomBox.left > edge - zoomBox.width() * 0.4f) {
                zoomNewLine()
                return
            }
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
    private fun displayColor() = Appearance.ink(pen.tool.storedColor(pen.color, pen.opacity), pen.tool, dark)

    /** Precisión de la malla del trazo: medio píxel al zoom actual, nunca más de 0,1 dp. */
    private fun epsilon() = (0.5f / max(camera.scale, inScale)).coerceAtMost(0.1f)

    private inner class DryLayer(context: Context) : View(context), Tiles.Content {
        private val chunks = StrokeChunks()
        private val tiles = Tiles(density)
        private var stale = true
        private var version = 0
        private val overview = Overview { post { invalidate() } }.also { ov ->
            ov.onSnapshot = { scale, _, wasDark, modified, count, bands -> persistVista(scale, wasDark, modified, count, bands) }
        }

        /** Foto al lado de la nota. La escribe el hilo que arma la imagen, si el pedido sigue vigente. */
        @Volatile var vistaFile: File? = null
        @Volatile var vistaModified = Long.MIN_VALUE
        @Volatile var vistaCount = -1
        @Volatile var vistaDark = false

        var vistaMode = VistaMode.LIVE

        /** Pluma, marcador y texto en una sola textura. El resaltador sigue en las baldosas. */
        private val inkNode = RenderNode("tinta-tinta").apply {
            clipToBounds = true
            setUseCompositingLayer(true, null)
        }
        private val inkWorld = RectF()
        private val inkQuery = RectF()
        private val inkMatrix = Matrix()
        private var inkScale = 0f
        private var inkSharp = 0f
        private var inkKeyOver = -1
        private var inkKeyRev = -1
        private var inkRev = 0
        private var inkResizeTracked: NoteText? = null
        private var inkBakeArmed = false
        private var inkWantFrame = false
        private var overviewAskedAt = 0L
        private var usableVersion = -1
        private var usableStamp: Any? = null
        private var usable = false

        /** [Overview.shows], revisado una vez por versión de la nota y por imagen. */
        private fun overviewUsable(): Boolean {
            val stamp = overview.stamp
            if (usableVersion != version || usableStamp !== stamp) {
                usable = overview.shows(strokes)
                usableVersion = version
                usableStamp = stamp
            }
            return usable
        }

        /**
         * Con la vista quieta, arma de antemano la imagen de toda la nota si es grande y la que
         * hay quedó vieja; como mucho cada [OVERVIEW_EVERY_MS], para no gastar mientras se escribe.
         */
        private fun prepareOverview() {
            if (strokes.size < CROWDED || overview.isCurrent(version)) return
            val now = android.os.SystemClock.uptimeMillis()
            if (overview.hasImage && now - overviewAskedAt < OVERVIEW_EVERY_MS) return
            overviewAskedAt = now
            overview.request(strokes.toList(), version, dark, vistaModified, vistaCount)
        }
        private val paper = Paper()

        fun setPaperColor(c: Int?) {
            paper.color = c ?: Paper.COLOR
            zoomDirty = true
        }
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

        fun meshesArrived() {
            stale = true
            contentStale = true
            version++
            zoomDirty = true
            invalidate()
        }

        fun strokesChanged() {
            inkRev++
            meshesArrived()
        }

        fun contentChanged() {
            inkRev++
            contentStale = true
            invalidate()
        }

        fun freshChanged() {
            inkRev++
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
            inkRev++
            zoomDirty = true
            version++
            overview.clear()
            chunks.dark = dark
            tiles.invalidateAll()
            invalidate()
        }

        fun reset() {
            chunks.clear()
            tiles.clear()
            overview.clear()
            inkNode.discardDisplayList()
            inkScale = 0f
            inkBakeArmed = false
            vistaMode = VistaMode.LIVE
            strokesChanged()
        }

        fun armVista(file: File, modified: Long, count: Int, dark: Boolean) {
            vistaFile = file
            vistaModified = modified
            vistaCount = count
            vistaDark = dark
        }

        fun vistaCovers(): Boolean {
            val s = overview.scale
            return s > 0f && camera.scale <= s * MAX_UPSCALE
        }

        fun vistaZoomNeedsMeshes() = vistaMode == VistaMode.HOLD && overview.scale > 0f && !vistaCovers()

        fun adoptVista(parts: List<Pair<Bitmap, RectF>>, scale: Float, source: List<InkStroke>) {
            overview.adopt(parts, scale, source, version)
            invalidate()
        }

        private fun persistVista(scale: Float, wasDark: Boolean, modified: Long, count: Int, bands: List<Overview.SavedBand>) {
            val file = vistaFile ?: return
            if (count < CROWDED || count != vistaCount || modified != vistaModified || wasDark != vistaDark) return
            val saved = bands.map { VistaCache.Band(it.left, it.top, it.right, it.bottom, it.png) }
            runCatching { VistaCache.write(file, modified, count, wasDark, scale, saved) }
        }

        /** Dedos, pase de hoja o la animación: se estira lo pintado y no se graba. */
        private fun pageMoving() = fingers.isNotEmpty() || flipAnim != null || flip?.decided == true

        /** Grabar baldosas o tandas le sacaría el cuadro al trazo o al gesto. Borrar sí graba. */
        private fun bakeFrozen() = penStroke != null || (pageMoving() && !erasing)

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
            var edge = Float.NEGATIVE_INFINITY
            fun take(right: Float, bottom: Float) {
                if (right.isNaN() || bottom.isNaN()) return
                val v = pages.endOf(right, bottom)
                if (v > edge) edge = v
            }
            for (st in strokes) take(st.right, st.bottom)
            for (i in images) Elements.imageBounds(i).let { take(it.right, it.bottom) }
            for (t in texts) Elements.textBounds(t).let { take(it.right, it.bottom) }
            for (wd in widgets) take(wd.right, wd.bottom)
            lifted?.let {
                val b = it.t.box(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)
                take(b[2], b[3])
            }
            if (edge == Float.NEGATIVE_INFINITY) edge = Float.NaN
            val count = pages.count(edge)
            if (count != pageCount) {
                // Apareció o se fue una hoja: se repinta desde la más baja de las dos.
                val lo = min(count, pageCount) - 1
                val hi = max(count, pageCount)
                val area = if (pages.horizontal) {
                    RectF(pages.pageLeft(lo) - BIG, pages.top - BIG, pages.pageLeft(hi) + pages.width + BIG, pages.top + pages.height + BIG)
                } else {
                    RectF(pages.left - BIG, pages.pageTop(lo), pages.right + BIG, pages.pageTop(hi) + BIG)
                }
                tiles.invalidate(area, urgent = true)
                pageCount = count
                post { listener?.onPageCountChanged(count) }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            // Al volver de otra app Android ya borró lo grabado en los nodos: se vuelve a grabar.
            if (chunks.recoverLost()) zoomDirty = true
            if (!zoomNode.hasDisplayList()) zoomDirty = true
            if (contentStale) updatePageCount()
            if (stale) {
                chunks.sync(strokes.subList(0, committedCount()))
                stale = false
            }
            view.set(camera.toWorldX(0f), camera.toWorldY(0f), camera.toWorldX(w), camera.toWorldY(h))
            // La foto alcanza sólo hasta un zoom, y sólo si sigue siendo esta nota.
            val held = vistaMode == VistaMode.HOLD && overview.hasImage
            if (held && (!overviewUsable() || camera.scale > overview.scale * MAX_UPSCALE)) {
                vistaMode = VistaMode.LIVE
                onNeedMeshes?.invoke()
            }
            camera.worldToScreen(worldToScreen)
            if (vistaMode != VistaMode.LIVE) {
                // Todavía no hay baldosas: el papel, las imágenes y la foto de la nota.
                paper.draw(canvas, paperStyle, camera.scale, camera.tx, camera.ty, w, h, dark, pages = pages, pageCount = pageCount)
                val save = canvas.save()
                canvas.concat(worldToScreen)
                drawLoose(canvas)
                if (vistaMode == VistaMode.HOLD && overview.hasImage && overviewUsable()) overview.draw(canvas)
                canvas.restoreToCount(save)
                basePending = false
                settledNow = !pageMoving()
                recordScaleNow = camera.scale
            } else {
                // Con el trazo en curso, o mientras se mueve la hoja, no se graba: se estira lo que ya está.
                val frozen = bakeFrozen()
                val still = !pageMoving() && penStroke == null
                val recordScale = tiles.recordScale(camera.scale, still)
                val ahead = if (pages.paged) pages.step else 0f
                val side = pages.horizontal
                tiles.neededArea(
                    view, needed,
                    padLeft = if (side) ahead * 0.35f else 0f,
                    padTop = if (side) 0f else ahead * 0.35f,
                    padRight = if (side) ahead else 0f,
                    padBottom = if (side) 0f else ahead,
                )
                val deadline = if (frozen) 0L else System.nanoTime() + CHUNK_BUDGET_NS
                var pending = chunks.update(needed.left, needed.top, needed.right, needed.bottom, recordScale, !frozen, deadline)
                for (r in chunks.urgentDamage) tiles.invalidate(r, urgent = true)
                for (r in chunks.damage) tiles.invalidate(r, urgent = false)
                for (r in damaged) tiles.invalidate(r, urgent = true)
                chunks.urgentDamage.clear()
                chunks.damage.clear()
                damaged.clear()
                // El papel ya está en las baldosas. Se pinta aparte sólo si falta una, aparece, o hay un hueco de goma.
                if (tiles.needsUnderlay(view)) {
                    paper.draw(canvas, paperStyle, camera.scale, camera.tx, camera.ty, w, h, dark, pages = pages, pageCount = pageCount)
                }
                for (img in images) if (RectF.intersects(needed, Elements.imageBounds(img))) ImageCache.touch(img.file)
                for (wd in widgets) if (wd is Media && wd.thumb != null && wd.right >= needed.left && wd.left <= needed.right &&
                    wd.bottom >= needed.top && wd.top <= needed.bottom
                ) ImageCache.touch(wd.thumb)

                val save = canvas.save()
                canvas.concat(worldToScreen)
                chunks.keepAlive(canvas) // antes que las baldosas
                // Mientras faltan baldosas (o están apareciendo) se ve debajo la imagen de toda la
                // nota, en vez del papel vacío. Se arma sólo en notas con muchos trazos a la vista,
                // que son las que tardan más de un cuadro; aunque esté vieja (después de escribir),
                // sirve igual hasta que llegan las baldosas.
                if (tiles.needsBackdrop(view)) {
                    val crowded = chunks.count(view.left, view.top, view.right, view.bottom) > CROWDED
                    if (crowded && !overview.hasImage && !overview.isCurrent(version)) {
                        overview.request(strokes.toList(), version, dark, vistaModified, vistaCount)
                    }
                    if (overview.hasImage && camera.scale <= overview.scale * MAX_UPSCALE && overviewUsable()) overview.draw(canvas)
                }
                canvas.restoreToCount(save)

                pending = tiles.draw(
                    canvas, view, camera.scale, camera.tx, camera.ty, !frozen, this,
                    padLeft = if (side) ahead * 0.35f else 0f,
                    padTop = if (side) 0f else ahead * 0.35f,
                    padRight = if (side) ahead else 0f,
                    padBottom = if (side) 0f else ahead,
                    record = !frozen,
                ) || pending
                basePending = pending
                settledNow = !pageMoving()
                recordScaleNow = recordScale
            }

            // El resaltador en curso (y su vista previa) queda debajo de la pluma, que se dibuja en [InkLayer].
            if (linePreview?.tool == Tool.HIGHLIGHTER || shapePreview?.tool == Tool.HIGHLIGHTER) {
                val preview = canvas.save()
                canvas.concat(worldToScreen)
                InkDraw.use { r -> drawPreviews(canvas, r, worldToScreen, under = true) }
                canvas.restoreToCount(preview)
            }
        }

        private var basePending = false
        private var settledNow = true
        private var recordScaleNow = 1f

        /** Pluma, marcador, texto y el cromo, encima del resaltador en curso. */
        fun drawInk(canvas: Canvas) {
            // Al empezar y al terminar de estirar un texto se rearma la foto una vez, no en cada movimiento.
            if (resizeText !== inkResizeTracked) {
                inkRev++
                inkResizeTracked = resizeText
            }
            inkWantFrame = false
            val save = canvas.save()
            canvas.concat(worldToScreen)
            if (vistaMode != VistaMode.LIVE) {
                drawVisibleTexts(canvas)
                drawInkExtras(canvas)
            } else {
                val frozen = bakeFrozen()
                val keyOk = inkNode.hasDisplayList() && inkKeyOver == chunks.overGen && inkKeyRev == inkRev
                val scaleOk = inkScale > 0f && abs(camera.scale - inkScale) <= inkScale * INK_SCALE_SLACK
                val covers = inkCovers()
                inkCover(inkQuery)
                val stable = !erasing && !frozen && view.width() > 1f &&
                    chunks.overStable(inkQuery.left, inkQuery.top, inkQuery.right, inkQuery.bottom)
                when {
                    !erasing && keyOk && covers && (scaleOk || frozen) -> drawFromPicture(canvas)
                    stable && inkBakeArmed -> {
                        recordInkPicture()
                        drawFromPicture(canvas)
                        inkBakeArmed = false
                    }
                    stable -> {
                        inkBakeArmed = true
                        drawLiveInk(canvas)
                        inkWantFrame = true
                    }
                    else -> {
                        inkBakeArmed = false
                        drawLiveInk(canvas)
                    }
                }
            }
            canvas.restoreToCount(save)
            if (found.isNotEmpty()) drawFound(canvas, worldToScreen)
            if (zoomOn) drawZoom(canvas, worldToScreen)
            drawLasso(canvas)
            drawSelection(canvas)
            captureRect?.let { drawCapture(canvas, it, worldToScreen) }
            textFrame?.let { drawTextFrame(canvas, it, worldToScreen) }
            var pending = drawLaser(canvas)
            cursor?.let {
                cursorPaint.color = if (dark) 0x99E0E0E0.toInt() else 0x99404040.toInt()
                canvas.drawCircle(it.x, it.y, eraserRadius * density, cursorPaint)
            }
            drawFlipShade(canvas)
            if (flipAnim != null || flip?.decided == true) pending = true
            if (vistaMode == VistaMode.LIVE && !pending && !inkWantFrame && settledNow && penPointer == NONE && fresh.isEmpty() && !bakeFrozen()) {
                prepareOverview()
                pending = chunks.prewarm(recordScaleNow, System.nanoTime() + PREWARM_NS)
            }
            // Mientras se escribe o se mueve la hoja no se pide otro cuadro para terminar baldosas.
            val moreTiles = vistaMode == VistaMode.LIVE && !bakeFrozen() && (basePending || inkWantFrame)
            if (pending || moreTiles) {
                postInvalidateOnAnimation()
                ink.postInvalidateOnAnimation()
            }
        }

        /** Imágenes y gráficos, sin baldosas: mientras la nota se muestra con la foto guardada. */
        private fun drawLoose(canvas: Canvas) {
            val l = view.left - view.width()
            val t = view.top - view.height()
            val r = view.right + view.width()
            val b = view.bottom + view.height()
            for (img in images) {
                val bounds = Elements.imageBounds(img)
                if (bounds.right < l || bounds.left > r || bounds.bottom < t || bounds.top > b) continue
                val bitmap = ImageCache.get(img.file) { damage(bounds) } ?: continue
                Elements.drawImage(canvas, img, bitmap)
                ImageCache.touch(img.file)
            }
            for (wd in widgets) {
                if (wd.right < l || wd.left > r || wd.bottom < t || wd.top > b) continue
                val area = RectF(wd.left, wd.top, wd.right, wd.bottom)
                painter.draw(canvas, wd, dark) { f -> ImageCache.get(f) { damage(area) } }
                if (wd is Media && wd.thumb != null) ImageCache.touch(wd.thumb)
            }
        }

        private fun drawVisibleTexts(canvas: Canvas) {
            for (txt in texts) {
                val shown = shownText(txt)
                if (!RectF.intersects(view, Elements.textBounds(shown))) continue
                Elements.drawText(canvas, shown, textPaint, Appearance.ink(shown.color, Tool.PEN, dark))
            }
        }

        /** Vistas previas y lo que se está moviendo: nunca entran en la foto. */
        private fun drawInkExtras(canvas: Canvas) {
            val over = linePreview?.tool == Tool.PEN || linePreview?.tool == Tool.MARKER ||
                shapePreview?.tool == Tool.PEN || shapePreview?.tool == Tool.MARKER
            if (over) InkDraw.use { r -> drawPreviews(canvas, r, worldToScreen, under = false) }
            drawLifted(canvas)
        }

        private fun drawLifted(canvas: Canvas) {
            lifted?.let { l ->
                val inner = canvas.save()
                canvas.translate(l.t.dx, l.t.dy)
                canvas.rotate(l.t.degrees)
                canvas.scale(l.t.a, l.t.a)
                canvas.drawRenderNode(l.node)
                canvas.restoreToCount(inner)
            }
        }

        /** Cada tanda de pluma y cada texto, como venía. */
        private fun drawLiveInk(canvas: Canvas) {
            chunks.forEachNode(view.left, view.top, view.right, view.bottom, under = false) { canvas.drawRenderNode(it) }
            chunks.drawUnrecordedOver(canvas, view.left, view.top, view.right, view.bottom)
            if (fresh.isNotEmpty() || linePreview != null || shapePreview != null) {
                InkDraw.use { r ->
                    for (s in fresh) {
                        val mesh = s.display(dark) ?: continue
                        r.draw(canvas, mesh, worldToScreen)
                    }
                    drawPreviews(canvas, r, worldToScreen, under = false)
                }
            }
            drawVisibleTexts(canvas)
            drawLifted(canvas)
        }

        private fun drawFromPicture(canvas: Canvas) {
            val layer = canvas.save()
            canvas.translate(inkWorld.left, inkWorld.top)
            canvas.scale(1f / inkSharp, 1f / inkSharp)
            canvas.drawRenderNode(inkNode)
            canvas.restoreToCount(layer)
            resizeText?.let { txt ->
                val shown = shownText(txt)
                if (RectF.intersects(view, Elements.textBounds(shown))) {
                    Elements.drawText(canvas, shown, textPaint, Appearance.ink(shown.color, Tool.PEN, dark))
                }
            }
            drawInkExtras(canvas)
        }

        private fun inkCover(out: RectF) {
            out.set(
                view.left - view.width() * INK_PAD,
                view.top - view.height() * INK_PAD,
                view.right + view.width() * INK_PAD,
                view.bottom + view.height() * INK_PAD,
            )
        }

        private fun inkCovers(): Boolean {
            if (!inkNode.hasDisplayList() || inkSharp <= 0f) return false
            return view.left >= inkWorld.left - 0.5f && view.top >= inkWorld.top - 0.5f &&
                view.right <= inkWorld.right + 0.5f && view.bottom <= inkWorld.bottom + 0.5f
        }

        /** Graba la pluma, el marcador y el texto en una textura del tamaño de la pantalla. */
        private fun recordInkPicture() {
            if (view.width() < 1f || view.height() < 1f) return
            inkCover(inkWorld)
            val wantW = ceil(inkWorld.width() * camera.scale).toInt().coerceAtLeast(1)
            val wantH = ceil(inkWorld.height() * camera.scale).toInt().coerceAtLeast(1)
            val longSide = max(wantW, wantH)
            val fit = if (longSide > INK_MAX_PX) INK_MAX_PX.toFloat() / longSide else 1f
            val pw = (wantW * fit).toInt().coerceAtLeast(1)
            val ph = (wantH * fit).toInt().coerceAtLeast(1)
            inkSharp = pw / inkWorld.width().coerceAtLeast(1f)
            inkScale = camera.scale
            inkNode.setPosition(0, 0, pw, ph)
            val rc = inkNode.beginRecording()
            inkMatrix.setScale(inkSharp, inkSharp)
            inkMatrix.postTranslate(-inkWorld.left * inkSharp, -inkWorld.top * inkSharp)
            rc.concat(inkMatrix)
            val l = inkWorld.left
            val t = inkWorld.top
            val rgt = inkWorld.right
            val b = inkWorld.bottom
            chunks.forEachNode(l, t, rgt, b, under = false) { rc.drawRenderNode(it) }
            if (fresh.isNotEmpty()) {
                InkDraw.use { renderer ->
                    for (s in fresh) {
                        if (!s.intersects(l, t, rgt, b)) continue
                        val mesh = s.display(dark) ?: continue
                        renderer.draw(rc, mesh, inkMatrix)
                    }
                }
            }
            val skip = resizeText
            for (txt in texts) {
                if (txt === skip) continue
                if (!RectF.intersects(inkWorld, Elements.textBounds(txt))) continue
                Elements.drawText(rc, txt, textPaint, Appearance.ink(txt.color, Tool.PEN, dark))
            }
            inkNode.endRecording()
            inkKeyOver = chunks.overGen
            inkKeyRev = inkRev
        }

        override fun invalidate() {
            super.invalidate()
            ink.invalidate()
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
                for (st in fresh) if (st.intersects(zoomBox.left, zoomBox.top, zoomBox.right, zoomBox.bottom)) {
                    rd.draw(canvas, st.display(dark) ?: continue, zoomMatrix)
                }
                drawPreviews(canvas, rd, zoomMatrix, under = true)
                drawPreviews(canvas, rd, zoomMatrix, under = false)
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
                paintInk(committed) { st ->
                    if (st.intersects(box.left, box.top, box.right, box.bottom)) rd.draw(rc, st.display(dark) ?: return@paintInk, m)
                }
            }
            for (txt in texts) {
                val shown = shownText(txt)
                if (!RectF.intersects(box, Elements.textBounds(shown))) continue
                Elements.drawText(rc, shown, textPaint, Appearance.ink(shown.color, Tool.PEN, dark))
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
            val textBox = selectedText != null && selection?.count == 1
            if (!textBox) {
                canvas.drawCircle(corners[4], corners[5], r, handleFill)
                canvas.drawCircle(corners[4], corners[5], r, handleRing)
            }
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
            if (textBox) drawTextHandles(canvas)
        }

        /** Manijas en los bordes y las esquinas del recuadro, para cambiarle el tamaño sin la letra. */
        private fun drawTextHandles(canvas: Canvas) {
            val t = selectedText ?: return
            val box = if (resizeText === t) resizeBox else RectF(t.left, t.top, t.left + t.width, t.top + t.height)
            val pts = floatArrayOf(
                box.left, box.top, box.centerX(), box.top, box.right, box.top,
                box.right, box.centerY(), box.right, box.bottom, box.centerX(), box.bottom,
                box.left, box.bottom, box.left, box.centerY(),
            )
            val radius = HANDLE_RADIUS * density * 0.75f
            for (i in pts.indices step 2) {
                val x = screenX(pts[i])
                val y = screenY(pts[i + 1])
                canvas.drawCircle(x, y, radius, handleFill)
                canvas.drawCircle(x, y, radius, handleRing)
            }
        }

        /** La línea recta y la figura en curso; en una nota con hojas, sólo lo que cae en la hoja. */
        private fun drawPreviews(canvas: Canvas, r: androidx.ink.rendering.android.canvas.CanvasStrokeRenderer, m: Matrix, under: Boolean) {
            val line = linePreview?.takeIf { (it.tool == Tool.HIGHLIGHTER) == under }
            val shape = shapePreview?.takeIf { (it.tool == Tool.HIGHLIGHTER) == under }
            if (line == null && shape == null) return
            val save = canvas.save()
            writingClip?.let { canvas.clipRect(it) }
            line?.display(dark)?.let { r.draw(canvas, it, m) }
            shape?.display(dark)?.let { r.draw(canvas, it, m) }
            canvas.restoreToCount(save)
        }

        private val captureShade = Paint().apply { color = 0x59000000 }
        private val captureEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = accent
        }
        private val captureCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4 * density
            strokeCap = Paint.Cap.ROUND
            color = accent
        }
        private val captureScreen = RectF()

        private val textFrameFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent and 0x00FFFFFF or 0x22000000 }
        private val textFrameEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = accent
        }

        /** El recuadro del texto, mientras se arrastra. */
        private fun drawTextFrame(canvas: Canvas, world: RectF, worldToScreen: Matrix) {
            val r = RectF(world)
            worldToScreen.mapRect(r)
            canvas.drawRect(r, textFrameFill)
            canvas.drawRect(r, textFrameEdge)
            val c = min(12 * density, min(r.width(), r.height()) / 3f)
            if (c <= 0f) return
            for ((x, dx) in listOf(r.left to c, r.right to -c)) {
                for ((y, dy) in listOf(r.top to c, r.bottom to -c)) {
                    canvas.drawLine(x, y, x + dx, y, textFrameEdge)
                    canvas.drawLine(x, y, x, y + dy, textFrameEdge)
                }
            }
        }

        /** La captura: afuera oscurecido, el borde y las esquinas marcadas. */
        private fun drawCapture(canvas: Canvas, world: RectF, worldToScreen: Matrix) {
            val r = captureScreen
            worldToScreen.mapRect(r, world)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, r.top, captureShade)
            canvas.drawRect(0f, r.bottom, w, h, captureShade)
            canvas.drawRect(0f, r.top, r.left, r.bottom, captureShade)
            canvas.drawRect(r.right, r.top, w, r.bottom, captureShade)
            canvas.drawRect(r, captureEdge)
            val c = min(14 * density, min(r.width(), r.height()) / 3)
            for ((x, dx) in listOf(r.left to c, r.right to -c)) {
                for ((y, dy) in listOf(r.top to c, r.bottom to -c)) {
                    canvas.drawLine(x, y, x + dx, y, captureCorner)
                    canvas.drawLine(x, y, x, y + dy, captureCorner)
                }
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
            // Sólo el resaltador. La pluma y el texto van en [InkLayer], encima del trazo en curso.
            chunks.forEachNode(left, top, right, bottom, under = true) { canvas.drawRenderNode(it) }
            canvas.restoreToCount(save)
        }

        override fun cost(left: Float, top: Float, right: Float, bottom: Float) = chunks.count(left, top, right, bottom, under = true)
    }

    /** La pluma, el marcador y el texto, encima del resaltador. */
    private inner class InkLayer(context: Context) : View(context) {
        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) = dry.drawInk(canvas)
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
        private const val DOUBLE_TAP_MS = 320L

        // Gestos sobre lo elegido.
        const val MOVE = 1
        const val SCALE = 2
        const val ROTATE = 3

        /** Arrastrar un borde del recuadro de texto. */
        const val RESIZE = 4

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

        /** Lado mínimo (dp en pantalla) de una captura; menos es un toque. */
        const val CAPTURE_MIN_DP = 16f

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
        const val CROWDED = 1500

        /** La foto de la pluma cubre la vista y este margen, para poder desplazar sin rehacerla. */
        private const val INK_PAD = 0.35f

        /** Lado mayor de esa foto. Más grande no entra en la GPU de la tablet. */
        private const val INK_MAX_PX = 4096

        /** Con el zoom quieto, se rehace la foto si la escala cambió más que esto. */
        private const val INK_SCALE_SLACK = 0.02f

        /** Cuánto se puede agrandar esa imagen (borrosa, pero mejor que el papel vacío). */
        const val MAX_UPSCALE = 4f

        /** Cada cuánto, como mucho, se rehace esa imagen de antemano. */
        const val OVERVIEW_EVERY_MS = 20_000L
        const val PALM_GRACE_MS = 400L

        /** La punta quieta, en el mismo punto, este tiempo elige el borrador sin soltar. */
        const val ERASER_HOLD_MS = 500L

        /** Tiempo por cuadro para grabar tandas de trazos que no corren prisa. */
        const val CHUNK_BUDGET_NS = 6_000_000L

        /** Tiempo por cuadro para grabar tandas lejanas con la vista quieta. */
        const val PREWARM_NS = 4_000_000L

        /** El pase de hoja: un desliz corto no cuenta, y la hoja sigue al dedo hasta esta fracción. */
        const val FLIP_ARM_DP = 32f
        const val FLIP_COMMIT_DP = 72f
        const val FLIP_FOLLOW = 0.42f
        const val FLIP_NS = 170_000_000L

        /** Los trazos frescos pasan a las baldosas cuando el lápiz descansa este tiempo... */
        const val MERGE_DELAY_MS = 700L

        /** ...o cuando se juntan estos. */
        const val MAX_FRESH = 48

        /** Separación (dp) de los puntos de una línea recta. */
        const val LINE_STEP = 4f
        const val SNAP_DEGREES = 4.0
        const val STYLUS_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

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
