package com.instinctazero.android

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Separate chapter files: unopened boards never retain trees or engine caches in memory. */
class StudyLibrary(private val directory: File) {
    init { check(directory.mkdirs() || directory.isDirectory) }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid chapter ID." }
        return File(directory, "$id.json")
    }
    @Synchronized fun read(id: String): String = AtomicFile(file(id)).openRead().bufferedReader().use { it.readText() }
    @Synchronized fun save(raw: String): Boolean {
        require(raw.length <= 1024 * 1024) { "Chapter exceeds 1 MB." }
        val state=JSONObject(raw)
        require(state.optInt("v") == 1 && state.optString("gameId", "").let { it.isBlank() || it == "null" }) {
            "Archived games cannot be written to the study library."
        }
        val id=state.getString("boardId")
        val atomic=AtomicFile(file(id)); val stream=atomic.startWrite()
        try { stream.write(raw.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        return true
    }
    @Synchronized fun list(): JSONArray {
        val result=JSONArray()
        // Parse one bounded document at a time; return metadata only to the page.
        directory.listFiles()?.map { File(directory, it.name.removeSuffix(".bak").removeSuffix(".new")) }
            ?.filter { it.extension == "json" }?.distinctBy { it.name }
            ?.sortedByDescending { it.lastModified() }?.forEach { f ->
            runCatching {
                val state=JSONObject(read(f.nameWithoutExtension))
                result.put(JSONObject().put("boardId", state.getString("boardId"))
                    .put("studyId", state.optString("studyId", state.getString("boardId")))
                    .put("title",state.optString("title","Study"))
                    .put("chapterTitle",state.optString("chapterTitle","Chapter 1"))
                    .put("repertoireColor",state.optString("repertoireColor","")))
            }
        }
        return result
    }
    @Synchronized fun delete(id: String) { AtomicFile(file(id)).delete() }
}
