package id.put.pdfbaca

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MIN_ZOOM = 0.5f
        private const val MAX_ZOOM = 5f
        private const val ZOOM_STEP = 1.25f
        private const val MAX_RENDER_W = 2400          // batas lebar bitmap per halaman (hemat memori)
        private const val MAX_RENDER_PX = 7_000_000L   // batas jumlah piksel per halaman
        private const val MENU_FILES = 1
        private const val MENU_FULLSCREEN = 2
        private const val MENU_ABOUT = 3
    }

    private lateinit var zoomFrame: ZoomFrame
    private lateinit var hScroll: PanScrollView
    private lateinit var pages: FixedWidthRecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var statusText: TextView
    private lateinit var zoomText: TextView
    private lateinit var pageText: TextView
    private lateinit var bottomBar: View
    private var fullscreen = false
    private lateinit var exitFullscreen: OnBackPressedCallback
    private val adapter = PageAdapter()

    private val renderExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    @Volatile private var closed = false

    private var pageSizes: List<IntArray> = emptyList()   // ukuran asli tiap halaman (point)
    private var zoom = 1f
    private var rotation = 0
    private var baseWidth = 0
    private var docKey = ""

    private lateinit var cache: LruCache<String, Bitmap>
    private val lastKeyForPage = HashMap<Int, String>()    // bitmap terakhir per halaman, dipakai sementara saat zoom

    private val pagePad by lazy { dp(6) }
    private val pageGap by lazy { dp(8) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        zoomFrame = findViewById(R.id.zoomFrame)
        hScroll = findViewById(R.id.hScroll)
        pages = findViewById(R.id.pages)
        statusText = findViewById(R.id.status)
        zoomText = findViewById(R.id.zoomText)
        pageText = findViewById(R.id.pageText)
        bottomBar = findViewById(R.id.bottomBar)

        exitFullscreen = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = setFullscreen(false)
        }
        onBackPressedDispatcher.addCallback(this, exitFullscreen)

        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        cache = object : LruCache<String, Bitmap>(maxKb / 4) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        lm = LinearLayoutManager(this)
        pages.layoutManager = lm
        pages.adapter = adapter
        pages.setItemViewCacheSize(2)
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updatePageText()
        })

        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { setZoom(zoom * ZOOM_STEP) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { setZoom(zoom / ZOOM_STEP) }
        findViewById<Button>(R.id.btnRotate).setOnClickListener { rotate() }
        zoomText.setOnClickListener { setZoom(1f) }
        pageText.setOnClickListener { askJumpPage() }

        zoomFrame.listener = object : ZoomFrame.Listener {
            override fun clampFactor(factor: Float): Float =
                (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM) / zoom

            override fun onScalePreview(factor: Float, focusX: Float, focusY: Float) {
                hScroll.pivotX = focusX
                hScroll.pivotY = focusY
                hScroll.scaleX = factor
                hScroll.scaleY = factor
            }

            override fun onScaleFinished(factor: Float, focusX: Float, focusY: Float) {
                hScroll.scaleX = 1f
                hScroll.scaleY = 1f
                setZoom(zoom * factor, focusX, focusY)
            }

            override fun onDoubleTap(x: Float, y: Float) {
                setZoom(if (zoom > 1.05f) 1f else 2.5f, x, y)
            }

            override fun onSingleTap() {
                if (fullscreen) setFullscreen(false)
            }
        }

        loadDocument()
    }

    // ---------- membuka dokumen ----------
    private fun loadDocument() {
        val path = intent.getStringExtra(EXTRA_PATH)
        val uri: Uri? = intent.data
        if (path == null && uri == null) {
            showError("Tidak ada file untuk dibuka.")
            return
        }

        val name: String
        if (path != null) {
            name = File(path).name
            docKey = "file:$path"
        } else if (uri!!.scheme == "file") {
            name = File(uri.path ?: "").name
            docKey = "file:${uri.path}"
        } else {
            name = displayName(uri)
            docKey = uri.toString()
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        title = name
        rotation = Store.getRotation(this, docKey)

        renderExec.execute {
            try {
                openRenderer(path, uri)
                val r = renderer!!
                val sizes = ArrayList<IntArray>(r.pageCount)
                synchronized(lock) {
                    for (i in 0 until r.pageCount) {
                        val p = r.openPage(i)
                        sizes.add(intArrayOf(p.width, p.height))
                        p.close()
                    }
                }
                main.post { onLoaded(name, sizes) }
            } catch (e: SecurityException) {
                main.post { showError("PDF ini dikunci password (belum didukung) atau aksesnya ditolak.") }
            } catch (e: Throwable) {
                main.post { showError("Gagal membuka PDF:\n${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun openRenderer(path: String?, uri: Uri?) {
        if (path != null || uri?.scheme == "file") {
            val f = File(path ?: uri!!.path!!)
            val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                renderer = PdfRenderer(fd)
                pfd = fd
            } catch (e: Throwable) {
                fd.close()
                throw e
            }
            return
        }
        val u = uri!!
        // Coba langsung dari penyedia konten; kalau gagal (stream tidak bisa di-seek), salin dulu ke cache.
        try {
            val fd = contentResolver.openFileDescriptor(u, "r") ?: throw IOException("null fd")
            try {
                renderer = PdfRenderer(fd)
                pfd = fd
                return
            } catch (e: SecurityException) {
                fd.close()
                throw e
            } catch (e: Exception) {
                fd.close()
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
        }
        val tmp = File(cacheDir, "sementara.pdf")
        val input = contentResolver.openInputStream(u) ?: throw IOException("File tidak bisa dibaca")
        input.use { inp -> FileOutputStream(tmp).use { out -> inp.copyTo(out) } }
        val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            renderer = PdfRenderer(fd)
            pfd = fd
        } catch (e: Throwable) {
            fd.close()
            throw e
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val s = c.getString(0)
                    if (!s.isNullOrBlank()) return s
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "Dokumen PDF"
    }

    private fun onLoaded(name: String, sizes: List<IntArray>) {
        if (isFinishing || isDestroyed) return
        pageSizes = sizes
        Store.addRecent(this, docKey, name)
        if (sizes.isEmpty()) {
            showError("PDF tidak berisi halaman.")
            return
        }
        statusText.visibility = View.GONE
        val startPage = Store.getPage(this, docKey).coerceIn(0, sizes.size - 1)
        zoomFrame.post {
            computeBaseWidth()
            applyContentWidth()
            adapter.notifyDataSetChanged()
            lm.scrollToPositionWithOffset(startPage, 0)
            updateZoomText()
            pageText.text = "${startPage + 1} / ${pageSizes.size}"
        }
    }

    private fun showError(msg: String) {
        statusText.visibility = View.VISIBLE
        statusText.text = msg
    }

    // ---------- ukuran & zoom ----------
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).roundToInt()

    private fun computeBaseWidth() {
        baseWidth = max(100, zoomFrame.width - 2 * pagePad)
    }

    /** Ukuran tampilan halaman (px) setelah rotasi & zoom. */
    private fun displaySize(pos: Int): IntArray {
        val s = pageSizes[pos]
        val w = if (rotation % 180 == 0) s[0] else s[1]
        val h = if (rotation % 180 == 0) s[1] else s[0]
        val dw = max(1, (baseWidth * zoom).roundToInt())
        val dh = max(1, (dw.toLong() * h / max(1, w)).toInt())
        return intArrayOf(dw, dh)
    }

    private fun applyContentWidth() {
        val viewport = zoomFrame.width
        val content = max(viewport, (baseWidth * zoom).roundToInt() + 2 * pagePad)
        pages.fixedWidth = content
        pages.requestLayout()
        hScroll.panEnabled = content > viewport
    }

    private fun setZoom(
        newZoom: Float,
        focusX: Float = zoomFrame.width / 2f,
        focusY: Float = zoomFrame.height / 2f
    ) {
        if (pageSizes.isEmpty() || baseWidth == 0) return
        val z = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(z - zoom) < 0.001f) {
            updateZoomText()
            return
        }
        val ratio = z / zoom
        val pos = lm.findFirstVisibleItemPosition()
        val top = if (pos >= 0) lm.findViewByPosition(pos)?.top ?: 0 else 0
        val newScrollX = ((hScroll.scrollX + focusX) * ratio - focusX).roundToInt()

        zoom = z
        applyContentWidth()
        adapter.notifyDataSetChanged()
        if (pos >= 0) lm.scrollToPositionWithOffset(pos, ((top - focusY) * ratio + focusY).roundToInt())
        scrollXAfterLayout(max(0, newScrollX))
        updateZoomText()
    }

    private fun scrollXAfterLayout(x: Int) {
        pages.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int
            ) {
                v.removeOnLayoutChangeListener(this)
                hScroll.post { hScroll.scrollTo(x, 0) }
            }
        })
    }

    private fun rotate() {
        if (pageSizes.isEmpty()) return
        val pos = max(0, lm.findFirstVisibleItemPosition())
        rotation = (rotation + 90) % 360
        Store.setRotation(this, docKey, rotation)
        lastKeyForPage.clear()
        applyContentWidth()
        adapter.notifyDataSetChanged()
        lm.scrollToPositionWithOffset(pos, 0)
        scrollXAfterLayout(0)
    }

    private fun updateZoomText() {
        zoomText.text = "${(zoom * 100).roundToInt()}%"
    }

    private fun currentPage(): Int {
        val first = lm.findFirstVisibleItemPosition()
        if (first < 0) return 0
        // Kalau halaman pertama yang terlihat tinggal sedikit, anggap sudah di halaman berikutnya.
        val v = lm.findViewByPosition(first)
        if (v != null && v.bottom < pages.height / 3 && first + 1 < pageSizes.size) return first + 1
        return first
    }

    private fun updatePageText() {
        if (pageSizes.isEmpty()) return
        pageText.text = "${currentPage() + 1} / ${pageSizes.size}"
    }

    private fun askJumpPage() {
        if (pageSizes.isEmpty()) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – ${pageSizes.size}"
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Ke halaman")
            .setView(box)
            .setPositiveButton("Buka") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                lm.scrollToPositionWithOffset((n - 1).coerceIn(0, pageSizes.size - 1), 0)
                main.post { updatePageText() }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayout()
    }

    /** Hitung ulang ukuran halaman setelah ukuran layar berubah (rotasi HP / layar penuh). */
    private fun relayout() {
        if (pageSizes.isEmpty()) return
        val pos = max(0, lm.findFirstVisibleItemPosition())
        zoomFrame.post {
            computeBaseWidth()
            applyContentWidth()
            adapter.notifyDataSetChanged()
            lm.scrollToPositionWithOffset(pos, 0)
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        exitFullscreen.isEnabled = on
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            supportActionBar?.hide()
            bottomBar.visibility = View.GONE
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "Ketuk layar sekali atau tekan Kembali untuk keluar dari layar penuh", Toast.LENGTH_SHORT).show()
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            supportActionBar?.show()
            bottomBar.visibility = View.VISIBLE
        }
        zoomFrame.postDelayed({ relayout() }, 250)
    }

    // ---------- menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Semua masuk ke menu titik tiga (⋮) di kanan atas.
        menu.add(0, MENU_FILES, 0, "Buka file lain").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_FULLSCREEN, 1, "Tampilkan Fullscreen").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_ABOUT, 2, "About").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                goToFiles()
                true
            }
            MENU_FILES -> {
                goToFiles()
                true
            }
            MENU_FULLSCREEN -> {
                setFullscreen(true)
                true
            }
            MENU_ABOUT -> {
                Store.showAbout(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun goToFiles() {
        if (isTaskRoot) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (pageSizes.isNotEmpty() && docKey.isNotEmpty()) Store.setPage(this, docKey, currentPage())
    }

    override fun onDestroy() {
        super.onDestroy()
        closed = true
        renderExec.execute {
            synchronized(lock) {
                try {
                    renderer?.close()
                } catch (_: Exception) {
                }
                try {
                    pfd?.close()
                } catch (_: Exception) {
                }
                renderer = null
                pfd = null
            }
        }
        renderExec.shutdown()
    }

    // ---------- render halaman ----------
    private fun renderSize(dw: Int, dh: Int): IntArray {
        var rw = min(dw, MAX_RENDER_W)
        var rh = max(1, (rw.toLong() * dh / dw).toInt())
        if (rw.toLong() * rh > MAX_RENDER_PX) {
            val k = sqrt(MAX_RENDER_PX.toDouble() / (rw.toLong() * rh))
            rw = max(1, (rw * k).toInt())
            rh = max(1, (rh * k).toInt())
        }
        return intArrayOf(rw, rh)
    }

    /** Dijalankan di thread render. */
    private fun renderPage(pos: Int, rw: Int, rh: Int, rot: Int): Bitmap? {
        synchronized(lock) {
            val r = renderer ?: return null
            if (closed) return null
            val page = r.openPage(pos)
            try {
                val pw = page.width.toFloat()
                val ph = page.height.toFloat()
                val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val sideForWidth = if (rot % 180 == 0) rw else rh
                val s = sideForWidth / pw
                val m = Matrix()
                m.setScale(s, s)
                when (rot) {
                    90 -> { m.postRotate(90f); m.postTranslate(ph * s, 0f) }
                    180 -> { m.postRotate(180f); m.postTranslate(pw * s, ph * s) }
                    270 -> { m.postRotate(270f); m.postTranslate(0f, pw * s) }
                }
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            } catch (e: OutOfMemoryError) {
                cache.evictAll()
                return null
            } finally {
                page.close()
            }
        }
    }

    inner class PageHolder(val frame: FrameLayout, val img: ImageView) : RecyclerView.ViewHolder(frame) {
        @Volatile var key: String? = null
    }

    inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = if (baseWidth == 0) 0 else pageSizes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val frame = FrameLayout(parent.context)
            frame.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            frame.setPadding(0, pageGap / 2, 0, pageGap / 2)
            val img = ImageView(parent.context)
            img.scaleType = ImageView.ScaleType.FIT_XY
            img.setBackgroundColor(Color.WHITE)
            frame.addView(img, FrameLayout.LayoutParams(1, 1, Gravity.CENTER_HORIZONTAL))
            return PageHolder(frame, img)
        }

        override fun onBindViewHolder(h: PageHolder, position: Int) {
            val d = displaySize(position)
            val lp = h.img.layoutParams as FrameLayout.LayoutParams
            lp.width = d[0]
            lp.height = d[1]
            lp.gravity = Gravity.CENTER_HORIZONTAL
            h.img.layoutParams = lp

            val r = renderSize(d[0], d[1])
            val rot = rotation
            val key = "$position|${r[0]}|$rot"
            h.key = key

            val cached = cache.get(key)
            if (cached != null) {
                h.img.setImageBitmap(cached)
                lastKeyForPage[position] = key
                return
            }
            // Tampilkan versi lama (resolusi lain) sementara, kalau ada.
            val prev = lastKeyForPage[position]?.let { cache.get(it) }
            if (prev != null) h.img.setImageBitmap(prev) else h.img.setImageDrawable(null)

            if (closed || renderExec.isShutdown) return
            renderExec.execute {
                if (h.key != key || closed) return@execute
                val bmp = cache.get(key) ?: renderPage(position, r[0], r[1], rot)?.also { cache.put(key, it) }
                if (bmp != null) {
                    main.post {
                        if (h.key == key) {
                            h.img.setImageBitmap(bmp)
                            lastKeyForPage[position] = key
                        }
                    }
                }
            }
        }
    }
}
