package com.openminis.app.files

/**
 * [Mines-38.9.11 d] Binary XML (AXML, the compiled AndroidManifest.xml / res/xml) → readable XML text + a small DOM.
 * Pure Kotlin. Handles RES_XML_TYPE (0x0003), string pool, resource-id map (0x0180), namespaces (0x0100/0x0101),
 * start/end element (0x0102/0x0103), CDATA (0x0104). Attribute names that were stripped (empty string) are recovered
 * from the resource-id map for the common android: attributes.
 */
object AxmlCore {
    class Attr(val ns: String?, val name: String, val type: Int, val data: Int, val raw: String?) {
        /** Printable value (reference → @0x7f…, bool → true/false, …). */
        val value: String get() = raw ?: formatValue(type, data)
        val prefixedName: String get() = if (ns == ANDROID_NS) "android:$name" else name
    }
    class Element(val name: String, val attrs: List<Attr>, val children: MutableList<Element> = mutableListOf(), var text: String? = null) {
        fun attr(n: String): Attr? = attrs.firstOrNull { it.name == n }
        fun a(n: String): String? = attr(n)?.value
        fun all(tag: String): List<Element> = children.filter { it.name == tag }
        fun walk(f: (Element) -> Unit) { f(this); children.forEach { it.walk(f) } }
    }
    class Doc(val root: Element?, val xml: String)

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** android:* attribute ids (android.R.attr) → name, for manifests whose attribute-name strings were stripped. */
    val ATTR_NAMES: Map<Int, String> = mapOf(
        0x01010000 to "theme", 0x01010001 to "label", 0x01010002 to "icon", 0x01010003 to "name", 0x01010006 to "permission",
        0x01010007 to "readPermission", 0x01010008 to "writePermission", 0x01010009 to "protectionLevel", 0x0101000b to "sharedUserId",
        0x0101000c to "hasCode", 0x0101000e to "enabled", 0x0101000f to "debuggable", 0x01010010 to "exported", 0x01010011 to "process",
        0x01010012 to "taskAffinity", 0x01010018 to "authorities", 0x0101001b to "grantUriPermissions", 0x0101001c to "priority",
        0x0101001d to "launchMode", 0x0101001e to "screenOrientation", 0x01010024 to "value", 0x01010025 to "resource",
        0x01010026 to "mimeType", 0x01010027 to "scheme", 0x01010028 to "host", 0x01010029 to "port", 0x0101002a to "path",
        0x0101002b to "pathPrefix", 0x0101002c to "pathPattern", 0x0101021b to "versionCode", 0x0101021c to "versionName",
        0x0101020c to "minSdkVersion", 0x01010270 to "targetSdkVersion", 0x01010271 to "maxSdkVersion", 0x010102b7 to "installLocation",
        0x01010280 to "allowBackup", 0x010104ec to "usesCleartextTraffic", 0x010104ea to "extractNativeLibs", 0x0101052c to "roundIcon",
        0x0101057a to "appComponentFactory", 0x01010603 to "requestLegacyExternalStorage", 0x01010202 to "targetActivity",
        0x010103af to "supportsRtl", 0x0101028e to "required", 0x01010599 to "foregroundServiceType", 0x01010505 to "directBootAware",
        0x010103a9 to "isolatedProcess", 0x0101048e to "multiArch", 0x0101035a to "largeHeap", 0x01010527 to "networkSecurityConfig",
        0x01010272 to "testOnly",
    )

    fun isAxml(b: ByteArray): Boolean = b.size >= 8 && (b[0].toInt() and 0xFF) == 0x03 && b[1].toInt() == 0x00

    fun parse(bytes: ByteArray): Doc {
        val r = BinReader(bytes)
        if (!isAxml(bytes)) throw FormatException("不是二进制 XML（AXML）")
        val headerSize = r.u16(2)
        val total = minOf(r.s32(4), bytes.size)
        var pool: ResStringPool? = null
        var resIds = IntArray(0)
        val nsPrefix = HashMap<String, String>()      // uri → prefix
        val pendingNs = mutableListOf<Pair<String, String>>()
        val stack = ArrayList<Element>()
        var root: Element? = null
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var depth = 0
        var p = headerSize
        fun str(i: Int): String? = if (i == -1) null else pool?.get(i)
        var guard = 0
        while (p + 8 <= total) {
            if (++guard > 2_000_000) throw FormatException("AXML 块数量异常")
            val type = r.u16(p); val hsz = r.u16(p + 2); val size = r.s32(p + 4)
            if (size < 8 || p.toLong() + size > total) throw FormatException("AXML 块长度错误 @0x${Integer.toHexString(p)}")
            when (type) {
                0x0001 -> pool = ResStringPool(r, p)
                0x0180 -> resIds = IntArray((size - hsz) / 4) { r.s32(p + hsz + it * 4) }
                0x0100 -> {
                    val prefix = str(r.s32(p + 16)) ?: ""; val uri = str(r.s32(p + 20)) ?: ""
                    nsPrefix[uri] = prefix; pendingNs += prefix to uri
                }
                0x0101 -> {}
                0x0102 -> {
                    val ext = p + hsz
                    val nsUri = str(r.s32(ext)); val name = str(r.s32(ext + 4)) ?: "?"
                    val attrStart = r.u16(ext + 8); val attrSize = r.u16(ext + 10); val attrCount = r.u16(ext + 12)
                    val attrs = ArrayList<Attr>(attrCount)
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * maxOf(attrSize, 20)
                        val ans = str(r.s32(a)); val nameIdx = r.s32(a + 4)
                        var an = str(nameIdx) ?: ""
                        if (an.isEmpty() && nameIdx >= 0 && nameIdx < resIds.size) an = ATTR_NAMES[resIds[nameIdx]] ?: "attr_0x${Integer.toHexString(resIds[nameIdx])}"
                        val rawIdx = r.s32(a + 8)
                        val vType = r.u8(a + 15); val vData = r.s32(a + 16)
                        val raw = if (rawIdx != -1) str(rawIdx) else if (vType == 0x03) str(vData) else null
                        attrs += Attr(ans, an, vType, vData, raw)
                    }
                    val el = Element(name, attrs)
                    if (stack.isEmpty()) { if (root == null) root = el } else stack.last().children += el
                    stack += el
                    out.append("  ".repeat(depth)).append('<').append(prefixed(nsUri, name, nsPrefix))
                    for ((pre, uri) in pendingNs) out.append(" xmlns").append(if (pre.isEmpty()) "" else ":$pre").append("=\"").append(esc(uri)).append('"')
                    pendingNs.clear()
                    for (a in attrs) {
                        out.append(if (attrs.size > 2) "\n" + "  ".repeat(depth + 2) else " ")
                        out.append(prefixed(a.ns, a.name, nsPrefix)).append("=\"").append(esc(a.value)).append('"')
                    }
                    out.append(">\n")
                    depth++
                }
                0x0103 -> {
                    val ext = p + hsz
                    val nsUri = str(r.s32(ext)); val name = str(r.s32(ext + 4)) ?: "?"
                    depth = maxOf(0, depth - 1)
                    out.append("  ".repeat(depth)).append("</").append(prefixed(nsUri, name, nsPrefix)).append(">\n")
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                0x0104 -> {
                    val t = str(r.s32(p + hsz)) ?: ""
                    stack.lastOrNull()?.text = t
                    out.append("  ".repeat(depth)).append(esc(t)).append('\n')
                }
            }
            p += size
        }
        return Doc(root, out.toString())
    }

    private fun prefixed(ns: String?, name: String, map: Map<String, String>): String {
        if (ns.isNullOrEmpty()) return name
        val pre = map[ns] ?: if (ns == ANDROID_NS) "android" else null
        return if (pre.isNullOrEmpty()) name else "$pre:$name"
    }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) { '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); else -> append(c) }
    }

    /** Res_value data → text (TYPE_REFERENCE 1, ATTRIBUTE 2, STRING 3, FLOAT 4, DIMENSION 5, FRACTION 6, INT_DEC 0x10, HEX 0x11, BOOL 0x12, colors 0x1c–0x1f). */
    fun formatValue(type: Int, data: Int): String = when (type) {
        0x00 -> ""
        0x01 -> "@0x" + "%08x".format(data)
        0x02 -> "?0x" + "%08x".format(data)
        0x04 -> java.lang.Float.intBitsToFloat(data).toString()
        0x05 -> complex(data, false)
        0x06 -> complex(data, true)
        0x10 -> data.toString()
        0x11 -> "0x" + Integer.toHexString(data)
        0x12 -> if (data != 0) "true" else "false"
        in 0x1c..0x1f -> "#" + "%08x".format(data)
        else -> "0x" + Integer.toHexString(data) + " (type " + type + ")"
    }

    private fun complex(data: Int, fraction: Boolean): String {
        val mant = (data shr 8).toFloat()
        val radix = (data shr 4) and 3
        val v = mant * floatArrayOf(1f / (1 shl 0), 1f / (1 shl 7), 1f / (1 shl 15), 1f / (1 shl 23))[radix]
        val unit = data and 0xF
        return if (fraction) "${v * 100}%" + if (unit == 1) "p" else ""
        else (if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()) +
            (listOf("px", "dp", "sp", "pt", "in", "mm").getOrNull(unit) ?: "")
    }
}
