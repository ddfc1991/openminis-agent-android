package com.openminis.app.ui.settings

import com.openminis.app.ui.plus.MT

import com.openminis.app.ui.brand.MinesIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.glass.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.theme.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl
import com.openminis.app.i18n.uppercaseForDisplay

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onRootfsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    /** [T-p2-agent-settings] Settings › Agents (delegation on/off), below Memory. */
    onAgentsClick: () -> Unit = {},
    onAgentToolsClick: () -> Unit = {},
    // [T-mcp-integration-android] MCP Integrations page, listed directly below
    // Memory. Default no-op for callers that haven't wired the route yet.
    onMcpClick: () -> Unit = {},
    // [T-soul-md] Soul settings page lives between Skills and Memory in the
    // Agent Runtime section; default no-op for callers that haven't wired
    // the route yet.
    onSoulClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    // T219-2: Mount External Folders entry. Default no-op for any caller
    // that hasn't wired the route yet.
    onMountedFoldersClick: () -> Unit = {},
    // T235: Shared Folders entry (Shared / Skills / Memory). Default no-op
    // for back-compat with callers wired before T235.
    onSharedFoldersClick: () -> Unit = {},
    // T50: Background & Notifications screen (battery optimisation +
    // OEM autostart guidance). Default no-op so older callers/tests
    // don't need to be retrofitted.
    onBackgroundClick: () -> Unit = {},
    // Hook accepted for forward-compat with AppNavigation's About route. The
    // About row below still has a TODO onClick in HEAD; future settings-bucket
    // work will wire this through.
    onAboutClick: () -> Unit = {},
    /** Minis Plus pages (work location, connections, wallpaper & avatar). */
    onPlusClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var showFeedbackSheet by remember { mutableStateOf(false) }
    // [Mines-warm-36] design36warm 05: settings merged into 6 groups (常用: 语音 / 账号与 AI 服务 / 网络;
    // 其他: 数据 / 外观 / 测试与上报). Top level shows the 6 rows; a group opens its merged list in place.
    var grp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = grp != null) { grp = null }
    val grpTitle = when (grp) { "ai" -> "账号与 AI 服务"; "net" -> "网络"; "data" -> "数据"; "look" -> "外观"; "test" -> "测试与上报"; else -> null }
    val acctTodo = com.openminis.app.plus.Digest.items.collectAsState().value.count { it.tab == com.openminis.app.plus.Digest.Tab.ACCOUNT }
    // [Mines-design-34] 「暖瓷 · 毛玻璃」 settings (design33 06-settings): warm backdrop, 22 sp title,
    // glass groups, accent line icons in tinted circles, 17 sp rows.
    val mc = com.openminis.app.ui.plus.mines()
    Box(Modifier.fillMaxSize()) {
    com.openminis.app.ui.plus.MinesBackdrop(Modifier.fillMaxSize())
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            com.openminis.app.ui.theme.TopAppBar(
                title = { Text(grpTitle ?: stringResource(R.string.settings_title), fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = mc.ink, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { if (grp != null) grp = null else onBack() }) {
                        MinesIcon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = mc.ink,
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            if (grp == null) {
            // [Mines-design-34] profile card: character + build
            // [Mines-38.1] board 05 personal card: the user's name (tap to set how Mines greets you) + build
            var nameEdit by remember { mutableStateOf(false) }
            var who by remember { mutableStateOf(com.openminis.app.ui.plus.MinesProfile.name(context)) }
            // [Mines-38.9.4] user 2026-10-17 「就好像苹果手机那样，账号，然后后面就是账号登录的，关联的」: the top card is the 账号 card
            // (like the Apple ID row): tap → the merged 账号 page (服务 / 登录); long-press → how Mines greets you.
            val nSvc = remember { runCatching { com.openminis.app.plus.PlusVault.init(context); com.openminis.app.plus.PlusVault.entries.value.size }.getOrDefault(0) }
            com.openminis.app.ui.plus.MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp)
                    .combinedClickable(onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS) }, onLongClick = { nameEdit = true }, onLongClickLabel = "改称呼")
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "账号，登录、关联、$nSvc 个服务" + if (acctTodo > 0) "，$acctTodo 个要处理" else "" },
                    verticalAlignment = Alignment.CenterVertically) {
                    val initial = who.trim().take(1)
                    if (initial.isNotEmpty()) Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(mc.container), contentAlignment = Alignment.Center) {
                        Text(initial, fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = mc.onContainer)
                    } else com.openminis.app.ui.plus.PetAvatar(size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("账号", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = mc.ink, maxLines = 1)
                        Text("登录 · 关联 · $nSvc 个服务" + if (acctTodo > 0) " · $acctTodo 个要处理" else "", fontSize = MT.Caption, color = mc.ink2, maxLines = 1)
                    }
                    MinesIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = mc.ink3, modifier = Modifier.size(18.dp))
                }
            }
            if (nameEdit) {
                var t by remember { mutableStateOf(who) }
                com.openminis.app.ui.glass.AlertDialog(onDismissRequest = { nameEdit = false },
                    title = { Text("怎么称呼你？", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold) },
                    text = { androidx.compose.material3.OutlinedTextField(t, { t = it.take(20) }, singleLine = true, placeholder = { Text("例如：杨arling") }) },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = {
                        com.openminis.app.ui.plus.MinesProfile.setName(context, t); who = t.trim(); nameEdit = false }) { Text("好") } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { nameEdit = false }) { Text("取消") } })
            }
            SettingsSection(title = "常用") {
                SettingsItem(icon = Icons.Outlined.Mic, iconColor = Color(0xFF5856D6), title = "语音",
                    subtitle = "每个 AI 的声音 · 声纹 · 通话线路",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.VOICE_SETTINGS) })
                SettingsItem(icon = Icons.Outlined.Key, iconColor = Color(0xFFFF9500), title = "账号与 AI 服务",
                    subtitle = "账号 · AI 服务商 · 模型 · 技能与插件",
                    alert = if (acctTodo > 0) "$acctTodo 个要处理" else null,
                    onClick = { grp = "ai" })
                // [Mines-38.7] 一键关联 (all AI platforms, phone + SMS code filled in) and 手机权限 (what each permission is for)
                // [Mines-38.9] 账号关联 keychain (一键关联 is its + button) and 应用代理
                // [Mines-38.9.3] one 「账号」 page (服务 / 登录 tabs) replaces 账号连接 + 账号关联
                // [Mines-38.9.4] its row here was a duplicate of the 账号 card at the top (same page) — removed
                SettingsItem(icon = Icons.Outlined.SmartToy, iconColor = Color(0xFF6A4BB0), title = "应用代理",
                    subtitle = "把任务交给你注册过的 AI 服务，省 token",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.AGENTS) })
                // [Mines-38.9.1] 已忽略的平台 moved into 账号关联 (user 2026-10-16 「这个应该收纳到账号关联」)
                SettingsItem(icon = Icons.Outlined.Shield, iconColor = Color(0xFF007AFF), title = "手机权限",
                    subtitle = "开了哪些、用来做什么，没开的一步步带你开",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PermGuide.ROUTE) })
                SettingsItem(icon = Icons.Outlined.Cloud, iconColor = Color(0xFF34C759), title = "网络",
                    subtitle = "电脑与服务器 · 模型路由与分流 · 浏览器验证",
                    onClick = { grp = "net" }, showDivider = false)
            }
            SettingsSection(title = "其他") {
                SettingsItem(icon = Icons.Outlined.Book, iconColor = Color(0xFFAF52DE), title = "数据",
                    subtitle = "知识库 · 对话整理 · 存储与备份 · 迁移",
                    onClick = { grp = "data" })
                SettingsItem(icon = Icons.Outlined.Palette, iconColor = Color(0xFF5856D6), title = "外观",
                    subtitle = "壁纸与形象 · 文字大小 · 主题 · 桌宠",
                    onClick = { grp = "look" })
                SettingsItem(icon = Icons.Outlined.BugReport, iconColor = Color(0xFFFF9500), title = "测试与上报",
                    subtitle = "自检 · 问题上报 · 更新 · 沙箱与权限 · 关于",
                    onClick = { grp = "test" }, showDivider = false)
            }
            }

            if (grp == "ai") {
            SettingsSection(title = "账号", footer = "密钥和登录加密保存在这台手机里，AI 看不到明文。") {
                SettingsItem(
                    icon = Icons.Outlined.Key,
                    iconColor = Color(0xFFFF9500),
                    title = "账号",
                    subtitle = "服务（AI 模型、开发、办公、金融、社交）和网站登录",
                    alert = if (acctTodo > 0) "$acctTodo 个要处理" else null,
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS) },
                )
                // [Mines-38.9.5 i] 验证码来源 + AI 邮箱
                SettingsItem(
                    icon = Icons.Outlined.Email,
                    iconColor = Color(0xFF34AADC),
                    title = "验证码与 AI 邮箱",
                    subtitle = "登录时从你的邮箱读验证码；给 Mines 一个收码邮箱",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.CODESRC) },
                )
                SettingsItem(
                    icon = Icons.Outlined.VerifiedUser,
                    iconColor = Color(0xFF5856D6),
                    title = "支付卡",
                    subtitle = "加密存在本机；网页付款时一键填卡，付款按钮由你自己点",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.CARDS) },
                    showDivider = false,
                )
            }
            SettingsSection(title = "技能与插件") {
                SettingsItem(
                    icon = Icons.Outlined.Storefront,
                    iconColor = Color(0xFFFF2D55),
                    title = "技能与市场",
                    subtitle = "一键装技能、插件和 MCP 服务",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.MARKET) },
                )
                SettingsItem(
                    icon = Icons.Outlined.Extension,
                    iconColor = Color(0xFF30B0C7),
                    title = "插件",
                    subtitle = "安装 dsh 插件，给 AI 加新工具",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.PLUGINS) },
                )
                // [Mines-38.9] 经验库: what Mines learned by itself (做法 / 教训), any model uses it
                SettingsItem(
                    icon = Icons.Outlined.Lightbulb,
                    iconColor = Color(0xFFFF9500),
                    title = "经验",
                    subtitle = "Mines 自己学会的做法和教训，可看可改可删",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.EXPERIENCE) },
                )
                // [Mines-38.9.8] 工作流记录: every run (task, steps, result, time); one tap saves it as a skill
                SettingsItem(
                    icon = Icons.Outlined.History,
                    iconColor = Color(0xFF34C759),
                    title = "工作流记录",
                    subtitle = "做过的事和步骤，可以一键存成技能",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.WORKFLOWS) },
                    showDivider = false,
                )
            }
            }

            if (grp == "net") {
            SettingsSection(title = "网络", footer = "工作位置决定任务在手机还是服务器上跑。") {
                SettingsItem(
                    icon = Icons.Outlined.Cloud,
                    iconColor = Color(0xFF34C759),
                    title = "电脑与服务器",
                    subtitle = "本机 / 多台服务器，自动选最快的",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.WORK) },
                )
                SettingsItem(
                    icon = Icons.Outlined.Speed,
                    iconColor = Color(0xFF34C759),
                    title = "模型路由与分流",
                    subtitle = "自动选最快的供应商；按网站走代理或直连",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.ROUTING) },
                )
                SettingsItem(
                    icon = Icons.Outlined.VerifiedUser,
                    iconColor = Color(0xFFFF9500),
                    title = "浏览器验证",
                    subtitle = "遇到验证码叫你点一下；自动填短信验证码",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.VERIFY) },
                    showDivider = false,
                )
            }
            }

            if (grp == "data") {
            SettingsSection(title = "资料与对话") {
                // [Mines-38.9.11 d] built-in MT-style file + APK manager (its own activity)
                val fmCtx = androidx.compose.ui.platform.LocalContext.current
                SettingsItem(
                    icon = Icons.Outlined.Description,
                    iconColor = Color(0xFFFF9500),
                    title = "文件管理",
                    subtitle = "共享文件、对话工作区、手机存储；看 APK、改文本、解压",
                    onClick = { com.openminis.app.files.FileManagerActivity.open(fmCtx) },
                )
                SettingsItem(
                    icon = Icons.Outlined.Book,
                    iconColor = Color(0xFFAF52DE),
                    title = "知识库",
                    subtitle = "导入 PDF、文档、网页，AI 回答时先查你的资料",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.KNOWLEDGE) },
                )
                SettingsItem(
                    icon = Icons.Outlined.Inventory2,
                    iconColor = Color(0xFF8E8E93),
                    title = "对话整理",
                    subtitle = "自动归档旧对话，按话题分组",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.TIDY) },
                )
                SettingsItem(
                    icon = Icons.Outlined.Backup,
                    iconColor = Color(0xFF8E8E93),
                    title = "从旧版迁移",
                    subtitle = "搬来旧版 Mines 的供应商、技能和其他内容",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.MIGRATE) },
                    showDivider = false,
                )
            }
            }

            if (grp == "look") {
            SettingsSection(title = "形象") {
                SettingsItem(
                    icon = Icons.Outlined.Wallpaper,
                    iconColor = Color(0xFFAF52DE),
                    title = "壁纸与形象",
                    subtitle = "壁纸、卡片文字颜色、虚拟形象、桌宠",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.PERSONALIZE) },
                    showDivider = false,
                )
            }
            }

            if (grp == "test") {
            SettingsSection(title = "自检与更新") {
                SettingsItem(
                    icon = Icons.Outlined.VerifiedUser,
                    iconColor = Color(0xFFFF9500),
                    title = "一键自检 + 上报",
                    subtitle = "检查语音、模型、权限、沙箱、MCP；有问题一键发送",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.REPORT) },
                )
                SettingsItem(
                    icon = Icons.Outlined.SystemUpdate,
                    iconColor = Color(0xFF007AFF),
                    title = "检查更新",
                    subtitle = if (com.openminis.app.plus.MinesUpdate.currentBuild > 0) "当前 build-${com.openminis.app.plus.MinesUpdate.currentBuild}，可一键回退" else "从 GitHub 下载新版，可一键回退",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.UPDATE) },
                )
                SettingsItem(
                    icon = Icons.Outlined.Memory,
                    iconColor = Color(0xFF007AFF),
                    title = "沙箱与防杀",
                    subtitle = "看内存、一键清理、防止被系统杀掉",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.SANDBOX) },
                    showDivider = false,
                )
            }
            // [Mines-38.9.13] 实验功能: each feature of this release can be switched off
            SettingsSection(title = "实验功能") {
                com.openminis.app.ui.plus.VpsPageFlagRow()
            }
            // [Mines-38.9.11.3] 远程诊断 — off by default, 24 h auto-off
            SettingsSection(title = "远程诊断", footer = "打开后，把卡顿、内存和崩溃信息发给开发者看（密钥、密码和聊天内容都会隐去）。对方只能做几项固定检查，不能操作手机；要截图会先问你。24 小时后自动关闭。") {
                com.openminis.app.ui.plus.DiagSettingsRow()
            }
            }

            if (grp == "ai") {
            // -- LLM Providers --
            SettingsSection(
                title = stringResource(R.string.settings_section_llm_providers),
                footer = stringResource(R.string.settings_section_llm_providers_footer),
            ) {
                SettingsItem(
                    icon = Icons.Outlined.Lock,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_manage_providers),
                    subtitle = stringResource(R.string.settings_manage_providers_subtitle),
                    onClick = onProvidersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Settings,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_model_groups),
                    subtitle = stringResource(R.string.settings_model_groups_subtitle),
                    onClick = onModelGroupsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.BarChart,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_token_usage),
                    subtitle = stringResource(R.string.settings_token_usage_subtitle),
                    onClick = onUsageClick,
                    showDivider = false,
                )
            }

            }

            if (grp == "look") {
            // [Mines-38.9] global 外观调节: glass transparency, fog strength, accent colour (light / dark separately)
            SettingsSection(title = "外观调节") {
                SettingsItem(
                    icon = Icons.Outlined.Tune,
                    iconColor = Color(0xFFB5532F),
                    title = "玻璃、雾气和主题色",
                    subtitle = "透明度、雾气浓淡、主题色；浅色深色分开调",
                    onClick = { onPlusClick(com.openminis.app.ui.plus.PlusRoutes.LOOK) },
                    showDivider = false,
                )
            }
            // -- Appearance --
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                SettingsItem(
                    icon = Icons.Outlined.Palette,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_section_appearance),
                    subtitle = stringResource(R.string.settings_appearance_subtitle),
                    onClick = onAppearanceClick,
                    showDivider = false,
                )
            }

            }

            if (grp == "ai") {
            // -- Agent Runtime --
            // [T-android-settings-runtime-order] Row order matches iOS
            // (ContentView.swift, Section "Agent Runtime"): Agent Tools, Sub
            // Agents, Skills, Soul, Memory, MCP, Environment Variables.
            //
            // The order is not alphabetical or historical — it runs from what
            // the agent may DO (tools, and who it may hand work to) to what it
            // is made OF (skills, identity, memory) and finally to what it
            // connects to (MCP, env). Android had grown its own order simply by
            // appending each row as it landed, which put the two capability
            // switches in the middle of the identity rows.
            SettingsSection(title = stringResource(R.string.settings_section_agent_runtime)) {
                // [T-tools-granular-switches] Which optional tools the agent may use.
                SettingsItem(
                    icon = Icons.Outlined.Handyman,
                    iconColor = Color(0xFF5E5CE6),
                    title = stringResource(R.string.settings_agent_tools),
                    subtitle = stringResource(R.string.settings_agent_tools_subtitle),
                    onClick = onAgentToolsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Groups,
                    iconColor = com.openminis.app.ui.chat.HelperAccentStatic,
                    title = stringResource(R.string.settings_agents),
                    subtitle = stringResource(R.string.settings_agents_subtitle),
                    onClick = onAgentsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Extension,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_skills),
                    subtitle = stringResource(R.string.settings_skills_subtitle),
                    onClick = onSkillsClick,
                )
                // [T-soul-md] Between Skills and Memory, as on iOS.
                SettingsItem(
                    icon = Icons.Outlined.AutoAwesome,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.settings_soul),
                    subtitle = stringResource(R.string.settings_soul_subtitle),
                    onClick = onSoulClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Psychology,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_memory),
                    subtitle = stringResource(R.string.settings_memory_subtitle),
                    onClick = onMemoryClick,
                )
                // [T-mcp-integration-android] MCP Integrations — below Memory.
                // [T-android-mcp-icon-distinct] Dashboard (2x2 block grid) instead of
                // Extension so MCP no longer shares the Skills row's puzzle-piece icon —
                // the grid reads as "multiple composed blocks/servers". teal unchanged.
                SettingsItem(
                    icon = Icons.Outlined.Dashboard,
                    iconColor = Color(0xFF30B0C7),
                    title = stringResource(R.string.settings_mcp),
                    subtitle = stringResource(R.string.settings_mcp_subtitle),
                    onClick = onMcpClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Terminal,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_env_vars),
                    subtitle = stringResource(R.string.settings_env_vars_subtitle),
                    onClick = onEnvVarsClick,
                    showDivider = false,
                )
            }

            }

            if (grp == "data") {
            // -- Storage --
            SettingsSection(title = stringResource(R.string.settings_section_storage)) {
                SettingsItem(
                    icon = Icons.Outlined.Inventory2,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_storage),
                    subtitle = stringResource(R.string.settings_storage_subtitle),
                    onClick = onRootfsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Folder,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_shared_folders),
                    subtitle = stringResource(R.string.settings_shared_folders_subtitle),
                    onClick = onSharedFoldersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.FolderShared,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.settings_mount_external_folders),
                    subtitle = stringResource(R.string.settings_mount_external_folders_subtitle),
                    onClick = onMountedFoldersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Backup,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_restore_subtitle),
                    onClick = onBackupClick,
                    showDivider = false,
                )
            }

            }

            if (grp == "test") {
            // -- Permissions --
            SettingsSection(title = stringResource(R.string.settings_section_permissions)) {
                SettingsItem(
                    icon = Icons.Outlined.Shield,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_permissions),
                    subtitle = stringResource(R.string.settings_permissions_subtitle),
                    onClick = onPermissionsClick,
                    showDivider = false,
                )
            }

            // -- Background & Notifications (T50) --
            SettingsSection(
                title = stringResource(R.string.bg_section_header),
                footer = stringResource(R.string.bg_section_footer),
            ) {
                SettingsItem(
                    icon = Icons.Outlined.BatteryFull,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.bg_section_header),
                    subtitle = stringResource(R.string.bg_section_subtitle),
                    onClick = onBackgroundClick,
                    showDivider = false,
                )
            }

            // -- Logs --
            SettingsSection(title = stringResource(R.string.settings_section_logs)) {
                SettingsItem(
                    icon = Icons.Outlined.Description,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_logs),
                    subtitle = stringResource(R.string.settings_logs_subtitle),
                    onClick = onLogsClick,
                    showDivider = false,
                )
            }

            // -- About --
            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_about_minis),
                    subtitle = stringResource(R.string.settings_about_subtitle),
                    onClick = onAboutClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.FrontHand,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_privacy_policy),
                    subtitle = null,
                    // iOS canonical URL — ContentView.swift / AddProviderView.swift
                    onClick = { openExternalUrl(context, "https://openminis.github.io/privacy-policy.html") },
                )
                SettingsItem(
                    icon = Icons.Outlined.Feedback,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_feedback),
                    subtitle = null,
                    onClick = { showFeedbackSheet = true },
                    showDivider = false,
                )
            }

            }

            Spacer(Modifier.height(24.dp))
        }
    }
    }

    if (showFeedbackSheet) {
        ModalBottomSheet(onDismissRequest = { showFeedbackSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                FeedbackSheetItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_submit_github_issues),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildBugReportUrl())
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    title = stringResource(R.string.settings_feedback_telegram),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, "https://t.me/+2NzhOJuzRyI1YmM1")
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.Outlined.Email,
                    title = stringResource(R.string.settings_feedback_email),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildFeedbackMailto())
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedbackSheetItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinesIcon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Build the GitHub Issues "new bug report" URL with the body pre-filled
 * from the existing bug-report template. Platform / OS version / app
 * version / device model are injected so the report arrives ready to
 * triage instead of asking the user to fill in environment details.
 *
 * URL shape:
 *   https://github.com/OpenMinis/OpenMinis/issues/new
 *     ?template=bug_report.md
 *     &title=[Bug]
 *     &body=<percent-encoded markdown>
 *
 * The body is a Markdown template with sections for Problem Summary,
 * Basic Information (table — auto-filled), Steps to Reproduce, Error
 * Details (fenced code block), Expected Behavior, and Additional
 * Information.
 */
private fun buildBugReportUrl(): String {
    val osVersion = android.os.Build.VERSION.RELEASE
    val sdkInt = android.os.Build.VERSION.SDK_INT
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL

    // Body matches the spec template. Triple-backtick fences are written
    // as "```" — they survive percent-encoding cleanly. Indentation here
    // is significant: trimIndent() removes the common Kotlin indentation
    // but preserves the Markdown structure as-is.
    val body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | Android |
        | OS Version | Android $osVersion (API $sdkInt) |
        | Minis Version | $versionName (build $versionCode) |
        | Device Model | $manufacturer $model |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

    """.trimIndent()

    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    // Title carries a "[Bug] " prefix with a trailing space so the cursor
    // lands after it on GitHub's page; encode the space as %20 explicitly
    // since URLEncoder turns spaces into '+' which GitHub also accepts but
    // the spec calls for the literal "[Bug] " form.
    val title = java.net.URLEncoder.encode("[Bug] ", "UTF-8")
    return "https://github.com/OpenMinis/OpenMinis/issues/new" +
        "?template=bug_report.md" +
        "&title=$title" +
        "&body=$encodedBody"
}

/**
 * Compose a `mailto:` URL with a prefilled subject and body that include
 * app version, Android version, and device model. Mirrors iOS
 * `ContentView.makeFeedbackEmailURL()`.
 */
private fun buildFeedbackMailto(): String {
    val body = """
        Please describe your feedback:


        ---
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Android Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
        Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}

        Screenshot (optional): Please attach a screenshot if relevant.
    """.trimIndent()
    val subject = java.net.URLEncoder.encode("Mines Feedback", "UTF-8")
    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    return "mailto:dev@openminis.app?subject=$subject&body=$encodedBody"
}

/**
 * A grouped settings section with header and optional footer, matching iOS grouped List sections.
 */
@Composable
private fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    val mc = com.openminis.app.ui.plus.mines()
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(title, fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = mc.ink2,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        com.openminis.app.ui.plus.MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
        }
        if (footer != null) Text(footer, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = mc.ink3,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

/**
 * A single settings row item with colored icon, title, optional subtitle, and chevron.
 * Styled to match iOS settings rows with SF Symbol-like colored circle icons.
 */
@Composable
private fun SettingsItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    /** [Mines-warm-36] red one-liner under the subtitle, e.g. 「1 个要处理」. */
    alert: String? = null,
) {
    // [Mines-design-34] accent line icon in a tinted circle (iconColor kept in the signature, not painted)
    val mc = com.openminis.app.ui.plus.mines()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.openminis.app.ui.plus.IconBubble(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.Medium, color = mc.ink)
                if (subtitle != null) Text(subtitle, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = mc.ink2, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (alert != null) Text(alert, fontSize = MT.Caption, lineHeight = MT.CaptionLH, fontWeight = FontWeight.SemiBold,
                    color = if (mc.dark) com.openminis.app.ui.plus.MinesDesign.DangerDark else com.openminis.app.ui.plus.MinesDesign.Danger, maxLines = 1)
            }
            MinesIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = mc.ink3, modifier = Modifier.size(18.dp))
        }
        if (showDivider) Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp).height(1.dp).background(mc.hairline))
    }
}

