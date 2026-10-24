package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.MemoryV2

/**
 * [Mines-38.9.13 memory_v2] 实验功能 switches for the on-phone directory memory.
 * Shown at the top of 记忆; the integrator may also call it from 设置 › 实验功能.
 */
@Composable
fun MemoryV2SettingsSection() {
    SettingsSection(
        header = "分类记忆（实验）",
        footer = "记忆按 项目 / 账号 / 偏好 / 机器 / 技能 分好，全部存在手机里。AI 只看目录摘要，需要时再翻原文，省 token。原来的记忆文件不改动；关掉就回到旧方式。",
    ) {
        MemoryV2FlagRows(lastDivider = false)
    }
    Spacer(modifier = Modifier.height(16.dp))
}

/** [Mines-38.9.13 integ] the two memory_v2 switches; also listed in 设置 → 实验功能. */
@Composable
fun MemoryV2FlagRows(lastDivider: Boolean) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(MemoryV2.isOn(context)) }
    var embed by remember { mutableStateOf(MemoryV2.embedOn(context)) }
    val hasEmbed = remember { MemoryV2.hasEmbeddingModel() }
    SettingsSwitchRow(
        title = "分类记忆",
        subtitle = "按目录找记忆，不再整段塞进对话",
        checked = on,
        onCheckedChange = { on = it; MemoryV2.setOn(context, it) },
        showDivider = on || lastDivider,
    )
    if (on) SettingsSwitchRow(
        title = "语义搜索",
        subtitle = if (hasEmbed) "用你已有的向量模型密钥，只在写入记忆时计算" else "需要先在账号里加硅基流动、智谱或 OpenAI 的密钥",
        checked = embed && hasEmbed,
        enabled = hasEmbed,
        onCheckedChange = { embed = it; MemoryV2.setEmbedOn(context, it) },
        showDivider = lastDivider,
    )
}
