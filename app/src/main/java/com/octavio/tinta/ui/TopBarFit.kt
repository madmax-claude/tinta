package com.octavio.tinta.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.core.view.isGone
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip

/**
 * En una ventana angosta (pantalla dividida) los botones de arriba no dejan ver las pestañas.
 * Los menos usados pasan a un menú y vuelven solos cuando hay lugar. Se acomoda al cambiar el
 * tamaño, sin recrear la pantalla: el editor declara configChanges y esto mira el ancho real.
 */
object TopBarFit {
    /** Se esconden en este orden, del que menos se usa al que más conviene dejar a la vista. */
    private val hideFirst = intArrayOf(
        R.id.dark,
        R.id.fit,
        R.id.export,
        R.id.search,
        R.id.sheet,
        R.id.beside,
        R.id.fullscreen,
    )

    /** Orden visual de la barra, para armar el menú de izquierda a derecha. */
    private val barOrder = intArrayOf(
        R.id.search,
        R.id.sheet,
        R.id.beside,
        R.id.dark,
        R.id.fit,
        R.id.fullscreen,
        R.id.export,
    )

    fun install(bar: LinearLayout) {
        val overflow = bar.findViewById<View>(R.id.top_overflow)
        overflow.tip(overflow.contentDescription)
        overflow.setOnClickListener { showMenu(bar, overflow) }
        bar.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) apply(bar, overflow)
        }
        bar.post { apply(bar, overflow) }
    }

    private fun apply(bar: LinearLayout, overflow: View) {
        val inner = bar.width - bar.paddingLeft - bar.paddingRight
        if (inner <= 0) return
        val back = bar.findViewById<View>(R.id.back)
        val tabs = bar.findViewById<View>(R.id.tabs)
        val optional = hideFirst.map { bar.findViewById<View>(it) }
        val sample = optional.firstOrNull() ?: return
        val tabsMargin = (tabs.layoutParams as? LinearLayout.LayoutParams)?.marginStart ?: 0
        val minTabs = bar.context.dp(160f).toInt() + tabsMargin
        val hideCount = topBarOverflowCount(inner, slotOf(back), slotOf(sample), minTabs, optional.size)
        val hidden = optional.take(hideCount).toSet()
        for (v in optional) {
            val want = if (v in hidden) View.GONE else View.VISIBLE
            if (v.visibility != want) v.visibility = want
        }
        val overflowWant = if (hideCount == 0) View.GONE else View.VISIBLE
        if (overflow.visibility != overflowWant) overflow.visibility = overflowWant
    }

    private fun slotOf(v: View): Int {
        val lp = v.layoutParams as? LinearLayout.LayoutParams
        val w = lp?.width?.takeIf { it > 0 } ?: v.width.coerceAtLeast(0)
        return w + (lp?.marginStart ?: 0) + (lp?.marginEnd ?: 0)
    }

    private fun showMenu(bar: LinearLayout, anchor: View) {
        val menu = PopupMenu(bar.context, anchor)
        var any = false
        for (id in barOrder) {
            val v = bar.findViewById<View>(id)
            if (!v.isGone) continue
            val title = v.contentDescription ?: continue
            any = true
            menu.menu.add(title).setOnMenuItemClickListener {
                v.performClick()
                true
            }
        }
        if (any) menu.show()
    }
}
