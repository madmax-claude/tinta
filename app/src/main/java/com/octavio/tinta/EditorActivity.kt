package com.octavio.tinta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.octavio.tinta.canvas.Elements
import com.octavio.tinta.canvas.NoteCanvasView
import com.octavio.tinta.canvas.NoteCanvasView.Mode
import com.octavio.tinta.export.Exporter
import com.octavio.tinta.export.Render
import com.octavio.tinta.model.Clipboard
import com.octavio.tinta.model.Find
import com.octavio.tinta.model.Graph2D
import com.octavio.tinta.model.Graph3D
import com.octavio.tinta.model.History
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Media
import com.octavio.tinta.model.MediaKind
import com.octavio.tinta.model.MeshBuilder
import com.octavio.tinta.model.Note
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import com.octavio.tinta.model.Pen
import com.octavio.tinta.model.Solid
import com.octavio.tinta.model.SolidKind
import com.octavio.tinta.model.Tool
import com.octavio.tinta.model.Transform
import com.octavio.tinta.model.Viewport
import com.octavio.tinta.model.Widget
import com.octavio.tinta.storage.CloudBackup
import com.octavio.tinta.storage.HandText
import com.octavio.tinta.storage.ImageImport
import com.octavio.tinta.storage.Imports
import com.octavio.tinta.storage.NoteStore
import com.octavio.tinta.storage.Stickers
import com.octavio.tinta.ui.AudioPlayerBar
import com.octavio.tinta.ui.CaptureBar
import com.octavio.tinta.ui.Chrome
import com.octavio.tinta.ui.CropDialog
import com.octavio.tinta.ui.FloatingToolbar
import com.octavio.tinta.ui.PageStrip
import com.octavio.tinta.ui.PageThumbs
import com.octavio.tinta.ui.Popups
import com.octavio.tinta.ui.RecorderBar
import com.octavio.tinta.ui.SearchPanel
import com.octavio.tinta.ui.SelectionBar
import com.octavio.tinta.ui.TabStrip
import com.octavio.tinta.ui.TimerPanel
import com.octavio.tinta.ui.TimerState
import com.octavio.tinta.ui.ToolDock
import com.octavio.tinta.ui.VideoPlayer
import com.octavio.tinta.ui.ZoomBar
import com.octavio.tinta.ui.WidgetEditors
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * Una mitad de la pantalla con notas en pestañas. La ventana 0 se abre desde la lista; la 1, al
 * lado (ver [Tabs]). Una nota se muestra en una sola ventana a la vez.
 */
class EditorActivity :
    TintaActivity(),
    NoteCanvasView.Listener,
    FloatingToolbar.Callbacks,
    SelectionBar.Callbacks,
    ToolDock.Callbacks,
    TabStrip.Callbacks,
    PageStrip.Callbacks,
    ZoomBar.Callbacks,
    SearchPanel.Callbacks,
    CaptureBar.Callbacks {

    private lateinit var store: NoteStore
    private lateinit var prefs: Prefs
    private lateinit var canvas: NoteCanvasView
    private lateinit var toolbar: FloatingToolbar
    private lateinit var selectionBar: SelectionBar
    private lateinit var dock: ToolDock
    private lateinit var tabs: TabStrip
    private lateinit var recorder: RecorderBar
    private lateinit var player: AudioPlayerBar
    private lateinit var loading: View
    private lateinit var timer: TimerPanel
    private lateinit var pageThumbs: PageThumbs
    private lateinit var pageStrip: PageStrip
    private lateinit var zoomBar: ZoomBar
    private lateinit var searchPanel: SearchPanel
    private lateinit var captureBar: CaptureBar

    /** Lo marcado con la herramienta de captura (rectángulo del mundo), o null. */
    private var captureArea: RectF? = null
    private val captureScreen = RectF()

    /** Los renglones leídos de la nota abierta, y para qué versión de la nota (su `modified`). */
    private var searchLines: List<HandText.Line>? = null
    private var searchLinesAt = 0L
    private var searchReading = false
    private var searchHits: List<HandText.Line> = emptyList()

    /** Lo que hay que buscar al terminar de abrir la nota (al venir de la búsqueda de la lista). */
    private var pendingSearch: String? = null

    /** Las miniaturas se rehacen un rato después del último cambio, no con cada trazo. */
    private val thumbsUpdate = Runnable { updatePageStrip(snapshot = true) }
    private lateinit var exitFullscreen: ImageButton

    /** Esta ventana: 0 la de la lista, 1 la de al lado. */
    private var slot = 0

    private var note: Note? = null
    private var history: History? = null
    private var meshes: MeshBuilder? = null

    /** La nota que se está leyendo del disco para mostrar. */
    private var loadingId: String? = null

    /** Títulos de las notas, para las pestañas. */
    private val titles = HashMap<String, String>()

    private var pens: List<Pen> = emptyList()
    private var selected = 0
    private var mode = Mode.PEN

    /** A qué volver cuando un clic del botón lateral (o el láser) se apaga. */
    private var previousMode = Mode.PEN

    /** Hay trazos o datos sin guardar. */
    private var dirty = false

    /** Sólo cambió la vista; se guarda al salir. */
    private var viewportChanged = false

    /** La miniatura no muestra los últimos cambios; se rehace al salir. */
    private var thumbnailStale = false

    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { save(withThumbnail = false) }
    private val selectionRect = RectF()

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::addImage)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::addVideo)
    }
    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("Sin permiso para el micrófono no se puede grabar")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        findViewById<View>(R.id.root).padForSystemBars { prefs.fullscreen }
        store = NoteStore.get(this)
        prefs = Prefs(this)
        Diag.init(this)
        slot = intent.getIntExtra(EXTRA_SLOT, 0).coerceIn(0, 1)
        canvas = findViewById(R.id.canvas)
        canvas.listener = this
        canvas.dark = isDark
        loading = findViewById(R.id.loading)
        tabs = findViewById(R.id.tabs)
        tabs.callbacks = this

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.search).setOnClickListener { if (searchPanel.isOpen) closeSearch() else openSearch() }
        findViewById<View>(R.id.sheet).setOnClickListener { pickSheet(it) }
        findViewById<View>(R.id.beside).setOnClickListener { chooseNote(it, beside = true) }
        findViewById<ImageButton>(R.id.dark).apply {
            setImageResource(if (isDark) R.drawable.ic_light else R.drawable.ic_dark)
            contentDescription = getString(if (isDark) R.string.light_mode else R.string.dark_mode)
            setOnClickListener { toggleDark() }
        }
        findViewById<View>(R.id.fit).setOnClickListener { canvas.fitToContent() }
        findViewById<View>(R.id.export).setOnClickListener { v -> Popups.exportFormats(this, v, ::export) }
        findViewById<View>(R.id.fullscreen).setOnClickListener { setFullscreen(true) }
        listOf(R.id.search, R.id.sheet, R.id.beside, R.id.dark, R.id.fit, R.id.fullscreen, R.id.export).forEach {
            findViewById<View>(it).apply { tip(contentDescription) }
        }

        setupOverlays()
        applyColors()
        setFullscreen(prefs.fullscreen)

        Tabs.windows[slot]?.takeIf { it !== this && !it.isFinishing }?.finish() // una sola pantalla por ventana
        Tabs.windows[slot] = this
        // Al volver a crearse (por ejemplo, al cambiar de modo) sigue en la pestaña que mostraba.
        val again = savedInstanceState?.let { Tabs.lastShown(slot) }
        val id = again ?: intent.getStringExtra(EXTRA_ID) ?: Tabs.list(prefs, slot).firstOrNull() ?: return finish()
        if (savedInstanceState == null) pendingSearch = intent.getStringExtra(EXTRA_SEARCH)?.takeIf { it.isNotBlank() }
        loadTitles()
        openTab(id)
    }

    override fun onResume() {
        super.onResume()
        // Una nota pudo borrarse desde la lista.
        Tabs.prune(prefs) { store.file(it).exists() }
        loadTitles()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(closedCheck)
        meshes?.cancel()
        recorder.cancel()
        val n = note
        val h = history
        if (n != null && h != null && isChangingConfigurations) NoteCache.put(NoteCache.Entry(n, h, dirty, thumbnailStale), always = true)
        // Si ya hay otra pantalla en esta ventana, la nota que muestra es suya.
        if (Tabs.windows[slot] === this) {
            Tabs.setActive(slot, null)
            Tabs.windows[slot] = null
            // Se cerró la mitad de al lado: sus pestañas vuelven a la ventana principal.
            if (slot == 1 && isFinishing && !isChangingConfigurations) {
                Tabs.moveAll(prefs, 1, 0)
                Tabs.windows[0]?.tabsChanged()
            }
        }
    }

    // --- Cerrar una mitad ---

    /** Se abrió otra pantalla encima en esta mitad (el selector de fotos): no es que se cerró. */
    private var childOpen = false

    private val closedCheck = Runnable { mergeIntoOther() }

    override fun onStart() {
        super.onStart()
        childOpen = false
        handler.removeCallbacks(closedCheck)
    }

    override fun onStop() {
        super.onStop()
        // Si al rato esta mitad sigue sin verse pero la otra sí, es que se cerró esta mitad
        // (arrastrando la división hasta el borde, o poniendo otra app en su lugar). Con el botón de
        // inicio se dejan de ver las dos y no pasa nada.
        if (!isChangingConfigurations && !isFinishing && !childOpen) handler.postDelayed(closedCheck, CLOSED_CHECK_MS)
    }

    /** Las pestañas de esta mitad pasan a la que quedó a la vista, y esta pantalla se cierra. */
    private fun mergeIntoOther() {
        if (isFinishing || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val other = Tabs.windows[1 - slot] ?: return
        if (other === this || other.isFinishing || !other.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        leaveCurrent()
        Tabs.moveAll(prefs, slot, 1 - slot)
        other.tabsChanged()
        finish()
    }

    /** Abre otra pantalla (selector, compartir) sin que esta mitad crea que la cerraron. */
    private fun launching(action: () -> Unit) {
        childOpen = true
        action()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autosave)
        if (recorder.recording) recorder.stop()
        canvas.finishPending() // lo que se estaba moviendo vuelve a la nota antes de guardarla
        save(withThumbnail = true)
        // Después del guardado (va en el mismo hilo de disco): la copia en Drive, si hay cambios.
        CloudBackup.get(this).backup()
    }

    // --- Pestañas ---

    /** Muestra en esta ventana la nota `id`, en su pestaña (que se agrega si no estaba). */
    fun openTab(id: String) {
        if (id == note?.id || id == loadingId) return
        val other = Tabs.showing(id)
        if (other >= 0 && other != slot) {
            toast(getString(R.string.already_open))
            return
        }
        leaveCurrent()
        Tabs.add(prefs, slot, id)
        Tabs.setActive(slot, id)
        refreshTabs()
        NoteCache.take(id)?.let { e ->
            dirty = e.dirty
            thumbnailStale = e.thumbnailStale
            show(e.note, e.history)
            return
        }
        loadingId = id
        loading.visibility = View.VISIBLE
        store.io.execute {
            val clipFiles = Clipboard.clip?.let { c -> c.images.map { it.file } + c.widgets.flatMap { it.files } }.orEmpty()
            val result = runCatching { store.load(id).also { store.cleanMedia(it, clipFiles) } }
            runOnUiThread {
                if (isDestroyed || loadingId != id) return@runOnUiThread
                loadingId = null
                loading.visibility = View.GONE
                result.onSuccess {
                    dirty = false
                    thumbnailStale = false
                    show(it, History())
                }.onFailure {
                    toast("No se pudo abrir la nota: ${it.message}", long = true)
                    Tabs.remove(prefs, slot, id)
                    Tabs.setActive(slot, null)
                    val next = Tabs.list(prefs, slot).firstOrNull()
                    if (next == null) finish() else openTab(next)
                }
            }
        }
    }

    /** Guarda la nota que se ve y la deja en memoria para volver rápido a su pestaña. */
    private fun leaveCurrent() {
        val n = note ?: return
        if (canvas.zoomOn) setZoom(false)
        canvas.clearCapture()
        closeSearch()
        searchLines = null
        if (recorder.recording) recorder.stop()
        player.close()
        canvas.finishPending()
        canvas.clearSelection()
        handler.removeCallbacks(autosave)
        save(withThumbnail = true)
        NoteCache.put(NoteCache.Entry(n, history ?: History(), dirty = false, thumbnailStale = false), always = false)
        meshes?.cancel()
        meshes = null
        Tabs.setActive(slot, null)
        note = null
        history = null
        canvas.strokes = mutableListOf()
        canvas.images = mutableListOf()
        canvas.texts = mutableListOf()
        canvas.widgets = mutableListOf()
    }

    private fun closeTab(id: String) {
        val list = Tabs.list(prefs, slot)
        if (id == note?.id) {
            val i = list.indexOf(id)
            val next = list.getOrNull(i + 1) ?: list.getOrNull(i - 1)
            leaveCurrent()
            Tabs.remove(prefs, slot, id)
            if (next == null) finish() else openTab(next)
        } else {
            Tabs.remove(prefs, slot, id)
            refreshTabs()
        }
    }

    /** La otra ventana se quedó con una de nuestras pestañas. */
    fun tabsChanged() = refreshTabs()

    private fun refreshTabs() {
        val current = note
        val list = Tabs.list(prefs, slot).map { id -> id to ((if (id == current?.id) current.title else null) ?: titles[id] ?: "…") }
        tabs.setTabs(list, current?.id ?: loadingId)
    }

    private fun loadTitles() {
        store.io.execute {
            val all = store.list()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                for (info in all) titles[info.id] = info.title
                refreshTabs()
            }
        }
    }

    override fun onTabClick(id: String) {
        if (id == note?.id) rename() else openTab(id)
    }

    override fun onTabClose(id: String) = closeTab(id)

    override fun onTabMenu(id: String, anchor: View) {
        PopupMenu(this, anchor).apply {
            if (id == note?.id) menu.add(R.string.rename).setOnMenuItemClickListener { rename(); true }
            menu.add(R.string.move_beside).setOnMenuItemClickListener {
                closeTab(id)
                openBeside(this@EditorActivity, id, 1 - slot)
                true
            }
            menu.add(R.string.close_tab).setOnMenuItemClickListener { closeTab(id); true }
            show()
        }
    }

    override fun onAddTab(anchor: View) = chooseNote(anchor, beside = false)

    /** Elige una nota (o una nueva) para abrirla en una pestaña o en la otra mitad. */
    private fun chooseNote(anchor: View, beside: Boolean) {
        val here = Tabs.list(prefs, slot).toSet()
        store.io.execute {
            val all = store.list()
            val folders = store.library.folders().associate { it.id to it.name }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                // Las que ya se están mostrando en alguna mitad no se ofrecen.
                val others = all.filter { Tabs.showing(it.id) < 0 && (beside || it.id !in here) }
                val names = listOf(getString(R.string.new_note)) +
                    others.map { info -> folders[info.folder]?.let { "${info.title}  ·  $it" } ?: info.title }
                AlertDialog.Builder(this)
                    .setTitle(if (beside) R.string.open_beside else R.string.open_tab)
                    .setItems(names.toTypedArray()) { _, which ->
                        val folder = note?.id?.let { store.library.folderOf(it) }
                        val id = if (which == 0) createNote(store, prefs, folder).id else others[which - 1].id
                        if (beside) openBeside(this, id, 1 - slot) else openTab(id)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun show(n: Note, h: History) {
        note = n
        history = h
        titles[n.id] = n.title
        canvas.mediaDir = store.mediaDir(n.id)
        canvas.paperStyle = n.background
        canvas.paperColor = n.paperColor
        canvas.pages = n.pages
        canvas.restoreViewport(n.viewport)
        canvas.strokes = n.strokes
        canvas.images = n.images
        canvas.texts = n.texts
        canvas.widgets = n.widgets
        updateUndo()
        refreshTabs()
        pageThumbs.clear()
        updatePageStrip(snapshot = true)
        // Una nota con hojas recién creada empieza con la primera hoja al ancho de la pantalla.
        if (n.pages.paged && n.viewport.x == 0f && n.viewport.y == 0f && n.strokes.isEmpty() && n.images.isEmpty()) {
            canvas.post { canvas.fitToContent() }
        }
        // Las mallas se arman de a tandas, empezando por el centro de la pantalla.
        val metrics = resources.displayMetrics
        val w = if (canvas.width > 0) canvas.width else metrics.widthPixels
        val h2 = if (canvas.height > 0) canvas.height else metrics.heightPixels
        meshes = MeshBuilder(
            n.strokes, canvas.camera.toWorldX(w / 2f), canvas.camera.toWorldY(h2 / 2f),
            onChunk = { canvas.post { canvas.meshesChanged() } },
        ).also { it.start() }
        pendingSearch?.let { q ->
            pendingSearch = null
            canvas.post { openSearch(q) }
        }
    }

    /**
     * El botón lateral del lápiz: un clic vuelve a la herramienta anterior. Puede llegar en los
     * eventos del lápiz (lo ve el lienzo), como tecla del lápiz, o, en el Lenovo Tab Pen Plus, como
     * tecla de su "control multimedia" por Bluetooth.
     */
    @SuppressLint("InlinedApi")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isPenButtonKey(keyCode, event)) {
            if (event.repeatCount == 0) Diag.log("tecla del botón: $event de ${event.device?.name}")
            return true
        }
        if (event.device?.supportsSource(InputDevice.SOURCE_STYLUS) == true) Diag.log("tecla del lápiz: $event")
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isPenButtonKey(keyCode, event)) {
            if (event.eventTime - event.downTime < BUTTON_CLICK_MS) swapTool()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isPenButtonKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in STYLUS_KEYS) return true
        val d = event.device ?: return false
        // Las teclas que manda el lápiz por Bluetooth (no las del volumen ni las de un teclado).
        return d.name.contains("pen", ignoreCase = true) && !d.supportsSource(InputDevice.SOURCE_STYLUS) &&
            keyCode != KeyEvent.KEYCODE_BACK
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Con dos notas en paralelo, lo que se cambió en la otra (modo, lápices) vale acá también.
        if (prefs.dark != isDark || prefs.look != lookShown) {
            handler.removeCallbacks(autosave)
            canvas.finishPending()
            save(withThumbnail = false)
            recreate()
            return
        }
        val saved = prefs.pens
        if (saved != pens) {
            pens = saved
            selected = selected.coerceIn(0, pens.lastIndex)
            applyTool()
        }
        canvas.lassoFilter = prefs.lassoFilter
        canvas.holdToErase = prefs.holdToErase
        canvas.holdForShapes = prefs.holdForShapes
    }

    // --- Lienzo ---

    override fun onStrokesAdded(strokes: List<InkStroke>) {
        val n = note ?: return
        history?.add(n.strokes, strokes) ?: return
        changed(redraw = false)
    }

    override fun onStrokesErased(removed: List<Pair<Int, InkStroke>>) {
        val n = note ?: return
        history?.recordRemoved(n.strokes, removed) ?: return
        changed(redraw = false)
    }

    override fun onEdited(edit: History.Edit) {
        history?.record(edit) ?: return
        changed(redraw = false)
    }

    override fun onViewportChanged() {
        viewportChanged = true
        if (note?.pages?.paged == true) pageStrip.setCurrent(canvas.currentPage())
    }

    override fun onSelectionChanged() {
        val shown = canvas.selectionScreenBounds(selectionRect)
        val editAs = when {
            canvas.selectedWidget is Media -> SelectionBar.Edit.PLAY
            canvas.selectedWidget != null -> SelectionBar.Edit.WIDGET
            canvas.selectedImage != null -> SelectionBar.Edit.CROP
            else -> null
        }
        selectionBar.showFor(if (shown && !canvas.busy) selectionRect else null, canvas.selectionHasInk, editAs)
        if (captureArea != null && canvas.captureOnScreen(captureScreen)) captureBar.showFor(captureScreen)
    }

    /** Clic del botón lateral: vuelve a la herramienta de antes (y otro clic, a esta). */
    override fun onStylusButtonClick() = swapTool()

    /** Una herramienta de la barra: el modo y, con la pluma, cuál de los lápices. */
    private data class ToolChoice(val mode: Mode, val pen: Int) {
        /** Para comparar: con el borrador o el lazo no importa qué lápiz estaba elegido. */
        val key get() = if (mode == Mode.PEN) this else copy(pen = -1)
    }

    private var currentTool: ToolChoice? = null
    private var previousTool: ToolChoice? = null

    private fun swapTool() {
        val p = previousTool
        if (p == null) {
            hint(getString(R.string.no_previous_tool))
            return
        }
        mode = p.mode
        selected = p.pen.coerceIn(0, pens.lastIndex)
        applyTool()
    }

    override fun onMediaTap(media: Media) = play(media)

    override fun onPagesChanged(pages: PageLayout) {
        note?.pages = pages
        changed(redraw = false)
    }

    /** `redraw`: la lista cambió desde acá (deshacer, borrar todo) y el lienzo no lo sabe. */
    private fun changed(redraw: Boolean) {
        val n = note ?: return
        n.modified = System.currentTimeMillis()
        dirty = true
        thumbnailStale = true
        if (redraw) canvas.refresh()
        updateUndo()
        handler.removeCallbacks(autosave)
        handler.postDelayed(autosave, AUTOSAVE_MS)
        handler.removeCallbacks(thumbsUpdate)
        handler.postDelayed(thumbsUpdate, THUMBS_DELAY_MS)
    }

    // --- Buscar en lo escrito ---

    private fun openSearch(query: String? = null) {
        val n = note ?: return
        val box = findViewById<View>(R.id.canvas_box)
        searchPanel.layoutParams = (searchPanel.layoutParams as FrameLayout.LayoutParams).apply {
            width = min(dp(SearchPanel.WIDTH_DP).toInt(), box.width - dp(16f).toInt()).coerceAtLeast(dp(220f).toInt())
        }
        searchPanel.setMaxListHeight((box.height * 0.5f).toInt())
        searchPanel.open(query)
        if (searchLines == null || searchLinesAt != n.modified) readHandwriting()
    }

    private fun closeSearch() {
        if (!searchPanel.isOpen) return
        searchPanel.close()
        canvas.clearFound()
        searchHits = emptyList()
        if (searchReading) HandText.get(this).cancel()
        searchReading = false
    }

    /** Lee la letra de la nota (lo que cambió desde la última vez) y después busca. */
    private fun readHandwriting() {
        val n = note ?: return
        val at = n.modified
        searchReading = true
        searchPanel.setStatus("Leyendo la letra…")
        HandText.get(this).read(n.id, at, n.strokes.toList(), n.texts.toList(), progress = { text ->
            if (note === n && searchPanel.isOpen) searchPanel.setStatus(text)
        }) { lines, error ->
            if (note !== n) return@read
            searchReading = false
            if (lines != null) {
                searchLines = lines
                searchLinesAt = at
            }
            if (error != null) searchPanel.setStatus(error) else runSearch()
        }
    }

    override fun onSearch(query: String) {
        val n = note ?: return
        // Se escribió algo desde la última lectura: se vuelve a leer (sólo lo nuevo) y se busca.
        if (!searchReading && (searchLines == null || searchLinesAt != n.modified)) readHandwriting() else runSearch()
    }

    private fun runSearch() {
        val n = note ?: return
        val shown = searchPanel.query
        val q = Find.normalize(shown)
        val lines = searchLines
        if (q.isEmpty() || lines == null) {
            searchHits = emptyList()
            searchPanel.setResults(emptyList())
            canvas.clearFound()
            if (!searchReading) {
                searchPanel.setStatus(
                    when {
                        lines == null -> null
                        lines.isEmpty() -> "No hay nada escrito para buscar."
                        else -> "Se puede buscar en ${count(lines.size, "renglón", "renglones")}."
                    },
                )
            }
            return
        }
        val hits = ArrayList<Pair<HandText.Line, HandText.Match>>()
        for (line in lines) line.match(q)?.let { hits.add(line to it) }
        // Primero lo que está tal cual; después lo parecido (la letra reconocida tiene errores).
        hits.sortBy { it.second.errors }
        searchHits = hits.map { it.first }
        searchPanel.setResults(hits.map { (line, m) ->
            SearchPanel.Result(if (n.pages.paged) "Hoja ${n.pages.pageAt((line.top + line.bottom) / 2).coerceAtLeast(0) + 1}" else "", m.text)
        })
        searchPanel.setStatus(
            (if (hits.isEmpty()) "No aparece «$shown»." else "Aparece en ${count(hits.size, "renglón", "renglones")}.") +
                if (searchReading) " Leyendo lo último…" else "",
        )
        canvas.showFound(searchHits.map { RectF(it.left, it.top, it.right, it.bottom) }, -1)
        if (hits.isNotEmpty()) onPick(0)
    }

    override fun onPick(k: Int) {
        if (k !in searchHits.indices) return
        canvas.showFound(searchHits.map { RectF(it.left, it.top, it.right, it.bottom) }, k)
        searchPanel.setCurrent(k)
    }

    override fun onSearchClose() = closeSearch()

    private fun count(k: Int, one: String, many: String) = if (k == 1) "1 $one" else "$k $many"

    // --- Miniaturas de las hojas ---

    /** Muestra la tira si la nota tiene hojas; con `snapshot`, le pasa una copia nueva de la nota. */
    private fun updatePageStrip(snapshot: Boolean) {
        val n = note
        val paged = n != null && n.pages.paged
        pageStrip.visibility = if (paged) View.VISIBLE else View.GONE
        if (!paged || n == null) return
        pageStrip.setSide(left = dock.right)
        pageStrip.setPages(canvas.pageCount, n.pages.height / n.pages.width)
        if (snapshot) {
            pageThumbs.update(
                PageThumbs.Snapshot(n.strokes.toList(), n.images.toList(), n.texts.toList(), n.widgets.toList(), n.background, n.pages, n.paperColor),
            )
            pageStrip.refresh()
        }
        pageStrip.setCurrent(canvas.currentPage())
    }

    // --- Lupa ---

    private fun setZoom(on: Boolean) {
        if (on && (mode == Mode.LASSO || mode == Mode.LASER)) {
            mode = Mode.PEN
            applyTool()
        }
        canvas.setZoom(on)
        dock.setLabel(ToolDock.Tool.ZOOM, null, highlighted = on)
    }

    override fun onZoomChanged() {
        val r = RectF()
        zoomBar.showFor(if (canvas.zoomPanelRect(r)) r else null)
    }

    override fun onZoomBack() = canvas.zoomMove(-0.5f)
    override fun onZoomForward() = canvas.zoomMove(0.5f)
    override fun onZoomNewLine() = canvas.zoomNewLine()
    override fun onZoomBigger() = canvas.zoomResize(1 / 1.25f)
    override fun onZoomSmaller() = canvas.zoomResize(1.25f)
    override fun onZoomClose() = setZoom(false)

    override fun onPageCountChanged(count: Int) {
        val n = note ?: return
        if (n.pages.paged) pageStrip.setPages(count, n.pages.height / n.pages.width)
    }

    override fun onPageClick(k: Int) = canvas.scrollToPage(k)

    override fun onPageMove(from: Int, to: Int) {
        loading.visibility = View.VISIBLE
        canvas.movePage(from, to) { loading.visibility = View.GONE }
    }

    override fun onAddPage() = canvas.addPageAtEnd()

    override fun onStripCollapsed(collapsed: Boolean) {
        prefs.pageStripCollapsed = collapsed
    }

    private fun save(withThumbnail: Boolean) {
        val n = note ?: return
        if (canvas.busy) {
            // Lo elegido está fuera de la nota mientras se mueve: se guarda cuando vuelva.
            handler.removeCallbacks(autosave)
            handler.postDelayed(autosave, AUTOSAVE_MS)
            return
        }
        val thumbnail = withThumbnail && (thumbnailStale || !store.thumbnail(n.id).exists())
        if (!dirty && !viewportChanged && !thumbnail) return
        n.viewport = Viewport(
            canvas.camera.toWorldX(0f), canvas.camera.toWorldY(0f), canvas.camera.zoom,
            canvas.width.toFloat(), canvas.height.toFloat(),
        )
        dirty = false
        viewportChanged = false
        if (thumbnail) thumbnailStale = false
        store.save(n, thumbnail) { e ->
            runOnUiThread { toast("No se pudo guardar: ${e.message}", long = true) }
        }
        // Al salir de la nota, su letra se pone al día para la búsqueda (sólo lo que cambió).
        if (withThumbnail && n.modified != searchLinesAt) HandText.get(this).update(n.id, n.modified, n.strokes.toList(), n.texts.toList())
        // Escribiendo mucho rato seguido, de vez en cuando también se sube la copia.
        CloudBackup.get(this).backup(minIntervalMs = CloudBackup.WHILE_WRITING_MS)
    }

    private fun updateUndo() {
        val h = history
        toolbar.setUndoState(h?.canUndo == true, h?.canRedo == true)
    }

    // --- Barras ---

    private fun setupOverlays() {
        val box = findViewById<FrameLayout>(R.id.canvas_box)
        fun wrap() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        dock = ToolDock(this)
        dock.callbacks = this
        box.addView(dock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        dock.setOrder(prefs.dockOrder)
        dock.setState(prefs.dockRight, prefs.dockCollapsed)
        toolbar = FloatingToolbar(this, isDark)
        toolbar.callbacks = this
        toolbar.vertical = prefs.toolbarVertical
        box.addView(toolbar, wrap())
        // Sólo si cambió el tamaño (pantalla dividida, girar): el listener también salta con cada
        // cambio de adentro (el reloj, cada segundo), y si no la barra volvía a donde estaba
        // mientras se la arrastraba.
        box.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) toolbar.placeAt(prefs.toolbarX, prefs.toolbarY)
        }
        selectionBar = SelectionBar(this)
        selectionBar.callbacks = this
        box.addView(selectionBar, wrap())
        recorder = RecorderBar(this)
        recorder.onDone = ::addRecording
        box.addView(recorder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12f).toInt()
        })
        player = AudioPlayerBar(this)
        box.addView(player, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12f).toInt()
        })
        pageThumbs = PageThumbs(dp(96f).toInt())
        pageStrip = PageStrip(this, pageThumbs)
        pageStrip.callbacks = this
        box.addView(pageStrip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START).apply {
            setMargins(0, dp(10f).toInt(), 0, dp(10f).toInt())
        })
        pageStrip.setCollapsed(prefs.pageStripCollapsed)
        pageStrip.visibility = View.GONE
        zoomBar = ZoomBar(this)
        zoomBar.callbacks = this
        box.addView(zoomBar, wrap())
        captureBar = CaptureBar(this)
        captureBar.callbacks = this
        box.addView(captureBar, wrap())
        searchPanel = SearchPanel(this)
        searchPanel.callbacks = this
        box.addView(searchPanel, FrameLayout.LayoutParams(dp(SearchPanel.WIDTH_DP).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(8f).toInt(), dp(8f).toInt(), 0)
        })
        timer = TimerPanel(this)
        timer.visibility = if (prefs.timerVisible) View.VISIBLE else View.GONE
        timer.onHide = { showTimer(false) }
        timer.onMoved = { fx, fy ->
            prefs.timerX = fx
            prefs.timerY = fy
        }
        timer.onTick = { text ->
            // Escondido, el tiempo se ve en la barra lateral mientras corre o si ya terminó.
            val show = timer.visibility != View.VISIBLE && (TimerState.running || TimerState.finished)
            dock.setLabel(ToolDock.Tool.TIMER, if (show) text else null, highlighted = show)
        }
        timer.onFinished = {
            showTimer(true)
            toast(getString(R.string.timer_done))
        }
        box.addView(timer, wrap())
        box.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) timer.placeAt(prefs.timerX, prefs.timerY)
        }
        exitFullscreen = ImageButton(this).apply {
            setImageResource(R.drawable.ic_fullscreen_exit)
            background = getDrawable(R.drawable.bg_toolbar)
            elevation = dp(4f)
            alpha = 0.75f
            contentDescription = getString(R.string.exit_fullscreen)
            tip(contentDescription)
            setOnClickListener { setFullscreen(false) }
            visibility = View.GONE
        }
        box.addView(exitFullscreen, FrameLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt(), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(10f).toInt(), dp(10f).toInt(), 0, 0)
        })
        pens = prefs.pens
        selected = prefs.selectedPen.coerceIn(0, pens.lastIndex)
        mode = prefs.mode.takeIf { it != Mode.LASER && it != Mode.CAPTURE } ?: Mode.PEN
        canvas.eraserRadius = prefs.eraserRadius
        canvas.partialErase = prefs.eraserPartial
        canvas.lassoFilter = prefs.lassoFilter
        canvas.holdToErase = prefs.holdToErase
        canvas.holdForShapes = prefs.holdForShapes
        applyTool()
    }

    private fun applyTool() {
        val now = ToolChoice(mode, selected)
        if (currentTool?.key != now.key) {
            if (currentTool != null) previousTool = currentTool
            currentTool = now
        } else {
            currentTool = now
        }
        canvas.pen = pens[selected]
        canvas.mode = mode
        toolbar.setPens(pens, selected, mode)
        dock.setActive(if (mode == Mode.LASER) ToolDock.Tool.LASER else null)
        prefs.selectedPen = selected
        prefs.mode = mode
    }

    override fun onPenClick(index: Int, button: View) {
        if (index == selected && mode == Mode.PEN) {
            editPen(index, button) // segundo toque: opciones del lápiz
            return
        }
        selected = index
        mode = Mode.PEN
        applyTool()
    }

    override fun onPenLongClick(index: Int, button: View) {
        Popups.penQuick(
            this, button, canDelete = pens.size > 1,
            onOptions = {
                selected = index
                mode = Mode.PEN
                applyTool()
                toolbar.post { toolbar.penButton(index)?.let { editPen(index, it) } }
            },
            onDelete = { deletePen(index) },
        )
    }

    /** Cambiar un lápiz de lugar en la barra; el elegido sigue elegido. */
    override fun onPenMoved(from: Int, to: Int) {
        if (from !in pens.indices || to !in pens.indices || from == to) return
        pens = pens.toMutableList().also { it.add(to, it.removeAt(from)) }
        fun follow(i: Int) = when {
            i == from -> to
            from < to && i in from + 1..to -> i - 1
            from > to && i in to until from -> i + 1
            else -> i
        }
        selected = follow(selected)
        currentTool = currentTool?.let { if (it.mode == Mode.PEN) it.copy(pen = follow(it.pen)) else it }
        previousTool = previousTool?.let { if (it.mode == Mode.PEN) it.copy(pen = follow(it.pen)) else it }
        prefs.pens = pens
        applyTool()
    }

    private fun deletePen(index: Int) {
        if (pens.size <= 1 || index !in pens.indices) return
        pens = pens.toMutableList().also { it.removeAt(index) }
        selected = when {
            selected > index -> selected - 1
            else -> selected.coerceAtMost(pens.lastIndex)
        }
        prefs.pens = pens
        applyTool()
    }

    /** Mantener apretada la manija de la barra: borrar todos los lápices o volver a los de siempre. */
    override fun onGripLongPress(grip: View) {
        PopupMenu(this, grip).apply {
            menu.add(R.string.delete_all_pens).setOnMenuItemClickListener {
                confirm(getString(R.string.delete_all_pens_confirm), getString(R.string.delete)) {
                    // Queda una pluma negra para poder seguir escribiendo; con + se agregan más.
                    pens = listOf(Pen.DEFAULTS[0])
                    selected = 0
                    mode = Mode.PEN
                    prefs.pens = pens
                    applyTool()
                }
                true
            }
            menu.add(R.string.default_pens).setOnMenuItemClickListener {
                pens = Pen.DEFAULTS
                selected = 0
                mode = Mode.PEN
                prefs.pens = pens
                applyTool()
                true
            }
            show()
        }
    }

    override fun onAddPen(button: View) {
        pens = pens + pens[selected]
        selected = pens.lastIndex
        mode = Mode.PEN
        prefs.pens = pens
        applyTool()
        toolbar.post { toolbar.penButton(selected)?.let { editPen(selected, it) } }
    }

    private fun editPen(index: Int, anchor: View) {
        Popups.pen(
            this, anchor, pens[index], isDark,
            savedColors = { prefs.savedColors },
            onSaveColor = { c -> prefs.savedColors = (listOf(c) + prefs.savedColors).distinct().take(MAX_SAVED_COLORS) },
            onForgetColor = { c ->
                prefs.savedColors = prefs.savedColors - c
                hint("Color quitado de la paleta")
            },
            canDelete = pens.size > 1,
            holdForShapes = canvas.holdForShapes,
            onHoldForShapes = {
                canvas.holdForShapes = it
                prefs.holdForShapes = it
            },
            onChange = { pen ->
                pens = pens.toMutableList().also { it[index] = pen }
                prefs.pens = pens
                applyTool()
            },
            onDelete = { deletePen(index) },
        )
    }

    override fun onEraserClick(button: View) {
        if (mode == Mode.ERASER) {
            Popups.eraser(
                this, button, canvas.eraserRadius, canvas.holdToErase, canvas.partialErase,
                onRadius = {
                    canvas.eraserRadius = it
                    prefs.eraserRadius = it
                },
                onHoldToErase = {
                    canvas.holdToErase = it
                    prefs.holdToErase = it
                },
                onPartial = {
                    canvas.partialErase = it
                    prefs.eraserPartial = it
                },
                onClearAll = { confirm(getString(R.string.clear_all), getString(R.string.delete)) { clearAll() } },
            )
            return
        }
        mode = Mode.ERASER
        applyTool()
    }

    override fun onLassoClick(button: View) {
        if (mode == Mode.LASSO) {
            Popups.lasso(
                this, button, canvas.lassoFilter, canPaste = Clipboard.clip != null,
                onFilter = {
                    canvas.lassoFilter = it
                    prefs.lassoFilter = it
                },
                onPaste = { Clipboard.clip?.let(::paste) },
            )
            return
        }
        mode = Mode.LASSO
        applyTool()
    }

    // --- Colores de Configuración ---

    /** Los colores elegidos para la barra de arriba, las barras de herramientas y la pestaña. */
    private fun applyColors() {
        prefs.topBarColor?.let { c ->
            Chrome.paint(findViewById(R.id.topbar), c)
            // Arriba, detrás de la barra del sistema, el mismo color (abajo queda el de siempre).
            val root = findViewById<View>(R.id.root)
            val rest = getColor(R.color.chrome)
            root.background = object : android.graphics.drawable.ColorDrawable(rest) {
                private val top = android.graphics.Paint().apply { color = c }
                override fun draw(canvas: android.graphics.Canvas) {
                    super.draw(canvas)
                    canvas.drawRect(0f, 0f, bounds.width().toFloat(), root.paddingTop.toFloat(), top)
                }
            }
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !Chrome.isDark(c)
        }
        tabs.barColor = prefs.topBarColor
        tabs.activeColor = prefs.tabColor
        prefs.toolsColor?.let { c ->
            Chrome.paint(toolbar, c)
            dock.paint(c)
            pageStrip.paint(c)
            for (bar in listOf(selectionBar, captureBar, zoomBar, timer, searchPanel)) Chrome.paint(bar, c)
            timer.textColor = Chrome.ink(c)
            searchPanel.barColor = c
        }
    }

    // --- Captura ---

    override fun onCaptureClick(button: View) {
        if (mode == Mode.CAPTURE) {
            // Segundo toque: capturar todo lo que se ve, sin marcar.
            PopupMenu(this, button).apply {
                menu.add(getString(R.string.capture_visible)).setOnMenuItemClickListener {
                    canvas.captureVisible()
                    true
                }
                show()
            }
            return
        }
        mode = Mode.CAPTURE
        applyTool()
        if (!prefs.captureHintShown) {
            prefs.captureHintShown = true
            hint(getString(R.string.capture_hint), long = true)
        }
    }

    override fun onCaptureArea(world: RectF) {
        captureArea = RectF(world)
        if (canvas.captureOnScreen(captureScreen)) captureBar.showFor(captureScreen)
    }

    override fun onCaptureCleared() {
        captureArea = null
        captureBar.showFor(null)
    }

    override fun onCaptureClose() = canvas.clearCapture()

    override fun onCaptureCopy() = withCapture { bytes, area ->
        // Para pegarla en una nota: como una imagen del mismo tamaño que lo capturado.
        val file = NoteStore.keep(File(filesDir, "portapapeles").apply { mkdirs() }, bytes)
        Clipboard.clip = Clipboard.Clip(
            emptyList(), listOf(NoteImage(area.left, area.top, area.width(), area.height(), 0f, file)), emptyList(), emptyList(), RectF(area),
        )
        // Y para pegarla en otra app.
        val uri = FileProvider.getUriForFile(this, "$packageName.files", exportFile(bytes))
        runOnUiThread {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newUri(contentResolver, getString(R.string.capture), uri))
            hint(getString(R.string.capture_copied), long = true)
            canvas.clearCapture()
        }
    }

    override fun onCaptureSave() = withCapture { bytes, _ ->
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, captureName() + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Tinta")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no se pudo crear la imagen")
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no se pudo escribir la imagen")
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        runOnUiThread {
            hint(getString(R.string.capture_saved))
            canvas.clearCapture()
        }
    }

    override fun onCaptureShare() = withCapture { bytes, _ ->
        val uri = FileProvider.getUriForFile(this, "$packageName.files", exportFile(bytes))
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runOnUiThread {
            launching { startActivity(Intent.createChooser(send, getString(R.string.capture))) }
            canvas.clearCapture()
        }
    }

    /**
     * Arma la imagen de lo capturado (en otro hilo, con una copia de la nota) y se la pasa a
     * `action`, que también corre en ese hilo. Se dibuja la nota misma, no la pantalla: sale
     * nítida, en colores claros y sin los botones encima.
     */
    private fun withCapture(action: (ByteArray, RectF) -> Unit) {
        val n = note ?: return
        val area = captureArea?.let(::RectF) ?: return
        canvas.finishPending()
        val strokes = n.strokes.toList()
        val images = n.images.toList()
        val texts = n.texts.toList()
        val widgets = n.widgets.toList()
        val background = n.background
        val pages = n.pages
        val pageCount = canvas.pageCount
        val paperColor = n.paperColor
        // Al menos 3 px por dp, o lo de la pantalla si está más cerca, sin pasar de 4096 px de
        // lado ni de 12 Mpx.
        val wanted = maxOf(CAPTURE_PX_PER_DP, canvas.camera.scale)
        val scale = min(wanted, min(CAPTURE_MAX_SIDE / maxOf(area.width(), area.height()), kotlin.math.sqrt(CAPTURE_MAX_PIXELS / (area.width() * area.height()))))
        hint(getString(R.string.capture_working))
        Thread({
            val result = runCatching {
                val bitmap = Render.area(strokes, images, texts, widgets, background, pages, pageCount, area, scale, paperColor)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                action(out.toByteArray(), area)
            }
            result.onFailure { e -> runOnUiThread { toast("No se pudo hacer la captura: ${e.message}", long = true) } }
        }, "tinta-captura").start()
    }

    /** La captura como archivo para compartir o copiar a otra app (uno nuevo cada vez). */
    private fun exportFile(bytes: ByteArray): File {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        return File(dir, captureName() + ".png").apply { writeBytes(bytes) }
    }

    private fun captureName(): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
        return Render.fileName("${note?.title ?: "Tinta"} $time")
    }

    override fun onUndo() {
        undoRedo { it.undo() }
    }

    override fun onRedo() {
        undoRedo { it.redo() }
    }

    private fun undoRedo(step: (History) -> History.Edit?) {
        val n = note ?: return
        val h = history ?: return
        canvas.clearSelection()
        val edit = step(h) ?: return
        if (edit.affects(n.images) || edit.affects(n.texts) || edit.affects(n.widgets)) canvas.elementsChanged()
        changed(redraw = true)
    }

    override fun onMoved(fx: Float, fy: Float) {
        prefs.toolbarX = fx
        prefs.toolbarY = fy
    }

    override fun onOrientationChanged(vertical: Boolean) {
        prefs.toolbarVertical = vertical
    }

    // --- Barra lateral ---

    override fun onDockTool(tool: ToolDock.Tool, button: View) {
        if (note == null) return
        when (tool) {
            ToolDock.Tool.PAGES -> pagesMenu(button)
            ToolDock.Tool.ZOOM -> setZoom(!canvas.zoomOn)
            ToolDock.Tool.IMAGE -> launching { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            ToolDock.Tool.GRAPH2D -> {
                val start = Graph2D(0f, 0f, 0f, 0f, listOf("x^2"), -5f, 5f, -5f, 5f, autoY = true, grid = true, color = widgetColor())
                WidgetEditors.graph2d(this, start, isDark, isNew = true) { insertWidget(it, 600f, 420f) }
            }
            ToolDock.Tool.GRAPH3D -> {
                val start = Graph3D(0f, 0f, 0f, 0f, "sen(x)*cos(y)", -3f, 3f, -3f, 3f, 35f, 25f, widgetColor())
                WidgetEditors.graph3d(this, start, isDark, isNew = true) { insertWidget(it, 540f, 480f) }
            }
            ToolDock.Tool.SOLID -> {
                val start = Solid(0f, 0f, 0f, 0f, SolidKind.CUBE, 1f, 1f, 1f, 30f, 20f, widgetColor(), hiddenEdges = true, shaded = true)
                WidgetEditors.solid(this, start, isDark, isNew = true) { insertWidget(it, 380f, 380f) }
            }
            ToolDock.Tool.AUDIO -> {
                if (recorder.recording) {
                    recorder.stop()
                } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    launching { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }
                }
            }
            ToolDock.Tool.VIDEO -> launching { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            ToolDock.Tool.LASER -> {
                if (mode == Mode.LASER) {
                    mode = previousMode
                } else {
                    previousMode = mode
                    mode = Mode.LASER
                }
                applyTool()
            }
            ToolDock.Tool.STICKERS -> showStickers(button)
            ToolDock.Tool.TIMER -> showTimer(timer.visibility != View.VISIBLE)
        }
    }

    private fun showTimer(show: Boolean) {
        timer.visibility = if (show) View.VISIBLE else View.GONE
        prefs.timerVisible = show
        if (show) timer.post { timer.placeAt(prefs.timerX, prefs.timerY) }
        val label = !show && (TimerState.running || TimerState.finished)
        dock.setLabel(ToolDock.Tool.TIMER, if (label) TimerState.text() else null, highlighted = label)
    }

    /**
     * Pantalla completa: se esconden las barras del sistema (vuelven un momento deslizando desde el
     * borde) y la barra de arriba; queda un botoncito para salir.
     */
    private fun setFullscreen(on: Boolean) {
        prefs.fullscreen = on
        findViewById<View>(R.id.topbar).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.topbar_divider).visibility = if (on) View.GONE else View.VISIBLE
        exitFullscreen.visibility = if (on) View.VISIBLE else View.GONE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        // Que el lugar de las barras del sistema se saque (o vuelva) ya, sin esperar al sistema; y
        // otra vez un rato después, cuando la tablet terminó de esconder las barras.
        val root = findViewById<View>(R.id.root)
        if (on) {
            root.setPadding(0, 0, 0, 0)
            clearAncestorPadding(root)
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        root.postDelayed({
            if (prefs.fullscreen == on) {
                if (on) clearAncestorPadding(root)
                androidx.core.view.ViewCompat.requestApplyInsets(root)
                val loc = IntArray(2).also { root.getLocationOnScreen(it) }
                Diag.log("pantalla completa=$on: raíz en y=${loc[1]}, relleno arriba=${root.paddingTop}")
            }
        }, FULLSCREEN_SETTLE_MS)
    }

    override fun onDockOrder(order: List<ToolDock.Tool>) {
        prefs.dockOrder = order
    }

    override fun onDockChanged(right: Boolean, collapsed: Boolean) {
        prefs.dockRight = right
        prefs.dockCollapsed = collapsed
        // La tira va del otro lado. Al abrir, la barra se pone como quedó antes de que exista la
        // tira (y la tira después toma el lado en updatePageStrip).
        if (::pageStrip.isInitialized) pageStrip.setSide(left = right)
    }

    /** Agregar, insertar o borrar hojas. En una nota infinita, primero hay que elegir un tamaño. */
    private fun pagesMenu(anchor: View) {
        if (!canvas.pages.paged) {
            hint(getString(R.string.pages_need_size), long = true)
            pickSheet(findViewById(R.id.sheet))
            return
        }
        val k = canvas.currentPage()
        fun shift(at: Int, delete: Boolean) {
            loading.visibility = View.VISIBLE
            canvas.shiftPages(at, delete) { loading.visibility = View.GONE }
        }
        Popups.pages(
            this, anchor, k, canvas.pageCount,
            stripShown = !pageStrip.collapsed,
            onToggleStrip = {
                pageStrip.setCollapsed(!pageStrip.collapsed)
                prefs.pageStripCollapsed = pageStrip.collapsed
            },
            onAddAtEnd = { canvas.addPageAtEnd() },
            onInsert = { after -> shift(if (after) k + 1 else k, delete = false) },
            onDelete = {
                confirm(getString(R.string.delete_page, k + 1), getString(R.string.delete)) { shift(k, delete = true) }
            },
        )
    }

    /** Los gráficos y figuras nuevos salen del color del lápiz elegido (si no es un resaltador). */
    private fun widgetColor() = pens[selected].takeIf { it.tool != Tool.HIGHLIGHTER }?.color ?: Tool.INK_COLORS[1]

    /** Pone el widget en el centro de lo que se ve, de `w`×`h` dp de pantalla (o menos, si no entra). */
    private fun insertWidget(widget: Widget, w: Float, h: Float) {
        val view = canvas.visibleWorld(RectF())
        val center = canvas.visibleCenter()
        val zoom = canvas.camera.zoom
        val f = min(1f, min(view.width() * 0.8f / (w / zoom), view.height() * 0.8f / (h / zoom)))
        val ww = w / zoom * f
        val hh = h / zoom * f
        canvas.clearSelection()
        mode = Mode.LASSO
        applyTool()
        canvas.insert(emptyList(), listOf(widget.placed(center.x - ww / 2, center.y - hh / 2, ww, hh)))
    }

    private fun startRecording() {
        val n = note ?: return
        player.close()
        val file = File(store.mediaDir(n.id), "voz-${UUID.randomUUID().toString().take(8)}.m4a")
        if (!recorder.start(file)) toast("No se pudo usar el micrófono")
    }

    private fun addRecording(file: File, ms: Long) {
        if (note == null || file.parentFile != canvas.mediaDir) {
            file.delete()
            return
        }
        insertWidget(Media(0f, 0f, 0f, 0f, MediaKind.AUDIO, file, null, ms), 260f, 64f)
    }

    private fun addVideo(uri: Uri) {
        val n = note ?: return
        val dir = store.mediaDir(n.id)
        hint("Agregando el video…")
        Thread({
            val result = runCatching { Imports.video(contentResolver, uri, dir) }
            runOnUiThread {
                if (note?.id != n.id) return@runOnUiThread
                result.onSuccess { v ->
                    val w = 480f
                    val h = (w * v.height / v.width.coerceAtLeast(1)).coerceIn(160f, 640f)
                    insertWidget(Media(0f, 0f, 0f, 0f, MediaKind.VIDEO, v.file, v.thumb, v.durationMs), w, h)
                }.onFailure { toast("No se pudo agregar el video: ${it.message}", long = true) }
            }
        }, "tinta-video").start()
    }

    private fun play(media: Media) {
        if (!media.file.exists()) {
            toast("No encuentro el archivo")
            return
        }
        if (media.kind == MediaKind.VIDEO) {
            player.close()
            VideoPlayer.show(this, media.file)
        } else {
            player.play(media.file)
        }
    }

    /** Los stickers para pegar uno; con `at` (del mundo), ahí, y si no, en el centro de la pantalla. */
    private fun showStickers(anchor: View, at: android.graphics.PointF? = null) {
        val stickers = Stickers(this)
        Thread({
            val list = stickers.list()
            val thumbs = list.map { BitmapFactory.decodeFile(it.thumbnail.path) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Popups.stickers(
                    this, anchor, thumbs,
                    onPick = { i ->
                        Thread({
                            val clip = runCatching { stickers.load(list[i]) }.getOrNull()
                            runOnUiThread { clip?.let { paste(it, at) } ?: toast("No se pudo abrir el sticker") }
                        }, "tinta-sticker").start()
                    },
                    onDelete = { i ->
                        confirm(getString(R.string.delete_sticker), getString(R.string.delete)) {
                            Thread({ stickers.delete(list[i]) }, "tinta-sticker").start()
                        }
                    },
                )
            }
        }, "tinta-sticker").start()
    }

    // --- Lo elegido con el lazo ---

    override fun onCopySelection() {
        if (canvas.copySelection()) hint(getString(R.string.copied))
    }

    /** Copiar y borrar en un paso (el borrado se puede deshacer). */
    override fun onCutSelection() {
        if (!canvas.copySelection()) return
        canvas.deleteSelection()
        hint(getString(R.string.cut_done))
    }

    override fun onDuplicateSelection() = canvas.duplicateSelection()

    override fun onColorSelection(button: View) {
        val colors = Tool.INK_COLORS.toList() + prefs.savedColors + pens.filter { it.tool != Tool.HIGHLIGHTER }.map { it.color }
        Popups.colors(this, button, colors, isDark) { canvas.recolorSelection(it) }
    }

    override fun onDeleteSelection() = canvas.deleteSelection()

    override fun onEditSelection(button: View) {
        canvas.selectedWidget?.let { w ->
            fun replace(new: Widget) = canvas.replaceSelected(null, new.placed(w.left, w.top, w.width, w.height))
            when (w) {
                is Graph2D -> WidgetEditors.graph2d(this, w, isDark, isNew = false, onDone = ::replace)
                is Graph3D -> WidgetEditors.graph3d(this, w, isDark, isNew = false, onDone = ::replace)
                is Solid -> WidgetEditors.solid(this, w, isDark, isNew = false, onDone = ::replace)
                is Media -> play(w)
            }
            return
        }
        canvas.selectedImage?.let(::crop)
    }

    override fun onStickerSelection() {
        val clip = canvas.selectionClip() ?: return
        Thread({
            val ok = runCatching { Stickers(this).save(clip) }.isSuccess
            runOnUiThread { if (ok) hint(getString(R.string.sticker_saved)) else toast("No se pudo guardar el sticker") }
        }, "tinta-sticker").start()
    }

    private fun paste(clip: Clipboard.Clip, at: android.graphics.PointF? = null) {
        if (note == null) return
        mode = Mode.LASSO
        applyTool()
        canvas.paste(clip, at?.x, at?.y)
    }

    /** Mantener apretado con el lazo en un lugar vacío: pegar ahí. */
    override fun onLassoLongPress(sx: Float, sy: Float, wx: Float, wy: Float) {
        val loc = IntArray(2)
        canvas.getLocationInWindow(loc)
        val at = android.graphics.PointF(wx, wy)
        Popups.pasteHere(
            this, canvas.rootView, loc[0] + sx.toInt(), loc[1] + sy.toInt(), canPaste = Clipboard.clip != null,
            onPaste = { Clipboard.clip?.let { paste(it, at) } },
            onStickers = { showStickers(canvas, at) },
        )
    }

    /** Recorta la imagen: la parte que queda ocupa en la hoja el mismo lugar que ocupaba. */
    private fun crop(image: NoteImage) {
        val dir = canvas.mediaDir ?: return
        Thread({
            val preview = Elements.decode(image.file, 1600, hardware = false)
            runOnUiThread {
                if (preview == null) {
                    toast("No se pudo abrir la imagen")
                    return@runOnUiThread
                }
                CropDialog.show(this, preview) { r ->
                    Thread({
                        val result = runCatching {
                            val full = BitmapFactory.decodeFile(image.file.path) ?: error("no se pudo leer la imagen")
                            val x = (r.left * full.width).toInt().coerceIn(0, full.width - 1)
                            val y = (r.top * full.height).toInt().coerceIn(0, full.height - 1)
                            val w = (r.width() * full.width).toInt().coerceIn(1, full.width - x)
                            val h = (r.height() * full.height).toInt().coerceIn(1, full.height - y)
                            val part = Bitmap.createBitmap(full, x, y, w, h)
                            val out = ByteArrayOutputStream()
                            part.compress(if (part.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
                            val file = NoteStore.keep(dir, out.toByteArray())
                            // En el marco de la imagen (sin girar), el centro del recorte se corre; después se gira.
                            val nw = image.width * r.width()
                            val nh = image.height * r.height()
                            val ox = (r.centerX() - 0.5f) * image.width
                            val oy = (r.centerY() - 0.5f) * image.height
                            val turn = Transform.rotation(Math.toRadians(image.rotation.toDouble()).toFloat(), 0f, 0f)
                            val cx = image.centerX + turn.x(ox, oy)
                            val cy = image.centerY + turn.y(ox, oy)
                            NoteImage(cx - nw / 2, cy - nh / 2, nw, nh, image.rotation, file, image.locked)
                        }
                        runOnUiThread {
                            result.onSuccess { canvas.replaceSelected(it, null) }
                                .onFailure { toast("No se pudo recortar: ${it.message}", long = true) }
                        }
                    }, "tinta-recorte").start()
                }
            }
        }, "tinta-recorte").start()
    }

    // --- Acciones ---

    private fun clearAll() {
        val n = note ?: return
        val h = history ?: return
        canvas.clearSelection()
        // Las páginas de un PDF quedan: son parte de la hoja.
        val edits = listOf(
            History.removeAt(n.strokes, n.strokes.indices.toList()),
            History.removeAt(n.images, n.images.indices.filter { !n.images[it].locked }),
            History.removeAt(n.texts, n.texts.indices.toList()),
            History.removeAt(n.widgets, n.widgets.indices.toList()),
        ).filter { it.removed.isNotEmpty() }
        if (edits.isEmpty()) return
        h.record(History.Group(edits))
        canvas.elementsChanged()
        changed(redraw = true)
    }

    private fun pickSheet(anchor: View) {
        val n = note ?: return
        Popups.sheet(
            this, anchor, n.pages, n.background, isDark, n.paperColor,
            onFormat = { size, landscape -> setFormat(size, landscape) },
            onPaperColor = { c ->
                n.paperColor = c
                canvas.paperColor = c
                prefs.newPaperColor = c
                pageThumbs.clear()
                changed(redraw = false)
                updatePageStrip(snapshot = true)
            },
            onBackground = { bg ->
                n.background = bg
                canvas.paperStyle = bg
                prefs.newBackground = bg
                changed(redraw = false)
            },
        )
    }

    /**
     * Cambia el tamaño de hoja. Al pasar de infinita a hojas, la primera hoja empieza un poco antes
     * de lo escrito; entre tamaños de hoja se queda donde estaba.
     */
    private fun setFormat(size: PageSize, landscape: Boolean) {
        val n = note ?: return
        canvas.clearSelection()
        val old = n.pages
        n.pages = when {
            size == PageSize.INFINITE -> PageLayout(left = old.left, top = old.top)
            old.paged -> old.copy(size = size, landscape = landscape)
            else -> Render.bounds(n.strokes, n.images, n.texts, n.widgets).let { b ->
                PageLayout.around(size, landscape, b?.left ?: Float.NaN, b?.top ?: Float.NaN)
            }
        }
        canvas.pages = n.pages
        canvas.fitToContent()
        pageThumbs.clear()
        updatePageStrip(snapshot = true)
        if (size != PageSize.CUSTOM) {
            prefs.newPageSize = size
            prefs.newLandscape = landscape
        }
        changed(redraw = false)
    }

    private fun toggleDark() {
        handler.removeCallbacks(autosave)
        canvas.finishPending()
        save(withThumbnail = false)
        prefs.dark = !isDark
        recreate()
    }

    private fun rename() {
        val n = note ?: return
        askText(getString(R.string.rename), n.title) {
            n.title = it
            titles[n.id] = it
            refreshTabs()
            changed(redraw = false)
        }
    }

    /** Pega una imagen de la galería en el centro de la pantalla y la deja elegida para moverla. */
    private fun addImage(uri: Uri) {
        val n = note ?: return
        val visible = canvas.visibleWorld(RectF())
        val center = canvas.visibleCenter()
        val pages = canvas.pages
        val density = resources.displayMetrics.density
        val dir = store.mediaDir(n.id)
        Thread({
            val result = runCatching {
                val loaded = ImageImport.load(contentResolver, uri)
                // A tamaño real en la pantalla, sin pasar de un 60 % de lo que se ve (ni de la hoja).
                var w = loaded.width / density
                var h = loaded.height / density
                var maxW = visible.width() * 0.6f
                if (pages.paged) maxW = min(maxW, pages.width - 2 * PageLayout.MARGIN)
                val f = min(1f, min(maxW / w, visible.height() * 0.6f / h))
                w *= f
                h *= f
                NoteImage(center.x - w / 2, center.y - h / 2, w, h, 0f, NoteStore.keep(dir, loaded.bytes))
            }
            runOnUiThread {
                if (note?.id != n.id) return@runOnUiThread
                result.onSuccess { image ->
                    canvas.clearSelection()
                    mode = Mode.LASSO
                    applyTool()
                    canvas.insert(listOf(image), emptyList())
                }.onFailure { toast("No se pudo abrir la imagen: ${it.message}", long = true) }
            }
        }, "tinta-imagen").start()
    }

    /** Exportar la nota que se ve (en el formato elegido). La nota entera se guarda antes. */
    private fun export(format: Exporter.Format) {
        val n = note ?: return
        canvas.finishPending()
        if (format == Exporter.Format.NOTE) {
            handler.removeCallbacks(autosave)
            save(withThumbnail = true)
        }
        if (format != Exporter.Format.TEXT) hint(getString(R.string.exporting))
        Exporter.start(this, store, Exporter.snapshot(n), format) { open -> launching(open) }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_SLOT = "slot"

        /** Cuánto esperar a que la tablet termine de esconder o mostrar las barras del sistema. */
        private const val FULLSCREEN_SETTLE_MS = 600L

        private const val CAPTURE_PX_PER_DP = 3f
        private const val CAPTURE_MAX_SIDE = 4096f
        private const val CAPTURE_MAX_PIXELS = 12_000_000f

        /** Al abrir desde la búsqueda de la lista: lo buscado, para mostrarlo en la nota. */
        const val EXTRA_SEARCH = "buscar"
        private const val AUTOSAVE_MS = 1500L

        /** Las miniaturas se rehacen este tiempo después del último cambio. */
        private const val THUMBS_DELAY_MS = 1200L

        /** Un clic del botón dura menos que esto. */
        private const val BUTTON_CLICK_MS = 600L

        /** Cuánto esperar para ver si la otra mitad sigue a la vista después de dejar de verse esta. */
        private const val CLOSED_CHECK_MS = 800L
        private const val MAX_SAVED_COLORS = 24

        @SuppressLint("InlinedApi")
        private val STYLUS_KEYS = setOf(
            KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
        )

        /** Si la nota se está mostrando en alguna mitad de la pantalla. */
        fun isOpen(id: String) = Tabs.showing(id) >= 0

        /**
         * Muestra la nota en la ventana `slot`: si esa mitad ya está a la vista, en una pestaña
         * nueva; si no, la abre al lado, como otra ventana de Tinta.
         */
        fun openBeside(activity: Activity, id: String, slot: Int = 1) {
            val window = Tabs.windows[slot]
            if (window != null && window !== activity && !window.isFinishing &&
                window.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                window.openTab(id)
                return
            }
            window?.finish()
            activity.startActivity(
                Intent(activity, EditorActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_SLOT, slot)
                    .addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            )
        }

        /** Una nota nueva con la hoja y el fondo que se usaron la última vez. */
        fun createNote(store: NoteStore, prefs: Prefs, folder: String?): Note {
            val title = SimpleDateFormat("'Nota del' d 'de' MMMM, HH:mm", Locale.forLanguageTag("es-AR")).format(Date())
            return store.create(title, prefs.newBackground, PageLayout(prefs.newPageSize, prefs.newLandscape), folder, prefs.newPaperColor)
        }
    }
}
