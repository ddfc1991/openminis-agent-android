package com.openminis.app.ui.plus

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.LoginCard
import com.openminis.app.plus.LoginMemoryCore.Field

/**
 * [Mines-38.9.5 i] the secure login card (user 22:42): glass bottom sheet, site logo + name, only the fields the page asks
 * for, 「保存到保险箱」 on by default, one primary button. Small type (MT tokens), no explanation paragraphs.
 */
@Composable
fun LoginCardHost() {
    val req by LoginCard.pending.collectAsState()
    val r = req ?: return
    val m = mines()
    val values = remember(r.id) { mutableStateMapOf<Field, String>().apply {
        r.ask.fields.firstOrNull { it == Field.ACCOUNT || it == Field.EMAIL || it == Field.PHONE }?.let { put(it, r.account) }
    } }
    var save by remember(r.id) { mutableStateOf(true) }
    var show by remember(r.id) { mutableStateOf(false) }
    val danger = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger
    MinesSheet(onDismiss = { LoginCard.answer(null) }, secure = !LoginCard.demoShown) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AcctLogo(r.host, r.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (r.task.isNotBlank()) Text(r.task, fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)   // [38.9.13] task name
                Text(r.name, fontSize = MT.Body, lineHeight = MT.SubLH, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.ask.title, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = if (r.ask.refused) danger else m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.Lock, null, tint = m.ink2, modifier = Modifier.size(16.dp))
        }
        r.lastLine?.let { line ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(line, fontSize = MT.Meta, lineHeight = MT.SubLH, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val acctField = r.ask.fields.firstOrNull { it == Field.ACCOUNT || it == Field.EMAIL || it == Field.PHONE }
                if (acctField != null) Text("换账号", fontSize = MT.Meta, color = m.primaryText, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { values[acctField] = "" }.padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        r.ask.fields.forEach { f ->
            val v = values[f].orEmpty()
            val secret = f == Field.PASSWORD
            val shape = RoundedCornerShape(14.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).heightIn(min = 46.dp).clip(shape)
                .background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, shape).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f.label, fontSize = MT.Caption, color = m.ink2, modifier = Modifier.width(56.dp), maxLines = 1)
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    BasicTextField(v, { values[f] = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = MT.Body, color = m.ink),
                        cursorBrush = SolidColor(m.primary),
                        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = when (f) {
                            Field.PASSWORD -> KeyboardType.Password; Field.PHONE -> KeyboardType.Phone
                            Field.EMAIL -> KeyboardType.Email; Field.CODE -> KeyboardType.Number; else -> KeyboardType.Text }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = f.label })
                }
                if (secret) Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { show = !show }
                    .semantics { contentDescription = if (show) "隐藏密码" else "显示密码" }, contentAlignment = Alignment.Center) {
                    Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = m.ink2, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (r.ask.fields.any { it != Field.CODE }) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp)
            .clickable { save = !save }.semantics(mergeDescendants = true) { contentDescription = "保存到密码库" },
            verticalAlignment = Alignment.CenterVertically) {
            Text("保存到密码库", fontSize = MT.Body, color = m.ink, modifier = Modifier.weight(1f))
            Switch(save, { save = it }, colors = SwitchDefaults.colors(checkedTrackColor = m.primary))
        }
        // [38.9.11.1] 验证码 is optional next to 账号 / 密码 (required only when it is the card's only field)
        val ready = r.ask.fields.all { values[it].orEmpty().isNotBlank() || (it == Field.CODE && r.ask.fields.size > 1) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
            GlassPill(if (r.ask.fields == listOf(Field.CODE)) "确定" else "提交", enabled = ready, modifier = Modifier.fillMaxWidth()) {
                val acct = r.ask.fields.firstOrNull { it == Field.ACCOUNT || it == Field.EMAIL || it == Field.PHONE }?.let { values[it] }?.trim() ?: r.account
                LoginCard.answer(LoginCard.Answer(acct, values[Field.PASSWORD].orEmpty(), values[Field.CODE].orEmpty().trim(), save))
            }
        }
    }
}
