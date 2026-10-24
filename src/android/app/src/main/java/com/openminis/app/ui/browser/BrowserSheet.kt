package com.openminis.app.ui.browser

import com.openminis.app.ui.plus.MT

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.browser.BrowserHistoryStore
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.browser.UserAgentProfile
import com.openminis.app.ui.chat.StandardChatSheet
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.DecorativeSpinner
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Bottom sheet presenting the browser tab pool with tab bar, URL bar,
 * WebView, and navigation controls. Mirrors iOS BrowserSheetView.
 */
@Composable
fun BrowserSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val tabs by tabPool.tabs.collectAsState()
    val selectedTabId by tabPool.selectedTabId.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val selectedTab = tabs.find { it.id == selectedTabId }
    val currentURL = selectedTab?.manager?.currentURL?.collectAsState()?.value ?: ""
    val pageTitle = selectedTab?.manager?.pageTitle?.collectAsState()?.value ?: ""
    val isLoading = selectedTab?.manager?.isLoading?.collectAsState()?.value ?: false
    val canGoBack = selectedTab?.manager?.canGoBack?.collectAsState()?.value ?: false
    val canGoForward = selectedTab?.manager?.canGoForward?.collectAsState()?.value ?: false
    val isAgentBusy = tabPool.isAgentBusy
    val userAgentProfile = tabPool.currentUserAgentProfile.collectAsState().value

    var urlInput by remember(currentURL) { mutableStateOf(currentURL) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // [T-android-browser-download-ux] Downloads panel + badge state.
    var showDownloads by remember { mutableStateOf(false) }
    var showTextSize by remember { mutableStateOf(false) }
    // [Mines-translate-35] 网页翻译: toolbar 「译」 + a one-time hint on foreign pages
    var translating by remember { mutableStateOf(false) }
    var translatedUrl by remember { mutableStateOf<String?>(null) }
    // [Mines-cards-35.1] 「填卡」: shown only on a payment form; fills after fingerprint / screen lock, never submits
    var payForm by remember { mutableStateOf(false) }
    var cardPick by remember { mutableStateOf(false) }
    val curTabState = androidx.compose.runtime.rememberUpdatedState(selectedTab)
    androidx.compose.runtime.LaunchedEffect(selectedTabId) {
        while (true) {
            val wv = curTabState.value?.manager?.webView
            payForm = if (wv == null) false else kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { c ->
                runCatching { wv.evaluateJavascript(com.openminis.app.plus.PayCards.DETECT_JS) { r -> if (c.isActive) c.resumeWith(Result.success(r?.contains("1") == true)) } }
                    .onFailure { if (c.isActive) c.resumeWith(Result.success(false)) }
            }
            kotlinx.coroutines.delay(3000)
        }
    }
    fun fillWith(card: com.openminis.app.plus.PayCards.Card) {
        val wv = selectedTab?.manager?.webView ?: return
        com.openminis.app.plus.PayCards.unlock(context, "填写 ${card.masked}") { ok, msg ->
            if (!ok) { android.widget.Toast.makeText(context, msg ?: "没有通过验证", android.widget.Toast.LENGTH_SHORT).show(); return@unlock }
            com.openminis.app.plus.PayCards.fill(wv, card) { _ ->
                android.widget.Toast.makeText(context, "已填好 ${card.masked}。请你自己检查，然后自己点付款/保存", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    if (cardPick) com.openminis.app.ui.glass.AlertDialog(onDismissRequest = { cardPick = false },
        title = { androidx.compose.material3.Text("用哪张卡？") },
        text = { androidx.compose.foundation.layout.Column {
            com.openminis.app.plus.PayCards.cards.value.forEach { cd ->
                androidx.compose.material3.TextButton(onClick = { cardPick = false; fillWith(cd) }) {
                    androidx.compose.material3.Text(cd.label.ifBlank { cd.masked } + " · " + cd.expMonth + "/" + cd.expYear, fontSize = MT.Body)
                }
            }
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { cardPick = false }) { androidx.compose.material3.Text("取消") } })
    val hinted = remember { mutableSetOf<String>() }
    val downloadEntries by tabPool.downloads.collectAsState()
    // [Mines-38.9.7] remember the page the user is on (and its sessionStorage) so an agent taking over keeps the login.
    // 无痕 pools are never recorded (BrowserHandoff.noteUser ignores them).
    androidx.compose.runtime.LaunchedEffect(selectedTabId, currentURL, isLoading) {
        val wv = curTabState.value?.manager?.webView ?: return@LaunchedEffect
        if (!isLoading && currentURL.startsWith("http"))
            com.openminis.app.browser.BrowserHandoff.noteUser(tabPool, wv, currentURL, userAgentProfile == UserAgentProfile.DESKTOP_CHROME, tabPool.incognito)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val t = curTabState.value
            val u = t?.manager?.currentURL?.value.orEmpty()
            if (t != null && u.startsWith("http"))
                runCatching { com.openminis.app.browser.BrowserHandoff.noteUser(tabPool, t.manager.webView, u, tabPool.currentUserAgentProfile.value == UserAgentProfile.DESKTOP_CHROME, tabPool.incognito) }
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val secondaryBg = MaterialTheme.colorScheme.surfaceContainer
    val tertiaryBg = MaterialTheme.colorScheme.surfaceContainerHigh

    // Mirror iOS onAppear: ensure at least one tab exists when the sheet opens.
    // Callers should also call `ensureTabForUI()` before flipping the sheet
    // visible so the first composition sees a non-empty tab list; this is a
    // defensive fallback.
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) tabPool.ensureTabForUI()
    }

    // Prevent the bottom sheet from swallowing WebView scroll gestures.
    // Returning the full delta as "consumed" on pre-scroll keeps the sheet's
    // nested-scroll dispatcher from dragging the sheet down when the user
    // scrolls inside a webpage.
    val webViewScrollGuard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
        }
    }

    StandardChatSheet(
        title = com.openminis.app.browser.IncognitoCore.title(pageTitle.ifEmpty { stringResource(R.string.browser_title) }, tabPool.incognito),
        onDismiss = onDismiss,
        sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,  // [Mines-webfit-38.3] full width in landscape
        singleLineTitle = true,  // [Mines-webpaint-38.4] long titles: one line + …, close X always visible
        leadingAction = {
            // UA-profile icon doubles as the entry point to settings, matching
            // the prior centered-title-with-icon affordance.
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    when (userAgentProfile) {
                        UserAgentProfile.MOBILE_CHROME -> Icons.Default.PhoneAndroid
                        UserAgentProfile.DESKTOP_CHROME -> Icons.Default.Computer
                        UserAgentProfile.CUSTOM -> Icons.Default.Edit
                    },
                    contentDescription = stringResource(R.string.browser_settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        // [Mines-webfit-38.3] the page shrinks above the keyboard (no-op when the sheet already consumed it)
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // [Mines-38.9.11.2] 无痕 bar: a small indicator (normal text size) + 保存到密码库 (the only way a 无痕 login is saved)
            // + 清除并关闭 (wipes this session's profile and closes)
            if (tabPool.incognito) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(tertiaryBg).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        com.openminis.app.browser.IncognitoCore.INDICATOR,
                        fontSize = MT.Meta, color = Color.White,
                        modifier = Modifier.background(Color(0xFF3C3C43), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        com.openminis.app.browser.IncognitoCore.SAVE_ACTION, fontSize = MT.Sub,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            val wv = selectedTab?.manager?.webView ?: return@clickable
                            wv.evaluateJavascript(com.openminis.app.browser.IncognitoCore.SAVE_JS) { raw ->
                                val r = com.openminis.app.browser.IncognitoCore.parseSaveResult(raw)
                                if (r == null || !com.openminis.app.browser.IncognitoCore.savesLogin(true, userTapped = true)) {
                                    android.widget.Toast.makeText(context, com.openminis.app.browser.IncognitoCore.NOTHING_TO_SAVE_MSG, android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val host = runCatching { android.net.Uri.parse(r.first).host.orEmpty() }.getOrDefault("")
                                    runCatching { com.openminis.app.plus.SavedLogins.put(context, com.openminis.app.plus.SavedLogins.Entry(
                                        com.openminis.app.plus.AutoAccounts.siteOf(host).ifBlank { host }, r.first, r.second, r.third)) }
                                    android.widget.Toast.makeText(context, com.openminis.app.browser.IncognitoCore.SAVED_MSG, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        com.openminis.app.browser.IncognitoCore.CLEAR_ACTION, fontSize = MT.Sub, color = accent, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onDismiss() }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            // ── Tab Bar with leading "+" and trailing History icon ──
            // (Add/History flank the tabs row so all tab-related controls
            // sit on one line, leaving the URL bar uncluttered below.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tertiaryBg)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { scope.launch { tabPool.newTabFromUI() } },
                    enabled = tabs.size < 3 && !isAgentBusy,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.browser_new_tab), modifier = Modifier.size(20.dp))
                }
                LazyRow(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val title = tab.manager.pageTitle.collectAsState().value
                        val domain = tab.manager.currentURL.collectAsState().value
                            .let { url -> try { java.net.URI(url).host } catch (_: Exception) { null } }
                        val displayTitle = title.ifEmpty { domain ?: "Tab ${tab.id}" }
                            .take(20)
                        val isSelected = tab.id == selectedTabId

                        TabChip(
                            title = displayTitle,
                            isSelected = isSelected,
                            showClose = !isAgentBusy,
                            accent = accent,
                            onClick = { tabPool.selectTab(tab.id) },
                            onClose = { scope.launch { tabPool.closeTabFromUI(tab.id) } },
                        )
                    }
                    item {
                        Text(
                            "${tabs.size}/3",
                            fontSize = MT.Tiny,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (!tabPool.incognito) IconButton(
                    onClick = { showHistory = true },
                    enabled = !isAgentBusy,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.History, contentDescription = stringResource(R.string.browser_history_action), modifier = Modifier.size(20.dp))
                }
            }

            // ── URL Bar ── (compact 36dp pill — OutlinedTextField defaults to
            // ~56dp which dominates the sheet header; users want to spend the
            // space on the WebView.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(secondaryBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(tertiaryBg, RoundedCornerShape(10.dp))
                        .border(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrowserAddressBarIcon(isLoading = isLoading, accent = accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            enabled = !isAgentBusy,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = MT.Caption,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val trimmed = urlInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    val normalized = normalizeURLInput(trimmed)
                                    selectedTab?.manager?.loadURL(normalized)
                                    urlInput = normalized
                                }
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (urlInput.isEmpty()) {
                            Text(
                                stringResource(R.string.browser_search_placeholder),
                                fontSize = MT.Caption,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isLoading) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { selectedTab?.manager?.stopLoading() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.browser_stop),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }

            // [Mines-38.9.1] per-site 手机版 / 电脑版 (remembered per domain, like the key WebView) + 用系统浏览器打开
            val uaHost = remember(currentURL) { runCatching { java.net.URI(currentURL).host?.removePrefix("www.") }.getOrNull() }
            val uaPrefs = remember { context.getSharedPreferences("mines_browser_ua", android.content.Context.MODE_PRIVATE) }
            LaunchedEffect(uaHost) {
                val want = uaHost?.let { uaPrefs.getString(it, null) } ?: return@LaunchedEffect
                val p = if (want == "d") UserAgentProfile.DESKTOP_CHROME else UserAgentProfile.MOBILE_CHROME
                if (!isAgentBusy && p != userAgentProfile && userAgentProfile != UserAgentProfile.CUSTOM) tabPool.setUserAgentFromUI(p)
            }
            if (uaHost != null) Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val desktop = userAgentProfile == UserAgentProfile.DESKTOP_CHROME
                androidx.compose.material3.FilterChip(selected = desktop, enabled = !isAgentBusy, onClick = {
                    val p = if (desktop) UserAgentProfile.MOBILE_CHROME else UserAgentProfile.DESKTOP_CHROME
                    uaPrefs.edit().putString(uaHost, if (p == UserAgentProfile.DESKTOP_CHROME) "d" else "m").apply()
                    tabPool.setUserAgentFromUI(p)
                }, label = { Text(if (desktop) "电脑版" else "手机版", fontSize = MT.Caption) },
                    leadingIcon = { Icon(if (desktop) Icons.Default.Computer else Icons.Default.PhoneAndroid, null, Modifier.size(16.dp)) },
                    modifier = Modifier.height(30.dp))
                androidx.compose.material3.AssistChip(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentURL))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, label = { Text("用系统浏览器打开", fontSize = MT.Caption) }, modifier = Modifier.height(30.dp))
                Text(uaHost, fontSize = MT.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }

            // ── WebView + overlays ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(webViewScrollGuard),
            ) {
                if (selectedTab != null && currentURL.isNotEmpty()) {
                    // Only mount the WebView once there's something to display.
                    // Mounting an empty WebView (no URL loaded) corrupts the
                    // OpenGL swap behavior for the hosting ModalBottomSheet —
                    // sibling Compose UI (top nav, URL bar, tab chips) draws
                    // blank white on the first-open case until the WebView
                    // has real content. Keep it out of the hierarchy until
                    // the user navigates somewhere.
                    BrowserWebView(
                        webView = selectedTab.manager.webView,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (selectedTab == null) stringResource(R.string.browser_no_tab_open)
                            else stringResource(R.string.browser_enter_url_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // [Mines] While a captcha waits for the user, hand the page over.
                val minesVerifyPending by com.openminis.app.plus.HumanVerify.pending.collectAsState()
                // [38.9.13 handoff_v2] a handoff on this conversation: no 「Mines 正在浏览」 cover, a bar with 「继续接管」 instead
                val handoffs by com.openminis.app.plus.HandoffBroker.list.collectAsState()
                val myHandoff = handoffs.filter { it.sessionId == tabPool.sessionKey }.maxByOrNull { it.createdAt }
                if (isAgentBusy && minesVerifyPending == null && myHandoff?.open != true) {
                    AgentBrowsingOverlay(
                        accent = accent,
                        // [T-android-browser-release-all-semantics] Takeover
                        // hands the wheel to the user — the page they are
                        // looking at must stay exactly as it is.
                        onTakeover = { tabPool.releaseAllTabsToUser() },
                    )
                }
                myHandoff?.let { com.openminis.app.ui.plus.HandoffSheetBar(it, Modifier.align(Alignment.BottomCenter)) }
            }

            // ── Download progress banner ──
            val activeDownload by tabPool.activeDownload.collectAsState()
            activeDownload?.let { dl ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(secondaryBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = accent,
                    )
                    Text(
                        dl.filename,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dl.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { dl.progress },
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                    }
                }
            }

            // ── Bottom Toolbar ──
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(secondaryBg)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIcon(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDesc = stringResource(R.string.browser_nav_back),
                    enabled = canGoBack && !isAgentBusy,
                    onClick = { selectedTab?.manager?.goBack() },
                )
                ToolbarIcon(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDesc = stringResource(R.string.browser_nav_forward),
                    enabled = canGoForward && !isAgentBusy,
                    onClick = { selectedTab?.manager?.goForward() },
                )
                // [Mines-zoom-34] 文字大小 (per site, 100–200 %); pinch zoom always works too
                ToolbarIcon(
                    icon = Icons.Default.FormatSize,
                    contentDesc = "文字大小",
                    enabled = selectedTab != null,
                    onClick = { showTextSize = true },
                )
                if (payForm) ToolbarIcon(
                    icon = Icons.Default.CreditCard,
                    contentDesc = "填卡（只填写，不会替你付款）",
                    enabled = selectedTab != null && !isAgentBusy,
                    onClick = {
                        com.openminis.app.plus.PayCards.load(context)
                        val cs = com.openminis.app.plus.PayCards.cards.value
                        when {
                            cs.isEmpty() -> { android.widget.Toast.makeText(context, "还没有保存卡。去 设置 → 支付卡 添加", android.widget.Toast.LENGTH_LONG).show()
                                com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.CARDS) }
                            cs.size == 1 -> fillWith(cs.first())
                            else -> cardPick = true
                        }
                    },
                )
                // [Mines-translate-35] 翻译成中文 / 显示原文
                ToolbarIcon(
                    icon = Icons.Default.Translate,
                    contentDesc = if (translatedUrl != null) "显示原文" else if (translating) "正在翻译" else "翻译成中文",
                    enabled = selectedTab != null && !translating,
                    tint = if (translatedUrl != null) accent else null,
                    onClick = {
                        val wv = selectedTab?.manager?.webView ?: return@ToolbarIcon
                        if (translatedUrl != null && translatedUrl == wv.url) {
                            scope.launch { com.openminis.app.browser.WebTranslate.restore(wv); translatedUrl = null }
                        } else {
                            translating = true
                            android.widget.Toast.makeText(context, "正在翻译成中文…", android.widget.Toast.LENGTH_SHORT).show()
                            scope.launch {
                                runCatching { com.openminis.app.browser.WebTranslate.translate(context, wv) }
                                    .onSuccess { n ->
                                        translatedUrl = wv.url
                                        android.widget.Toast.makeText(context, if (n > 0) "已翻译成中文（再点一次看原文）" else "这页没有需要翻译的外文", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure { e ->
                                        android.widget.Toast.makeText(context, "翻译失败：${e.message?.take(60)}。再点「译」重试", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                translating = false
                            }
                        }
                    },
                )
                // [T-android-browser-download-ux] Downloads entry: shows while
                // the session has ANY download records, disappears when the
                // user clears the last one; the badge counts in-flight +
                // unviewed terminal entries and hides at 0 (iOS v2 spec).
                if (downloadEntries.isNotEmpty()) {
                    val badgeCount = tabPool.downloadBadgeCount(downloadEntries)
                    androidx.compose.material3.BadgedBox(
                        badge = {
                            if (badgeCount > 0) {
                                androidx.compose.material3.Badge { Text("$badgeCount") }
                            }
                        },
                    ) {
                        ToolbarIcon(
                            icon = Icons.Default.Download,
                            contentDesc = stringResource(R.string.browser_downloads_title),
                            enabled = true,
                            tint = accent,
                            onClick = { showDownloads = true },
                        )
                    }
                }
                if (isLoading) {
                    ToolbarIcon(
                        icon = Icons.Default.Close,
                        contentDesc = stringResource(R.string.browser_stop),
                        enabled = !isAgentBusy,
                        tint = accent,
                        onClick = { selectedTab?.manager?.stopLoading() },
                    )
                } else {
                    ToolbarIcon(
                        icon = Icons.Default.Refresh,
                        contentDesc = stringResource(R.string.browser_reload),
                        enabled = !isAgentBusy,
                        tint = accent,
                        onClick = { selectedTab?.manager?.reload() },
                    )
                }
            }
        }
    }

    // [Mines-translate-35] foreign page finished loading → one quiet hint per site
    androidx.compose.runtime.LaunchedEffect(isLoading, selectedTab) {
        if (isLoading) { translatedUrl = null; return@LaunchedEffect }
        val wv = selectedTab?.manager?.webView ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        val host = runCatching { android.net.Uri.parse(wv.url ?: "").host }.getOrNull() ?: return@LaunchedEffect
        if (host in hinted) return@LaunchedEffect
        if (com.openminis.app.browser.WebTranslate.detectForeign(wv)) {
            hinted += host
            android.widget.Toast.makeText(context, "这页是外文，点工具栏的「译」可以翻译成中文", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    if (showTextSize) {
        selectedTab?.manager?.let { mgr -> BrowserTextSizeDialog(mgr.webView, onDismiss = { showTextSize = false }) }
            ?: run { showTextSize = false }
    }

    if (showDownloads) {
        BrowserDownloadsSheet(
            tabPool = tabPool,
            onDismiss = { showDownloads = false },
        )
    }

    if (showHistory) {
        BrowserHistorySheet(
            historyStore = BrowserHistoryStore.getInstance(context),
            // [T-android-browser-history-no-tab] C1: with no tabs open,
            // selectedTab is null and the old safe-call silently dropped the
            // navigation. Create a tab first (same path as the "+" button);
            // if the pool refuses (MAX_TABS), just dismiss the sheet.
            onNavigate = { url ->
                scope.launch {
                    val tab = selectedTab ?: tabPool.newTabFromUI()
                    tab?.manager?.loadURL(url)
                    showHistory = false
                }
            },
            onDismiss = { showHistory = false },
        )
    }

    if (showSettings) {
        BrowserSettingsSheet(
            tabPool = tabPool,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun TabChip(
    title: String,
    isSelected: Boolean,
    showClose: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (isSelected) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = if (isSelected) accent.copy(alpha = 0.4f) else Color.Transparent
    val textColor = if (isSelected) accent else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .background(bg, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (showClose) 6.dp else 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = MT.Caption,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showClose) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.browser_close_tab),
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = contentDesc,
            modifier = Modifier.size(22.dp),
            tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                else tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Globe icon with a spinning arc border when loading. */
@Composable
private fun BrowserAddressBarIcon(isLoading: Boolean, accent: Color) {
    // [T-android-decorative-anim-perf] Clock only while loading (nothing to
    // spin otherwise), and the rotation moves to the layer lambda below —
    // `.rotate(angle)` read the value in composition, recomposing the icon
    // every frame on top of the spinner's own animation.
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isLoading) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isLoading) {
            // [T-android-decorative-anim-perf] DecorativeSpinner, not Material's:
            // rotating Material's indicator from our clock still left its OWN
            // InfiniteTransition asking for every frame, which is the whole
            // reason the chat spinners were swapped. A page load during agent
            // browsing is long-lived, so this one matters.
            DecorativeSpinner(
                color = accent,
                modifier = Modifier.size(22.dp),
                strokeWidth = 1.5.dp,
                periodMs = 1000,
            )
        }
    }
}

/** Breathing-light overlay shown when the agent is controlling the browser. */
@Composable
private fun AgentBrowsingOverlay(accent: Color, onTakeover: () -> Unit) {
    // [T-android-decorative-anim-perf] The breathing dot used to rebuild
    // `accent.copy(alpha = …)` in composition every frame; now the alpha is
    // a layer property read from the shared tick and the background colour
    // is constant.
    val tick = rememberDecorativeTick()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        alpha = 0.3f + 0.7f * decorativePingPong(decorativePhase(tick.value, 3000))
                    }
                    .background(accent, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.browser_minis_browsing),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = MT.Caption,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(Color.White.copy(alpha = 0.3f)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.browser_takeover),
                color = accent,
                fontSize = MT.Caption,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onTakeover),
            )
        }
    }
}

/** Normalize URL input: search terms → Google search, bare domains → https:// prefix. */
private fun normalizeURLInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.contains("://")) return trimmed
    if (trimmed.contains(' ') || !trimmed.contains('.')) {
        return "https://www.google.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
    }
    return "https://$trimmed"
}
