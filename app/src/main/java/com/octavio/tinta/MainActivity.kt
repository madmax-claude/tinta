package com.octavio.tinta

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.octavio.tinta.export.Exporter
import com.octavio.tinta.export.Render
import com.octavio.tinta.jnotes.JnotesImporter
import com.octavio.tinta.model.Find
import com.octavio.tinta.model.NoteInfo
import com.octavio.tinta.model.Note
import com.octavio.tinta.storage.CloudBackup
import com.octavio.tinta.ui.Chrome
import com.octavio.tinta.ui.Popups
import com.octavio.tinta.storage.HandText
import com.octavio.tinta.storage.Imports
import com.octavio.tinta.storage.Library
import com.octavio.tinta.storage.NoteStore
import java.util.concurrent.Executors

class MainActivity : TintaActivity() {

    private lateinit var store: NoteStore
    private lateinit var prefs: Prefs
    private lateinit var emptyView: TextView
    private lateinit var foldersBox: LinearLayout
    private lateinit var folderTitle: TextView
    private val adapter = NotesAdapter()
    private val thumbs = LruCache<String, Bitmap>(64)
    private val thumbLoader = Executors.newFixedThreadPool(2)

    /** Todas las notas, sin filtrar por carpeta. */
    private var notes: List<NoteInfo> = emptyList()

    /** Carpeta abierta: null para todas, [NO_FOLDER] para las que no tienen. */
    private var folder: String? = null

    private lateinit var searchBox: EditText
    private lateinit var searchStatus: TextView

    /** Lo leído de la letra de cada nota, para buscar. Se llena al empezar a buscar. */
    private val handLines = HashMap<String, List<HandText.Line>>()
    private var handReading = false

    /** Las notas encontradas por lo escrito: qué dice el renglón (para mostrarlo debajo del título). */
    private val snippets = HashMap<String, String>()
    private val searchLater = Runnable { showFolder() }

    private val query get() = if (::searchBox.isInitialized) searchBox.text.toString().trim() else ""

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importAll(uris)
    }

    private lateinit var cloud: CloudBackup
    private lateinit var cloudRow: TextView

    /** Elegir el archivo de la copia en Drive (o donde sea). */
    private val pickBackupTarget = registerForActivityResult(ActivityResultContracts.CreateDocument(CloudBackup.MIME)) { uri ->
        if (uri != null) cloud.connect(uri, displayName(uri))
    }

    /** Elegir una copia para traer sus notas. */
    private val pickBackupSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()
        store = NoteStore.get(this)
        prefs = Prefs(this)
        emptyView = findViewById(R.id.empty)
        foldersBox = findViewById(R.id.folders)
        folderTitle = findViewById(R.id.folder_title)
        folder = prefs.folder

        val list = findViewById<RecyclerView>(R.id.list)
        val columns = ((resources.configuration.screenWidthDp - 240) / 260).coerceAtLeast(2)
        adapter.columns = columns
        list.layoutManager = GridLayoutManager(this, columns).apply {
            // Las carpetas van arriba, en sus filas; un hueco completa la última, así las notas
            // empiezan en una fila nueva.
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.isGap(position)) adapter.gapSpan else 1
            }
        }
        list.adapter = adapter
        folderTitle.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        findViewById<View>(R.id.new_note).setOnClickListener { newNote() }
        // PDF para escribir encima; el mismo botón sigue aceptando los .Jnotes.
        findViewById<View>(R.id.import_jnotes).setOnClickListener { pickFiles.launch(arrayOf("application/pdf", "*/*")) }
        findViewById<TextView>(R.id.dark).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (isDark) R.drawable.ic_light else R.drawable.ic_dark, 0, 0, 0)
            setText(if (isDark) R.string.light_mode else R.string.dark_mode)
            setOnClickListener {
                prefs.dark = !isDark
                recreate()
            }
        }
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        searchBox = findViewById(R.id.search)
        searchStatus = findViewById(R.id.search_status)
        searchBox.doAfterTextChanged {
            searchBox.removeCallbacks(searchLater)
            searchBox.postDelayed(searchLater, SEARCH_DELAY_MS)
        }
        searchBox.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            searchBox.removeCallbacks(searchLater)
            showFolder()
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchBox.windowToken, 0)
            true
        }
        applyColors()
        cloud = CloudBackup.get(this)
        cloudRow = findViewById(R.id.cloud)
        cloudRow.setOnClickListener { cloudMenu(it) }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        // Si se cambió el modo desde el editor, esta pantalla quedó con el anterior.
        if (prefs.dark != isDark || prefs.look != lookShown) {
            recreate()
            return
        }
        // Las notas pudieron cambiar en el editor: si se está buscando, se vuelven a leer.
        handReading = false
        refresh()
        cloud.onStatus = ::showCloudStatus
        showCloudStatus()
        // Al volver de una nota: si hubo cambios, se suben.
        cloud.backup()
    }

    override fun onPause() {
        super.onPause()
        if (cloud.onStatus == ::showCloudStatus) cloud.onStatus = null
    }

    // --- Google Drive ---

    private fun showCloudStatus() {
        if (cloud.target == null) {
            cloudRow.text = getString(R.string.cloud_connect)
            return
        }
        val state = when {
            cloud.running -> "subiendo…"
            cloud.lastError != null -> "no se pudo subir (tocá para ver)"
            cloud.lastBackup == 0L -> "todavía sin subir"
            System.currentTimeMillis() - cloud.lastBackup < 60_000L -> "copia recién subida"
            else -> "copia " + DateUtils.getRelativeTimeSpanString(cloud.lastBackup, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
        }
        cloudRow.text = "Copia en Drive\n$state"
    }

    private fun cloudMenu(anchor: View) {
        if (cloud.target == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.cloud_connect)
                .setMessage(R.string.cloud_explain)
                .setPositiveButton(R.string.cloud_choose) { _, _ -> pickBackupTarget.launch(CloudBackup.FILE_NAME) }
                .setNeutralButton(R.string.cloud_restore_other) { _, _ -> pickBackupSource.launch(arrayOf(CloudBackup.MIME, "application/octet-stream", "*/*")) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        PopupMenu(this, anchor).apply {
            cloud.targetName?.let { menu.add("Archivo: $it").isEnabled = false }
            cloud.lastError?.let { error ->
                menu.add("Último error: $error").isEnabled = false
            }
            menu.add("Subir ahora").setOnMenuItemClickListener {
                cloud.backup(force = true)
                true
            }
            menu.add("Traer las notas de la copia…").setOnMenuItemClickListener {
                cloud.target?.let { uri -> confirm(getString(R.string.cloud_restore_confirm), "Traer") { restoreFrom(uri) } }
                true
            }
            menu.add(getString(R.string.cloud_restore_other)).setOnMenuItemClickListener {
                pickBackupSource.launch(arrayOf(CloudBackup.MIME, "application/octet-stream", "*/*"))
                true
            }
            menu.add("Guardar la copia en otro archivo…").setOnMenuItemClickListener {
                pickBackupTarget.launch(CloudBackup.FILE_NAME)
                true
            }
            menu.add("Dejar de copiar en Drive").setOnMenuItemClickListener {
                confirm("¿Dejar de copiar en Drive? El archivo que ya está en Drive no se borra.", "Dejar de copiar") { cloud.disconnect() }
                true
            }
            show()
        }
    }

    private fun restoreFrom(uri: Uri) {
        hint("Trayendo las notas de la copia…")
        Thread({
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { cloud.restore(it, EditorActivity::isOpen) } ?: error("no se pudo leer el archivo")
            }
            runOnUiThread {
                result.onSuccess { r ->
                    r.ids.forEach(NoteCache::drop)
                    val parts = mutableListOf<String>()
                    fun n(k: Int, one: String, many: String) = if (k == 1) "1 $one" else "$k $many"
                    if (r.added > 0) parts.add(n(r.added, "nota nueva", "notas nuevas"))
                    if (r.updated > 0) parts.add(n(r.updated, "actualizada", "actualizadas"))
                    if (r.skipped > 0) parts.add(n(r.skipped, "ya estaba al día", "ya estaban al día"))
                    if (r.busy.isNotEmpty()) parts.add("sin tocar porque están abiertas: ${r.busy.joinToString(", ")}")
                    toast(if (parts.isEmpty()) "La copia no tenía notas" else parts.joinToString("; "), long = true)
                    refresh()
                }.onFailure { toast("No se pudo traer la copia: ${it.message}", long = true) }
            }
        }, "tinta-restaurar").start()
    }

    /** Con la pantalla dividida esta pantalla no se pausa: el modo cambiado en la otra mitad se ve al volver. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && (prefs.dark != isDark || prefs.look != lookShown)) recreate()
    }

    // --- Colores del menú principal (Configuración) ---

    /** La barra de carpetas es oscura: sus renglones (que se rearman) van en claro. */
    private var menuDark = false

    private fun applyColors() {
        prefs.menuColor?.let { c ->
            val bar = findViewById<View>(R.id.sidebar)
            Chrome.paint(bar, c)
            menuDark = Chrome.isDark(c)
        }
        prefs.listColor?.let { c ->
            // El fondo de atrás de todo (también detrás de las barras del sistema).
            findViewById<View>(R.id.root).setBackgroundColor(c)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !Chrome.isDark(c)
                isAppearanceLightNavigationBars = !Chrome.isDark(c)
            }
            if (Chrome.isDark(c)) {
                // Los textos sobre el fondo; las tarjetas y el buscador tienen su propio fondo.
                for (id in listOf(R.id.folder_title, R.id.empty, R.id.search_status, R.id.import_jnotes, R.id.new_note)) {
                    Chrome.lighten(findViewById(id))
                }
                crumbColor = Chrome.muted(c, getColor(R.color.muted))
            }
        }
    }

    // --- Configuración ---

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showSettings() {
        val pad = dp(24f).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8f).toInt(), pad, 0)
        }
        box.addView(Switch(this).apply {
            text = getString(R.string.help_messages)
            textSize = 16f
            setTextColor(getColor(R.color.ink))
            isChecked = prefs.helpMessages
            setOnCheckedChangeListener { _, on ->
                prefs.helpMessages = on
                Help.on = on
            }
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.help_messages_hint)
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            setPadding(0, dp(4f).toInt(), dp(56f).toInt(), 0)
        })
        // Colores de la interfaz.
        box.addView(TextView(this).apply {
            text = getString(R.string.colors)
            textSize = 16f
            setTextColor(getColor(R.color.ink))
            setPadding(0, dp(20f).toInt(), 0, 0)
        })
        fun colorRow(title: String, original: Int, current: Int?, save: (Int?) -> Unit) {
            box.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(getColor(R.color.muted))
                setPadding(0, dp(10f).toInt(), 0, dp(2f).toInt())
            })
            box.addView(Popups.colorChoices(this, original, com.octavio.tinta.ui.Chrome.COLORS, current, columns = 15, onPick = save))
        }
        colorRow(getString(R.string.color_top_bar), getColor(R.color.chrome), prefs.topBarColor) { prefs.topBarColor = it }
        colorRow(getString(R.string.color_tools), getColor(R.color.toolbar), prefs.toolsColor) { prefs.toolsColor = it }
        colorRow(getString(R.string.color_tab), getColor(R.color.paper), prefs.tabColor) { prefs.tabColor = it }
        colorRow(getString(R.string.color_menu), getColor(R.color.sidebar), prefs.menuColor) { prefs.menuColor = it }
        colorRow(getString(R.string.color_list), getColor(R.color.chrome), prefs.listColor) { prefs.listColor = it }
        // Al cerrar, si cambió, la pantalla se vuelve a armar (sin o con las etiquetas de los botones).
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener { if (prefs.look != lookShown) recreate() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbLoader.shutdownNow()
    }

    private fun refresh() {
        store.io.execute {
            val all = store.list()
            runOnUiThread {
                notes = all
                showFolder()
            }
        }
    }

    // --- Carpetas ---

    private fun showFolder() {
        val lib = store.library
        val folders = lib.folders()
        if (folder != null && folder != NO_FOLDER && folders.none { it.id == folder }) folder = null
        // Sin carpetas no hay "Sin carpeta": se vuelve a todas.
        if (folder == NO_FOLDER && folders.isEmpty()) folder = null
        prefs.folder = folder
        val q = query
        val shown = when {
            q.isNotEmpty() -> searchResults(q)
            folder == null -> notes
            folder == NO_FOLDER -> notes.filter { it.folder == null }
            else -> notes.filter { it.folder == folder }
        }
        // Las carpetas de este nivel, como en un explorador (al buscar, las que se llaman así).
        val cards = when {
            q.isNotEmpty() -> Find.normalize(q).let { nq -> folders.filter { Find.normalize(it.name).contains(nq) } }
            folder == NO_FOLDER -> emptyList()
            else -> lib.children(folder)
        }
        if (q.isEmpty()) {
            snippets.clear()
            searchStatus.visibility = View.GONE
        }
        adapter.submit(cards, shown)
        folderTitle.text = when {
            q.isNotEmpty() -> "Buscar «$q»"
            folder == null -> getString(R.string.all_notes)
            folder == NO_FOLDER -> getString(R.string.no_folder)
            else -> breadcrumb(folder!!)
        }
        emptyView.visibility = if (shown.isEmpty() && cards.isEmpty()) View.VISIBLE else View.GONE
        when {
            q.isNotEmpty() -> emptyView.text = "No aparece en ninguna nota."
            else -> emptyView.setText(if (folder == null) R.string.empty else R.string.empty_folder)
        }

        foldersBox.removeAllViews()
        addFolderRow(getString(R.string.all_notes), R.drawable.ic_notes, notes.size, folder == null, depth = 0) { open(null) }
        // El árbol: las de arriba de todo, y abiertas las que llevan a la carpeta que se ve.
        val openPath = folder?.takeIf { it != NO_FOLDER }?.let { id -> lib.path(id).map { it.id }.toSet() }.orEmpty()
        fun tree(parent: String?, depth: Int) {
            for (f in lib.children(parent)) {
                val inside = lib.subtree(f.id)
                val count = notes.count { it.folder in inside }
                addFolderRow(f.name, R.drawable.ic_folder, count, folder == f.id, depth = depth, onMenu = { folderMenu(it, f) }) { open(f.id) }
                if (f.id in openPath) tree(f.id, depth + 1)
            }
        }
        tree(null, 0)
        if (folders.isNotEmpty()) {
            addFolderRow(getString(R.string.no_folder), R.drawable.ic_folder, notes.count { it.folder == null }, folder == NO_FOLDER, depth = 0) {
                open(NO_FOLDER)
            }
        }
        val here = targetFolder()?.let { lib.folder(it)?.name }
        addFolderRow(if (here != null) getString(R.string.new_folder_in, here) else getString(R.string.new_folder), R.drawable.ic_folder_add, -1, false, depth = 0) {
            newFolder()
        }
        if (menuDark) Chrome.lighten(foldersBox)
    }

    /** Color de las partes de la ruta que llevan a otra carpeta (con un fondo oscuro, más claro). */
    private var crumbColor: Int? = null

    /** "Todas las notas › Física › Termo", y cada parte lleva a esa carpeta. */
    private fun breadcrumb(id: String): CharSequence {
        val out = android.text.SpannableStringBuilder()
        fun part(text: String, target: String?, last: Boolean) {
            val start = out.length
            out.append(text)
            if (!last) {
                out.setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) = open(target)
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.color = crumbColor ?: getColor(R.color.muted)
                        ds.isUnderlineText = false
                    }
                }, start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append("  ›  ")
                out.setSpan(android.text.style.ForegroundColorSpan(crumbColor ?: getColor(R.color.muted)), out.length - 5, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        part(getString(R.string.all_notes), null, last = false)
        val path = store.library.path(id)
        for ((i, f) in path.withIndex()) part(f.name, f.id, last = i == path.lastIndex)
        return out
    }

    /** "Física › Termo", para mostrar dónde está una carpeta o una nota. */
    private fun pathName(id: String?): String? = id?.let { store.library.path(it).joinToString(" › ") { f -> f.name }.ifEmpty { null } }

    private fun open(id: String?) {
        folder = id
        // Elegir una carpeta deja de buscar.
        if (query.isNotEmpty()) searchBox.setText("")
        showFolder()
    }

    // --- Buscar ---

    /** Las notas con `q` en el título o en lo escrito (en todas las carpetas). */
    private fun searchResults(q: String): List<NoteInfo> {
        readHandwriting()
        val nq = Find.normalize(q)
        snippets.clear()
        val out = ArrayList<NoteInfo>()
        for (info in notes) {
            val lines = handLines[info.id].orEmpty()
            var first: HandText.Match? = null
            var n = 0
            for (line in lines) {
                val m = line.match(nq) ?: continue
                // Se muestra el primero que está tal cual, si hay.
                if (first == null || m.errors < first.errors) first = m
                n++
            }
            if (first != null) snippets[info.id] = "«${first.text}»" + if (n > 1) " y ${n - 1} más" else ""
            if (first != null || Find.normalize(info.title).contains(nq)) out.add(info)
        }
        return out
    }

    /** Empieza a leer la letra de las notas (las ya leídas salen enseguida del disco). */
    private fun readHandwriting() {
        if (handReading) return
        handReading = true
        val open = notes.map { it.id }.filter(EditorActivity::isOpen).toSet()
        searchStatus.visibility = View.GONE
        HandText.get(this).readAll(
            notes,
            load = { id -> runCatching { store.load(id) }.getOrNull() },
            isOpen = { it in open },
            progress = { text ->
                searchStatus.text = text
                searchStatus.visibility = if (text != null && query.isNotEmpty()) View.VISIBLE else View.GONE
            },
            each = { id, lines ->
                handLines[id] = lines
                // Se vuelve a filtrar, pero no con cada nota: a lo sumo un par de veces por segundo.
                if (query.isNotEmpty()) {
                    searchBox.removeCallbacks(searchLater)
                    searchBox.postDelayed(searchLater, REFILTER_MS)
                }
            },
        )
    }

    /** Una fila de la barra lateral; con `onMenu`, lleva un botón ⋮ (y mantenerla apretada hace lo mismo). */
    private fun addFolderRow(
        name: String,
        icon: Int,
        count: Int,
        selected: Boolean,
        depth: Int,
        onMenu: ((View) -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val row = TextView(this, null, 0, R.style.SidebarItem).apply {
            text = if (count >= 0) "$name  ·  $count" else name
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            // Las de adentro, corridas: se ve qué carpeta está dentro de cuál.
            if (depth > 0) setPaddingRelative(paddingStart + dp(18f).toInt() * depth, paddingTop, paddingEnd, paddingBottom)
            isSelected = selected
            setOnClickListener { onClick() }
        }
        if (onMenu == null) {
            foldersBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()))
            return
        }
        val more = ImageView(this).apply {
            setImageResource(R.drawable.ic_more)
            background = getDrawable(R.drawable.bg_tool)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Opciones de la carpeta «$name»"
            tip(contentDescription)
            setOnClickListener { onMenu(it) }
        }
        row.setOnLongClickListener {
            onMenu(more)
            true
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(row, LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f))
            addView(more, LinearLayout.LayoutParams(dp(36f).toInt(), dp(44f).toInt()))
        }
        foldersBox.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()))
    }

    /** Una carpeta nueva dentro de la que está abierta (o arriba de todo). */
    private fun newFolder(parent: String? = targetFolder()) {
        val title = store.library.folder(parent)?.let { getString(R.string.new_folder_in, it.name) } ?: getString(R.string.new_folder)
        askText(title, "") { name ->
            store.io.execute {
                val f = store.library.create(name, parent)
                runOnUiThread { open(f.id) }
            }
        }
    }

    private fun folderMenu(anchor: View, f: Library.Folder) {
        val lib = store.library
        val inside = lib.subtree(f.id)
        val insideNotes = notes.filter { it.folder in inside }
        val up = lib.parentOf(f.id)
        PopupMenu(this, anchor).apply {
            menu.add(R.string.rename).setOnMenuItemClickListener {
                askText(getString(R.string.rename), f.name) { name ->
                    store.io.execute {
                        lib.rename(f.id, name)
                        runOnUiThread { showFolder() }
                    }
                }
                true
            }
            menu.add(R.string.new_subfolder).setOnMenuItemClickListener {
                newFolder(f.id)
                true
            }
            menu.add(R.string.move_folder).setOnMenuItemClickListener {
                moveFolder(f)
                true
            }
            menu.add(R.string.delete_folder_only).setOnMenuItemClickListener {
                val where = lib.folder(up)?.name?.let { "«$it»" } ?: getString(R.string.no_folder).lowercase()
                confirm(getString(R.string.delete_folder, f.name, where), getString(R.string.delete)) {
                    store.io.execute {
                        lib.delete(f.id)
                        runOnUiThread { if (folder == f.id) folder = up }
                        refresh()
                    }
                }
                true
            }
            if (insideNotes.isNotEmpty()) {
                val label = if (insideNotes.size == 1) getString(R.string.delete_folder_and_note) else getString(R.string.delete_folder_and_notes, insideNotes.size)
                menu.add(label).setOnMenuItemClickListener {
                    deleteFolderWithNotes(f, insideNotes)
                    true
                }
            }
            show()
        }
    }

    /** Pasa la carpeta (con lo que tiene) adentro de otra, o arriba de todo. */
    private fun moveFolder(f: Library.Folder) {
        val lib = store.library
        val inside = lib.subtree(f.id)
        val targets = lib.folders().filter { it.id !in inside }.map { it to (pathName(it.id) ?: it.name) }.sortedBy { it.second.lowercase() }
        val names = (listOf(getString(R.string.top_level)) + targets.map { it.second }).toTypedArray()
        val current = lib.parentOf(f.id)?.let { p -> targets.indexOfFirst { it.first.id == p } + 1 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.move_folder_title, f.name))
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                val target = if (which == 0) null else targets[which - 1].first.id
                store.io.execute {
                    lib.moveFolder(f.id, target)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Borra la carpeta y las notas que tiene (pide confirmación; las abiertas no se pueden borrar). */
    private fun deleteFolderWithNotes(f: Library.Folder, inside: List<NoteInfo>) {
        val open = inside.filter { EditorActivity.isOpen(it.id) }
        if (open.isNotEmpty()) {
            toast(getString(R.string.close_first_named, open.joinToString(", ") { "«${it.title}»" }), long = true)
            return
        }
        val message = if (inside.size == 1) {
            getString(R.string.delete_folder_and_note_confirm, f.name, inside[0].title)
        } else {
            getString(R.string.delete_folder_and_notes_confirm, f.name, inside.size)
        }
        confirm(message, getString(R.string.delete)) {
            inside.forEach { NoteCache.drop(it.id) }
            store.io.execute {
                inside.forEach { store.delete(it.id) }
                val up = store.library.parentOf(f.id)
                val gone = store.library.deleteTree(f.id)
                runOnUiThread {
                    if (folder in gone) folder = up
                    refresh()
                }
            }
        }
    }

    private fun moveToFolder(info: NoteInfo) {
        val folders = store.library.folders()
        if (folders.isEmpty()) {
            toast("Primero creá una carpeta en la barra de la izquierda")
            return
        }
        // Con la ruta de cada una ("Física › Termo"), ordenadas por ruta.
        val sorted = folders.map { it to (pathName(it.id) ?: it.name) }.sortedBy { it.second.lowercase() }
        val names = (sorted.map { it.second } + getString(R.string.no_folder)).toTypedArray()
        val current = sorted.indexOfFirst { it.first.id == info.folder }.let { if (it < 0) sorted.size else it }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.move_to_folder).removeSuffix("…"))
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                val target = sorted.getOrNull(which)?.first?.id
                store.io.execute {
                    store.library.move(info.id, target)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** La carpeta donde van las notas nuevas o importadas: la abierta, si es una carpeta. */
    private fun targetFolder() = folder?.takeIf { it != NO_FOLDER }

    // --- Notas ---

    private fun openNote(id: String) {
        if (EditorActivity.isOpen(id)) {
            toast(getString(R.string.already_open))
            return
        }
        val intent = Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, id)
        // Encontrada por lo escrito: la nota se abre mostrando dónde.
        if (snippets.containsKey(id)) intent.putExtra(EditorActivity.EXTRA_SEARCH, query)
        startActivity(intent)
    }

    private fun newNote() {
        val note = EditorActivity.createNote(store, prefs, targetFolder())
        openNote(note.id) // el editor carga en el mismo hilo de disco, después de guardar
    }

    // --- Importar ---

    /** Un PDF o un .Jnotes abierto o compartido desde otra app (por ejemplo, desde el propio Jnotes). */
    private fun handleIncoming(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            else -> emptyList()
        }
        if (uris.isNotEmpty()) importAll(uris)
    }

    private fun importAll(uris: List<Uri>) {
        hint("Importando…")
        val target = targetFolder()
        store.io.execute {
            val messages = uris.map { uri ->
                val name = displayName(uri)
                runCatching {
                    val title = name.substringBeforeLast('.')
                    if (Exporter.isBundle(name) { contentResolver.openInputStream(uri) }) {
                        val n = contentResolver.openInputStream(uri)?.use { Exporter.importBundle(store, it) } ?: error("no se pudo leer el archivo")
                        store.library.move(n.id, target)
                        "«${n.title}»: nota de Tinta, ${n.strokes.size} trazos"
                    } else if (isPdf(uri, name)) {
                        val n = Imports.pdf(contentResolver, uri, store, title)
                        finishImport(n, target)
                        "«${n.title}»: ${n.images.size} páginas para escribir encima"
                    } else {
                        val id = store.newId()
                        val r = contentResolver.openInputStream(uri)?.use {
                            JnotesImporter.import(it, id, title, resources.displayMetrics.density, store.mediaDir(id))
                        } ?: error("no se pudo leer el archivo")
                        val n = r.note
                        finishImport(n, target)
                        buildString {
                            append("«${n.title}»: ${n.strokes.size} trazos")
                            if (n.images.isNotEmpty()) append(", ${n.images.size} imágenes")
                            if (n.texts.isNotEmpty()) append(", ${n.texts.size} textos")
                            if (r.failedStrokes > 0) append(" (${r.failedStrokes} trazos vacíos o dañados, omitidos)")
                        }
                    }
                }.getOrElse { "$name: ${it.message}" }
            }
            runOnUiThread {
                toast(messages.joinToString("\n"), long = true)
                refresh()
            }
        }
    }

    /** Exportar una nota de la lista: se lee del disco y se exporta. */
    private fun export(info: NoteInfo, format: Exporter.Format) {
        if (format != Exporter.Format.TEXT) hint(getString(R.string.exporting))
        store.io.execute {
            val n = runCatching { store.load(info.id) }.getOrNull()
            runOnUiThread {
                if (n == null) toast("No se pudo abrir la nota", long = true) else Exporter.start(this, store, n, format) { it() }
            }
        }
    }

    private fun isPdf(uri: Uri, name: String): Boolean {
        if (name.endsWith(".pdf", ignoreCase = true) || contentResolver.getType(uri) == "application/pdf") return true
        // Sin nombre ni tipo confiables: los PDF empiezan con "%PDF".
        val head = ByteArray(4)
        val n = runCatching { contentResolver.openInputStream(uri)?.use { it.read(head) } }.getOrNull() ?: 0
        return n == 4 && String(head, Charsets.ISO_8859_1) == "%PDF"
    }

    private fun finishImport(n: Note, folder: String?) {
        store.write(n.id, store.header(n), n.strokes, n.images, n.texts, n.widgets)
        store.writeThumbnail(n.id, Render.thumbnail(n.strokes, n.images, n.texts, n.widgets, n.background, n.pages, 480, 360, n.paperColor))
        store.library.move(n.id, folder)
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Jnotes"

    // --- Menú de cada nota ---

    private fun showMenu(anchor: View, info: NoteInfo) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.rename).setOnMenuItemClickListener { rename(info); true }
            menu.add(R.string.open_side).setOnMenuItemClickListener {
                if (EditorActivity.isOpen(info.id)) toast(getString(R.string.already_open)) else EditorActivity.openBeside(this@MainActivity, info.id)
                true
            }
            menu.add(R.string.move_to_folder).setOnMenuItemClickListener { moveToFolder(info); true }
            menu.add(R.string.export).setOnMenuItemClickListener {
                Popups.exportFormats(this@MainActivity, anchor) { f -> export(info, f) }
                true
            }
            menu.add(R.string.delete).setOnMenuItemClickListener {
                if (EditorActivity.isOpen(info.id)) {
                    toast(getString(R.string.close_first))
                    return@setOnMenuItemClickListener true
                }
                confirm(getString(R.string.delete_note, info.title), getString(R.string.delete)) {
                    NoteCache.drop(info.id)
                    store.io.execute {
                        store.delete(info.id)
                        refresh()
                    }
                }
                true
            }
            show()
        }
    }

    private fun rename(info: NoteInfo) {
        if (EditorActivity.isOpen(info.id)) {
            toast(getString(R.string.close_first))
            return
        }
        askText(getString(R.string.rename), info.title) { title ->
            NoteCache.drop(info.id)
            store.io.execute {
                runCatching {
                    val note = store.load(info.id)
                    note.title = title
                    store.write(note.id, store.header(note), note.strokes, note.images, note.texts, note.widgets)
                }
                refresh()
            }
        }
    }

    // --- Lista ---

    /** Primero las carpetas de este nivel (como en un explorador de archivos) y después las notas. */
    private inner class NotesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var folders: List<Library.Folder> = emptyList()
        private var items: List<NoteInfo> = emptyList()

        var columns = 2

        /** Columnas que faltan para completar la fila de las carpetas (0: no hace falta hueco). */
        val gapSpan get() = if (folders.isEmpty() || items.isEmpty()) 0 else (columns - folders.size % columns) % columns
        private val gaps get() = if (gapSpan > 0) 1 else 0

        fun isGap(position: Int) = gaps == 1 && position == folders.size

        fun submit(cards: List<Library.Folder>, notes: List<NoteInfo>) {
            folders = cards
            items = notes
            @Suppress("NotifyDataSetChanged") // lista corta; se recarga entera al volver a la pantalla
            notifyDataSetChanged()
        }

        override fun getItemCount() = folders.size + gaps + items.size

        override fun getItemViewType(position: Int) = when {
            position < folders.size -> FOLDER
            isGap(position) -> GAP
            else -> NOTE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                FOLDER -> FolderHolder(inflater.inflate(R.layout.item_folder, parent, false))
                GAP -> object : RecyclerView.ViewHolder(View(parent.context).apply { layoutParams = RecyclerView.LayoutParams(1, 1) }) {}
                else -> Holder(inflater.inflate(R.layout.item_note, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is FolderHolder -> holder.bind(folders[position])
                is Holder -> holder.bind(items[position - folders.size - gaps])
            }
        }

        inner class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.title)
            private val meta: TextView = view.findViewById(R.id.meta)
            private val more: View = view.findViewById(R.id.more)

            fun bind(f: Library.Folder) {
                val lib = store.library
                title.text = f.name
                val inside = lib.subtree(f.id)
                val n = notes.count { it.folder in inside }
                val subs = lib.children(f.id).size
                meta.text = listOfNotNull(
                    if (n == 1) "1 nota" else "$n notas",
                    if (subs == 0) null else if (subs == 1) "1 carpeta" else "$subs carpetas",
                    // Al buscar, dónde está.
                    if (query.isNotEmpty()) lib.parentOf(f.id)?.let(::pathName) else null,
                ).joinToString(" · ")
                itemView.setOnClickListener { open(f.id) }
                itemView.setOnLongClickListener { folderMenu(more, f); true }
                more.setOnClickListener { folderMenu(it, f) }
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val thumb: ImageView = view.findViewById(R.id.thumb)
            private val title: TextView = view.findViewById(R.id.title)
            private val meta: TextView = view.findViewById(R.id.meta)
            private val more: View = view.findViewById(R.id.more)

            init {
                view.clipToOutline = true
            }

            fun bind(info: NoteInfo) {
                title.text = info.title
                val ago = DateUtils.getRelativeTimeSpanString(info.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                val where = if (folder == null) pathName(info.folder) else null
                meta.text = snippets[info.id] ?: listOfNotNull(ago, "${info.strokeCount} trazos", where).joinToString(" · ")
                itemView.setOnClickListener { openNote(info.id) }
                itemView.setOnLongClickListener { showMenu(more, info); true }
                more.setOnClickListener { showMenu(it, info) }

                val key = "${info.id}@${info.modified}"
                thumb.tag = key
                val cached = thumbs.get(key)
                thumb.setImageBitmap(cached)
                if (cached != null) return
                val file = store.thumbnail(info.id)
                thumbLoader.execute {
                    val bitmap = BitmapFactory.decodeFile(file.path) ?: return@execute
                    thumbs.put(key, bitmap)
                    thumb.post { if (thumb.tag == key) thumb.setImageBitmap(bitmap) }
                }
            }
        }
    }

    private companion object {
        const val FOLDER = 0
        const val NOTE = 1
        const val GAP = 2
        const val NO_FOLDER = "-"
        const val SEARCH_DELAY_MS = 300L
        const val REFILTER_MS = 400L
    }
}
