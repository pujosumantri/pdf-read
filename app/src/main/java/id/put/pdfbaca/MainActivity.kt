package id.put.pdfbaca

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.util.concurrent.Executors

enum class Kind { UP, ROOT, DIR, PDF, RECENT }

data class Entry(
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val file: File? = null,
    val key: String? = null
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAB_RECENT = 0
        private const val TAB_FOLDER = 1
        private const val TAB_ALL = 2
        private const val MENU_OPEN = 1
        private const val MENU_RESCAN = 2
        private const val MENU_CLEAR = 3
        private const val MENU_ABOUT = 4
    }

    private lateinit var tabs: TabLayout
    private lateinit var list: RecyclerView
    private lateinit var pathBar: TextView
    private lateinit var emptyText: TextView
    private lateinit var permBar: View

    private val adapter = FileAdapter({ onItemClick(it) }, { onItemLongClick(it) })
    private var currentDir: File? = null       // null = daftar penyimpanan (internal / kartu SD)
    private var allPdf: List<Entry>? = null
    private var scanning = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            openUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabs = findViewById(R.id.tabs)
        list = findViewById(R.id.list)
        pathBar = findViewById(R.id.pathBar)
        emptyText = findViewById(R.id.emptyText)
        permBar = findViewById(R.id.permBar)
        findViewById<Button>(R.id.btnPerm).setOnClickListener { requestAccess() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        tabs.addTab(tabs.newTab().setText("Terbaru"))
        tabs.addTab(tabs.newTab().setText("Folder"))
        tabs.addTab(tabs.newTab().setText("Semua PDF"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = refresh()
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) = refresh()
        })

        Store.getLastDir(this)?.let { p -> File(p).takeIf { it.isDirectory }?.let { currentDir = it } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!navigateUp()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        permBar.visibility = if (hasFileAccess()) View.GONE else View.VISIBLE
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---------- menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_OPEN, 0, "Buka file…").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_RESCAN, 1, "Pindai ulang semua PDF")
        menu.add(0, MENU_CLEAR, 2, "Hapus riwayat Terbaru")
        menu.add(0, MENU_ABOUT, 3, "About")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_OPEN -> pickPdf.launch(arrayOf("application/pdf"))
            MENU_ABOUT -> Store.showAbout(this)
            MENU_RESCAN -> {
                allPdf = null
                tabs.getTabAt(TAB_ALL)?.select()
                refresh()
            }
            MENU_CLEAR -> AlertDialog.Builder(this)
                .setMessage("Hapus semua riwayat file terbaru?")
                .setPositiveButton("Hapus") { _, _ -> Store.clearRecents(this); refresh() }
                .setNegativeButton("Batal", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- izin ----------
    private fun hasFileAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permBar.visibility = if (hasFileAccess()) View.GONE else View.VISIBLE
        allPdf = null
        refresh()
    }

    // ---------- tampilan tab ----------
    private fun refresh() {
        when (tabs.selectedTabPosition) {
            TAB_FOLDER -> showFolder()
            TAB_ALL -> showAll()
            else -> showRecent()
        }
    }

    private fun setItems(items: List<Entry>, emptyMsg: String) {
        adapter.items = items
        emptyText.text = emptyMsg
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showRecent() {
        pathBar.visibility = View.GONE
        val items = Store.recents(this).map { r ->
            val where = if (r.key.startsWith("file:")) {
                File(r.key.removePrefix("file:")).parent ?: ""
            } else "dari aplikasi lain"
            Entry(Kind.RECENT, r.name, "${Store.fmtDate(r.time)} · $where", key = r.key)
        }
        setItems(items, "Belum ada file yang dibuka.\nBuka PDF lewat tab Folder atau Semua PDF.")
    }

    private fun storageRoots(): List<File> {
        val roots = mutableListOf(Environment.getExternalStorageDirectory())
        for (d in getExternalFilesDirs(null)) {
            if (d == null) continue
            val p = d.absolutePath
            val idx = p.indexOf("/Android/data/")
            if (idx > 0) {
                val r = File(p.substring(0, idx))
                if (roots.none { it.absolutePath == r.absolutePath }) roots.add(r)
            }
        }
        return roots
    }

    private fun rootLabel(i: Int, f: File) = if (i == 0) "Penyimpanan internal" else "Kartu SD (${f.name})"

    private fun showFolder() {
        val roots = storageRoots()
        if (currentDir == null && roots.size == 1) currentDir = roots[0]
        val dir: File? = currentDir
        pathBar.visibility = View.VISIBLE

        if (dir == null) {
            pathBar.text = "Penyimpanan"
            setItems(roots.mapIndexed { i, r -> Entry(Kind.ROOT, rootLabel(i, r), r.absolutePath, file = r) }, "")
            return
        }

        pathBar.text = dir.absolutePath
        val items = mutableListOf<Entry>()
        val isRoot = roots.any { it.absolutePath == dir.absolutePath }
        if (!isRoot || roots.size > 1) items.add(Entry(Kind.UP, "..", "Folder di atasnya"))

        val children = dir.listFiles()
        if (children == null) {
            setItems(items, "Folder tidak bisa dibaca.\nPastikan izin akses file sudah diberikan.")
            emptyText.visibility = View.VISIBLE
            return
        }
        children.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .forEach { items.add(Entry(Kind.DIR, it.name, "Folder", file = it)) }
        children.filter { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .forEach {
                items.add(Entry(Kind.PDF, it.name, "${Store.fmtSize(it.length())} · ${Store.fmtDate(it.lastModified())}", file = it))
            }
        setItems(items, "")
        if (items.none { it.kind != Kind.UP }) {
            emptyText.text = "Tidak ada PDF atau folder di sini."
            emptyText.visibility = View.VISIBLE
        }
    }

    private fun showAll() {
        pathBar.visibility = View.GONE
        allPdf?.let {
            setItems(it, "Tidak ditemukan file PDF.")
            return
        }
        setItems(emptyList(), "Memindai semua PDF di HP…")
        if (scanning) return
        scanning = true
        val roots = storageRoots()
        io.execute {
            val found = ArrayList<File>()
            roots.forEach { scan(it, 0, found, true) }
            found.sortByDescending { it.lastModified() }
            val entries = found.map {
                Entry(Kind.PDF, it.name, "${Store.fmtSize(it.length())} · ${it.parent}", file = it)
            }
            main.post {
                scanning = false
                allPdf = entries
                if (tabs.selectedTabPosition == TAB_ALL) showAll()
            }
        }
    }

    private fun scan(dir: File, depth: Int, out: MutableList<File>, isRoot: Boolean) {
        if (depth > 12) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (isRoot && f.name == "Android") continue
                scan(f, depth + 1, out, false)
            } else if (f.name.endsWith(".pdf", ignoreCase = true)) {
                out.add(f)
            }
        }
    }

    // ---------- navigasi ----------
    private fun goTo(dir: File?) {
        currentDir = dir
        Store.setLastDir(this, dir?.absolutePath)
        showFolder()
        list.scrollToPosition(0)
    }

    /** true kalau berhasil naik folder (dipakai tombol Kembali). */
    private fun navigateUp(): Boolean {
        if (tabs.selectedTabPosition != TAB_FOLDER) return false
        val dir = currentDir ?: return false
        val roots = storageRoots()
        if (roots.any { it.absolutePath == dir.absolutePath }) {
            if (roots.size > 1) {
                goTo(null)
                return true
            }
            return false
        }
        goTo(dir.parentFile)
        return true
    }

    private fun onItemClick(e: Entry) {
        when (e.kind) {
            Kind.UP -> navigateUp()
            Kind.ROOT, Kind.DIR -> goTo(e.file)
            Kind.PDF -> e.file?.let { openFile(it) }
            Kind.RECENT -> e.key?.let { openKey(it) }
        }
    }

    private fun onItemLongClick(e: Entry) {
        if (e.kind != Kind.RECENT) return
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setMessage("Hapus dari daftar Terbaru?")
            .setPositiveButton("Hapus") { _, _ ->
                Store.removeRecent(this, e.key ?: return@setPositiveButton)
                showRecent()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openFile(f: File) {
        if (!f.exists()) {
            Toast.makeText(this, "File tidak ditemukan (mungkin sudah dipindah/dihapus)", Toast.LENGTH_LONG).show()
            Store.removeRecent(this, "file:${f.absolutePath}")
            refresh()
            return
        }
        startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun openUri(uri: Uri) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun openKey(key: String) {
        if (key.startsWith("file:")) openFile(File(key.removePrefix("file:")))
        else openUri(Uri.parse(key))
    }
}

class FileAdapter(
    private val onClick: (Entry) -> Unit,
    private val onLong: (Entry) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    var items: List<Entry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.icon.text = when (e.kind) {
            Kind.UP -> "⬆️"
            Kind.ROOT -> "💾"
            Kind.DIR -> "📁"
            Kind.PDF -> "📄"
            Kind.RECENT -> "🕘"
        }
        h.title.text = e.title
        h.sub.text = e.subtitle
        h.sub.visibility = if (e.subtitle.isEmpty()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { onClick(e) }
        h.itemView.setOnLongClickListener { onLong(e); e.kind == Kind.RECENT }
    }
}
