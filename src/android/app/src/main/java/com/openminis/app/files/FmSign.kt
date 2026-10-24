package com.openminis.app.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.plus.GlassPill
import com.openminis.app.ui.plus.GlassSecondaryPill
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.MinesSheet
import com.openminis.app.ui.plus.glassFieldColors
import com.openminis.app.ui.plus.mines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [Mines-38.9.11 d] 重新签名: pick a keystore (or make one), enter its password in a secure sheet, sign (align → v1+v2+v3
 * → verify). [source] is an APK or an unpacked APK folder (which is repacked first).
 *
 * This is for the user's own or authorised packages; the sheet says so, and nothing here removes or bypasses another
 * app's signature, licence or DRM check.
 */
@Composable
internal fun FmSignSheet(source: File, isFolder: Boolean, minSdk: Int = 21, packageName: String? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    val keyDir = remember { File(FileRoots.work(ctx), "签名密钥").apply { mkdirs() } }
    var stores by remember { mutableStateOf<List<File>>(emptyList()) }
    var picked by remember { mutableStateOf<File?>(null) }
    var pass by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf<String?>(null) }
    var aliases by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("我的签名.p12") }
    var newPass by remember { mutableStateOf("") }
    var newCn by remember { mutableStateOf("Mines") }
    val ownPackages = remember { setOf(ctx.packageName) }

    fun rescan() {
        stores = buildList {
            keyDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("p12", "pfx", "jks", "keystore", "bks") }?.sortedBy { it.name }?.let { addAll(it) }
            source.parentFile?.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("p12", "pfx", "jks", "keystore") }?.let { addAll(it) }
        }.distinctBy { it.absolutePath }
    }
    LaunchedEffect(Unit) { rescan() }
    LaunchedEffect(picked, pass) {
        val p = picked
        aliases = emptyList(); alias = null
        if (p != null && pass.isNotEmpty()) {
            withContext(Dispatchers.IO) { runCatching { ApkResignCore.aliases(p, pass.toCharArray()) } }
                .onSuccess { aliases = it; alias = it.firstOrNull(); status = if (it.isEmpty()) "这个密钥库里没有条目" else null }
                .onFailure { status = it.message ?: "打不开密钥库" }
        }
    }

    // credential sheet: FLAG_SECURE (no screenshots of the password)
    MinesSheet(onDismiss = onDismiss, secure = true) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isFolder) "回编译并签名" else "重新签名", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink)
            Text(source.name, fontSize = 12.sp, color = m.ink2, fontFamily = FontFamily.Monospace)
            if (packageName != null) {
                val notice = ApkResignCore.authorisationNotice(
                    ApkInfoCore.Info(source, packageName, null, null, null, null, null, null, false, null, emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, "", null, emptyList()),
                    ownPackages,
                )
                if (notice != null) Text(notice, fontSize = 12.sp, lineHeight = 17.sp, color = m.ink2)
            }

            if (creating) {
                Text("新建一个签名密钥（存在共享文件 › 文件管理 › 签名密钥）", fontSize = MT.Sub, color = m.ink)
                OutlinedTextField(newName, { newName = it }, singleLine = true, colors = glassFieldColors(), label = { Text("文件名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newCn, { newCn = it }, singleLine = true, colors = glassFieldColors(), label = { Text("证书名字（随便写）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newPass, { newPass = it }, singleLine = true, colors = glassFieldColors(), label = { Text("密码（至少 6 位，自己记好）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill("创建", enabled = newPass.length >= 6 && newName.isNotBlank() && !busy) {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    val f = File(keyDir, if (newName.contains('.')) newName else "$newName.p12")
                                    SelfSignedCore.createKeystore(f, newPass.toCharArray(), "mines", newCn.ifBlank { "Mines" }); f
                                }
                            }
                            busy = false
                            r.onSuccess { f -> creating = false; rescan(); picked = f; pass = newPass; status = "已创建「${f.name}」" }
                                .onFailure { status = it.message ?: "创建失败" }
                        }
                    }
                    GlassSecondaryPill("取消") { creating = false }
                }
            } else {
                Text("选一个签名密钥", fontSize = MT.Sub, color = m.ink2)
                if (stores.isEmpty()) Text("还没有密钥文件。可以新建一个，或把 .p12 / .jks 放到共享文件 › 文件管理 › 签名密钥。", fontSize = 12.sp, color = m.ink2)
                stores.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { picked = f; status = null }.padding(vertical = 6.dp)) {
                        Text((if (picked == f) "● " else "○ ") + f.name, fontSize = MT.Sub, color = if (picked == f) m.primaryText else m.ink, modifier = Modifier.weight(1f))
                        Text(HexCore.humanSize(f.length()), fontSize = 11.sp, color = m.ink2)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassSecondaryPill("新建密钥") { creating = true }
                    GlassSecondaryPill("刷新列表") { rescan() }
                }
                if (picked != null) {
                    OutlinedTextField(pass, { pass = it }, singleLine = true, colors = glassFieldColors(), label = { Text("密钥库密码") },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (aliases.size > 1) {
                        Text("条目", fontSize = 12.sp, color = m.ink2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            aliases.forEach { a -> FmChip(a, alias == a) { alias = a } }
                        }
                    }
                }
            }

            status?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = if (it.startsWith("✓") || it.startsWith("已")) m.primaryText else Color(0xFFD9534F)) }

            if (!creating) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(if (busy) "正在签名…" else "签名", enabled = !busy && picked != null && pass.isNotEmpty() && aliases.isNotEmpty()) {
                    busy = true; status = null
                    val ks = picked!!; val pw = pass.toCharArray(); val al = alias
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val key = ApkResignCore.loadKey(ks, pw, al)
                                val parent = (if (isFolder) source.parentFile else source.parentFile) ?: FileRoots.work(ctx)
                                val baseName = (if (isFolder) source.name else source.nameWithoutExtension) + "-signed.apk"
                                val out = FileOpsCore.uniqueTarget(parent, baseName)
                                val input = if (isFolder) {
                                    val tmp = File(ctx.cacheDir, "fm-repack.apk").apply { delete() }
                                    ApkResignCore.repack(source, tmp)
                                    tmp
                                } else source
                                val res = ApkResignCore.sign(input, out, key, minSdk = minSdk, workDir = ctx.cacheDir)
                                if (isFolder) input.delete()
                                res
                            }
                        }
                        busy = false
                        r.onSuccess { res -> status = (if (res.verified) "✓ " else "") + res.message + "\n新文件：" + res.out.name; Fm.toast(ctx, res.message) }
                            .onFailure { status = "签名失败：" + (it.message ?: it.javaClass.simpleName) }
                    }
                }
                GlassSecondaryPill("关闭") { onDismiss() }
            }
            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}
