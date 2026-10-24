package com.openminis.app.ui.plus

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RestartAlt
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupImporter
import com.openminis.app.backup.BackupManifest
import com.openminis.app.backup.BackupPackageReader
import com.openminis.app.backup.BackupZip
import com.openminis.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Move data from the earlier Mines install (package com.openminis.mines) into
 * this one, which installs beside it because the earlier APK was signed with
 * a throwaway key.
 *
 * Why it goes through a backup file and not a raw copy of the old data dir:
 *  - the earlier build is a release (non-debuggable) APK, so `run-as` refuses
 *    it even from the Shizuku shell uid, and the shell uid cannot read
 *    /data/data of another app;
 *  - provider API keys are sealed with the old app's own Android Keystore
 *    key, which never leaves that app, so copied files would arrive without
 *    usable keys anyway.
 * The old app's own backup export decrypts them and writes a portable
 * package, which this app's existing importer reads. Nothing here writes to
 * or deletes anything belonging to the old app.
 *
 * Providers and skills are imported first and verified; everything else in
 * the package follows on a best-effort basis.
 */
object MinesMigration {
    const val OLD_PACKAGE = "com.openminis.mines"
    private const val PREFS = "mines_migration"

    fun oldInstalled(ctx: Context): Boolean =
        ctx.packageName != OLD_PACKAGE && runCatching {
            ctx.packageManager.getApplicationInfo(OLD_PACKAGE, 0); true
        }.getOrDefault(false)

    fun oldDebuggable(ctx: Context): Boolean = runCatching {
        (ctx.packageManager.getApplicationInfo(OLD_PACKAGE, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }.getOrDefault(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun shouldPrompt(ctx: Context): Boolean = oldInstalled(ctx) && !prefs(ctx).getBoolean("prompted", false)
    fun markPrompted(ctx: Context) { prefs(ctx).edit().putBoolean("prompted", true).apply() }
    fun markDone(ctx: Context, summary: String) {
        prefs(ctx).edit().putBoolean("prompted", true).putString("last_summary", summary).apply()
    }
    fun lastSummary(ctx: Context): String? = prefs(ctx).getString("last_summary", null)

    fun openOldApp(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(OLD_PACKAGE) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun skillsDir(ctx: Context) = File(ctx.filesDir, "minis-global/skills")
    fun skillNames(ctx: Context): Set<String> =
        skillsDir(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.toSet()
            ?: emptySet()

    fun providerCount(ctx: Context): Int =
        (ctx.applicationContext as? MinisApp)?.providerRepositoryOrNull?.instances?.size ?: 0

    class Picked(val extracted: File, val manifest: BackupManifest) {
        val categories: Set<BackupCategory> =
            manifest.categories.keys.mapNotNull(BackupCategory::fromKey).toSet()
        val encrypted: Boolean get() = manifest.encryption != null
        val providersInPackage: Int get() = manifest.categories[BackupCategory.PROVIDERS.key]?.entries ?: 0
        val skillsInPackage: Int get() = manifest.categories[BackupCategory.SKILLS.key]?.entries ?: 0
        val hasKeys: Boolean? get() = manifest.categories[BackupCategory.PROVIDERS.key]?.includesCredentials
    }

    /** Copy the picked file to cache, unzip it and read its manifest. Off the main thread. */
    fun pick(ctx: Context, uri: Uri): Picked {
        val zip = File(ctx.cacheDir, "mines-migrate.minisbak")
        ctx.contentResolver.openInputStream(uri)?.use { inp -> zip.outputStream().use { inp.copyTo(it) } }
            ?: throw IllegalStateException("打不开这个文件")
        val out = File(ctx.cacheDir, "mines-migrate").apply { deleteRecursively(); mkdirs() }
        try {
            BackupZip.extract(zip, out)
        } finally {
            zip.delete()
        }
        val root = BackupZip.packageRoot(out)
        val manifest = BackupPackageReader(root).readManifest()
        return Picked(out, manifest)
    }

    data class Result(
        val providersNew: Int,
        val providersExisting: Int,
        val providersUnreadable: Int,
        val keysRestored: Int,
        val keysKept: Int,
        val providersNow: Int,
        val skillsInPackage: Int,
        val skillsVerified: Int,
        val skillsNew: Int,
        val others: List<String>,
        val problems: List<String>,
    ) {
        val providersMigrated: Int get() = providersNew + providersExisting
        fun headline(): String = "已迁移 $providersMigrated 个供应商、$skillsVerified 个技能"
    }

    suspend fun run(
        ctx: Context,
        picked: Picked,
        passphrase: String?,
        onStatus: (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val importer = BackupImporter(ctx, AppDatabase.getInstance(ctx))
        val problems = mutableListOf<String>()
        val skillsBefore = skillNames(ctx)

        // Phase 1: providers + skills, the two things that matter most.
        val first = setOf(BackupCategory.PROVIDERS, BackupCategory.SKILLS).intersect(picked.categories)
        if (first.isEmpty()) problems += "备份里没有供应商和技能"
        var pr: BackupImporter.CategoryReport? = null
        if (first.isNotEmpty()) {
            onStatus("正在迁移供应商和技能…")
            val r1 = importer.import(
                picked.extracted,
                BackupImporter.Options(categories = first, passphrase = passphrase),
                onProgress = { onStatus(it) },
            )
            pr = r1.categories.firstOrNull { it.category == BackupCategory.PROVIDERS.key }
            r1.categories.forEach { c -> c.failed?.let { problems += "${c.category}: $it" } }
            if (r1.integrityFailed.isNotEmpty()) problems += "${r1.integrityFailed.size} 个文件校验失败"
        }
        // Verify against what is actually on the device now.
        val skillsAfter = skillNames(ctx)
        val skillsNew = (skillsAfter - skillsBefore).size
        val skillsVerified = if (picked.skillsInPackage > 0) minOf(picked.skillsInPackage, skillsAfter.size) else skillsNew
        if (picked.skillsInPackage > skillsAfter.size) {
            problems += "备份里有 ${picked.skillsInPackage} 个技能，本机只找到 ${skillsAfter.size} 个"
        }

        // Phase 2: everything else, best effort.
        val rest = picked.categories - first
        val others = mutableListOf<String>()
        if (rest.isNotEmpty()) {
            onStatus("正在迁移其余内容…")
            runCatching {
                importer.import(
                    picked.extracted,
                    BackupImporter.Options(categories = rest, passphrase = passphrase),
                    onProgress = { onStatus(it) },
                )
            }.onSuccess { r2 ->
                r2.categories.forEach { c ->
                    if (c.failed != null) problems += "${c.category}: ${c.failed}"
                    else others += "${categoryName(c.category)} ${c.imported + c.updated}"
                }
            }.onFailure { problems += "其余内容：${it.message}" }
        }
        picked.extracted.deleteRecursively()

        Result(
            providersNew = pr?.imported ?: 0,
            providersExisting = pr?.skipped ?: 0,
            providersUnreadable = pr?.unreadable ?: 0,
            keysRestored = pr?.credentialsRestored ?: 0,
            keysKept = pr?.credentialsKept ?: 0,
            providersNow = providerCount(ctx),
            skillsInPackage = picked.skillsInPackage,
            skillsVerified = skillsVerified,
            skillsNew = skillsNew,
            others = others,
            problems = problems,
        )
    }

    private fun categoryName(key: String) = when (key) {
        BackupCategory.CHATS.key -> "对话"
        BackupCategory.MEMORY.key -> "记忆"
        BackupCategory.MCP_SERVERS.key -> "MCP"
        BackupCategory.SHARED_FILES.key -> "共享文件"
        BackupCategory.ENVIRONMENT_VARIABLES.key -> "环境变量"
        BackupCategory.VOICE_CORRECTIONS.key -> "语音纠错"
        else -> key
    }

    /** Relaunch so every repository re-reads what was restored. */
    fun restart(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

@Composable
fun MigrateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val oldInstalled = remember { MinesMigration.oldInstalled(context) }
    var picked by remember { mutableStateOf<MinesMigration.Picked?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<MinesMigration.Result?>(null) }
    val last = remember { MinesMigration.lastSummary(context) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null; result = null; status = "正在读取备份…"
        scope.launch {
            try {
                picked = withContext(Dispatchers.IO) { MinesMigration.pick(context, uri) }
            } catch (e: Exception) {
                error = "这个文件读不了：${e.message ?: "格式不对"}"
            } finally {
                busy = false; status = null
            }
        }
    }

    PlusPage("从旧版迁移", onBack) {
        Text(
            "把旧版 Mines 的供应商（地址、密钥、模型）和技能搬到这里，其余内容尽量一起带上。旧版的数据只读不改，迁完两个可以并存。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        if (last != null && result == null) {
            Text("上次：$last", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp))
        }

        PlusSection(
            "第一步 · 在旧版导出",
            footer = "在旧版里：设置 → 备份 → 勾选「供应商」「技能」（其余随意）→ 导出，建议设一个密码，存到「下载」。" +
                if (oldInstalled) "" else "\n没检测到旧版 Mines；如果已有备份文件，直接做第二步。",
        ) {
            ActionRow(Icons.Outlined.OpenInNew, if (oldInstalled) "打开旧版 Mines" else "旧版未安装") {
                if (!MinesMigration.openOldApp(context)) error = "打不开旧版 Mines"
            }
        }

        PlusSection("第二步 · 选择备份文件", footer = "支持 Minis / Mines 的 .minisbak 备份。") {
            ActionRow(Icons.Outlined.FileOpen, picked?.let { "已选：${it.manifest.deviceName} · ${it.manifest.createdAt.take(16)}" } ?: "选择备份文件") {
                if (!busy) pickLauncher.launch(arrayOf("*/*"))
            }
        }

        picked?.let { p ->
            PlusSection(
                "第三步 · 导入",
                footer = buildString {
                    append("备份里有 ${p.providersInPackage} 个供应商、${p.skillsInPackage} 个技能。")
                    if (p.hasKeys == false) append("\n注意：这个备份没带 API 密钥，导出时请勾上含密钥的选项。")
                },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.encrypted) {
                        OutlinedTextField(
                            passphrase, { passphrase = it }, label = { Text("备份密码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        enabled = !busy && (!p.encrypted || passphrase.isNotEmpty()),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val r = MinesMigration.run(context, p, passphrase.ifEmpty { null }) { status = it }
                                    result = r
                                    picked = null
                                    MinesMigration.markDone(context, r.headline())
                                } catch (e: Exception) {
                                    error = "导入失败：${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    busy = false; status = null
                                }
                            }
                        },
                    ) { Text("开始迁移") }
                }
            }
        }

        if (busy) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        error?.let {
            Text(it, color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }

        result?.let { r ->
            PlusSection("结果", footer = "重启后新数据全部生效。") {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.headline(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("供应商：新增 ${r.providersNew}，已存在 ${r.providersExisting}，本机现有 ${r.providersNow} 个；密钥写入 ${r.keysRestored} 个" +
                        if (r.keysKept > 0) "（保留本机已有 ${r.keysKept} 个）" else "",
                        style = MaterialTheme.typography.bodySmall)
                    Text("技能：备份 ${r.skillsInPackage} 个，本机确认 ${r.skillsVerified} 个（新增 ${r.skillsNew}）",
                        style = MaterialTheme.typography.bodySmall)
                    if (r.providersUnreadable > 0) Text("${r.providersUnreadable} 个供应商读不了，已跳过",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
                    if (r.others.isNotEmpty()) Text("其余：" + r.others.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                    r.problems.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500)) }
                }
                PlusDivider()
                ActionRow(Icons.Outlined.RestartAlt, "立即重启 Mines") { MinesMigration.restart(context) }
            }
        }
    }
}

/** One-time prompt on first launch when the earlier Mines is installed beside this one. */
@Composable
fun MinesMigrationPrompt(onOpen: () -> Unit) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(MinesMigration.shouldPrompt(context)) }
    if (!show) return
    AlertDialog(
        onDismissRequest = { MinesMigration.markPrompted(context); show = false },
        title = { Text("从旧版迁移？") },
        text = { Text("检测到旧版 Mines。要把它的供应商、技能和其他内容搬过来吗？旧版数据不会被改动。以后也可以在 设置 → Mines → 从旧版迁移 里做。") },
        confirmButton = {
            TextButton(onClick = { MinesMigration.markPrompted(context); show = false; onOpen() }) { Text("去迁移") }
        },
        dismissButton = {
            TextButton(onClick = { MinesMigration.markPrompted(context); show = false }) { Text("以后再说") }
        },
    )
}
