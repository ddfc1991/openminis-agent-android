package com.openminis.app.files

import java.io.File

/**
 * [Mines-38.9.11 d] File operations for the dual-pane manager: copy / move / rename / delete / new folder / search,
 * with the rules that matter on a phone — never copy a folder into itself, never overwrite silently (「名字 (2).txt」),
 * rename refuses '/' and reserved names, and every op reports what it did in Chinese.
 */
object FileOpsCore {
    class Result(val ok: Int, val failed: List<String>, val outputs: List<File> = emptyList()) {
        val message: String get() = when {
            failed.isEmpty() -> "完成 $ok 项"
            ok == 0 -> "失败：" + failed.take(3).joinToString("；")
            else -> "完成 $ok 项，${failed.size} 项失败：" + failed.take(2).joinToString("；")
        }
    }

    /** Name check for rename / new folder; null = OK, else the reason in Chinese. */
    fun badName(name: String): String? = when {
        name.isBlank() -> "名字不能为空"
        name == "." || name == ".." -> "不能用 . 或 .."
        name.contains('/') || name.contains('\u0000') -> "名字里不能有 /"
        name.toByteArray().size > 255 -> "名字太长"
        else -> null
    }

    /** 「a.txt」 taken → 「a (2).txt」, 「a (3).txt」 …; folders → 「dir (2)」. */
    fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0 && !File(dir, name).isDirectory) name.substring(0, dot) to name.substring(dot) else name to ""
        val stem = Regex("""^(.*) \((\d+)\)$""").find(base)?.groupValues?.get(1) ?: base
        var i = 2
        while (f.exists()) { f = File(dir, "$stem ($i)$ext"); i++ }
        return f
    }

    /** True when [dest] is [src] itself or lies inside it (copying a folder into itself would never end). */
    fun isInside(src: File, dest: File): Boolean {
        val s = src.canonicalFile.path; val d = dest.canonicalFile.path
        return d == s || d.startsWith(s + File.separator)
    }

    fun copy(items: List<File>, destDir: File, onProgress: ((String) -> Unit)? = null): Result {
        var ok = 0; val failed = ArrayList<String>(); val outs = ArrayList<File>()
        for (src in items) {
            try {
                if (!src.exists()) { failed += "${src.name} 不存在"; continue }
                if (src.isDirectory && isInside(src, destDir)) { failed += "${src.name} 不能复制到它自己里面"; continue }
                val target = uniqueTarget(destDir, src.name)
                if (src.isDirectory) copyDir(src, target, onProgress) else { src.copyTo(target); target.setLastModified(src.lastModified()); onProgress?.invoke(src.name) }
                outs += target; ok++
            } catch (e: Exception) { failed += "${src.name}：${e.message ?: e.javaClass.simpleName}" }
        }
        return Result(ok, failed, outs)
    }

    private fun copyDir(src: File, target: File, onProgress: ((String) -> Unit)?) {
        target.mkdirs()
        src.listFiles()?.sortedBy { it.name }?.forEach { c ->
            val t = File(target, c.name)
            if (java.nio.file.Files.isSymbolicLink(c.toPath())) return@forEach   // never follow links out of the tree
            if (c.isDirectory) copyDir(c, t, onProgress) else { c.copyTo(t, overwrite = false); t.setLastModified(c.lastModified()); onProgress?.invoke(c.name) }
        }
    }

    fun move(items: List<File>, destDir: File, onProgress: ((String) -> Unit)? = null): Result {
        var ok = 0; val failed = ArrayList<String>(); val outs = ArrayList<File>()
        for (src in items) {
            try {
                if (!src.exists()) { failed += "${src.name} 不存在"; continue }
                if (src.parentFile?.canonicalFile == destDir.canonicalFile) { failed += "${src.name} 已经在这里"; continue }
                if (src.isDirectory && isInside(src, destDir)) { failed += "${src.name} 不能移动到它自己里面"; continue }
                val target = uniqueTarget(destDir, src.name)
                if (!src.renameTo(target)) {   // different mount (shared ↔ phone storage): copy then delete
                    if (src.isDirectory) copyDir(src, target, onProgress) else src.copyTo(target)
                    if (!src.deleteRecursively()) failed += "${src.name} 已复制，但原文件删不掉"
                }
                onProgress?.invoke(src.name)
                outs += target; ok++
            } catch (e: Exception) { failed += "${src.name}：${e.message ?: e.javaClass.simpleName}" }
        }
        return Result(ok, failed, outs)
    }

    fun rename(f: File, newName: String): Result {
        badName(newName)?.let { return Result(0, listOf(it)) }
        if (newName == f.name) return Result(0, listOf("名字没有变"))
        val t = File(f.parentFile, newName)
        if (t.exists()) return Result(0, listOf("已有同名的「$newName」"))
        return if (f.renameTo(t)) Result(1, emptyList(), listOf(t)) else Result(0, listOf("重命名失败（没有权限？）"))
    }

    fun delete(items: List<File>): Result {
        var ok = 0; val failed = ArrayList<String>()
        for (f in items) {
            val done = if (f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())) f.deleteRecursively() else f.delete()
            if (done) ok++ else failed += "${f.name} 删不掉"
        }
        return Result(ok, failed)
    }

    fun newFolder(dir: File, name: String): Result {
        badName(name)?.let { return Result(0, listOf(it)) }
        val t = File(dir, name)
        if (t.exists()) return Result(0, listOf("已有「$name」"))
        return if (t.mkdirs()) Result(1, emptyList(), listOf(t)) else Result(0, listOf("新建失败（没有权限？）"))
    }

    fun newFile(dir: File, name: String): Result {
        badName(name)?.let { return Result(0, listOf(it)) }
        val t = File(dir, name)
        if (t.exists()) return Result(0, listOf("已有「$name」"))
        return if (runCatching { t.createNewFile() }.getOrDefault(false)) Result(1, emptyList(), listOf(t)) else Result(0, listOf("新建失败（没有权限？）"))
    }

    /** Directory listing: folders first, then by [sort]; hidden files only when asked. */
    enum class Sort { NAME, SIZE, TIME, TYPE }
    fun list(dir: File, sort: Sort = Sort.NAME, showHidden: Boolean = false): List<File> {
        val all = dir.listFiles()?.toList() ?: emptyList()
        val shown = if (showHidden) all else all.filter { !it.name.startsWith(".") }
        val cmp: Comparator<File> = when (sort) {
            Sort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.SIZE -> compareByDescending<File> { it.length() }.thenBy { it.name.lowercase() }
            Sort.TIME -> compareByDescending<File> { it.lastModified() }.thenBy { it.name.lowercase() }
            Sort.TYPE -> compareBy<File> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
        }
        return shown.sortedWith(compareBy<File> { !it.isDirectory }.then(cmp))
    }

    /**
     * Recursive name search (case-insensitive substring, or a glob with * and ?), at most [limit] hits and [maxDepth]
     * levels; symlinks are not followed. Optional [contentText] also greps small text files (≤ 2 MB).
     */
    fun search(root: File, query: String, limit: Int = 500, maxDepth: Int = 24, contentText: String? = null, cancelled: () -> Boolean = { false }): List<File> {
        val q = query.trim()
        if (q.isEmpty() && contentText.isNullOrEmpty()) return emptyList()
        val glob = q.contains('*') || q.contains('?')
        val rx = if (glob) Regex("^" + q.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } } + "$", RegexOption.IGNORE_CASE) else null
        val out = ArrayList<File>()
        fun nameHit(f: File) = q.isEmpty() || (rx?.matches(f.name) ?: f.name.contains(q, ignoreCase = true))
        fun contentHit(f: File): Boolean {
            if (contentText.isNullOrEmpty()) return true
            if (!f.isFile || f.length() > 2L * 1024 * 1024) return false
            val head = HexCore.window(f, 0, 4096)
            if (!HexCore.isMostlyText(head)) return false
            return runCatching { f.readText().contains(contentText, ignoreCase = true) }.getOrDefault(false)
        }
        fun walk(d: File, depth: Int) {
            if (depth > maxDepth || out.size >= limit || cancelled()) return
            val kids = d.listFiles() ?: return
            for (k in kids.sortedBy { it.name.lowercase() }) {
                if (out.size >= limit || cancelled()) return
                if (nameHit(k) && contentHit(k)) out += k
                if (k.isDirectory && !java.nio.file.Files.isSymbolicLink(k.toPath())) walk(k, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    /** Total size + file count of a selection (folders recursively, links not followed). */
    fun measure(items: List<File>): Pair<Long, Int> {
        var bytes = 0L; var count = 0
        fun go(f: File) {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return
            if (f.isDirectory) f.listFiles()?.forEach { go(it) } else { bytes += f.length(); count++ }
        }
        items.forEach { go(it) }
        return bytes to count
    }
}
