package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AgentToolSwitch

/**
 * [T-tools-granular-switches] Settings › Agent Runtime › Tools — port of iOS
 * `ToolsSettingsView`.
 *
 * One switch per optional capability. The switches gate the tool SCHEMA, so a
 * disabled tool is not merely refused at call time — the model never sees it
 * and cannot plan around it. The remaining tools (shell, files, memory) are
 * core to the agent and deliberately have no switch.
 *
 * The title stays "Tools" in English to sit alongside Skills and Memory in the
 * Agent Runtime section; locales whose bare "tools" reads as hardware render
 * it as "available tools" instead.
 */
@Composable
fun AgentToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var browserEnabled by remember {
        mutableStateOf(AgentToolSwitch.BROWSER.isEnabled(context))
    }

    SettingsScaffold(title = stringResource(R.string.settings_agent_tools), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.agent_tools_browser_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_browser),
                checked = browserEnabled,
                onCheckedChange = {
                    browserEnabled = it
                    AgentToolSwitch.BROWSER.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        // [Mines-38.9.6 i17] 技能优先 (default on)
        var skillFirst by remember { mutableStateOf(AgentToolSwitch.SKILL_FIRST.isEnabled(context)) }
        SettingsSection(footer = null) {
            SettingsSwitchRow(
                title = "技能优先",
                checked = skillFirst,
                onCheckedChange = {
                    skillFirst = it
                    AgentToolSwitch.SKILL_FIRST.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        // [Mines-38.9.7] 高级工程模式 (default on)
        var engineer by remember { mutableStateOf(AgentToolSwitch.ENGINEER.isEnabled(context)) }
        SettingsSection(footer = null) {
            SettingsSwitchRow(
                title = "高级工程模式",
                checked = engineer,
                onCheckedChange = {
                    engineer = it
                    AgentToolSwitch.ENGINEER.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        // [Mines-38.9.9] 内置提示词: always on; each section can be edited / switched off / restored
        BuiltinPromptsSection()
        // [Mines-38.9.9] 自动调度 is automatic; this is only the hidden override (高级)
        var autoSched by remember { mutableStateOf(com.openminis.app.agent.ModelScore.enabled(context)) }
        SettingsSection(header = "高级", footer = "默认开：卡住时自动换更强的模型、跳过刚出错的模型、同一模型先换账号。一般不用动。") {
            SettingsSwitchRow(title = "自动调度模型", checked = autoSched, onCheckedChange = {
                autoSched = it; com.openminis.app.agent.ModelScore.setEnabled(context, it)
            }, showDivider = false)
        }
        // [T-android-subagent-settings-parity] The agents switch is NOT here.
        //
        // It lives on Settings > Agents > Sub Agents, which is also where the
        // roster is configured — one place to look, and no second copy of the
        // switch to drift out of sync with it. Mirrors iOS, whose
        // ToolsSettingsView likewise only migrates the legacy key and shows no
        // agents row.
    }
}

@Composable
private fun BuiltinPromptsSection() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<com.openminis.app.agent.BuiltinPrompts.Section?>(null) }
    SettingsSection(header = "内置提示词", footer = "每次对话都会带上，帮 Mines 排障更稳、做完自动归档。点一项可以改写、关掉或恢复默认。") {
        com.openminis.app.agent.BuiltinPrompts.SECTIONS.forEachIndexed { i, s ->
            val st = remember(tick) { com.openminis.app.agent.BuiltinPrompts.stored(context, s.key) }
            val on = st?.enabled ?: true
            SettingsRow(
                title = s.title,
                subtitle = if (!on) "已关" else if (st?.text != null) "已改写" else "默认",
                onClick = { editing = s },
                showDivider = i < com.openminis.app.agent.BuiltinPrompts.SECTIONS.size - 1,
            )
        }
    }
    editing?.let { s ->
        val st = remember(s) { com.openminis.app.agent.BuiltinPrompts.stored(context, s.key) }
        var text by remember(s) { mutableStateOf(com.openminis.app.agent.BuiltinPrompts.textOf(s, st)) }
        var on by remember(s) { mutableStateOf(st?.enabled ?: true) }
        com.openminis.app.ui.glass.AlertDialog(
            onDismissRequest = { editing = null },
            title = { androidx.compose.material3.Text(s.title) },
            text = {
                androidx.compose.foundation.layout.Column {
                    SettingsSwitchRow(title = "启用", checked = on, onCheckedChange = { on = it }, showDivider = false)
                    androidx.compose.material3.OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                        textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                com.openminis.app.ui.components.MinisTextButton(onClick = {
                    com.openminis.app.agent.BuiltinPrompts.save(context, s.key, text, on); tick++; editing = null
                }) { androidx.compose.material3.Text("保存") }
            },
            dismissButton = {
                com.openminis.app.ui.components.MinisTextButton(onClick = {
                    com.openminis.app.agent.BuiltinPrompts.reset(context, s.key); tick++; editing = null
                }) { androidx.compose.material3.Text("恢复默认") }
            },
        )
    }
}
