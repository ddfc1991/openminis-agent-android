# MODULES.md — com.openminis.app.plus 按功能归包映射（规划，未移动）

状态：**仅规划**。本版（仓库整理 r-step0）没有移动任何文件、没有改任何 package 声明；下表是将来按功能拆包时的目标位置。
范围：`src/android/app/src/main/java/com/openminis/app/` 下的 `plus/`、`plus/group/`、`ui/plus/`（Mines 自有代码，290 个 .kt）。上游 OpenMinis 其余包（agent、sandbox、ui/chat …）不在此表。

目标结构：逻辑层 `com.openminis.app.plus.<模块>`，界面层 `com.openminis.app.ui.plus.<模块>`。

## 汇总

| 模块 | 目标子包 | 文件数 | 行数 |
|---|---|---:|---:|
| 天气 | `plus.weather` / `ui.plus.weather` | 13 | 1962 |
| 消息/简报 | `plus.mail` / `ui.plus.mail` | 14 | 2921 |
| 模型/提供方 | `plus.providers` / `ui.plus.providers` | 21 | 5966 |
| 台账 | `plus.ledger` / `ui.plus.ledger` | 5 | 542 |
| 账号 | `plus.accounts` / `ui.plus.accounts` | 45 | 9768 |
| 服务器 | `plus.servers` / `ui.plus.servers` | 11 | 3106 |
| 浏览器 | `plus.browser` / `ui.plus.browser` | 13 | 1446 |
| 记忆 | `plus.memory` / `ui.plus.memory` | 15 | 3317 |
| 任务 | `plus.tasks` / `ui.plus.tasks` | 18 | 4338 |
| 网络/代理 | `plus.net` / `ui.plus.net` | 26 | 4342 |
| 语音/通话 | `plus.voice` / `ui.plus.voice` | 19 | 4297 |
| 技能/插件/市场 | `plus.skills` / `ui.plus.skills` | 8 | 2391 |
| 稳定性/诊断 | `plus.stability` / `ui.plus.stability` | 22 | 3272 |
| 桌宠 | `plus.pet` / `ui.plus.pet` | 4 | 1563 |
| 多智能体群组 | `plus.group` / `ui.plus.group` | 13 | 3589 |
| 界面外壳 | `plus.shell` / `ui.plus.shell` | 43 | 10220 |

## 移动前必须处理的风险（因此本轮不移动）

1. **Manifest 组件类名会变**：以下文件里的类在 AndroidManifest.xml 里按全名注册。移动后系统会把它们当成新组件：用户在系统设置里选的 **自动填充服务（MinesAutofillService）会被重置**，JobScheduler 里已排的 **AccountPatrolJob** 失效，桌面小组件 / 短信接收器要重新注册。移动这些类要么保留原类名做薄壳转发，要么放到最后单独一版并在更新说明里提醒。
   - `plus/AccountPatrol.kt` → AccountPatrolJob
   - `plus/AcraProbeService.kt` → AcraProbeService
   - `plus/ClashService.kt` → ClashBootReceiver, ClashService
   - `plus/DeskPetService.kt` → DeskPetService
   - `plus/DshWidgets.kt` → PluginWidgetService
   - `plus/MinesAutofillService.kt` → AutofillAuthActivity, MinesAutofillService
   - `plus/PlusConnect.kt` → KeepAliveReceiver
   - `plus/SelfTestProvider.kt` → SelfTestProvider
   - `plus/SmsCodes.kt` → SmsCodeReceiver, SmsConsentActivity
   - `plus/VoiceSession.kt` → VoiceCallService, VoiceTrampolineActivity
   - `plus/WakeWordService.kt` → WakeWordService
   - `ui/plus/GeminiAccountsScreen.kt` → GeminiAccountsActivity
   - `ui/plus/MinesCallActivity.kt` → MinesCallActivity
2. **Intent action 字符串**：`DeskPetService`（`com.openminis.app.plus.PET_*`）、`VoiceSession`（`com.openminis.app.plus.VOICE_HANGUP`）用旧包名当 action 常量；只改 package 时保持字符串不变即可。
3. **R8 / proguard-rules.pro 与 mapping**：混淆映射会整体变化，旧版 mapping-*.txt 不能再对应新版堆栈；按包名的 keep 规则需同步。
4. **CI 审计步骤写死了路径**：Overlay audit（`plus/Overlays.kt`、`plus/DshWidgets.kt`、`plus/DeskPetService.kt`）、Readability audit（`ui/plus/TodayCard.kt`）、Market git 检查（`plus/MarketGitCore.kt`）、单元测试 `--tests com.openminis.app.plus.*Test` 全名列表——移动时要一起改。
5. **单元测试包名**：`src/test/java/com/openminis/app/plus/` 下的 *CoreTest 依赖同包可见性（internal / 同包），要随被测类一起移动。
6. **一个文件多个顶层类**：如 `MinesAutofillService.kt` 内含 `AutofillAuthActivity`，按文件移动会连带移动这些类。

建议顺序（以后各自一版、各自跑 smoke）：天气 → 台账 → 浏览器 → 记忆 → 任务 → 服务器 → 账号（最后，含自动填充服务）。每步只做 `git mv` + package/import 改动，APK 行为不变，用方法级 dex 对比验收。

## 明细

### 天气 → `plus.weather` / `ui.plus.weather`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/DayNight.kt` | 54 | `plus/weather/DayNight.kt` |
| `plus/HomeWeatherCore.kt` | 141 | `plus/weather/HomeWeatherCore.kt` |
| `plus/RainSkyCore.kt` | 41 | `plus/weather/RainSkyCore.kt` |
| `plus/SkyFreezeCore.kt` | 59 | `plus/weather/SkyFreezeCore.kt` |
| `plus/SkyModeCore.kt` | 135 | `plus/weather/SkyModeCore.kt` |
| `plus/SkyPerfCore.kt` | 146 | `plus/weather/SkyPerfCore.kt` |
| `plus/WeatherSkyCore.kt` | 169 | `plus/weather/WeatherSkyCore.kt` |
| `plus/WindDriftCore.kt` | 94 | `plus/weather/WindDriftCore.kt` |
| `ui/plus/AnimeWeather.kt` | 189 | `ui/plus/weather/AnimeWeather.kt` |
| `ui/plus/RainSky.kt` | 32 | `ui/plus/weather/RainSky.kt` |
| `ui/plus/SkyFreeze.kt` | 70 | `ui/plus/weather/SkyFreeze.kt` |
| `ui/plus/WeatherAmbience.kt` | 153 | `ui/plus/weather/WeatherAmbience.kt` |
| `ui/plus/WeatherSky.kt` | 679 | `ui/plus/weather/WeatherSky.kt` |

### 消息/简报 → `plus.mail` / `ui.plus.mail`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/AgentMailbox.kt` | 258 | `plus/mail/AgentMailbox.kt` |
| `plus/AiMailCore.kt` | 51 | `plus/mail/AiMailCore.kt` |
| `plus/Horizon.kt` | 256 | `plus/mail/Horizon.kt` |
| `plus/HorizonCore.kt` | 423 | `plus/mail/HorizonCore.kt` |
| `plus/Inbox3912Core.kt` | 100 | `plus/mail/Inbox3912Core.kt` |
| `plus/MailConfig.kt` | 69 | `plus/mail/MailConfig.kt` |
| `plus/MailImap.kt` | 144 | `plus/mail/MailImap.kt` |
| `plus/MailInbox.kt` | 375 | `plus/mail/MailInbox.kt` |
| `plus/MailNet.kt` | 50 | `plus/mail/MailNet.kt` |
| `plus/MailParse.kt` | 246 | `plus/mail/MailParse.kt` |
| `plus/MailSources.kt` | 298 | `plus/mail/MailSources.kt` |
| `plus/SelfMailCore.kt` | 103 | `plus/mail/SelfMailCore.kt` |
| `ui/plus/HorizonBriefPane.kt` | 188 | `ui/plus/mail/HorizonBriefPane.kt` |
| `ui/plus/MailboxPane.kt` | 360 | `ui/plus/mail/MailboxPane.kt` |

### 模型/提供方 → `plus.providers` / `ui.plus.providers`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/AutoSources.kt` | 240 | `plus/providers/AutoSources.kt` |
| `plus/BadKeys.kt` | 45 | `plus/providers/BadKeys.kt` |
| `plus/CodeSourcesCore.kt` | 114 | `plus/providers/CodeSourcesCore.kt` |
| `plus/DshLlmBridge.kt` | 191 | `plus/providers/DshLlmBridge.kt` |
| `plus/GeminiLive.kt` | 949 | `plus/providers/GeminiLive.kt` |
| `plus/GeminiRoute.kt` | 324 | `plus/providers/GeminiRoute.kt` |
| `plus/KeyAgent.kt` | 392 | `plus/providers/KeyAgent.kt` |
| `plus/KeyCheck.kt` | 49 | `plus/providers/KeyCheck.kt` |
| `plus/KeyCheckCore.kt` | 44 | `plus/providers/KeyCheckCore.kt` |
| `plus/KeyTester.kt` | 135 | `plus/providers/KeyTester.kt` |
| `plus/KeyTombstone.kt` | 23 | `plus/providers/KeyTombstone.kt` |
| `plus/ModelFetch.kt` | 208 | `plus/providers/ModelFetch.kt` |
| `plus/ModelHealth.kt` | 60 | `plus/providers/ModelHealth.kt` |
| `plus/PlusConnect.kt` | 465 | `plus/providers/PlusConnect.kt` ⚠️manifest |
| `plus/ProviderHealth.kt` | 196 | `plus/providers/ProviderHealth.kt` |
| `plus/ProviderHealthCore.kt` | 135 | `plus/providers/ProviderHealthCore.kt` |
| `plus/ProviderKeys.kt` | 185 | `plus/providers/ProviderKeys.kt` |
| `plus/ProviderMergeCore.kt` | 53 | `plus/providers/ProviderMergeCore.kt` |
| `ui/plus/CodeSourcesScreen.kt` | 254 | `ui/plus/providers/CodeSourcesScreen.kt` |
| `ui/plus/ConnectionsScreen.kt` | 1468 | `ui/plus/providers/ConnectionsScreen.kt` |
| `ui/plus/KeyAgentScreen.kt` | 436 | `ui/plus/providers/KeyAgentScreen.kt` |

### 台账 → `plus.ledger` / `ui.plus.ledger`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/Ledger.kt` | 151 | `plus/ledger/Ledger.kt` |
| `plus/LedgerCore.kt` | 124 | `plus/ledger/LedgerCore.kt` |
| `plus/RegisterCore.kt` | 52 | `plus/ledger/RegisterCore.kt` |
| `ui/plus/LedgerChipUi.kt` | 109 | `ui/plus/ledger/LedgerChipUi.kt` |
| `ui/plus/RegisterHelper.kt` | 106 | `ui/plus/ledger/RegisterHelper.kt` |

### 账号 → `plus.accounts` / `ui.plus.accounts`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/AccountJar.kt` | 226 | `plus/accounts/AccountJar.kt` |
| `plus/AccountLabel.kt` | 115 | `plus/accounts/AccountLabel.kt` |
| `plus/AccountPatrol.kt` | 246 | `plus/accounts/AccountPatrol.kt` ⚠️manifest |
| `plus/AccountsCore.kt` | 183 | `plus/accounts/AccountsCore.kt` |
| `plus/AccountsPrompt.kt` | 16 | `plus/accounts/AccountsPrompt.kt` |
| `plus/AccountsTool.kt` | 350 | `plus/accounts/AccountsTool.kt` |
| `plus/AgentLogin.kt` | 105 | `plus/accounts/AgentLogin.kt` |
| `plus/AppPasswordCore.kt` | 40 | `plus/accounts/AppPasswordCore.kt` |
| `plus/AutoAccounts.kt` | 198 | `plus/accounts/AutoAccounts.kt` |
| `plus/AutoLogin.kt` | 211 | `plus/accounts/AutoLogin.kt` |
| `plus/AutoLoginCore.kt` | 111 | `plus/accounts/AutoLoginCore.kt` |
| `plus/GeminiAccounts.kt` | 429 | `plus/accounts/GeminiAccounts.kt` |
| `plus/GeminiAccountsCore.kt` | 306 | `plus/accounts/GeminiAccountsCore.kt` |
| `plus/HumanVerify.kt` | 346 | `plus/accounts/HumanVerify.kt` |
| `plus/IdentityCore.kt` | 377 | `plus/accounts/IdentityCore.kt` |
| `plus/IdentityStore.kt` | 228 | `plus/accounts/IdentityStore.kt` |
| `plus/Keychain.kt` | 236 | `plus/accounts/Keychain.kt` |
| `plus/KeychainCore.kt` | 171 | `plus/accounts/KeychainCore.kt` |
| `plus/LinkCodeReader.kt` | 89 | `plus/accounts/LinkCodeReader.kt` |
| `plus/LoginAssist.kt` | 358 | `plus/accounts/LoginAssist.kt` |
| `plus/LoginMemory.kt` | 195 | `plus/accounts/LoginMemory.kt` |
| `plus/LoginMemoryCore.kt` | 121 | `plus/accounts/LoginMemoryCore.kt` |
| `plus/LoginValueCore.kt` | 81 | `plus/accounts/LoginValueCore.kt` |
| `plus/LoginWeb.kt` | 191 | `plus/accounts/LoginWeb.kt` |
| `plus/MinesAutofillService.kt` | 183 | `plus/accounts/MinesAutofillService.kt` ⚠️manifest |
| `plus/PayCards.kt` | 309 | `plus/accounts/PayCards.kt` |
| `plus/PlusVault.kt` | 736 | `plus/accounts/PlusVault.kt` |
| `plus/Relogin.kt` | 81 | `plus/accounts/Relogin.kt` |
| `plus/SavedLogins.kt` | 173 | `plus/accounts/SavedLogins.kt` |
| `plus/SecretRefs.kt` | 134 | `plus/accounts/SecretRefs.kt` |
| `plus/SecretRefsCore.kt` | 38 | `plus/accounts/SecretRefsCore.kt` |
| `plus/SessionKeeper.kt` | 145 | `plus/accounts/SessionKeeper.kt` |
| `plus/SessionStatus.kt` | 131 | `plus/accounts/SessionStatus.kt` |
| `plus/SmsCodes.kt` | 137 | `plus/accounts/SmsCodes.kt` ⚠️manifest |
| `plus/SourceAccounts.kt` | 243 | `plus/accounts/SourceAccounts.kt` |
| `plus/WebLoginDiag.kt` | 40 | `plus/accounts/WebLoginDiag.kt` |
| `plus/group/PresetAuth.kt` | 265 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/WebAccounts.kt` | 49 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `ui/plus/AccountsUi.kt` | 243 | `ui/plus/accounts/AccountsUi.kt` |
| `ui/plus/AppPasswordGuide.kt` | 177 | `ui/plus/accounts/AppPasswordGuide.kt` |
| `ui/plus/GeminiAccountsScreen.kt` | 311 | `ui/plus/accounts/GeminiAccountsScreen.kt` ⚠️manifest |
| `ui/plus/KeychainScreen.kt` | 719 | `ui/plus/accounts/KeychainScreen.kt` |
| `ui/plus/LoginCardUi.kt` | 124 | `ui/plus/accounts/LoginCardUi.kt` |
| `ui/plus/PayCardsScreen.kt` | 220 | `ui/plus/accounts/PayCardsScreen.kt` |
| `ui/plus/WebLoginScreen.kt` | 381 | `ui/plus/accounts/WebLoginScreen.kt` |

### 服务器 → `plus.servers` / `ui.plus.servers`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/GatewayTunnel.kt` | 68 | `plus/servers/GatewayTunnel.kt` |
| `plus/LinkBridge.kt` | 109 | `plus/servers/LinkBridge.kt` |
| `plus/MinesLink.kt` | 297 | `plus/servers/MinesLink.kt` |
| `plus/PlusVps.kt` | 455 | `plus/servers/PlusVps.kt` |
| `plus/SshNetCore.kt` | 43 | `plus/servers/SshNetCore.kt` |
| `plus/VpsSsh.kt` | 575 | `plus/servers/VpsSsh.kt` |
| `plus/WorkPoolCore.kt` | 89 | `plus/servers/WorkPoolCore.kt` |
| `plus/WorkspaceCore.kt` | 177 | `plus/servers/WorkspaceCore.kt` |
| `plus/WorkspaceStore.kt` | 204 | `plus/servers/WorkspaceStore.kt` |
| `ui/plus/ServerDetailScreen.kt` | 332 | `ui/plus/servers/ServerDetailScreen.kt` |
| `ui/plus/WorkLocationScreen.kt` | 757 | `ui/plus/servers/WorkLocationScreen.kt` |

### 浏览器 → `plus.browser` / `ui.plus.browser`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/EdgeDrm.kt` | 189 | `plus/browser/EdgeDrm.kt` |
| `plus/Favicons.kt` | 57 | `plus/browser/Favicons.kt` |
| `plus/HiddenWeb.kt` | 74 | `plus/browser/HiddenWeb.kt` |
| `plus/StrictWebCore.kt` | 31 | `plus/browser/StrictWebCore.kt` |
| `plus/WebBlock.kt` | 39 | `plus/browser/WebBlock.kt` |
| `plus/WebHintsCore.kt` | 36 | `plus/browser/WebHintsCore.kt` |
| `plus/WebIsolation.kt` | 48 | `plus/browser/WebIsolation.kt` |
| `plus/WebProxy.kt` | 63 | `plus/browser/WebProxy.kt` |
| `plus/WebSession.kt` | 71 | `plus/browser/WebSession.kt` |
| `plus/WebSessionCore.kt` | 79 | `plus/browser/WebSessionCore.kt` |
| `plus/group/WebBridge.kt` | 197 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/WebChat.kt` | 404 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `ui/plus/WebLoad.kt` | 158 | `ui/plus/browser/WebLoad.kt` |

### 记忆 → `plus.memory` / `ui.plus.memory`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/ArchiveCore.kt` | 40 | `plus/memory/ArchiveCore.kt` |
| `plus/ChatKnowledge.kt` | 328 | `plus/memory/ChatKnowledge.kt` |
| `plus/ChatOrganizer.kt` | 164 | `plus/memory/ChatOrganizer.kt` |
| `plus/Experience.kt` | 153 | `plus/memory/Experience.kt` |
| `plus/KnowledgeBase.kt` | 352 | `plus/memory/KnowledgeBase.kt` |
| `plus/MemohKey.kt` | 43 | `plus/memory/MemohKey.kt` |
| `plus/MemohKeyCore.kt` | 42 | `plus/memory/MemohKeyCore.kt` |
| `plus/NexusCore.kt` | 186 | `plus/memory/NexusCore.kt` |
| `plus/SelfMap.kt` | 113 | `plus/memory/SelfMap.kt` |
| `plus/SelfMapCore.kt` | 100 | `plus/memory/SelfMapCore.kt` |
| `ui/plus/ExperienceScreen.kt` | 235 | `ui/plus/memory/ExperienceScreen.kt` |
| `ui/plus/GalaxyMap.kt` | 177 | `ui/plus/memory/GalaxyMap.kt` |
| `ui/plus/KnowledgeMap.kt` | 485 | `ui/plus/memory/KnowledgeMap.kt` |
| `ui/plus/KnowledgeScreen.kt` | 341 | `ui/plus/memory/KnowledgeScreen.kt` |
| `ui/plus/NexusHud.kt` | 558 | `ui/plus/memory/NexusHud.kt` |

### 任务 → `plus.tasks` / `ui.plus.tasks`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/AppAgents.kt` | 274 | `plus/tasks/AppAgents.kt` |
| `plus/Digest.kt` | 489 | `plus/tasks/Digest.kt` |
| `plus/DigestWriter.kt` | 144 | `plus/tasks/DigestWriter.kt` |
| `plus/FeedRefresh.kt` | 116 | `plus/tasks/FeedRefresh.kt` |
| `plus/MinesJobs.kt` | 251 | `plus/tasks/MinesJobs.kt` |
| `plus/SkillMatch.kt` | 49 | `plus/tasks/SkillMatch.kt` |
| `plus/SkillMatchCore.kt` | 69 | `plus/tasks/SkillMatchCore.kt` |
| `plus/TaskProgress.kt` | 242 | `plus/tasks/TaskProgress.kt` |
| `plus/TaskProgressCore.kt` | 403 | `plus/tasks/TaskProgressCore.kt` |
| `plus/WorkflowLog.kt` | 55 | `plus/tasks/WorkflowLog.kt` |
| `plus/WorkflowLogCore.kt` | 117 | `plus/tasks/WorkflowLogCore.kt` |
| `ui/plus/AppAgentsScreen.kt` | 290 | `ui/plus/tasks/AppAgentsScreen.kt` |
| `ui/plus/DigestScreen.kt` | 944 | `ui/plus/tasks/DigestScreen.kt` |
| `ui/plus/FeedTasks.kt` | 139 | `ui/plus/tasks/FeedTasks.kt` |
| `ui/plus/MinesJobsHost.kt` | 145 | `ui/plus/tasks/MinesJobsHost.kt` |
| `ui/plus/ShellTask.kt` | 54 | `ui/plus/tasks/ShellTask.kt` |
| `ui/plus/TaskProgressUi.kt` | 350 | `ui/plus/tasks/TaskProgressUi.kt` |
| `ui/plus/WorkflowLogScreen.kt` | 207 | `ui/plus/tasks/WorkflowLogScreen.kt` |

### 网络/代理 → `plus.net` / `ui.plus.net`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/AddressFix.kt` | 34 | `plus/net/AddressFix.kt` |
| `plus/ClashConfigCore.kt` | 80 | `plus/net/ClashConfigCore.kt` |
| `plus/ClashCore.kt` | 359 | `plus/net/ClashCore.kt` |
| `plus/ClashService.kt` | 119 | `plus/net/ClashService.kt` ⚠️manifest |
| `plus/ClashSubCore.kt` | 141 | `plus/net/ClashSubCore.kt` |
| `plus/ConnDedupe.kt` | 40 | `plus/net/ConnDedupe.kt` |
| `plus/FreeOffers.kt` | 128 | `plus/net/FreeOffers.kt` |
| `plus/FreeRouteCore.kt` | 60 | `plus/net/FreeRouteCore.kt` |
| `plus/FreeRoutes.kt` | 84 | `plus/net/FreeRoutes.kt` |
| `plus/LocalGateway.kt` | 108 | `plus/net/LocalGateway.kt` |
| `plus/LocalProxy.kt` | 112 | `plus/net/LocalProxy.kt` |
| `plus/LocalProxyCore.kt` | 66 | `plus/net/LocalProxyCore.kt` |
| `plus/PartnerGateway.kt` | 307 | `plus/net/PartnerGateway.kt` |
| `plus/ProviderRouteCore.kt` | 201 | `plus/net/ProviderRouteCore.kt` |
| `plus/ProviderRoutes.kt` | 139 | `plus/net/ProviderRoutes.kt` |
| `plus/ReachCore.kt` | 57 | `plus/net/ReachCore.kt` |
| `plus/ReachProbe.kt` | 72 | `plus/net/ReachProbe.kt` |
| `plus/RouteStatusCore.kt` | 98 | `plus/net/RouteStatusCore.kt` |
| `plus/SandboxNet.kt` | 298 | `plus/net/SandboxNet.kt` |
| `plus/SandboxProxyCore.kt` | 22 | `plus/net/SandboxProxyCore.kt` |
| `plus/SmartRouter.kt` | 175 | `plus/net/SmartRouter.kt` |
| `plus/SplitProxy.kt` | 762 | `plus/net/SplitProxy.kt` |
| `plus/SplitProxyCore.kt` | 94 | `plus/net/SplitProxyCore.kt` |
| `ui/plus/RouteStatusCard.kt` | 230 | `ui/plus/net/RouteStatusCard.kt` |
| `ui/plus/RoutingScreen.kt` | 414 | `ui/plus/net/RoutingScreen.kt` |
| `ui/plus/SandboxNetSection.kt` | 142 | `ui/plus/net/SandboxNetSection.kt` |

### 语音/通话 → `plus.voice` / `ui.plus.voice`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/CharacterVoices.kt` | 86 | `plus/voice/CharacterVoices.kt` |
| `plus/ExternalTts.kt` | 367 | `plus/voice/ExternalTts.kt` |
| `plus/Listen.kt` | 175 | `plus/voice/Listen.kt` |
| `plus/MinesCall.kt` | 93 | `plus/voice/MinesCall.kt` |
| `plus/MinesVoiceBridge.kt` | 77 | `plus/voice/MinesVoiceBridge.kt` |
| `plus/PlainVoice.kt` | 192 | `plus/voice/PlainVoice.kt` |
| `plus/VoiceClone.kt` | 395 | `plus/voice/VoiceClone.kt` |
| `plus/VoiceDiag.kt` | 85 | `plus/voice/VoiceDiag.kt` |
| `plus/VoiceIntents.kt` | 96 | `plus/voice/VoiceIntents.kt` |
| `plus/VoiceSession.kt` | 250 | `plus/voice/VoiceSession.kt` ⚠️manifest |
| `plus/Voiceprint.kt` | 459 | `plus/voice/Voiceprint.kt` |
| `plus/WakeWordService.kt` | 188 | `plus/voice/WakeWordService.kt` ⚠️manifest |
| `ui/plus/CustomVoiceSection.kt` | 126 | `ui/plus/voice/CustomVoiceSection.kt` |
| `ui/plus/MinesCallActivity.kt` | 333 | `ui/plus/voice/MinesCallActivity.kt` ⚠️manifest |
| `ui/plus/TtsSection.kt` | 164 | `ui/plus/voice/TtsSection.kt` |
| `ui/plus/VoiceLiveScreen.kt` | 543 | `ui/plus/voice/VoiceLiveScreen.kt` |
| `ui/plus/VoiceSettingsScreen.kt` | 136 | `ui/plus/voice/VoiceSettingsScreen.kt` |
| `ui/plus/VoiceSourceSection.kt` | 224 | `ui/plus/voice/VoiceSourceSection.kt` |
| `ui/plus/VoiceprintSection.kt` | 308 | `ui/plus/voice/VoiceprintSection.kt` |

### 技能/插件/市场 → `plus.skills` / `ui.plus.skills`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/DshWidgets.kt` | 368 | `plus/skills/DshWidgets.kt` ⚠️manifest |
| `plus/MarketGitCore.kt` | 124 | `plus/skills/MarketGitCore.kt` |
| `plus/MarketZh.kt` | 114 | `plus/skills/MarketZh.kt` |
| `plus/SkillHubCn.kt` | 344 | `plus/skills/SkillHubCn.kt` |
| `plus/SkillInstallKey.kt` | 36 | `plus/skills/SkillInstallKey.kt` |
| `plus/SkillsHub.kt` | 270 | `plus/skills/SkillsHub.kt` |
| `ui/plus/MarketScreen.kt` | 874 | `ui/plus/skills/MarketScreen.kt` |
| `ui/plus/PluginsScreen.kt` | 261 | `ui/plus/skills/PluginsScreen.kt` |

### 稳定性/诊断 → `plus.stability` / `ui.plus.stability`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/A11yCheck.kt` | 48 | `plus/stability/A11yCheck.kt` |
| `plus/AcraProbeService.kt` | 31 | `plus/stability/AcraProbeService.kt` ⚠️manifest |
| `plus/AtomicStore.kt` | 40 | `plus/stability/AtomicStore.kt` |
| `plus/AtomicStoreCore.kt` | 65 | `plus/stability/AtomicStoreCore.kt` |
| `plus/Diag.kt` | 469 | `plus/stability/Diag.kt` |
| `plus/DiagCore.kt` | 242 | `plus/stability/DiagCore.kt` |
| `plus/MemGuard.kt` | 102 | `plus/stability/MemGuard.kt` |
| `plus/MemGuardCore.kt` | 55 | `plus/stability/MemGuardCore.kt` |
| `plus/MinesCrash.kt` | 87 | `plus/stability/MinesCrash.kt` |
| `plus/MinesSelfCheck.kt` | 417 | `plus/stability/MinesSelfCheck.kt` |
| `plus/OnceCache.kt` | 26 | `plus/stability/OnceCache.kt` |
| `plus/ProcessGuard.kt` | 47 | `plus/stability/ProcessGuard.kt` |
| `plus/ProcessGuardCore.kt` | 15 | `plus/stability/ProcessGuardCore.kt` |
| `plus/SafeStart.kt` | 113 | `plus/stability/SafeStart.kt` |
| `plus/SafeStartCore.kt` | 78 | `plus/stability/SafeStartCore.kt` |
| `plus/SandboxCare.kt` | 89 | `plus/stability/SandboxCare.kt` |
| `plus/SelfTestProvider.kt` | 720 | `plus/stability/SelfTestProvider.kt` ⚠️manifest |
| `plus/ShizukuAsk.kt` | 60 | `plus/stability/ShizukuAsk.kt` |
| `ui/plus/CrashReportUi.kt` | 91 | `ui/plus/stability/CrashReportUi.kt` |
| `ui/plus/DiagUi.kt` | 103 | `ui/plus/stability/DiagUi.kt` |
| `ui/plus/ReportScreen.kt` | 136 | `ui/plus/stability/ReportScreen.kt` |
| `ui/plus/SandboxScreen.kt` | 238 | `ui/plus/stability/SandboxScreen.kt` |

### 桌宠 → `plus.pet` / `ui.plus.pet`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/DeskPetService.kt` | 1111 | `plus/pet/DeskPetService.kt` ⚠️manifest |
| `plus/PetFrameGen.kt` | 141 | `plus/pet/PetFrameGen.kt` |
| `plus/PetSprite.kt` | 189 | `plus/pet/PetSprite.kt` |
| `plus/PetStatus.kt` | 122 | `plus/pet/PetStatus.kt` |

### 多智能体群组 → `plus.group` / `ui.plus.group`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/group/Adapters.kt` | 203 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/AiCatalog.kt` | 140 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/AiClient.kt` | 382 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/CozeBots.kt` | 146 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/GroupRunner.kt` | 213 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/GroupSelfTest.kt` | 1514 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/GroupStore.kt` | 129 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/MultiAgentGroup.kt` | 90 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/Partners.kt` | 296 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/PhoneApps.kt` | 102 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/PhoneTyping.kt` | 79 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/PlatformPreset.kt` | 173 | 保持 `plus/group/`（群组子包，功能相关但不拆） |
| `plus/group/PresetRegistry.kt` | 122 | 保持 `plus/group/`（群组子包，功能相关但不拆） |

### 界面外壳 → `plus.shell` / `ui.plus.shell`

| 现位置 | 行数 | 目标 |
|---|---:|---|
| `plus/AppLauncher.kt` | 242 | `plus/shell/AppLauncher.kt` |
| `plus/AppSettingsMap.kt` | 103 | `plus/shell/AppSettingsMap.kt` |
| `plus/Avatars.kt` | 379 | `plus/shell/Avatars.kt` |
| `plus/ErrZhCore.kt` | 74 | `plus/shell/ErrZhCore.kt` |
| `plus/GlassAuto.kt` | 182 | `plus/shell/GlassAuto.kt` |
| `plus/HomeFixCore.kt` | 86 | `plus/shell/HomeFixCore.kt` |
| `plus/HomeGreeting.kt` | 470 | `plus/shell/HomeGreeting.kt` |
| `plus/ImageGridCore.kt` | 26 | `plus/shell/ImageGridCore.kt` |
| `plus/ImageOnDemandCore.kt` | 52 | `plus/shell/ImageOnDemandCore.kt` |
| `plus/ImageStackCore.kt` | 72 | `plus/shell/ImageStackCore.kt` |
| `plus/MinesUpdate.kt` | 151 | `plus/shell/MinesUpdate.kt` |
| `plus/Overlays.kt` | 107 | `plus/shell/Overlays.kt` |
| `plus/PlusPrefs.kt` | 267 | `plus/shell/PlusPrefs.kt` |
| `plus/SharedFiles.kt` | 48 | `plus/shell/SharedFiles.kt` |
| `plus/SharedFilesCore.kt` | 108 | `plus/shell/SharedFilesCore.kt` |
| `plus/ToolZh.kt` | 43 | `plus/shell/ToolZh.kt` |
| `plus/ValueChips.kt` | 192 | `plus/shell/ValueChips.kt` |
| `plus/XpTitles.kt` | 138 | `plus/shell/XpTitles.kt` |
| `plus/XpViewCore.kt` | 182 | `plus/shell/XpViewCore.kt` |
| `ui/plus/AvatarPicker.kt` | 273 | `ui/plus/shell/AvatarPicker.kt` |
| `ui/plus/CardTextSection.kt` | 94 | `ui/plus/shell/CardTextSection.kt` |
| `ui/plus/ChatCards.kt` | 68 | `ui/plus/shell/ChatCards.kt` |
| `ui/plus/ChatChipsUi.kt` | 151 | `ui/plus/shell/ChatChipsUi.kt` |
| `ui/plus/Glass.kt` | 229 | `ui/plus/shell/Glass.kt` |
| `ui/plus/GlassAutoUi.kt` | 117 | `ui/plus/shell/GlassAutoUi.kt` |
| `ui/plus/GlassBlurCheck.kt` | 30 | `ui/plus/shell/GlassBlurCheck.kt` |
| `ui/plus/GlassDialogs.kt` | 432 | `ui/plus/shell/GlassDialogs.kt` |
| `ui/plus/GlassKit.kt` | 278 | `ui/plus/shell/GlassKit.kt` |
| `ui/plus/Hub3912.kt` | 309 | `ui/plus/shell/Hub3912.kt` |
| `ui/plus/Hub3912b.kt` | 389 | `ui/plus/shell/Hub3912b.kt` |
| `ui/plus/ImageStack.kt` | 155 | `ui/plus/shell/ImageStack.kt` |
| `ui/plus/LinkAll.kt` | 563 | `ui/plus/shell/LinkAll.kt` |
| `ui/plus/LookScreen.kt` | 258 | `ui/plus/shell/LookScreen.kt` |
| `ui/plus/MigrateScreen.kt` | 372 | `ui/plus/shell/MigrateScreen.kt` |
| `ui/plus/MinesDesign.kt` | 448 | `ui/plus/shell/MinesDesign.kt` |
| `ui/plus/MinesExtras.kt` | 340 | `ui/plus/shell/MinesExtras.kt` |
| `ui/plus/MotionGate.kt` | 41 | `ui/plus/shell/MotionGate.kt` |
| `ui/plus/PermGuide.kt` | 127 | `ui/plus/shell/PermGuide.kt` |
| `ui/plus/PersonalizeScreen.kt` | 354 | `ui/plus/shell/PersonalizeScreen.kt` |
| `ui/plus/PlusComponents.kt` | 721 | `ui/plus/shell/PlusComponents.kt` |
| `ui/plus/TodayCard.kt` | 908 | `ui/plus/shell/TodayCard.kt` |
| `ui/plus/WarmComponents.kt` | 491 | `ui/plus/shell/WarmComponents.kt` |
| `ui/plus/XpCards.kt` | 150 | `ui/plus/shell/XpCards.kt` |

说明：`plus/group/`（多智能体群组）已经是独立子包，保持不动即可，表中目标与现状相同。分类按文件名与文件头注释归纳，个别跨模块文件（如 `PlusConnect`、`SmartRouter`）移动前再按依赖确认。
