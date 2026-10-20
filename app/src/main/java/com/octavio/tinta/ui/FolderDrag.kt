package com.octavio.tinta.ui

import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Arrastrar notas y carpetas de la pantalla principal hasta otra carpeta, con el arrastrar y soltar
 * de Android. Se empieza manteniendo apretada la tarjeta o la fila ([start]); soltarla sin moverla
 * abre su menú, como antes. Los destinos ([target]: tarjetas de carpeta y filas de la barra) se
 * marcan al pasar por encima; quedarse un momento sobre una carpeta la abre ([onHoverOpen]), para
 * llegar a las de adentro. Cerca del borde de una zona que se desplaza ([area]) se desplaza sola.
 */
class FolderDrag(
    /** Si `item` puede ir a la carpeta `folder` (null: arriba de todo / sin carpeta). */
    private val canDrop: (Item, String?) -> Boolean,
    private val onDrop: (Item, String?) -> Unit,
    /** Abrir la carpeta sobre la que se quedó el dedo. */
    private val onHoverOpen: (String) -> Unit,
) {
    sealed class Item {
        data class Note(val id: String) : Item()
        data class Folder(val id: String) : Item()
    }

    /** Un destino: la carpeta a la que lleva. `open`: si quedarse encima la abre. */
    private class Target(val folder: String?, val open: Boolean, val radius: Float, val fill: Boolean)

    private class Session(val item: Item, val source: View, val onMenu: () -> Unit) {
        var startX = Float.NaN
        var startY = Float.NaN
        var moved = false
        var dropped = false
        var ended = false
    }

    private var session: Session? = null
    private val highlighted = HashMap<View, GradientDrawable>()
    private val areas = ArrayList<View>()
    private var hovered: View? = null

    private var scrollView: View? = null
    private var scrollSpeed = 0f

    fun start(source: View, item: Item, scale: Float, onMenu: () -> Unit) {
        if (session?.ended == false) return
        val s = Session(item, source, onMenu)
        session = s
        val started = source.startDragAndDrop(ClipData.newPlainText("tinta", ""), Shadow(source, scale), s, 0)
        if (!started) {
            session = null
            onMenu()
            return
        }
        source.alpha = 0.35f
    }

    /**
     * La vista lleva a `folder` (null: arriba de todo). Las vistas de una lista se reutilizan:
     * llamar cada vez que se le asigna una carpeta.
     */
    fun target(view: View, folder: String?, open: Boolean = folder != null, radiusDp: Float = 12f) {
        view.setTag(TAG, Target(folder, open, radiusDp * view.resources.displayMetrics.density, fill = true))
        view.setOnDragListener(listener)
        unmark(view)
    }

    /** Una vista que deja de ser destino (por ejemplo, una tarjeta reutilizada para otra cosa). */
    fun clear(view: View) {
        view.setTag(TAG, null)
        view.setOnDragListener(null)
        unmark(view)
    }

    /**
     * Una zona que se desplaza (la lista o la barra): recibe el arrastre donde no hay un destino,
     * para desplazarse sola cerca del borde. Con `folder()` distinto de null, soltar en un lugar
     * vacío de la zona también la manda a esa carpeta (la que se está viendo).
     */
    fun area(view: View, folder: () -> String?) {
        areas.add(view)
        view.setTag(TAG_AREA, folder)
        view.setOnDragListener(listener)
    }

    private val listener = View.OnDragListener { v, e -> handle(v, e) }

    private fun handle(v: View, e: DragEvent): Boolean {
        val s = e.localState as? Session ?: return false
        if (s !== session) return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                if (s.startX.isNaN()) screen(v, e.x, e.y).let { (x, y) -> s.startX = x; s.startY = y }
                return true
            }
            DragEvent.ACTION_DRAG_ENTERED -> {
                val folder = folderOf(v)
                if (folder != null && canDrop(s.item, folder.value)) {
                    mark(v, fill = v.getTag(TAG) != null)
                    hovered = v
                    val t = v.getTag(TAG) as? Target
                    if (t?.open == true && t.folder != null) v.postDelayed(springOpen, OPEN_DELAY_MS)
                }
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                val (x, y) = screen(v, e.x, e.y)
                if (!s.moved && !s.startX.isNaN()) {
                    s.moved = hypot(x - s.startX, y - s.startY) > MOVE_DP * v.resources.displayMetrics.density
                }
                autoScroll(x, y, v)
            }
            DragEvent.ACTION_DRAG_EXITED -> leave(v)
            DragEvent.ACTION_DROP -> {
                leave(v)
                val folder = folderOf(v) ?: return false
                if (!s.moved || !canDrop(s.item, folder.value)) return false
                s.dropped = true
                onDrop(s.item, folder.value)
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                // Llega a todas las vistas que participaron: se atiende una vez.
                if (s.ended) return true
                s.ended = true
                finish(s)
                if (!s.dropped && !s.moved) s.onMenu()
            }
        }
        return true
    }

    /** La carpeta a la que lleva la vista; null si la vista no es un destino válido acá. */
    private fun folderOf(v: View): Box? {
        (v.getTag(TAG) as? Target)?.let { return Box(it.folder) }
        @Suppress("UNCHECKED_CAST")
        val area = v.getTag(TAG_AREA) as? () -> String? ?: return null
        return area()?.let { Box(it) }
    }

    /** Un valor que puede ser null (la carpeta de arriba de todo), distinto de "no hay destino". */
    private class Box(val value: String?)

    private val springOpen = Runnable {
        val v = hovered ?: return@Runnable
        val t = v.getTag(TAG) as? Target ?: return@Runnable
        val s = session ?: return@Runnable
        if (s.ended || t.folder == null || !t.open) return@Runnable
        leave(v)
        onHoverOpen(t.folder)
    }

    private fun leave(v: View) {
        unmark(v)
        if (hovered === v) {
            v.removeCallbacks(springOpen)
            hovered = null
        }
    }

    private fun mark(v: View, fill: Boolean) {
        if (highlighted.containsKey(v)) return
        val d = v.resources.displayMetrics.density
        val radius = (v.getTag(TAG) as? Target)?.radius ?: (12 * d)
        val accent = v.context.getColor(com.octavio.tinta.R.color.accent)
        val drawable = GradientDrawable().apply {
            cornerRadius = radius
            setStroke((2 * d).toInt().coerceAtLeast(2), accent)
            if (fill) setColor(accent and 0x00FFFFFF or 0x33000000) else setColor(0)
            setBounds(0, 0, v.width, v.height)
        }
        v.overlay.add(drawable)
        highlighted[v] = drawable
    }

    private fun unmark(v: View) {
        highlighted.remove(v)?.let { v.overlay.remove(it) }
    }

    private fun finish(s: Session) {
        s.source.alpha = 1f
        for ((v, d) in highlighted) v.overlay.remove(d)
        highlighted.clear()
        hovered?.removeCallbacks(springOpen)
        hovered = null
        stopScroll()
    }

    // --- Desplazamiento solo cerca de los bordes ---

    private fun autoScroll(x: Float, y: Float, from: View) {
        val r = Rect()
        val loc = IntArray(2)
        for (a in areas) {
            if (!a.isShown) continue
            a.getLocationOnScreen(loc)
            r.set(loc[0], loc[1], loc[0] + a.width, loc[1] + a.height)
            if (!r.contains(x.toInt(), y.toInt())) continue
            val edge = min(EDGE_DP * a.resources.displayMetrics.density, a.height / 4f)
            val top = y - r.top
            val bottom = r.bottom - y
            val max = MAX_SPEED_DP * a.resources.displayMetrics.density
            val speed = when {
                top < edge -> -max * (1 - top / edge)
                bottom < edge -> max * (1 - bottom / edge)
                else -> 0f
            }
            if (speed == 0f) stopScroll() else startScroll(a, speed)
            return
        }
        stopScroll()
    }

    private val scrollStep = object : Runnable {
        override fun run() {
            val v = scrollView ?: return
            if (scrollSpeed == 0f || session?.ended != false) return
            v.scrollBy(0, scrollSpeed.toInt())
            v.postOnAnimation(this)
        }
    }

    private fun startScroll(v: View, speed: Float) {
        val wasStopped = scrollSpeed == 0f || scrollView !== v
        scrollView?.takeIf { it !== v }?.removeCallbacks(scrollStep)
        scrollView = v
        scrollSpeed = speed
        if (wasStopped) v.postOnAnimation(scrollStep)
    }

    private fun stopScroll() {
        scrollView?.removeCallbacks(scrollStep)
        scrollView = null
        scrollSpeed = 0f
    }

    private fun screen(v: View, x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return (loc[0] + x) to (loc[1] + y)
    }

    /** La vista achicada (las tarjetas de notas son grandes), con el dedo en el medio. */
    private class Shadow(view: View, private val scale: Float) : View.DragShadowBuilder(view) {
        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            val v: View? = view
            size.set(max(1, ((v?.width ?: 1) * scale).toInt()), max(1, ((v?.height ?: 1) * scale).toInt()))
            touch.set(size.x / 2, size.y / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            val v: View = view ?: return
            canvas.scale(scale, scale)
            v.draw(canvas)
        }
    }

    private companion object {
        val TAG = com.octavio.tinta.R.id.drag_target
        val TAG_AREA = com.octavio.tinta.R.id.drag_area
        const val OPEN_DELAY_MS = 900L
        const val MOVE_DP = 12f
        const val EDGE_DP = 72f
        const val MAX_SPEED_DP = 14f
    }
}
