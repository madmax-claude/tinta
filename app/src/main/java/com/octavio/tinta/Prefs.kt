package com.octavio.tinta

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import com.octavio.tinta.canvas.NoteCanvasView
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.LassoFilter
import com.octavio.tinta.model.PageSize
import com.octavio.tinta.model.Pen

/** Preferencias de la app: modo oscuro, lápices de la barra, colores guardados, lazo, hojas nuevas. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("tinta", Context.MODE_PRIVATE)

    var dark: Boolean
        get() = p.getBoolean("dark", false)
        set(v) = p.edit { putBoolean("dark", v) }

    var pens: List<Pen>
        get() = Pen.listFromJson(p.getString("pens", null))
        set(v) = p.edit { putString("pens", Pen.listToJson(v)) }

    var selectedPen: Int
        get() = p.getInt("pen", 0)
        set(v) = p.edit { putInt("pen", v) }

    /** Lo elegido en la barra: lápiz, borrador o lazo. */
    var mode: NoteCanvasView.Mode
        get() = NoteCanvasView.Mode.entries.getOrNull(p.getInt("mode", -1))
            ?: if (p.getBoolean("eraser", false)) NoteCanvasView.Mode.ERASER else NoteCanvasView.Mode.PEN
        set(v) = p.edit { putInt("mode", v.ordinal) }

    /** El orden de las herramientas de la barra lateral (las que no estén, al final). */
    var dockOrder: List<com.octavio.tinta.ui.ToolDock.Tool>
        get() = p.getString("dockOrder", null)?.split(',')?.mapNotNull { n -> com.octavio.tinta.ui.ToolDock.Tool.entries.firstOrNull { it.name == n } }.orEmpty()
        set(v) = p.edit { putString("dockOrder", v.joinToString(",") { it.name }) }

    /** Color de hoja para las notas nuevas (el último elegido), o null para el de siempre. */
    var newPaperColor: Int?
        get() = p.getInt("newPaperColor", 0).takeIf { it != 0 }
        set(v) = p.edit { putInt("newPaperColor", v ?: 0) }

    /** Colores de la interfaz elegidos en Configuración (null: los de siempre). */
    var topBarColor: Int?
        get() = p.getInt("topBarColor", 0).takeIf { it != 0 }
        set(v) = p.edit { putInt("topBarColor", v ?: 0) }
    var toolsColor: Int?
        get() = p.getInt("toolsColor", 0).takeIf { it != 0 }
        set(v) = p.edit { putInt("toolsColor", v ?: 0) }
    var tabColor: Int?
        get() = p.getInt("tabColor", 0).takeIf { it != 0 }
        set(v) = p.edit { putInt("tabColor", v ?: 0) }

    /** Menú principal: la barra de carpetas y el fondo de la lista de notas. */
    var menuColor: Int?
        get() = p.getInt("menuColor", 0).takeIf { it != 0 }
        set(v) = p.edit { putInt("menuColor", v ?: 0) }
    var listColor: Int?
        get() = p.getInt("listColor", 0).takeIf { it != 0 }
        set(v) = p.edit { putInt("listColor", v ?: 0) }

    /** Todo lo de Configuración que cambia cómo se arma la pantalla (si cambia, se vuelve a armar). */
    val look: String get() = "$helpMessages/$topBarColor/$toolsColor/$tabColor/$menuColor/$listColor"

    /** Mostrar los mensajes de ayuda ([Help]). */
    var helpMessages: Boolean
        get() = p.getBoolean("helpMessages", true)
        set(v) = p.edit { putBoolean("helpMessages", v) }

    /** Ya se explicó cómo se usa la captura. */
    var captureHintShown: Boolean
        get() = p.getBoolean("captureHint", false)
        set(v) = p.edit { putBoolean("captureHint", v) }

    /** El borrador saca sólo la parte que toca. */
    var eraserPartial: Boolean
        get() = p.getBoolean("eraserPartial", false)
        set(v) = p.edit { putBoolean("eraserPartial", v) }

    var holdToErase: Boolean
        get() = p.getBoolean("holdToErase", true)
        set(v) = p.edit { putBoolean("holdToErase", v) }

    var holdForShapes: Boolean
        get() = p.getBoolean("holdForShapes", true)
        set(v) = p.edit { putBoolean("holdForShapes", v) }

    var lassoFilter: LassoFilter
        get() = LassoFilter(
            p.getBoolean("lassoPens", true),
            p.getBoolean("lassoHighlighters", true),
            p.getBoolean("lassoImages", true),
            p.getBoolean("lassoTexts", true),
            p.getBoolean("lassoWidgets", true),
        )
        set(v) = p.edit {
            putBoolean("lassoPens", v.pens)
            putBoolean("lassoHighlighters", v.highlighters)
            putBoolean("lassoImages", v.images)
            putBoolean("lassoTexts", v.texts)
            putBoolean("lassoWidgets", v.widgets)
        }

    /** Hoja y fondo de las notas nuevas: los últimos que se eligieron. */
    var newPageSize: PageSize
        get() = PageSize.fromId(p.getInt("pageSize", PageSize.INFINITE.id))
        set(v) = p.edit { putInt("pageSize", v.id) }

    var newLandscape: Boolean
        get() = p.getBoolean("landscape", false)
        set(v) = p.edit { putBoolean("landscape", v) }

    var newBackground: Background
        get() = Background.fromId(p.getInt("background", Background.GRID.id))
        set(v) = p.edit { putInt("background", v.id) }

    /** Radio del borrador en dp de pantalla. */
    var eraserRadius: Float
        get() = p.getFloat("eraserRadius", 12f)
        set(v) = p.edit { putFloat("eraserRadius", v) }

    /** Colores que eligió con el selector y guardó, del más nuevo al más viejo. */
    var savedColors: List<Int>
        get() = p.getString("colors", "").orEmpty().split(',').mapNotNull { it.toLongOrNull()?.toInt() }
        set(v) = p.edit { putString("colors", v.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }) }

    /** Posición de la barra flotante como fracción del espacio libre (0 a 1 en cada eje). */
    var toolbarX: Float
        get() = p.getFloat("toolbarX", 0.5f)
        set(v) = p.edit { putFloat("toolbarX", v) }

    var toolbarY: Float
        get() = p.getFloat("toolbarY", 0f)
        set(v) = p.edit { putFloat("toolbarY", v) }

    var toolbarVertical: Boolean
        get() = p.getBoolean("toolbarVertical", false)
        set(v) = p.edit { putBoolean("toolbarVertical", v) }

    /** Pestañas abiertas en la ventana `slot` (ver [Tabs]). */
    fun tabs(slot: Int): List<String> = p.getString("tabs$slot", "").orEmpty().split(',').filter { it.isNotBlank() }

    fun setTabs(slot: Int, ids: List<String>) = p.edit { putString("tabs$slot", ids.joinToString(",")) }

    /** Pantalla completa: sin barras del sistema ni barra de arriba. */
    var fullscreen: Boolean
        get() = p.getBoolean("fullscreen", false)
        set(v) = p.edit { putBoolean("fullscreen", v) }

    /** El temporizador: si se ve y dónde (fracción del espacio libre). */
    var timerVisible: Boolean
        get() = p.getBoolean("timerVisible", false)
        set(v) = p.edit { putBoolean("timerVisible", v) }

    var timerX: Float
        get() = p.getFloat("timerX", 0.5f)
        set(v) = p.edit { putFloat("timerX", v) }

    var timerY: Float
        get() = p.getFloat("timerY", 0.02f)
        set(v) = p.edit { putFloat("timerY", v) }

    /** La tira de miniaturas de las hojas está escondida. */
    var pageStripCollapsed: Boolean
        get() = p.getBoolean("pageStripCollapsed", false)
        set(v) = p.edit { putBoolean("pageStripCollapsed", v) }

    /** La barra lateral: de qué lado y si está escondida. */
    var dockRight: Boolean
        get() = p.getBoolean("dockRight", true)
        set(v) = p.edit { putBoolean("dockRight", v) }

    var dockCollapsed: Boolean
        get() = p.getBoolean("dockCollapsed", false)
        set(v) = p.edit { putBoolean("dockCollapsed", v) }

    /** Carpeta abierta en la lista de notas; null para todas. */
    var folder: String?
        get() = p.getString("folder", null)
        set(v) = p.edit { putString("folder", v) }
}

/**
 * Base de las pantallas: aplica el modo oscuro elegido en la app, que puede no coincidir con el del
 * sistema, cambiando el modo noche de la configuración (así se usan los recursos de values-night).
 */
open class TintaActivity : ComponentActivity() {
    /** Cómo estaban los mensajes de ayuda al armar esta pantalla (si cambian, se vuelve a armar). */
    var helpShown = true
        private set

    /** Cómo estaba [Prefs.look] al armar esta pantalla. */
    var lookShown = ""
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        Help.on = Prefs(newBase).helpMessages
        helpShown = Help.on
        lookShown = Prefs(newBase).look
        val night = if (Prefs(newBase).dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val base = newBase.resources.configuration.uiMode
        applyOverrideConfiguration(Configuration().apply { uiMode = (base and Configuration.UI_MODE_NIGHT_MASK.inv()) or night })
    }

    val isDark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
