package com.octavio.tinta.canvas

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import com.octavio.tinta.model.Appearance
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Tool
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * Los trazos agrupados en tandas de trazos consecutivos, cada una grabada en un [RenderNode] en
 * coordenadas del mundo. Dibujar un trazo con Ink cuesta ~20 µs de CPU: se graba cada tanda una vez
 * y las baldosas de [Tiles] reproducen las grabaciones. Sólo se vuelve a grabar la tanda que cambió,
 * y su caja queda en [damage] para que se repinten las baldosas que la tocan.
 *
 * El orden de dibujo se conserva: una tanda junta trazos seguidos de la lista, los trazos nuevos
 * van a la última, y deshacer devuelve cada trazo a la tanda que tenía.
 *
 * Todas las tandas cuelgan de un nodo raíz invisible que se dibuja en cada cuadro ([keepAlive]):
 * Android libera lo grabado en un RenderNode que deja de dibujarse, y una tanda que ninguna baldosa
 * a la vista usa perdería su grabación.
 */
class StrokeChunks {

    private class Chunk {
        val node = RenderNode("tinta").apply { clipToBounds = false }
        var members = ArrayList<InkStroke>()
        var next = ArrayList<InkStroke>()
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        var built = -1

        /** Cambiaron los trazos: hay que grabarla ya (por ejemplo, después de borrar). */
        var contentDirty = true

        /** Llegaron mallas o cambió mucho el zoom: se graba cuando haya tiempo. */
        var meshDirty = false
        var recorded = false
        var recordedScale = 0f

        /** Caja de lo que tiene grabado ahora, para repintar lo que deja de estar. */
        val recordedBounds = RectF()

        fun intersects(l: Float, t: Float, r: Float, b: Float) =
            members.isNotEmpty() && !(right < l || left > r || bottom < t || top > b)
    }

    private val chunks = ArrayList<Chunk>()
    private val owner = IdentityHashMap<InkStroke, Chunk>()
    private var tail: Chunk? = null
    private val root = RenderNode("tinta-raiz").apply {
        clipToBounds = false
        alpha = 0f
    }
    private var rootDirty = true

    /**
     * Zonas del mundo cuya grabación cambió desde la última vez que se leyó. Las de [urgentDamage]
     * son cambios que se tienen que ver en este cuadro (un trazo borrado o recién pasado a las
     * tandas); las de [damage], mejoras que pueden esperar (llegaron mallas, mejor antialiasing).
     */
    val damage = ArrayList<RectF>()
    val urgentDamage = ArrayList<RectF>()

    /** Modo oscuro: cambia el color de todos los trazos. */
    var dark = false
        set(value) {
            if (field == value) return
            field = value
            for (c in chunks) c.meshDirty = true
        }

    /**
     * Pone las tandas al día con la lista de trazos. O(n), sin grabar nada. Las tandas que nunca se
     * grabaron (al abrir la nota) se graban de a poco; las que ya se veían y cambiaron, enseguida.
     */
    fun sync(strokes: List<InkStroke>) {
        for (c in chunks) c.next.clear()
        var previous: Chunk? = null
        for (s in strokes) {
            // Un trazo nuevo en el medio de la lista (un pedazo que dejó la goma) va con el de
            // antes, para que se siga dibujando debajo de lo que estaba encima.
            val c = owner[s] ?: previous?.takeIf { it !== tail && it.next.size < 2 * CAPACITY }?.also { owner[s] = it }
                ?: tailFor(s).also { owner[s] = it }
            c.next.add(s)
            previous = c
        }
        for (c in chunks) {
            val built = c.next.count { it.stroke != null }
            if (!sameMembers(c)) {
                val first = c.members.isEmpty() && !c.recorded && !opened
                c.members = c.next.also { c.next = c.members }
                c.contentDirty = !first
                updateBounds(c)
                rootDirty = true
                if (c.members.isEmpty() && c.recorded) {
                    // Se vació: se borra su grabación y se repinta donde estaba.
                    urgentDamage.add(RectF(c.recordedBounds))
                    c.node.discardDisplayList()
                    c.recorded = false
                }
            } else if (built != c.built) {
                c.meshDirty = true
            }
            c.built = built
        }
        opened = true
    }

    /**
     * Cuando la ventana deja de verse (otra app encima), Android borra lo grabado en todos los
     * nodos. Al volver, las tandas creen que siguen grabadas y la nota se ve vacía. Si se perdió la
     * grabación del nodo raíz, se perdieron todas: se marcan para volver a grabarlas. Devuelve true
     * si pasó.
     */
    fun recoverLost(): Boolean {
        if (rootDirty || root.hasDisplayList()) return false
        rootDirty = true
        for (c in chunks) if (c.recorded && !c.node.hasDisplayList()) c.recorded = false
        return true
    }

    /** Ya pasó la primera sincronización: lo que cambie desde ahora se tiene que ver enseguida. */
    private var opened = false

    fun clear() {
        for (c in chunks) c.node.discardDisplayList()
        chunks.clear()
        owner.clear()
        tail = null
        rootDirty = true
        opened = false
        damage.clear()
        urgentDamage.clear()
    }

    /** Cuántos trazos tienen las tandas que tocan el rectángulo (cuenta tandas enteras). */
    fun count(l: Float, t: Float, r: Float, b: Float): Int {
        var n = 0
        for (c in chunks) if (c.intersects(l, t, r, b)) n += c.members.size
        return n
    }

    /**
     * Graba las tandas del rectángulo que lo necesitan, a la escala `scale` (px por dp, para el
     * antialiasing de Ink). Las que cambiaron de trazos se graban siempre; las que esperan mallas o
     * mejor calidad, sólo hasta `deadline`. Devuelve true si quedó algo pendiente.
     */
    fun update(l: Float, t: Float, r: Float, b: Float, scale: Float, settled: Boolean, deadline: Long): Boolean {
        var pending = false
        for (c in chunks) {
            if (!c.intersects(l, t, r, b)) continue
            val blurry = settled && c.recorded && abs(ln(scale / c.recordedScale)) > REZOOM
            when {
                c.contentDirty -> record(c, scale, urgent = true)
                c.meshDirty || blurry || !c.recorded ->
                    if (System.nanoTime() < deadline) record(c, scale, urgent = false) else pending = true
            }
        }
        return pending
    }

    /**
     * Graba, hasta `deadline`, tandas que nunca se grabaron aunque estén lejos de la vista. Se
     * hace con la vista quieta: la primera vez que se dibuja una malla, Android la sube a la GPU
     * (varios ms por tanda), y así ese costo no cae todo junto en el primer zoom hacia afuera.
     * Devuelve true si quedan.
     */
    fun prewarm(scale: Float, deadline: Long): Boolean {
        for (c in chunks) {
            if (c.recorded || c.members.isEmpty() || c.built <= 0) continue
            if (System.nanoTime() >= deadline) return true
            record(c, scale, urgent = false)
        }
        return false
    }

    /** Llama a `action` con el nodo de cada tanda grabada que toca el rectángulo, en orden. */
    inline fun forEachNode(l: Float, t: Float, r: Float, b: Float, action: (RenderNode) -> Unit) {
        for (i in 0 until size) nodeIfIntersects(i, l, t, r, b)?.let(action)
    }

    val size get() = chunks.size

    fun nodeIfIntersects(i: Int, l: Float, t: Float, r: Float, b: Float): RenderNode? {
        val c = chunks[i]
        return if (c.recorded && c.intersects(l, t, r, b)) c.node else null
    }

    /** Dibuja el nodo raíz invisible que mantiene vivas las grabaciones. Va antes que las baldosas. */
    fun keepAlive(canvas: Canvas) {
        if (rootDirty) recordRoot()
        canvas.drawRenderNode(root)
    }

    private fun recordRoot() {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (c in chunks) {
            if (c.members.isEmpty() || !c.left.isFinite()) continue
            l = minOf(l, c.left)
            t = minOf(t, c.top)
            r = maxOf(r, c.right)
            b = maxOf(b, c.bottom)
        }
        if (l > r) {
            l = 0f
            t = 0f
            r = 1f
            b = 1f
        }
        val left = floor(l).toInt()
        val top = floor(t).toInt()
        root.setPosition(left, top, ceil(r).toInt() + 1, ceil(b).toInt() + 1)
        val rc = root.beginRecording()
        rc.translate(-left.toFloat(), -top.toFloat())
        for (c in chunks) if (c.members.isNotEmpty()) rc.drawRenderNode(c.node)
        root.endRecording()
        rootDirty = false
    }

    private val identity = Matrix()

    private fun record(c: Chunk, scale: Float, urgent: Boolean) {
        // La posición del nodo tiene que ser su caja real: Android descarta los nodos cuya caja
        // queda fuera de lo que se está pintando. El contenido se graba relativo a esa esquina.
        val finite = c.left.isFinite() && c.bottom.isFinite()
        val left = if (finite) floor(c.left).toInt() else 0
        val top = if (finite) floor(c.top).toInt() else 0
        if (finite) c.node.setPosition(left, top, ceil(c.right).toInt() + 1, ceil(c.bottom).toInt() + 1)
        val rc = c.node.beginRecording()
        rc.translate(-left.toFloat(), -top.toFloat())
        // La matriz sólo le dice a Ink la escala en pantalla, para el antialiasing.
        identity.setScale(scale, scale)
        InkDraw.use { r ->
            for (s in c.members) {
                val mesh = s.display(dark)
                if (mesh != null) r.draw(rc, mesh, identity) else drawRough(rc, s)
            }
        }
        c.node.endRecording()
        val box = RectF(c.left, c.top, c.right, c.bottom)
        val target = if (urgent) urgentDamage else damage
        target.add(if (c.recorded) RectF(c.recordedBounds).apply { union(box) } else box)
        c.recordedBounds.set(box)
        c.recorded = true
        c.recordedScale = scale
        c.contentDirty = false
        c.meshDirty = false
    }

    private val roughPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val roughPath = Path()

    /**
     * Un trazo cuya malla todavía no se armó (al abrir una nota grande se arman de a miles en
     * segundo plano): una línea por sus puntos, del grosor y el color del trazo. Así la nota se ve
     * enseguida en vez de vacía; cuando llega la malla, la tanda se vuelve a grabar con el trazo
     * de verdad.
     */
    private fun drawRough(canvas: Canvas, s: InkStroke) {
        val p = runCatching { s.points() }.getOrNull() ?: return
        if (p.size < 2) return
        roughPath.rewind()
        roughPath.moveTo(p[0], p[1])
        if (p.size == 2) roughPath.lineTo(p[0] + 0.01f, p[1]) else for (k in 1 until p.size / 2) roughPath.lineTo(p[2 * k], p[2 * k + 1])
        roughPaint.color = Appearance.ink(s.color, s.tool, dark)
        roughPaint.strokeWidth = s.size * if (s.tool == Tool.PEN) ROUGH_PEN_WIDTH else 1f
        canvas.drawPath(roughPath, roughPaint)
    }

    /** La tanda para un trazo nuevo: la última, salvo que esté llena o muy lejos. */
    private fun tailFor(s: InkStroke): Chunk {
        val t = tail
        if (t != null && t.next.size < CAPACITY && near(t, s)) return t
        return Chunk().also {
            chunks.add(it)
            tail = it
        }
    }

    private fun near(c: Chunk, s: InkStroke): Boolean {
        if (c.next.isEmpty() || s.left.isNaN()) return true
        val first = c.next[0]
        if (first.left.isNaN()) return true
        return abs(s.left - first.left) < FAR && abs(s.top - first.top) < FAR
    }

    private fun sameMembers(c: Chunk): Boolean {
        if (c.next.size != c.members.size) return false
        for (i in c.next.indices) if (c.next[i] !== c.members[i]) return false
        return true
    }

    private fun updateBounds(c: Chunk) {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (s in c.members) {
            if (s.left.isNaN()) continue
            l = minOf(l, s.left)
            t = minOf(t, s.top)
            r = maxOf(r, s.right)
            b = maxOf(b, s.bottom)
        }
        c.left = l
        c.top = t
        c.right = r
        c.bottom = b
    }

    private companion object {
        /** Trazos por tanda: grabar una lleva unos 3 ms en la P12. */
        const val CAPACITY = 64

        /**
         * Distancia (dp del mundo) a partir de la cual un trazo nuevo empieza otra tanda. Tandas
         * compactas tocan menos baldosas: repintar una baldosa reproduce las tandas enteras.
         */
        const val FAR = 300f

        /** Ancho de la línea provisoria de la pluma, por su grosor (la presión la hace variar). */
        const val ROUGH_PEN_WIDTH = 0.8f

        /** Regrabar si el zoom cambió más de ×1,6 desde la grabación (antialiasing de Ink). */
        val REZOOM = ln(1.6f)
    }
}
