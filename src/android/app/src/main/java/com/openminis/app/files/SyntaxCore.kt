package com.openminis.app.files

/**
 * [Mines-38.9.11 d] Light syntax highlighting for the built-in text editor: one pass over the line, producing
 * (start, end, token) spans. Deliberately simple — comments, strings, numbers, keywords — so a 10 000-line file
 * still scrolls. Pure Kotlin: the colours live in the UI layer.
 */
object SyntaxCore {
    enum class Token { PLAIN, COMMENT, STRING, NUMBER, KEYWORD, TAG, ATTR }
    class Span(val start: Int, val end: Int, val token: Token)

    enum class Lang { NONE, C_LIKE, SMALI, XML, JSON, SHELL, PYTHON, MARKDOWN }

    fun langOf(fileName: String): Lang = when (fileName.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java", "c", "cc", "cpp", "h", "hpp", "js", "ts", "tsx", "jsx", "go", "rs", "swift", "gradle" -> Lang.C_LIKE
        "smali" -> Lang.SMALI
        "xml", "html", "htm", "svg", "axml" -> Lang.XML
        "json" -> Lang.JSON
        "sh", "bash", "zsh", "profile", "env", "conf", "ini", "toml", "properties", "cfg", "yml", "yaml" -> Lang.SHELL
        "py" -> Lang.PYTHON
        "md", "markdown" -> Lang.MARKDOWN
        else -> Lang.NONE
    }

    private val C_KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package",
        "private", "public", "protected", "internal", "override", "suspend", "data", "null", "true", "false", "this", "super",
        "try", "catch", "finally", "throw", "new", "static", "final", "void", "int", "long", "float", "double", "boolean",
        "const", "let", "function", "func", "def", "struct", "enum", "type", "impl", "pub", "use", "in", "is", "as", "by",
    )
    private val SH_KEYWORDS = setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "export", "local", "return", "true", "false", "null")
    private val PY_KEYWORDS = setOf("def", "class", "import", "from", "as", "if", "elif", "else", "for", "while", "return", "try", "except", "finally", "with", "lambda", "None", "True", "False", "self", "not", "and", "or", "in", "is", "pass", "raise", "yield")

    /** Spans for ONE line (the editor highlights what is on screen). [inBlockComment] carries a block comment across lines. */
    fun spans(line: String, lang: Lang, inBlockComment: Boolean = false): Pair<List<Span>, Boolean> {
        if (lang == Lang.NONE || line.isEmpty()) return emptyList<Span>() to inBlockComment
        val out = ArrayList<Span>()
        var block = inBlockComment
        var i = 0
        val keywords = when (lang) { Lang.C_LIKE -> C_KEYWORDS; Lang.SHELL -> SH_KEYWORDS; Lang.PYTHON -> PY_KEYWORDS; else -> emptySet() }
        if (lang == Lang.MARKDOWN) {
            if (line.startsWith("#")) out += Span(0, line.length, Token.KEYWORD)
            else if (line.trimStart().startsWith("```")) out += Span(0, line.length, Token.COMMENT)
            return out to false
        }
        if (lang == Lang.SMALI) {
            if (line.trimStart().startsWith("#")) return listOf(Span(0, line.length, Token.COMMENT)) to false
            if (line.trimStart().startsWith(".")) out += Span(line.indexOf('.'), line.length, Token.KEYWORD)
        }
        while (i < line.length) {
            if (block) {
                val e = line.indexOf("*/", i)
                if (e < 0) { out += Span(i, line.length, Token.COMMENT); return out to true }
                out += Span(i, e + 2, Token.COMMENT); i = e + 2; block = false; continue
            }
            val c = line[i]
            when {
                lang == Lang.C_LIKE && c == '/' && i + 1 < line.length && line[i + 1] == '/' -> { out += Span(i, line.length, Token.COMMENT); return out to false }
                lang == Lang.C_LIKE && c == '/' && i + 1 < line.length && line[i + 1] == '*' -> { block = true; i += 2 }
                (lang == Lang.SHELL || lang == Lang.PYTHON || lang == Lang.SMALI) && c == '#' -> { out += Span(i, line.length, Token.COMMENT); return out to false }
                c == '"' || c == '\'' -> { val e = closeQuote(line, i, c); out += Span(i, e, Token.STRING); i = e }
                lang == Lang.XML && c == '<' -> { val e = line.indexOf('>', i).let { if (it < 0) line.length else it + 1 }
                    out += Span(i, minOf(e, line.length), Token.TAG); i = e }
                c.isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit()) -> {
                    var j = i; while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == 'x')) j++
                    out += Span(i, j, Token.NUMBER); i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i; while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val w = line.substring(i, j)
                    if (w in keywords) out += Span(i, j, Token.KEYWORD)
                    else if (lang == Lang.JSON && j < line.length && line.getOrNull(j) == ':') out += Span(i, j, Token.ATTR)
                    i = j
                }
                else -> i++
            }
        }
        return out to block
    }

    private fun closeQuote(line: String, start: Int, q: Char): Int {
        var i = start + 1
        while (i < line.length) { if (line[i] == '\\') i += 2 else if (line[i] == q) return i + 1 else i++ }
        return line.length
    }

    /** Guard for the editor: a file it should not try to open as text. */
    fun tooBigToEdit(bytes: Long): Boolean = bytes > 8L * 1024 * 1024
}
