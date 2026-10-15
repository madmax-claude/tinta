package com.octavio.tinta.canvas

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * La hoja pintada en baldosas de [SIZE] px, cada una en una capa de la GPU (un RenderNode con
 * capa propia). Android repinta una capa sólo cuando cambia lo que tiene grabado, así que mover la
 * vista es componer unas decenas de texturas, sin reproducir los miles de mallas de los trazos.
 *
 * Las baldosas se pintan a escalas fijas, separadas por un factor √2 ([levelScale]); entre una y
 * otra se agrandan o achican un poco. Al cambiar de escala, las nuevas se pintan de a algunas por
 * cuadro (según cuántos trazos tienen, [Content.cost]) y mientras tanto se ven las de la escala
 * anterior estiradas.
 *
 * Una baldosa nueva aparece de a poco ([FADE_MS]) sobre lo que haya debajo (la escala anterior
 * o la imagen de toda la nota), así la hoja se va enfocando en vez de llenarse de a cuadrados.
 * Las que se repintan porque cambió algo aparecen de una.
 *
 * Una baldosa sólo se repinta cuando se vuelve a grabar, nunca sola: si cambia una tanda de
 * trazos, quien la cambió marca la zona con [invalidate].
 */
class Tiles(private val density: Float) {

    interface Content {
        /**
         * Graba lo que va en el rectángulo del mundo en `canvas`, de [SIZE] × [SIZE] px: la
         * esquina (left, top) va en (0, 0) y cada dp mide `scale` px.
         */
        fun record(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, scale: Float)

        /** Trazos aproximados que hay que reproducir para pintar el rectángulo. */
        fun cost(left: Float, top: Float, right: Float, bottom: Float): Int
    }

    private class Tile(val level: Int, val ix: Int, val iy: Int, span: Float) {
        val left = ix * span
        val top = iy * span
        val right = left + span
        val bottom = top + span
        val node = RenderNode("tinta-baldosa").apply {
            setPosition(0, 0, SIZE, SIZE)
            setUseCompositingLayer(true, null)
            clipToBounds = true
        }
        var ready = false
        var dirty = true

        /** Lo que cambió se tiene que ver en este cuadro: se repinta aunque no haya presupuesto. */
        var urgent = false
        var frame = 0L

        /** Desde cuándo aparece (ms), -1 si empieza en el próximo cuadro, 0 si ya se ve entera. */
        var fadeFrom = 0L
        var alpha = 1f

        fun intersects(r: RectF) = !(right < r.left || left > r.right || bottom < r.top || top > r.bottom)
    }

    private val tiles = HashMap<Long, Tile>()
    private var frame = 0L

    /** Escala en la que se pintan las baldosas ahora, y la anterior mientras se completa. */
    var level = NONE
        private set
    private var previous = NONE

    /**
     * Trazos que se pueden reproducir por cuadro al pintar baldosas nuevas. Lo ajusta
     * [adapt] según lo que tardan de verdad los cuadros en esta tablet.
     */
    var budget = 1500
        private set

    /**
     * Ajusta [budget] con lo que tardó un cuadro en el hilo de dibujo y la GPU: si se pasa de
     * lo que entra en un cuadro, se pinta menos por cuadro; si sobra tiempo, más.
     */
    fun adapt(renderNanos: Long) {
        budget = when {
            renderNanos > SLOW_NS -> (budget * 0.7f).toInt().coerceAtLeast(MIN_BUDGET)
            renderNanos < FAST_NS -> (budget * 1.1f).toInt().coerceAtMost(MAX_BUDGET)
            else -> budget
        }
    }

    /** true mientras falta pintar alguna baldosa a la vista de la escala actual. */
    var incomplete = false
        private set

    fun levelScale(level: Int) = density * 2f.pow(level / 2f)

    private fun bestLevel(scale: Float) = ceil(2 * ln(scale / density) / LN2 - 0.01f).toInt()

    /** Marca para repintar las baldosas que tocan el rectángulo del mundo (en todas las escalas). */
    fun invalidate(r: RectF, urgent: Boolean) {
        for (t in tiles.values) {
            if (!t.intersects(r)) continue
            t.dirty = true
            if (urgent && t.ready) t.urgent = true
        }
    }

    fun invalidateAll() {
        for (t in tiles.values) t.dirty = true
    }

    fun clear() {
        for (t in tiles.values) t.node.discardDisplayList()
        tiles.clear()
        level = NONE
        previous = NONE
    }

    /** La escala a la que conviene grabar los trazos para la vista actual. */
    fun recordScale(scale: Float, settled: Boolean): Float {
        chooseLevel(scale, settled)
        return levelScale(level)
    }

    private fun chooseLevel(scale: Float, settled: Boolean) {
        val best = bestLevel(scale)
        if (level == NONE) {
            level = best
            return
        }
        if (best == level) return
        // Con los dedos moviendo la vista se estiran las baldosas hasta cierto punto; al soltar,
        // se pasa a la escala justa.
        val k = scale / levelScale(level)
        if (settled || k > MAX_STRETCH || k < MIN_SHRINK) {
            // Si la escala actual quedó a medio pintar, se sigue mostrando la anterior completa.
            val leaving = level
            level = best
            if (previous == NONE || previous == best || !incomplete) previous = leaving
        }
    }

    /**
     * El rectángulo del mundo que cubren las baldosas a la vista y las de alrededor (una vuelta,
     * y [AHEAD_ROWS] filas arriba y abajo) en la escala actual.
     */
    fun neededArea(view: RectF, out: RectF): RectF {
        val span = SIZE / levelScale(level)
        return out.apply {
            set(
                (floor(view.left / span) - 1) * span,
                (floor(view.top / span) - AHEAD_ROWS) * span,
                (floor(view.right / span) + 2) * span,
                (floor(view.bottom / span) + 1 + AHEAD_ROWS) * span,
            )
        }
    }

    /**
     * Graba las baldosas que faltan (hasta agotar el presupuesto del cuadro) y las dibuja.
     * `view` es el rectángulo del mundo a la vista; (scale, tx, ty), la cámara. Con `settled` en
     * false (dedos moviendo la vista) sólo se pinta lo que entra en el presupuesto, para no trabar
     * el movimiento. Devuelve true si quedó algo por pintar.
     */
    fun draw(canvas: Canvas, view: RectF, scale: Float, tx: Float, ty: Float, settled: Boolean, content: Content): Boolean {
        frame++
        val span = SIZE / levelScale(level)
        val ix0 = floor(view.left / span).toInt()
        val iy0 = floor(view.top / span).toInt()
        val ix1 = floor(view.right / span).toInt()
        val iy1 = floor(view.bottom / span).toInt()
        val cx = (ix0 + ix1) / 2f
        val cy = (iy0 + iy1) / 2f

        // Las de la vista primero (del centro hacia afuera), después las de alrededor: una vuelta y
        // más filas arriba y abajo, porque las notas se recorren sobre todo hacia abajo. Las de
        // afuera se pintan sólo si sobra presupuesto, y se dibujan (fuera de la pantalla) para que
        // Android no suelte su capa.
        val wanted = ArrayList<Tile>()
        for (iy in iy0 - AHEAD_ROWS..iy1 + AHEAD_ROWS) for (ix in ix0 - 1..ix1 + 1) wanted.add(tile(level, ix, iy, span))
        wanted.sortBy {
            val inside = it.ix in ix0..ix1 && it.iy in iy0..iy1
            val d = (it.ix - cx) * (it.ix - cx) + (it.iy - cy) * (it.iy - cy)
            if (inside) d else d + 1e6f
        }
        var spent = 0
        var pending = false
        var missing = false
        for (t in wanted) {
            t.frame = frame
            if (t.ready && !t.dirty) continue
            val inside = t.ix in ix0..ix1 && t.iy in iy0..iy1
            val cost = content.cost(t.left, t.top, t.right, t.bottom) + 1
            // Quieta, la vista pinta al menos una por cuadro aunque tenga muchos trazos; con los
            // dedos moviéndola, la mitad, para que el movimiento no se trabe.
            val limit = if (settled) budget else budget / 2
            val fits = t.urgent || spent + cost <= limit || (settled && spent == 0)
            if (fits) {
                record(t, content)
                spent += cost
            } else {
                pending = true
                if (inside && !t.ready) missing = true
            }
        }
        // Las que todavía están apareciendo también necesitan algo debajo.
        val fading = wanted.any { it.fadeFrom != 0L && it.ix in ix0..ix1 && it.iy in iy0..iy1 }
        incomplete = missing || fading

        // Mientras faltan baldosas se ven, debajo, las de la escala anterior.
        val prev = previous
        if (prev != NONE) {
            if (!incomplete || prev == level) {
                if (prev != level) drop { it.level == prev }
                previous = NONE
            } else {
                for (t in tiles.values) {
                    if (t.level != prev || !t.ready || t.dirty) continue // vieja: mejor no mostrarla
                    t.frame = frame
                    drawTile(canvas, t, scale, tx, ty)
                }
            }
        }
        val now = SystemClock.uptimeMillis()
        for (t in wanted) {
            if (!t.ready) continue
            fade(t, now)
            drawTile(canvas, t, scale, tx, ty)
        }
        // Las que quedaron lejos se sueltan: Android libera su capa al dejar de dibujarlas.
        drop { it.frame != frame }
        return pending || fading
    }

    /** Ajusta la transparencia de una baldosa que está apareciendo. */
    private fun fade(t: Tile, now: Long) {
        if (t.fadeFrom == 0L) {
            setAlpha(t, 1f)
            return
        }
        if (t.fadeFrom < 0) t.fadeFrom = now
        val a = ((now - t.fadeFrom) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (a >= 1f) t.fadeFrom = 0L
        // Suave al principio y al final.
        setAlpha(t, a * a * (3 - 2 * a))
    }

    private fun setAlpha(t: Tile, a: Float) {
        if (t.alpha == a) return
        t.alpha = a
        t.node.alpha = a
    }

    private fun key(level: Int, ix: Int, iy: Int) = (level + 64).toLong() shl 52 or ((ix + OFFSET).toLong() shl 26) or (iy + OFFSET).toLong()

    private fun tile(level: Int, ix: Int, iy: Int, span: Float): Tile = tiles.getOrPut(key(level, ix, iy)) { Tile(level, ix, iy, span) }

    /**
     * Si en este cuadro va a faltar (o estar apareciendo) alguna baldosa de la vista, para poner
     * algo debajo antes de dibujarlas. Llamar después de [recordScale].
     */
    fun needsBackdrop(view: RectF): Boolean {
        if (level == NONE) return true
        val span = SIZE / levelScale(level)
        for (iy in floor(view.top / span).toInt()..floor(view.bottom / span).toInt()) {
            for (ix in floor(view.left / span).toInt()..floor(view.right / span).toInt()) {
                val t = tiles[key(level, ix, iy)] ?: return true
                if (!t.ready || t.fadeFrom != 0L) return true
            }
        }
        return false
    }

    private fun record(t: Tile, content: Content) {
        val s = levelScale(t.level)
        val rc = t.node.beginRecording(SIZE, SIZE)
        content.record(rc, t.left, t.top, t.right, t.bottom, s)
        t.node.endRecording()
        // Nueva: aparece de a poco. Si ya se veía (cambió algo), se cambia de una.
        if (!t.ready) t.fadeFrom = -1L
        t.ready = true
        t.dirty = false
        t.urgent = false
    }

    /** Las esquinas se redondean a píxeles enteros para que no queden rendijas entre baldosas. */
    private fun drawTile(canvas: Canvas, t: Tile, scale: Float, tx: Float, ty: Float) {
        val x0 = (t.left * scale + tx).roundToInt()
        val y0 = (t.top * scale + ty).roundToInt()
        val x1 = (t.right * scale + tx).roundToInt()
        val y1 = (t.bottom * scale + ty).roundToInt()
        if (x1 <= x0 || y1 <= y0) return
        val save = canvas.save()
        canvas.translate(x0.toFloat(), y0.toFloat())
        canvas.scale((x1 - x0) / SIZE.toFloat(), (y1 - y0) / SIZE.toFloat())
        canvas.drawRenderNode(t.node)
        canvas.restoreToCount(save)
    }

    private inline fun drop(predicate: (Tile) -> Boolean) {
        val it = tiles.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (predicate(t)) {
                t.node.discardDisplayList()
                it.remove()
            }
        }
    }

    companion object {
        /** Lado de una baldosa en px: 1 MB en la GPU. */
        const val SIZE = 512


        private const val NONE = Int.MIN_VALUE
        private const val MIN_BUDGET = 200
        private const val MAX_BUDGET = 8000
        private const val SLOW_NS = 12_000_000L
        private const val FAST_NS = 6_000_000L
        private const val OFFSET = 1 shl 25
        private val LN2 = ln(2f)
        private const val MAX_STRETCH = 1.6f
        private const val MIN_SHRINK = 0.5f

        /** Cuánto tarda en aparecer una baldosa nueva. */
        private const val FADE_MS = 180L

        /** Filas que se preparan arriba y abajo de la vista. */
        private const val AHEAD_ROWS = 2
    }
}
