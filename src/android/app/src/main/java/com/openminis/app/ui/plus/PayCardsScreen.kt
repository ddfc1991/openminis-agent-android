package com.openminis.app.ui.plus

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.PayCards
import kotlinx.coroutines.launch

/**
 * [Mines-cards-35.1] 设置 → 支付卡. Opens only after fingerprint / screen lock. List shows brand + last four.
 * Add: 拍照识别 / 从相册选 (on-device ML Kit OCR, the photo is deleted right after) or type it in.
 */
@Composable
fun PayCardsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PayCards.load(context) }
    val cards by PayCards.cards.collectAsState()
    var unlocked by remember { mutableStateOf(PayCards.isUnlocked()) }
    var note by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<PayCards.Card?>(null) }
    var askImport by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!unlocked) PayCards.unlock(context, "打开支付卡") { ok, msg -> unlocked = ok; note = msg }
    }
    LaunchedEffect(unlocked) {
        if (unlocked) {
            val p = context.getSharedPreferences("mines_pay_cards", android.content.Context.MODE_PRIVATE)
            if (!p.getBoolean("vars_asked", false) && PayCards.cardVars(context).any { PayCards.luhn(it.third) }) askImport = true
        }
    }
    PlusPage("支付卡", onBack = onBack) {
        if (!unlocked) {
            PlusSection("需要验证") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(note ?: "验证指纹或锁屏密码后才能看和用你的卡", fontSize = MT.Body, color = mines().ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WarmPrimaryPill(if (note == null) "验证" else "再试一次", height = 44.dp, fontSize = 15, onClick = {
                            PayCards.unlock(context, "打开支付卡") { ok, msg -> unlocked = ok; note = msg } })
                        if (note != null) androidx.compose.material3.OutlinedButton(onClick = {
                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, modifier = Modifier.heightIn(min = 44.dp)) { Text("锁屏设置", fontSize = MT.Body) }
                    }
                }
            }
            return@PlusPage
        }
        note?.let { Text(it, fontSize = MT.Body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        PlusSection("我的卡", footer = "卡只存在这台手机里（加密），不会发给 AI、不会出现在对话里。网页上要付款时，点浏览器工具栏的「填卡」，Mines 只帮你填写，付款按钮永远由你自己点。") {
            if (cards.isEmpty()) Text("还没有卡", fontSize = MT.Body, modifier = Modifier.padding(16.dp))
            cards.forEach { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { edit = c }.padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "${c.brand} 尾号 ${c.last4}，有效期 ${c.expMonth} 月 ${c.expYear} 年，点按编辑" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label.ifBlank { c.masked }, fontSize = MT.Body, fontWeight = FontWeight.SemiBold)
                        Text("${c.masked} · ${c.expMonth}/${c.expYear}" + (if (c.name.isNotBlank()) " · ${c.name}" else ""), fontSize = MT.Caption, color = mines().ink2)
                    }
                    Text("›", fontSize = MT.PageTitle)
                }
            }
        }
        WarmPrimaryPill("＋ 添加卡", height = 48.dp, fontSize = 16, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            onClick = { edit = PayCards.Card(id = "", number = "", expMonth = "", expYear = "") })
    }
    edit?.let { c -> CardEditor(c, onDone = { edit = null }) }
    if (askImport) AlertDialog(onDismissRequest = { askImport = false },
        title = { Text("把「变量」里的卡移到支付卡？") },
        text = { Text("你之前把银行卡存在了 Mines 的「变量」里，那里 AI 的沙箱也能读到。移到「支付卡」更安全：加密保存、用前要验证。移过去后会删掉那些变量。", fontSize = MT.Body) },
        confirmButton = { TextButton(onClick = {
            context.getSharedPreferences("mines_pay_cards", android.content.Context.MODE_PRIVATE).edit().putBoolean("vars_asked", true).apply()
            val c = PayCards.importFromVars(context); askImport = false
            note = if (c != null) "已移入 ${c.masked}，变量里的卡已删除" else "没找到完整的卡号"
        }) { Text("移过去") } },
        dismissButton = { TextButton(onClick = {
            context.getSharedPreferences("mines_pay_cards", android.content.Context.MODE_PRIVATE).edit().putBoolean("vars_asked", true).apply()
            askImport = false
        }) { Text("先不用") } })
}

@Composable
private fun CardEditor(c0: PayCards.Card, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = c0.id.isEmpty()
    var number by remember { mutableStateOf(c0.number) }
    var mm by remember { mutableStateOf(c0.expMonth) }
    var yy by remember { mutableStateOf(c0.expYear) }
    var cvc by remember { mutableStateOf(c0.cvc) }
    var name by remember { mutableStateOf(c0.name) }
    var country by remember { mutableStateOf(c0.country) }
    var line1 by remember { mutableStateOf(c0.line1) }
    var city by remember { mutableStateOf(c0.city) }
    var state by remember { mutableStateOf(c0.state) }
    var postal by remember { mutableStateOf(c0.postal) }
    var label by remember { mutableStateOf(c0.label) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val camFile = remember { java.io.File(context.filesDir, "camera-photos/card_scan.jpg") }

    fun apply(s: PayCards.Scan) {
        s.number?.let { number = it }; s.expMonth?.let { mm = it }; s.expYear?.let { yy = it }
        s.name?.let { if (name.isBlank()) name = it }; s.cvc?.let { if (cvc.isBlank()) cvc = it }
        msg = if (s.number != null) "识别好了，请核对一下" + (if (s.cvc == null) "。背面的安全码（CVC）请你自己填" else "")
            else "没认出卡号。换个角度、光线亮一点再拍，或直接手动填"
    }
    fun scan(uri: android.net.Uri, deleteAfter: java.io.File?) {
        busy = true; msg = "正在本机识别（照片不会上传）…"
        scope.launch {
            val r = runCatching { CardOcr.read(context, uri) }
            deleteAfter?.delete()
            busy = false
            r.onSuccess { apply(PayCards.parseOcr(it)) }.onFailure { msg = "识别失败：${it.message?.take(60)}。可以手动填" }
        }
    }
    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scan(android.net.Uri.fromFile(camFile), camFile) else camFile.delete()
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) scan(uri, null) }

    AlertDialog(onDismissRequest = onDone,
        title = { Text(if (isNew) "添加卡" else "编辑卡") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        camFile.parentFile?.mkdirs()
                        val u = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", camFile)
                        runCatching { takePic.launch(u) }.onFailure { msg = "打不开相机：${it.message?.take(40)}" }
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("拍照识别", fontSize = MT.Body) }
                    OutlinedButton(enabled = !busy, onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("从相册选", fontSize = MT.Body) }
                }
                msg?.let { Text(it, fontSize = MT.Body) }
                OutlinedTextField(number, { number = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(23) }, label = { Text("卡号") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mm, { mm = it.filter(Char::isDigit).take(2) }, label = { Text("月 MM") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(yy, { yy = it.filter(Char::isDigit).take(4) }, label = { Text("年 YY") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(cvc, { cvc = it.filter(Char::isDigit).take(4) }, label = { Text("CVC") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(name, { name = it }, label = { Text("持卡人姓名（英文）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("账单地址（网站要时才填）", fontSize = MT.Body)
                OutlinedTextField(country, { country = it.uppercase().take(2) }, label = { Text("国家代码，如 CN / US") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(line1, { line1 = it }, label = { Text("地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(city, { city = it }, label = { Text("城市") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(state, { state = it }, label = { Text("省/州") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(postal, { postal = it }, label = { Text("邮编") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(label, { label = it }, label = { Text("备注名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!isNew) TextButton(onClick = { PayCards.delete(context, c0.id); onDone() }) { Text("删除这张卡") }
            }
        },
        confirmButton = { TextButton(onClick = {
            val d = PayCards.digits(number)
            when {
                !PayCards.luhn(d) -> msg = "卡号好像不对（校验没通过），请再核对一下"
                mm.toIntOrNull() !in 1..12 || yy.length < 2 -> msg = "有效期不完整"
                else -> {
                    PayCards.upsert(context, PayCards.Card(id = c0.id.ifEmpty { java.util.UUID.randomUUID().toString() }, number = d,
                        expMonth = mm.padStart(2, '0'), expYear = yy.takeLast(2), cvc = cvc, name = name.trim(), country = country.trim(),
                        line1 = line1.trim(), city = city.trim(), state = state.trim(), postal = postal.trim(), label = label.trim()))
                    onDone()
                }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDone) { Text("取消") } })
}

/** On-device text recognition (ML Kit bundled Latin model). The image never leaves the phone. */
object CardOcr {
    suspend fun read(context: android.content.Context, uri: android.net.Uri): String =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val img = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
            val rec = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            rec.process(img)
                .addOnSuccessListener { t -> rec.close(); if (cont.isActive) cont.resumeWith(Result.success(t.text)) }
                .addOnFailureListener { e -> rec.close(); if (cont.isActive) cont.resumeWith(Result.failure(e)) }
        }
}
