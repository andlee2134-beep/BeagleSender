package com.beagle.sender

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerDevices: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvStorageInfo: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cbAutoCrop: CheckBox
    private lateinit var rvBooks: RecyclerView

    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var activeProtocol: BeagleProtocol? = null
    private val bookList = mutableListOf<BeagleBook>()
    private lateinit var bookAdapter: BookAdapter

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleSelectedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerDevices = findViewById(R.id.spinnerDevices)
        tvStatus = findViewById(R.id.tvStatus)
        tvStorageInfo = findViewById(R.id.tvStorageInfo)
        progressBar = findViewById(R.id.progressBar)
        cbAutoCrop = findViewById(R.id.cbAutoCrop)
        rvBooks = findViewById(R.id.rvBooks)

        bookAdapter = BookAdapter(bookList) { book -> confirmDeleteBook(book) }
        rvBooks.layoutManager = LinearLayoutManager(this)
        rvBooks.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rvBooks.adapter = bookAdapter

        requestBluetoothPermissions()

        findViewById<Button>(R.id.btnRefreshBt).setOnClickListener { loadPairedDevices() }
        findViewById<Button>(R.id.btnConnectSync).setOnClickListener { syncDeviceShelf() }
        findViewById<Button>(R.id.btnSelectFile).setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeProtocol?.close()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            ActivityCompat.requestPermissions(this, perms, 100)
        }
        loadPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("请先开启手机蓝牙。")
            return
        }
        pairedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        val names = pairedDevices.map { "${it.name ?: "未知设备"} (${it.address})" }
        spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val beagleIdx = pairedDevices.indexOfFirst { it.name?.contains("beagle", ignoreCase = true) == true }
        if (beagleIdx >= 0) spinnerDevices.setSelection(beagleIdx)
    }

    private fun getOrConnectProtocol(): BeagleProtocol {
        val pos = spinnerDevices.selectedItemPosition
        if (pos < 0 || pos >= pairedDevices.size) {
            throw IllegalStateException("请先在下拉列表中选择已配对的 Txtr Beagle")
        }
        val device = pairedDevices[pos]
        if (activeProtocol == null || activeProtocol?.isConnected() != true) {
            activeProtocol?.close()
            activeProtocol = BeagleProtocol(device).apply { connect() }
        }
        return activeProtocol!!
    }

    private fun syncDeviceShelf() {
        lifecycleScope.launch {
            try {
                log("正在连接电纸书并读取存储与书架...")
                val (memInfo, books) = withContext(Dispatchers.IO) {
                    val proto = getOrConnectProtocol()
                    val mem = proto.getMemoryInfo()
                    val list = proto.getBooks()
                    Pair(mem, list)
                }
                tvStorageInfo.text = "$memInfo | 共 ${books.size} 本书"
                bookList.clear()
                bookList.addAll(books)
                bookAdapter.notifyDataSetChanged()
                log("书架同步完成！")
            } catch (e: Exception) {
                activeProtocol?.close()
                activeProtocol = null
                log("连接或读取失败：${e.message}")
            }
        }
    }

    private fun confirmDeleteBook(book: BeagleBook) {
        AlertDialog.Builder(this)
            .setTitle("删除书籍")
            .setMessage("确定要从 Txtr Beagle 中永久删除《${book.title}》吗？")
            .setPositiveButton("确定删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        log("正在删除《${book.title}》...")
                        val (memInfo, books) = withContext(Dispatchers.IO) {
                            val proto = getOrConnectProtocol()
                            proto.deleteBook(book.id)
                            val mem = proto.getMemoryInfo()
                            val list = proto.getBooks()
                            Pair(mem, list)
                        }
                        tvStorageInfo.text = "$memInfo | 共 ${books.size} 本书"
                        bookList.clear()
                        bookList.addAll(books)
                        bookAdapter.notifyDataSetChanged()
                        log("已删除《${book.title}》并刷新空间。")
                    } catch (e: Exception) {
                        log("删除失败：${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handleSelectedFile(uri: Uri) {
        val fileName = getFileName(uri)
        val isTex = fileName.endsWith(".tex", ignoreCase = true)

        lifecycleScope.launch {
            try {
                if (isTex) {
                    sendTexFile(uri, fileName)
                } else {
                    sendPdfFile(uri, fileName)
                }
                // 传输完成后自动刷新设备书架和剩余空间
                syncDeviceShelf()
            } catch (e: Exception) {
                activeProtocol?.close()
                activeProtocol = null
                log("传输中断：${e.message}")
            }
        }
    }

    private suspend fun sendPdfFile(uri: Uri, fileName: String) {
        val autoCrop = cbAutoCrop.isChecked
        withContext(Dispatchers.IO) {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("无法读取文件")
            val renderer = PdfRenderer(pfd)
            val totalPages = renderer.pageCount

            withContext(Dispatchers.Main) {
                progressBar.max = totalPages
                progressBar.progress = 0
                log("正在准备传输 PDF，共 $totalPages 页...")
            }

            val protocol = getOrConnectProtocol()
            val bookId = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
            protocol.startBook(bookId, fileName.removeSuffix(".pdf"), "PDF Document")

            for (i in 0 until totalPages) {
                val page = renderer.openPage(i)
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val packed = DitherUtils.processPageForBeagle(bmp, autoCrop)
                bmp.recycle()

                protocol.sendPage(i + 1, packed)
                withContext(Dispatchers.Main) {
                    progressBar.progress = i + 1
                    log("正在发送第 ${i + 1} / $totalPages 页")
                }
            }

            protocol.endBook()
            renderer.close()
            pfd.close()
            withContext(Dispatchers.Main) { log("PDF 传输完成！正在刷新书架...") }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sendTexFile(uri: Uri, fileName: String) {
        log("正在解析 LaTeX 源码并排版...")
        val texContent = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }

        val bodyContent = texContent
            .substringAfter("\\begin{document}", texContent)
            .substringBefore("\\end{document}")
            .replace(Regex("\\\\section\\*?\\{(.*?)\\}"), "<h2>$1</h2>")
            .replace(Regex("\\\\subsection\\*?\\{(.*?)\\}"), "<h3>$1</h3>")
            .replace("\n\n", "<p>")

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
              <script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
              <script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"></script>
              <style>
                body { width: 560px; margin: 20px; font-size: 20px; line-height: 1.6; color: #000; background: #fff; font-family: serif; }
                h2 { font-size: 24px; border-bottom: 1px solid #000; }
              </style>
            </head>
            <body>
              <div id="content">$bodyContent</div>
              <script>
                renderMathInElement(document.body, {
                  delimiters: [
                    {left: "$$", right: "$$", display: true},
                    {left: "$", right: "$", display: false},
                    {left: "\\[", right: "\\]", display: true},
                    {left: "\\(", right: "\\)", display: false}
                  ]
                });
              </script>
            </body>
            </html>
        """.trimIndent()

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.layout(0, 0, DitherUtils.BEAGLE_WIDTH, DitherUtils.BEAGLE_HEIGHT)

        suspendCancellableCoroutine { cont ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            webView.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
        }
        delay(800)

        val contentHeight = (webView.contentHeight * webView.scale).toInt().coerceAtLeast(DitherUtils.BEAGLE_HEIGHT)
        val totalPages = (contentHeight + DitherUtils.BEAGLE_HEIGHT - 1) / DitherUtils.BEAGLE_HEIGHT
        progressBar.max = totalPages

        val bitmaps = mutableListOf<Bitmap>()
        for (p in 0 until totalPages) {
            webView.scrollTo(0, p * DitherUtils.BEAGLE_HEIGHT)
            delay(100)
            val pageBmp = Bitmap.createBitmap(DitherUtils.BEAGLE_WIDTH, DitherUtils.BEAGLE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(pageBmp)
            canvas.drawColor(Color.WHITE)
            canvas.translate(0f, -(p * DitherUtils.BEAGLE_HEIGHT).toFloat())
            webView.draw(canvas)
            bitmaps.add(pageBmp)
        }

        withContext(Dispatchers.IO) {
            val protocol = getOrConnectProtocol()
            val bookId = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
            protocol.startBook(bookId, fileName.removeSuffix(".tex"), "LaTeX Document")

            bitmaps.forEachIndexed { idx, bmp ->
                val packed = DitherUtils.processPageForBeagle(bmp, false)
                bmp.recycle()
                protocol.sendPage(idx + 1, packed)
                withContext(Dispatchers.Main) {
                    progressBar.progress = idx + 1
                    log("正在发送 TeX 第 ${idx + 1} / $totalPages 页")
                }
            }
            protocol.endBook()
            withContext(Dispatchers.Main) { log("TeX 传输完成！正在刷新书架...") }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Document"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun log(msg: String) {
        tvStatus.text = "状态：$msg"
    }

    class BookAdapter(
        private val items: List<BeagleBook>,
        private val onDeleteClick: (BeagleBook) -> Unit
    ) : RecyclerView.Adapter<BookAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvBookTitle)
            val tvMeta: TextView = view.findViewById(R.id.tvBookMeta)
            val btnDelete: Button = view.findViewById(R.id.btnDeleteBook)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val book = items[position]
            holder.tvTitle.text = book.title
            val progressText = if (book.totalPages > 0) "进度: ${book.currentPage}/${book.totalPages} 页" else "ID: ${book.id}"
            holder.tvMeta.text = "${book.author} | $progressText"
            holder.btnDelete.setOnClickListener { onDeleteClick(book) }
        }

        override fun getItemCount(): Int = items.size
    }
}
