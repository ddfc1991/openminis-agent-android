package com.openminis.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.glass.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import sh.calvin.reorderable.ReorderableColumn
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.SwipeRowAction
import com.openminis.app.ui.components.SwipeRowActions
import com.openminis.app.logging.AppLogger
import com.openminis.app.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onProviderClick: (String) -> Unit,
    onVoiceServiceClick: (String) -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val instances = config.instances
    val groupedInstances = instances.groupBy { it.providerType }
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    // [T-android-swipe-row-actions] Pending swipe-delete target. Held here
    // rather than per-row so the confirmation survives the row being
    // recomposed/reordered underneath it.
    var instanceToDelete by remember {
        mutableStateOf<com.openminis.app.data.model.ProviderInstance?>(null)
    }

    // [Mines-38.9.11 i32] reason sheet + 一键修复
    var sheetFor by remember { mutableStateOf<String?>(null) }
    val fixScope = androidx.compose.runtime.rememberCoroutineScope()
    fun runFix(id: String) {
        if (com.openminis.app.plus.ProviderHealth.fixing.value.containsKey(id)) return
        fixScope.launch {
            val r = com.openminis.app.plus.ProviderHealth.fix(context, id)
            when (val h = r.hand) {
                is com.openminis.app.plus.ProviderHealth.Hand.Relogin -> { sheetFor = null; onProviderClick(h.id) }
                is com.openminis.app.plus.ProviderHealth.Hand.FetchKey -> {
                    sheetFor = null
                    com.openminis.app.ui.plus.PlusNav.fixAccount = h.kind
                    com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS)
                }
                else -> if (!r.ok) sheetFor = id
            }
            Toast.makeText(context, r.line, Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = ProviderImportZip.queryDisplayName(context, uri).orEmpty()
        val looksLikeZip = mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            name.lowercase().endsWith(".zip")
        try {
            if (looksLikeZip) {
                val toastFailed = context.getString(R.string.import_zip_extract_failed)
                val toastNoSupported = context.getString(R.string.import_zip_no_supported)
                ProviderImportZip.importFromZip(
                    context = context,
                    uri = uri,
                    onImportSingle = { jsonStr -> providerRepository.importInstanceJSON(jsonStr) },
                    onExtractFailed = { Toast.makeText(context, toastFailed, Toast.LENGTH_SHORT).show() },
                    onNoSupported = { Toast.makeText(context, toastNoSupported, Toast.LENGTH_SHORT).show() },
                    onSummary = { ok, total ->
                        val msg = context.getString(R.string.import_zip_summary, ok, total)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                )
            } else {
                val jsonStr = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (jsonStr != null) {
                    val label = providerRepository.importInstanceJSON(jsonStr)
                    if (label != null) {
                        Toast.makeText(context, "已导入服务商「$label」", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "服务商配置文件无效", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.provider_list_providers),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.provider_list_add_provider))
            }
        },
    ) {
        if (instances.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
                Text(
                    text = stringResource(R.string.provider_list_no_providers_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.provider_list_add_a_provider_to_get_started),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            // [Mines-38.9.11 i32] user 08:14: a flat list where every row had a green dot whatever its state. Now: a summary
            // chip + 全部检测, then 不能用 (with 一键修复 and the reason) and 能用 from REAL checks (ProviderHealth: one tiny
            // chat each, cached, background, three at a time), and 已停用 last. Long-press drag stays within a section.
            androidx.compose.runtime.LaunchedEffect(Unit) { com.openminis.app.plus.ProviderHealth.checkAll(context) }
            val checks by com.openminis.app.plus.ProviderHealth.checks.collectAsState()
            val running by com.openminis.app.plus.ProviderHealth.running.collectAsState()
            val fixing by com.openminis.app.plus.ProviderHealth.fixing.collectAsState()
            val enabled = instances.filter { it.isEnabled }
            val bad = enabled.filter { checks[it.id]?.ok == false && it.id !in running }
            val good = enabled.filter { it !in bad }
            val off = instances.filter { !it.isEnabled }
            val okCount = enabled.count { checks[it.id]?.ok == true && it.id !in running }
            ProviderMergeCard(providerRepository, instances.size)   // [Mines-38.9.11.2] 合并重复供应商
            KeyCheckCard(providerRepository)   // [Mines-38.9.11.3] 测试全部密钥
            ProviderHealthHeader(
                ok = okCount,
                bad = bad.size,
                pending = enabled.count { it.id in running || checks[it.id] == null },
                checking = running.isNotEmpty(),
                onCheckAll = { com.openminis.app.plus.ProviderHealth.checkAll(context, force = true) },
            )
            val sections = listOf("不能用" to bad, "能用" to good, "已停用" to off).filter { it.second.isNotEmpty() }
            sections.forEach { (title, list) ->
                SettingsSection(header = "$title · ${list.size}") {
                    var localOrder by remember(list.map { it.id }) { mutableStateOf(list) }
                    ReorderableColumn(
                        list = localOrder,
                        onSettle = { fromIndex, toIndex ->
                            localOrder = localOrder.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                            providerRepository.reorderInstances(localOrder.map { it.id })
                        },
                    ) { index, instance, isDragging ->
                        key(instance.id) {
                            val modelCount = providerRepository.visibleEntries(instance.id).size
                            val apiKey = providerRepository.loadApiKey(instance.id)
                            val elevation by animateDpAsState(targetValue = if (isDragging) 4.dp else 0.dp, label = "provider_drag_elevation")
                            Surface(shadowElevation = elevation, color = Color.Transparent, modifier = Modifier.longPressDraggableHandle()) {
                                SwipeRowActions(
                                    actions = listOf(
                                        SwipeRowAction(label = stringResource(R.string.common_edit), icon = Icons.Filled.Edit,
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            onClick = { onProviderClick(instance.id) }),
                                        SwipeRowAction(label = stringResource(R.string.common_delete), icon = Icons.Filled.Delete,
                                            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                            onClick = { instanceToDelete = instance }),
                                    ),
                                ) {
                                    ProviderHealthRow(
                                        instance = instance,
                                        modelCount = modelCount,
                                        apiKey = apiKey,
                                        check = checks[instance.id],
                                        checking = instance.id in running,
                                        fixingStep = fixing[instance.id],
                                        onClick = {
                                            if (checks[instance.id]?.ok == false) sheetFor = instance.id else onProviderClick(instance.id)
                                        },
                                        onFix = { runFix(instance.id) },
                                    )
                                }
                            }
                            if (index < localOrder.size - 1) {
                                Box(Modifier.fillMaxWidth().padding(start = 62.dp, end = 14.dp).height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                            }
                        }
                    }
                }
            }
        }

        // [T-android-provider-voice] Voice Services: runtime shadow mirror of
        // every enabled instance that owns audio-modality models (mirrors iOS
        // ProviderInstancesView's Voice Services section). Rows are read-only
        // views onto the underlying instance — no stored entity.
        val shadows = remember(config) { providerRepository.shadowVoiceProviders() }
        if (shadows.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.voice_services_section),
                footer = if (providerRepository.hasFoldedShadowDuplicates()) {
                    stringResource(R.string.voice_services_duplicate_hint)
                } else {
                    null
                },
            ) {
                shadows.forEachIndexed { index, shadow ->
                    ShadowVoiceRow(
                        shadow = shadow,
                        onClick = { onVoiceServiceClick(shadow.instanceId) },
                    )
                    if (index < shadows.size - 1) {
                        val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 38.dp, end = 14.dp)
                                .height(0.5.dp)
                                .background(divider),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }

    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            onAddProvider()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.provider_list_add_provider), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                ),
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.provider_list_import_provider), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    sheetFor?.let { id ->
        val inst = instances.firstOrNull { it.id == id }
        val checks by com.openminis.app.plus.ProviderHealth.checks.collectAsState()
        val fixing by com.openminis.app.plus.ProviderHealth.fixing.collectAsState()
        val running by com.openminis.app.plus.ProviderHealth.running.collectAsState()
        if (inst == null) { sheetFor = null } else ProviderReasonSheet(
            instance = inst,
            check = checks[id],
            busy = fixing[id] ?: if (id in running) "检测中…" else null,
            onDismiss = { sheetFor = null },
            onFix = { runFix(id) },
            onRetest = { com.openminis.app.plus.ProviderHealth.checkOne(context, id) },
            onOpen = { sheetFor = null; onProviderClick(id) },
        )
    }

    // [T-android-swipe-row-actions] Same dialog copy and same repository call as
    // ProviderDetailScreen's "Delete Provider" button, so swipe-delete and
    // detail-delete cannot drift apart.
    instanceToDelete?.let { target ->
        MinisAlertDialog(
            onDismissRequest = { instanceToDelete = null },
            title = stringResource(R.string.provider_detail_delete_provider),
            text = stringResource(
                R.string.provider_detail_delete_provider_confirm,
                target.label,
            ),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                providerRepository.removeInstance(target.id)
                AppLogger.info(
                    "ProviderList",
                    "Deleted provider instance ${target.id} (${target.label}) via swipe",
                )
                instanceToDelete = null
            },
        )
    }
}

@Composable
private fun ProviderInstanceRow(
    instance: ProviderInstance,
    modelCount: Int,
    apiKey: String?,
    isConfigured: Boolean,
    needsReauth: Boolean = false,
    onClick: () -> Unit,
) {
    val isActive = isConfigured && instance.isEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (needsReauth) MaterialTheme.colorScheme.error
                        else if (isActive) Color(0xFF34C759)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    shape = CircleShape,
                ),
        )

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = instance.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.provider_list_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                    // [T-empty-key-compat-endpoints] Keyless compatible endpoint:
                    // say so instead of the alarming "No API key".
                    text = if (needsReauth) stringResource(R.string.provider_oauth_sign_in_expired)
                        else if (!apiKey.isNullOrBlank()) maskKey(apiKey)
                        else if (instance.allowsEmptyAPIKey) stringResource(R.string.provider_no_key_required)
                        else "No API key",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsReauth) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (modelCount > 0) {
                Text(
                    text = stringResource(R.string.provider_list_models_count, modelCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        if (!instance.isEnabled) {
            Text(
                text = stringResource(R.string.provider_list_disabled),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(50),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun maskKey(key: String): String {
    if (key.length <= 8) return "****"
    return key.take(6) + "..." + key.takeLast(4)
}

/** One shadow Voice Service row: name + ASR/TTS model counts. */
@Composable
private fun ShadowVoiceRow(
    shadow: ProviderRepository.ShadowVoiceProvider,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = shadow.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val parts = buildList {
                if (shadow.inputModels.isNotEmpty()) {
                    add(stringResource(R.string.voice_services_stt_count, shadow.inputModels.size))
                }
                if (shadow.outputModels.isNotEmpty()) {
                    add(stringResource(R.string.voice_services_tts_count, shadow.outputModels.size))
                }
            }
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}


// ───────────────────────── [Mines-38.9.11 i32] 能用 / 不能用 ─────────────────────────

private val OkGreen = Color(0xFF2E9E5B)
private val OkTint = Color(0x1F2E9E5B)

@Composable
private fun ProviderHealthHeader(ok: Int, bad: Int, pending: Int, checking: Boolean, onCheckAll: () -> Unit) {
    val summary = com.openminis.app.plus.ProviderHealthCore.summary(ok, bad, pending)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .androidx_semantics(summary),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountChip("能用 $ok", OkTint, OkGreen)
        Spacer(Modifier.width(6.dp))
        CountChip("不能用 $bad", if (bad > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            if (bad > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (pending > 0) {
            Spacer(Modifier.width(6.dp))
            CountChip("检测中 $pending", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(enabled = !checking, onClick = onCheckAll)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            else Icon(androidx.compose.material.icons.Icons.Default.Refresh, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(4.dp))
            Text(if (checking) "检测中" else "全部检测", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private fun Modifier.androidx_semantics(desc: String): Modifier =
    this.semantics(mergeDescendants = false) { contentDescription = desc }

@Composable
private fun CountChip(text: String, bg: Color, fg: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 4.dp))
}

/** brand icon of the service (vault catalogue by API host), GitHub for Copilot, else a soft letter avatar */
@Composable
private fun ProviderAvatar(instance: ProviderInstance) {
    val icon = remember(instance.id, instance.customBaseURL) {
        when {
            instance.providerType == com.openminis.app.data.model.ProviderType.githubCopilot -> R.drawable.ic_brand_github
            else -> runCatching {
                com.openminis.app.plus.ProviderKeys.kindFor(instance.customBaseURL ?: instance.effectiveBaseURL, instance.providerType)
                    ?.let { com.openminis.app.plus.PlusVault.kind(it)?.icon }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    if (icon != null && icon != 0) {
        Box(Modifier.size(36.dp).clip(shape).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(22.dp))
        }
    } else {
        val hue = com.openminis.app.plus.ProviderHealthCore.avatarHue(instance.label)
        Box(Modifier.size(36.dp).clip(shape).background(Color.hsl(hue, 0.42f, 0.86f)), contentAlignment = Alignment.Center) {
            Text(com.openminis.app.plus.ProviderHealthCore.initial(instance.label), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.hsl(hue, 0.45f, 0.30f))
        }
    }
}

@Composable
private fun StatusPill(check: com.openminis.app.plus.ProviderHealthCore.Check?, checking: Boolean, enabled: Boolean) {
    val (text, bg, fg) = when {
        !enabled -> Triple("已停用", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        checking || check == null -> Triple("检测中", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        check.ok -> Triple("能用", OkTint, OkGreen)
        else -> Triple("不能用", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
    }
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (enabled && (checking || check == null)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.2.dp, color = fg)
            Spacer(Modifier.width(4.dp))
        } else {
            Box(Modifier.size(6.dp).background(fg, CircleShape))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

private fun credentialLine(instance: ProviderInstance, apiKey: String?): String = when {
    instance.customBaseURL?.contains("127.0.0.1") == true -> "免登录"
    instance.providerType == com.openminis.app.data.model.ProviderType.githubCopilot -> "GitHub 登录"
    instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth -> "账号登录"
    !apiKey.isNullOrBlank() -> maskKey(apiKey)
    instance.allowsEmptyAPIKey -> "无需密钥"
    else -> "还没填密钥"
}

@Composable
private fun ProviderHealthRow(
    instance: ProviderInstance,
    modelCount: Int,
    apiKey: String?,
    check: com.openminis.app.plus.ProviderHealthCore.Check?,
    checking: Boolean,
    fixingStep: String?,
    onClick: () -> Unit,
    onFix: () -> Unit,
) {
    val bad = instance.isEnabled && check?.ok == false && !checking
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderAvatar(instance)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(instance.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (modelCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$modelCount 模型", style = MaterialTheme.typography.labelSmall, color = ink2, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            val now = System.currentTimeMillis()
            val meta = buildList {
                add(credentialLine(instance, apiKey))
                if (check != null && !checking) add(com.openminis.app.plus.ProviderHealthCore.ago(check.at, now))
                if (check?.ok == true && check.ms > 0) add("${check.ms} 毫秒")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = ink2, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (bad) {
                Text(fixingStep ?: check!!.reason, style = MaterialTheme.typography.bodySmall,
                    color = if (fixingStep != null) ink2 else MaterialTheme.colorScheme.error, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(check, checking, instance.isEnabled)
            if (bad) {
                val busy = fixingStep != null
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.error)
                        .clickable(enabled = !busy, onClick = onFix).padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onError)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(if (busy) "修复中" else "一键修复", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onError, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderReasonSheet(
    instance: ProviderInstance,
    check: com.openminis.app.plus.ProviderHealthCore.Check?,
    busy: String?,
    onDismiss: () -> Unit,
    onFix: () -> Unit,
    onRetest: () -> Unit,
    onOpen: () -> Unit,
) {
    var showRaw by remember(instance.id) { mutableStateOf(false) }
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderAvatar(instance)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(instance.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(listOfNotNull(check?.model?.takeIf { it.isNotBlank() }?.let { "测试模型 $it" },
                        check?.let { com.openminis.app.plus.ProviderHealthCore.ago(it.at, System.currentTimeMillis()) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = ink2, maxLines = 1)
                }
                StatusPill(check, busy != null && check == null, instance.isEnabled)
            }
            Text("为什么不能用", style = MaterialTheme.typography.labelLarge, color = ink2)
            Text(check?.reason ?: "还没检测", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (check != null && !check.ok) {
                val oauth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth
                val plan = com.openminis.app.plus.ProviderHealthCore.fixPlan(check.kind, oauth, hasVaultKey = true, hasKeyRecipe = true)
                Text("一键修复会：" + plan.joinToString(" → ") { it.zh }, style = MaterialTheme.typography.bodySmall, color = ink2)
            }
            if (busy != null) Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
                Text(busy, style = MaterialTheme.typography.bodySmall, color = ink2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onFix, enabled = busy == null, modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("一键修复") }
                androidx.compose.material3.OutlinedButton(onClick = onRetest, enabled = busy == null, modifier = Modifier.weight(1f)) { Text("重新检测") }
                androidx.compose.material3.OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("打开详情") }
            }
            val raw = check?.raw.orEmpty()
            if (raw.isNotBlank()) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { showRaw = !showRaw }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showRaw) "收起原始信息" else "原始信息（给开发者看）", style = MaterialTheme.typography.labelMedium, color = ink2)
                }
                if (showRaw) Text(raw, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = ink2, maxLines = 12,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp))
            }
        }
    }
}

/**
 * [Mines-38.9.11.2] 合并重复供应商: shown when two or more providers share a base-URL host. Tap → preview of every group →
 * 确定合并 (the old config + keys are backed up first) → 撤销 stays available until dismissed. Never merges on its own.
 */
/** [Mines-38.9.11.3] 测试全部密钥: every key checked once, invalid ones flagged (and skipped by chat) — one tap, off the main thread */
@Composable
private fun KeyCheckCard(repo: ProviderRepository) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (running) "正在测试密钥… $progress" else "测试全部密钥", style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(enabled = !running, onClick = {
                running = true; lines = emptyList()
                scope.launch {
                    val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { val h = android.os.Handler(android.os.Looper.getMainLooper()); com.openminis.app.plus.KeyCheck.runAll(repo) { d, n -> h.post { progress = "$d/$n" } } }.getOrDefault(emptyList())
                    }
                    lines = if (rows.isEmpty()) listOf("没有可测的密钥") else com.openminis.app.plus.KeyCheckCore.summary(rows)
                    running = false; progress = ""
                }
            }) { Text(if (running) "测试中" else "开始") }
        }
        lines.forEach { l -> Text(l, style = MaterialTheme.typography.bodyMedium, color = if ("无效" in l) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        if (lines.any { "无效" in it }) Text("无效的密钥已标记，聊天会自动跳过它们。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProviderMergeCard(repo: ProviderRepository, count: Int) {
    val context = LocalContext.current
    val config by repo.config.collectAsState()
    // [38.9.11.3] user: 「卡」 — the plan decrypts every provider's keys: computed off the main thread, only on this screen
    val plan by androidx.compose.runtime.produceState(emptyList<com.openminis.app.plus.ProviderMergeCore.Group>(), config) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { repo.mergePlan() }.getOrDefault(emptyList()) }
    }
    var preview by remember { mutableStateOf(false) }
    var hasBackup by remember(config) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(config) { hasBackup = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { repo.hasMergeBackup() }.getOrDefault(false) } }
    if (plan.isEmpty() && !hasBackup) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (plan.isNotEmpty()) {
            Text("合并重复供应商", style = MaterialTheme.typography.titleMedium)
            Text("发现 ${plan.size} 组地址相同的供应商（共 ${plan.sumOf { it.members.size }} 个）。合并后每组只留 1 个，所有 key 轮换使用，模型合在一起。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.TextButton(onClick = { preview = true }) { Text("看看怎么合并") }
        }
        if (hasBackup) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已合并，原来的配置备份着", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.TextButton(onClick = {
                    val ok = repo.undoMerge(); hasBackup = repo.hasMergeBackup()
                    Toast.makeText(context, if (ok) "已撤销合并，恢复成原来的 $count 个以上供应商" else "没有可以撤销的合并", Toast.LENGTH_SHORT).show()
                }) { Text("撤销") }
                androidx.compose.material3.TextButton(onClick = { repo.dropMergeBackup(); hasBackup = false }) { Text("不用了") }
            }
        }
    }
    if (preview) {
        com.openminis.app.ui.glass.AlertDialog(
            onDismissRequest = { preview = false },
            title = { Text("合并重复供应商") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan.forEach { g -> Text("• " + com.openminis.app.plus.ProviderMergeCore.line(g), style = MaterialTheme.typography.bodyMedium) }
                    Text("合并前会先备份，合并后可以撤销。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    preview = false
                    val n = repo.applyMerge(plan); hasBackup = repo.hasMergeBackup()
                    Toast.makeText(context, "已合并 $n 组，可以撤销", Toast.LENGTH_SHORT).show()
                }) { Text("确定合并") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { preview = false }) { Text("取消") } },
        )
    }
}
