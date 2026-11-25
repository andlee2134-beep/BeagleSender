package com.beagle.sender

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater

data class BeagleBook(
    val id: String,
    var title: String = "未命名书籍",
    var author: String = "未知作者",
    var totalPages: Int = 0,
    var currentPage: Int = 0
)

class BeagleProtocol(private val device: BluetoothDevice) {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    private var reader: BufferedReader? = null

    fun isConnected(): Boolean = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect() {
        if (isConnected()) return
        socket = device.createRfcommSocketToServiceRecord(sppUuid)
        socket?.connect()
        outStream = socket?.outputStream
        reader = BufferedReader(InputStreamReader(socket?.inputStream, Charsets.UTF_8))
        sendCommand("PARTNER_ID=0123456789ABCDEF")
    }

    /**
     * 查询电纸书剩余存储空间
     */
    fun getMemoryInfo(): String {
        val lines = sendCommandReadBlock("MEMORY")
        val raw = lines.joinToString(" ").trim()
        val numbers = Regex("\\d+").findAll(raw).map { it.value.toLongOrNull() ?: 0L }.toList()
        return if (numbers.isNotEmpty()) {
            val freeKb = numbers.first()
            val freeMb = String.format("%.1f", freeKb / 1024.0)
            "剩余可用空间：约 $freeMb MB ($freeKb KB)"
        } else {
            "存储状态：$raw"
        }
    }

    /**
     * 读取电纸书内已存在的书籍列表及阅读进度
     */
    fun getBooks(): List<BeagleBook> {
        val lines = sendCommandReadBlock("GETBOOKS")
        val books = mutableListOf<BeagleBook>()
        var current: BeagleBook? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.equals("OK", ignoreCase = true)) continue

            when {
                trimmed.startsWith("BOOK", ignoreCase = true) -> {
                    current?.let { books.add(it) }
                    val idMatch = Regex("ID=([^\\s]+)", RegexOption.IGNORE_CASE).find(trimmed)
                    val id = idMatch?.groupValues?.get(1) ?: trimmed.substringAfter(" ").trim()
                    current = BeagleBook(id = id)
                }
                trimmed.startsWith("TITLE", ignoreCase = true) -> {
                    current?.title = trimmed.substringAfter(" ").trim()
                }
                trimmed.startsWith("AUTHOR", ignoreCase = true) -> {
                    current?.author = trimmed.substringAfter(" ").trim()
                }
                trimmed.startsWith("PAGES", ignoreCase = true) -> {
                    current?.totalPages = trimmed.substringAfter(" ").trim().toIntOrNull() ?: 0
                }
                trimmed.startsWith("LASTPAGE", ignoreCase = true) || trimmed.startsWith("PAGE", ignoreCase = true) -> {
                    current?.currentPage = trimmed.substringAfter(" ").trim().toIntOrNull() ?: 0
                }
            }
        }
        current?.let { books.add(it) }
        return books
    }

    /**
     * 删除电纸书内指定 ID 的书籍
     */
    fun deleteBook(bookId: String) {
        sendCommand("DELETEBOOK ID=$bookId")
    }

    fun startBook(bookId: String, title: String, author: String) {
        val cleanTitle = title.replace("\n", " ").take(60)
        val cleanAuthor = author.replace("\n", " ").take(40)
        sendCommand("BOOK ID=$bookId")
        sendCommand("TITLE $cleanTitle")
        sendCommand("AUTHOR $cleanAuthor")
    }

    fun sendPage(pageNumber: Int, raw4BitImage: ByteArray) {
        val compressed = compressWith2KWindow(raw4BitImage)
        sendCommand("PAGE $pageNumber ${compressed.size}")
        outStream?.write(compressed)
        outStream?.flush()
        waitForOk()
    }

    fun endBook() {
        sendCommand("ENDBOOK")
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
        }
    }

    private fun sendCommand(cmd: String) {
        outStream?.write("$cmd\r\n".toByteArray(Charsets.UTF_8))
        outStream?.flush()
        waitForOk()
    }

    private fun sendCommandReadBlock(cmd: String): List<String> {
        outStream?.write("$cmd\r\n".toByteArray(Charsets.UTF_8))
        outStream?.flush()
        val result = mutableListOf<String>()
        while (true) {
            val line = reader?.readLine() ?: break
            if (line.contains("ERR", ignoreCase = true)) {
                throw IllegalStateException("Beagle 返回错误: $line")
            }
            if (line.trim().equals("OK", ignoreCase = true) || line.trim().equals("END", ignoreCase = true)) {
                break
            }
            result.add(line)
        }
        return result
    }

    private fun waitForOk() {
        while (true) {
            val line = reader?.readLine() ?: break
            if (line.contains("ERR", ignoreCase = true)) {
                throw IllegalStateException("Beagle 报错: $line")
            }
            if (line.trim().equals("OK", ignoreCase = true)) {
                break
            }
        }
    }

    private fun compressWith2KWindow(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(2048)

        var offset = 0
        val chunkSize = 2048
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            deflater.setInput(data, offset, len)
            var count = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
            while (count > 0) {
                output.write(buffer, 0, count)
                count = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
            }
            offset += len
        }
        deflater.finish()
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }
}
