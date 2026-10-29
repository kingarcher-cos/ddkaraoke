package com.ddkaraoke.standalone

import java.io.File

// Songs on the box. Just a folder scan for now.
class Library(private var folder: File) {
    private val extensions = setOf("mp4", "mkv", "webm", "m4v", "mov", "mp3", "m4a")

    fun path(): String = folder.absolutePath

    fun folderFile(): File = folder

    // exact file name match in the songs folder
    fun has(name: String): Boolean {
        if (name.contains('/') || name.contains('\\')) return false
        return File(folder, name).isFile
    }

    @Synchronized
    fun setFolder(f: File) { f.mkdirs(); folder = f }

    fun songs(): List<Song> {
        folder.mkdirs()
        return folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Song(it.name, it.nameWithoutExtension.replace(Regex("---[\\w-]{11}$"), "")) }
            ?: emptyList()
    }

    fun search(query: String): List<Song> {
        val q = query.trim().lowercase()
        val all = songs()
        return if (q.isEmpty()) all else all.filter { it.title.lowercase().contains(q) }
    }

    fun fileFor(song: Song): File? {
        val f = File(folder, song.file)
        // only files directly in the songs folder
        return if (f.isFile && f.parentFile?.canonicalPath == folder.canonicalPath) f else null
    }

    fun find(fileName: String): Song? = songs().firstOrNull { it.file == fileName }
}
