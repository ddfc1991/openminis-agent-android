package com.openminis.app.files

import java.io.File

/**
 * [Mines-38.9.11 d] APK inspector: manifest (package / versions / sdk), permissions, components, native ABIs,
 * dex files, signatures. Everything is decoded in-process by the pure cores, so it works for any APK file the user
 * can read — not only installed packages.
 */
object ApkInfoCore {
    class Component(val kind: String, val name: String, val exported: Boolean?, val permission: String?, val intents: List<String>)

    class Info(
        val file: File,
        val packageName: String?,
        val versionCode: String?,
        val versionName: String?,
        val minSdk: String?, val targetSdk: String?, val compileSdk: String?,
        val label: String?,
        val debuggable: Boolean,
        val usesCleartext: Boolean?,
        val permissions: List<String>,
        val components: List<Component>,
        val abis: List<String>,
        val dexFiles: List<String>,
        val entryCount: Int,
        val totalSize: Long,
        val manifestXml: String,
        val signatures: ApkSigCore.Report?,
        val warnings: List<String>,
    ) {
        val launcher: String? get() = components.firstOrNull { it.intents.any { i -> i.contains("android.intent.category.LAUNCHER") } }?.name
        fun summaryRows(): List<Pair<String, String>> = buildList {
            add("包名" to (packageName ?: "？"))
            add("版本" to listOfNotNull(versionName, versionCode?.let { "($it)" }).joinToString(" ").ifBlank { "？" })
            add("系统要求" to listOfNotNull(minSdk?.let { "最低 API $it" }, targetSdk?.let { "目标 API $it" }).joinToString("、").ifBlank { "？" })
            add("大小" to HexCore.humanSize(totalSize))
            add("原生库" to (abis.joinToString("、").ifBlank { "无" }))
            add("dex" to "${dexFiles.size} 个")
            add("权限" to "${permissions.size} 项")
            signatures?.let { s ->
                add("签名方案" to (s.schemes.joinToString(" + ").ifBlank { "未签名" }))
                s.primary?.let { c -> add("证书 SHA-256" to c.sha256) }
            }
            if (debuggable) add("注意" to "这个包是可调试版本（debuggable）")
        }
    }

    /** Danger-first ordering: the permissions a user should see at the top, then the rest alphabetically. */
    val NOTABLE = listOf(
        "android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
        "android.permission.READ_CALL_LOG", "android.permission.CALL_PHONE",
        "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.SYSTEM_ALERT_WINDOW",
    )

    fun permissionZh(p: String): String = when (p) {
        "android.permission.INTERNET" -> "联网"
        "android.permission.READ_SMS", "android.permission.RECEIVE_SMS" -> "读取短信"
        "android.permission.SEND_SMS" -> "发送短信"
        "android.permission.READ_CONTACTS" -> "读取通讯录"
        "android.permission.WRITE_CONTACTS" -> "修改通讯录"
        "android.permission.ACCESS_FINE_LOCATION" -> "精确定位"
        "android.permission.ACCESS_COARSE_LOCATION" -> "大致定位"
        "android.permission.ACCESS_BACKGROUND_LOCATION" -> "后台定位"
        "android.permission.CAMERA" -> "相机"
        "android.permission.RECORD_AUDIO" -> "录音"
        "android.permission.READ_CALL_LOG" -> "读取通话记录"
        "android.permission.CALL_PHONE" -> "直接拨号"
        "android.permission.MANAGE_EXTERNAL_STORAGE" -> "管理所有文件"
        "android.permission.QUERY_ALL_PACKAGES" -> "查看已装应用"
        "android.permission.REQUEST_INSTALL_PACKAGES" -> "安装应用"
        "android.permission.SYSTEM_ALERT_WINDOW" -> "悬浮窗"
        "android.permission.POST_NOTIFICATIONS" -> "发通知"
        "android.permission.RECEIVE_BOOT_COMPLETED" -> "开机自启"
        "android.permission.FOREGROUND_SERVICE" -> "前台服务"
        "android.permission.WAKE_LOCK" -> "保持唤醒"
        else -> p.substringAfterLast('.')
    }

    fun sortPermissions(list: List<String>): List<String> =
        list.distinct().sortedWith(compareBy({ if (it in NOTABLE) NOTABLE.indexOf(it) else 1000 }, { it }))

    fun read(apk: File, withSignatures: Boolean = true): Info {
        val warnings = ArrayList<String>()
        var entries = 0
        val abis = sortedSetOf<String>()
        val dex = ArrayList<String>()
        java.util.zip.ZipFile(apk).use { z ->
            z.entries().asSequence().forEach { e ->
                entries++
                val n = e.name
                if (n.startsWith("lib/") && n.endsWith(".so")) n.split('/').getOrNull(1)?.let { abis += it }
                if (n.matches(Regex("""classes\d*\.dex"""))) dex += n
            }
        }
        val manifestBytes = runCatching { ZipCore.readEntry(apk, "AndroidManifest.xml") }.getOrNull()
        var doc: AxmlCore.Doc? = null
        if (manifestBytes == null) warnings += "包里没有 AndroidManifest.xml"
        else doc = runCatching { AxmlCore.parse(manifestBytes) }.onFailure { warnings += "清单解析失败：${it.message}" }.getOrNull()
        val root = doc?.root
        val app = root?.children?.firstOrNull { it.name == "application" }
        val perms = root?.all("uses-permission")?.mapNotNull { it.a("name") } ?: emptyList()
        val sdkEl = root?.all("uses-sdk")?.firstOrNull()
        val components = ArrayList<Component>()
        app?.children?.forEach { c ->
            if (c.name in setOf("activity", "activity-alias", "service", "receiver", "provider")) {
                val intents = c.all("intent-filter").flatMap { f ->
                    f.children.mapNotNull { x -> x.a("name")?.let { "${x.name}:$it" } }
                }
                components += Component(c.name, c.a("name") ?: "?", c.a("exported")?.let { it == "true" },
                    c.a("permission") ?: c.a("readPermission"), intents)
            }
        }
        val sig = if (withSignatures) runCatching { ApkSigCore.read(apk) }.onFailure { warnings += "签名读取失败：${it.message}" }.getOrNull() else null
        if (sig != null && sig.schemes.isEmpty()) warnings += "这个包没有有效签名，系统不会安装"
        return Info(
            file = apk,
            packageName = root?.a("package"),
            versionCode = root?.a("versionCode"),
            versionName = root?.a("versionName"),
            minSdk = sdkEl?.a("minSdkVersion"),
            targetSdk = sdkEl?.a("targetSdkVersion"),
            compileSdk = root?.attrs?.firstOrNull { it.name == "compileSdkVersion" }?.value,
            label = app?.a("label"),
            debuggable = app?.a("debuggable") == "true",
            usesCleartext = app?.a("usesCleartextTraffic")?.let { it == "true" },
            permissions = sortPermissions(perms),
            components = components,
            abis = abis.toList(),
            dexFiles = dex.sorted(),
            entryCount = entries,
            totalSize = apk.length(),
            manifestXml = doc?.xml ?: "",
            signatures = sig,
            warnings = warnings,
        )
    }

    fun isApk(f: File): Boolean = f.isFile && (f.extension.equals("apk", true) || f.extension.equals("apks", true) ||
        (runCatching { ZipCore.readEntry(f, "AndroidManifest.xml") != null }.getOrDefault(false)))
}
