package com.instinctazero.android

import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only these immutable upstream files may be installed and served to a local worker. */
internal class StockfishDownloads(private val folder: File, private val http: OkHttpClient) {
    private data class Asset(val file: String, val bytes: Long, val hash: String)
    private val packs = mapOf(
        "lite" to listOf(
            Asset("stockfish-19-lite-single.js",28230,"ccb130d7ff29408531c47d859a49f91d59d4f482d05a53eb1a3231a4f624c91a"),
            Asset("stockfish-19-lite-single.wasm",1845971,"ee4f115bdb2d32cadf96cada104a05b5ec257d7ad6f57582190b49bc71f096de")),
        "full" to listOf(
            Asset("stockfish-19-single.js",28130,"8071dee87312f74805a859de933e1a1ef465579ab1a51fbd597388cdce9d444d"),
            Asset("stockfish-19-single.wasm",99169054,"991bede2f253ebb08ff01fcc48270f4b9c8e66b12d5bd5f8d6f52eb3e3b3a740"))
    )
    fun installed(key: String) = packs[key]?.all { File(folder,it.file).length()==it.bytes } == true

    @Synchronized fun install(key: String) {
        val assets = requireNotNull(packs[key])
        folder.mkdirs()
        for (asset in assets) {
            val dest = File(folder,asset.file)
            if (dest.length()==asset.bytes) continue
            val temp = File.createTempFile("download-", ".part", folder)
            try {
                val url = "https://github.com/nmrugg/stockfish.js/releases/download/v19.0.1/" + asset.file
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    response.body!!.byteStream().use { input -> temp.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            check(total <= asset.bytes)
                            digest.update(buffer,0,size)
                            out.write(buffer,0,size)
                        }
                        out.fd.sync()
                    } }
                    check(total==asset.bytes && digest.digest().joinToString("") { "%02x".format(it) }==asset.hash)
                }
                Files.move(temp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
        }
    }

    fun response(url: String): WebResourceResponse? {
        val name = url.removePrefix(AnalysisWebPolicy.ORIGIN + "/assets/analysis/fish/")
        if (packs.values.flatten().none { it.file==name }) return null
        val file=File(folder,name)
        if (!file.isFile) return null
        val mime=if(name.endsWith(".js")) "application/javascript" else "application/wasm"
        return WebResourceResponse(mime,null,200,"OK",mapOf("Content-Security-Policy" to
            "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'"),file.inputStream())
    }
}
