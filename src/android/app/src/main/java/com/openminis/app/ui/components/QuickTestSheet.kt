package com.openminis.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.glass.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.normalizeModalityName
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope

private const val TAG = "QuickTest"

/**
 * [T-android-model-quick-test] A lightweight half-sheet that fires
 * modality-matched smoke tests against one model so the user can confirm
 * "this model + credential actually works" without leaving the provider
 * screen. Mirrors iOS `ModelQuickTestSheet`.
 *
 * Up to 3 applicable tests run CONCURRENTLY, each with its own
 * running / success / failure state laid out as a stacked card.
 */

/** One testable capability. Priority order (most distinctive first) is
 *  imageGen > speechOut > transcription > text. */
enum class QuickTestKind {
    IMAGE_GEN,
    SPEECH_OUT,
    TRANSCRIPTION,
    TEXT;

    val titleRes: Int
        get() = when (this) {
            TEXT -> R.string.quicktest_kind_text
            IMAGE_GEN -> R.string.quicktest_kind_image
            SPEECH_OUT -> R.string.quicktest_kind_speech
            TRANSCRIPTION -> R.string.quicktest_kind_transcription
        }

    val icon: ImageVector
        get() = when (this) {
            TEXT -> Icons.Outlined.TextFields
            IMAGE_GEN -> Icons.Outlined.Image
            SPEECH_OUT -> Icons.Outlined.GraphicEq
            TRANSCRIPTION -> Icons.Outlined.Mic
        }
}

/** Result state of a single test run. */
sealed class QuickTestState {
    object Running : QuickTestState()
    data class TextReply(val text: String) : QuickTestState()
    data class ImageReply(val data: ByteArray) : QuickTestState()
    /** [T-android-provider-voice] Synthesized audio bytes, playable in place. */
    data class AudioReply(val data: ByteArray) : QuickTestState()
    data class Failure(val message: String) : QuickTestState()
}

/** Mutable per-run holder observed by its card. */
private class QuickTestRun(val kind: QuickTestKind) {
    var state by mutableStateOf<QuickTestState>(QuickTestState.Running)
    var elapsedMs by mutableStateOf(0L)
}

/**
 * Top-3 most distinctive applicable test kinds for [entry]. A model is
 * testable for a kind when its effective modality exposes that capability.
 * Text is always included as a baseline if the model has text output;
 * if nothing matches we fall back to text so there is always one test.
 */
internal fun applicableKinds(entry: ModelEntry): List<QuickTestKind> {
    val model = entry.model
    val outputs = (model.outputModalities ?: emptyList()).map { it.normalizeModalityName() }
    val inputs = (model.inputModalities ?: emptyList()).map { it.normalizeModalityName() }
    val kinds = mutableListOf<QuickTestKind>()
    if ("image" in outputs) kinds.add(QuickTestKind.IMAGE_GEN)
    if ("audio" in outputs) kinds.add(QuickTestKind.SPEECH_OUT)
    if ("audio" in inputs) kinds.add(QuickTestKind.TRANSCRIPTION)
    // outputModalities null/empty ⇒ text-out (documented convention), so text
    // is applicable whenever the list is empty OR explicitly contains text.
    if (outputs.isEmpty() || "text" in outputs) kinds.add(QuickTestKind.TEXT)
    if (kinds.isEmpty()) kinds.add(QuickTestKind.TEXT)
    return kinds.take(3)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTestSheet(
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    /** [Mines-38.9.9 i26] the name the picker row shows (「Mines-Test · 1908」 when two models share a name) */
    titleName: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs = remember(entry.id) {
        mutableStateListOf<QuickTestRun>().apply {
            applicableKinds(entry).forEach { add(QuickTestRun(it)) }
        }
    }
    val isRunning by remember { derivedStateOf { runs.any { it.state is QuickTestState.Running } } }

    // Kick off all tests on first show (once per entry).
    androidx.compose.runtime.LaunchedEffect(entry.id) {
        runAll(scope, context, entry, providerRepository, runs)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Toolbar row: title + Run again / Done.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.quicktest_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { runAll(scope, context, entry, providerRepository, runs) },
                    enabled = !isRunning,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.quicktest_run_again),
                    )
                }
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.quicktest_done))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Header: icon + name + id.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        titleName ?: entry.model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        entry.model.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            runs.forEach { run ->
                TestCard(run)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun TestCard(run: QuickTestRun) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // [Mines-38.9.6 i14] a quiet tint on the sheet's own glass (was surfaceContainerLow: another translucent layer)
            .background(com.openminis.app.ui.plus.mines().ink.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, com.openminis.app.ui.plus.mines().glassBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                run.kind.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(run.kind.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(run)
        }
        Spacer(Modifier.height(8.dp))
        TestContent(run)
    }
}

@Composable
private fun StatusBadge(run: QuickTestRun) {
    when (val s = run.state) {
        is QuickTestState.Running -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        is QuickTestState.Failure -> Icon(
            Icons.Filled.Cancel,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            if (run.elapsedMs > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    String.format("%.1fs", run.elapsedMs / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TestContent(run: QuickTestRun) {
    when (val s = run.state) {
        is QuickTestState.Running -> {
            // [Mines-38.9.8] seconds so far — a stuck test is visible, and it stops at the limit with a reason
            val secs by androidx.compose.runtime.produceState(0) { while (true) { kotlinx.coroutines.delay(1000); value += 1 } }
            Text(
                stringResource(R.string.quicktest_testing) + if (secs >= 3) " ${secs} 秒（最多 ${quickTestLimitMs(run.kind) / 1000} 秒）" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is QuickTestState.TextReply -> Text(
            s.text,
            style = MaterialTheme.typography.bodyMedium,
        )
        is QuickTestState.ImageReply -> {
            val bmp = remember(s.data) {
                runCatching { BitmapFactory.decodeByteArray(s.data, 0, s.data.size) }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(8.dp),
                        ),
                )
            } else {
                Text(
                    "Received ${s.data.size} bytes (couldn't decode preview)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is QuickTestState.AudioReply -> AudioReplyContent(s.data)
        is QuickTestState.Failure -> Text(
            s.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [T-android-provider-voice] Playable synthesized-audio result. Writes the
 * bytes to a cache file and drives a MediaPlayer; auto-plays once on first
 * appearance (mirrors iOS Quick Test playing the returned clip).
 */
@Composable
private fun AudioReplyContent(data: ByteArray) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    val player = remember { android.media.MediaPlayer() }
    val file = remember(data) {
        java.io.File(context.cacheDir, "quicktest-audio-${data.hashCode()}.bin").apply {
            writeBytes(data)
        }
    }

    fun play() {
        runCatching {
            player.reset()
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { isPlaying = false }
            player.prepare()
            player.start()
            isPlaying = true
        }.onFailure {
            AppLogger.warning(TAG, "[QuickTest] audio playback failed: ${it.message}")
            isPlaying = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(file) { play() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            runCatching { file.delete() }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (!isPlaying) play() }) {
            Icon(
                if (isPlaying) Icons.Outlined.GraphicEq else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.quicktest_audio_play else R.string.quicktest_audio_replay,
                ),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "%.1f KB".format(data.size / 1024.0),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Reset every run to Running and fire each test concurrently. */
private fun runAll(
    scope: CoroutineScope,
    context: android.content.Context,
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    runs: SnapshotStateList<QuickTestRun>,
) {
    runs.forEach { it.state = QuickTestState.Running; it.elapsedMs = 0 }
    runs.forEach { run ->
        AppLogger.info(TAG, "[QuickTest] start model=${entry.model.id} kind=${run.kind}")
        scope.launch {
            val start = System.currentTimeMillis()
            // [Mines-38.9.8] real phone: 「测试中…」 forever (a 免登录 web route never answered) — every test has a limit now and
            // says why it stopped (for the 免登录 gateway: that route's own last reason)
            val limit = quickTestLimitMs(run.kind)
            // detached so a blocking socket read can not hold the timeout back (await is cancellable, the call is not)
            val call = CoroutineScope(Dispatchers.IO).async { performTest(run.kind, entry, providerRepository, context) }
            val result = kotlinx.coroutines.withTimeoutOrNull(limit) { call.await() }
                ?: QuickTestState.Failure(quickTestTimeoutText(limit, entry, providerRepository)).also { call.cancel() }
            run.elapsedMs = System.currentTimeMillis() - start
            run.state = result
            when (result) {
                is QuickTestState.Failure ->
                    AppLogger.warning(TAG, "[QuickTest] FAIL model=${entry.model.id} kind=${run.kind}: ${result.message}")
                else ->
                    AppLogger.info(TAG, "[QuickTest] OK model=${entry.model.id} kind=${run.kind} in ${run.elapsedMs}ms")
            }
        }
    }
}

internal fun quickTestLimitMs(kind: QuickTestKind): Long = when (kind) {
    QuickTestKind.IMAGE_GEN -> 120_000L
    QuickTestKind.TEXT -> 60_000L
    else -> 90_000L
}

private fun quickTestTimeoutText(limit: Long, entry: ModelEntry, repo: ProviderRepository): String {
    val base = "${limit / 1000} 秒没有回复，已停止。"
    val inst = runCatching { repo.instance(entry.providerInstanceId) }.getOrNull()
    val gw = inst?.customBaseURL?.contains("127.0.0.1:${com.openminis.app.plus.PartnerGateway.PORT}") == true
    if (gw) {
        val why = com.openminis.app.plus.PartnerGateway.statusOf(entry.model.id)?.reason?.takeIf { it.isNotBlank() }
        return base + (why ?: "这是免登录 AI（网页版 / 扣子）路线：网页没打开或要重新登录，在模型组顶部「免登录 AI」里看状态")
    }
    val host = runCatching { java.net.URI(inst?.effectiveBaseURL ?: "").host }.getOrNull().orEmpty()
    return base + if (host.isNotBlank()) "$host 没响应：检查网络 / 代理，或换一个模型" else "检查网络 / 代理，或换一个模型"
}

/** Run one smoke test against a real provider (no mocks). */
internal suspend fun performTest(
    kind: QuickTestKind,
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    context: android.content.Context,
): QuickTestState = withContext(Dispatchers.IO) {
    fun failure(msg: String) = QuickTestState.Failure(msg)

    val instance = providerRepository.instance(entry.providerInstanceId)
        ?: return@withContext failure("找不到这个服务商")
    // [T-android-keyless-provider-selection] usableApiKey, NOT loadApiKey.
    //
    // A self-hosted OpenAI/Anthropic-compatible endpoint (ollama, LM Studio,
    // LiteLLM, an unauthenticated internal gateway) legitimately stores no key,
    // so `loadApiKey` returns null and Quick Test refused to run at all —
    // reporting "No API key configured" for a provider that needs none, before
    // any request was attempted. That is the same local short-circuit the
    // refresh path had (f2be9a834); the credential decision belongs to the
    // server, not to a pre-flight null check.
    //
    // `usableApiKey` substitutes "" exactly where an empty key is a valid
    // configuration (`ProviderInstance.allowsEmptyAPIKey`) and still returns
    // null for an official endpoint with no key, so providers that genuinely
    // require one keep failing fast with the same message.
    // [Mines-38.9.9] user (GitHub Copilot signed in, 「该服务商还没配置 API Key」): an OAuth provider (Copilot, Kimi Code,
    // Google 账号) has no API-key slot — the factory reads its token itself, so "" is the right argument (same as chat).
    val apiKey = providerRepository.usableApiKey(instance)
        ?: (if (providerRepository.hasAnyCredential(instance)) "" else null)
        ?: return@withContext failure(if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.apiKey)
            "该服务商还没配置 API Key" else "还没登录这个服务商：到服务商页面点「登录」")

    // [T-android-provider-voice] Speech tests route through the VoiceProvider
    // stack (vendor adapters + endpoints), NOT the chat provider — mirrors iOS
    // ModelQuickTestSheet .speechOut / .transcription.
    if (kind == QuickTestKind.SPEECH_OUT || kind == QuickTestKind.TRANSCRIPTION) {
        val voice = com.openminis.app.provider.voice.VoiceProviderFactory.make(instance, apiKey)
            ?: return@withContext failure(context.getString(R.string.quicktest_voice_unsupported))
        return@withContext when (kind) {
            QuickTestKind.SPEECH_OUT -> {
                if (!voice.supportsVoiceOutput) {
                    failure(context.getString(R.string.quicktest_voice_unsupported))
                } else {
                    runCatching {
                        // The selected entry id is the model AND the voice id
                        // (template voices carry the voice id as the model id) —
                        // iOS 0a52bdbf: pass entry.model.id for both so the test
                        // speaks in THAT voice, not the vendor default.
                        val data = voice.synthesize(
                            com.openminis.app.provider.voice.VoiceOutputRequest(
                                input = "Hi! This is Mines testing text to speech.",
                                model = entry.model.id,
                                voice = entry.model.id,
                                resolvedModel = entry.model,
                            ),
                        )
                        if (data.isEmpty()) {
                            failure(context.getString(R.string.quicktest_no_audio))
                        } else {
                            QuickTestState.AudioReply(data)
                        }
                    }.getOrElse { failure(zhTestError(it.message, "语音合成请求失败")) }
                }
            }
            else -> { // TRANSCRIPTION
                if (!voice.supportsVoiceInput) {
                    failure(context.getString(R.string.quicktest_voice_unsupported))
                } else {
                    val spoken = "Hello from Mines, testing speech to text."
                    val clip = synthesizeTestClip(context, spoken)
                        ?: return@withContext failure(context.getString(R.string.quicktest_clip_failed))
                    runCatching {
                        val resp = voice.transcribe(
                            com.openminis.app.provider.voice.VoiceInputRequest(
                                audioData = clip,
                                model = entry.baseModel.id,
                                language = "en",
                                resolvedModel = entry.model,
                            ),
                        )
                        val heard = resp.text.trim().ifEmpty { "(empty transcription)" }
                        QuickTestState.TextReply(
                            context.getString(R.string.quicktest_transcript_result, spoken, heard),
                        )
                    }.getOrElse { failure(zhTestError(it.message, "语音转写请求失败")) }
                }
            }
        }
    }

    val provider = runCatching {
        ProviderFactory.create(instance, apiKey, entry.model, context, overrides = entry.overrides)
    }.getOrElse { return@withContext failure(zhTestError(it.message, "无法创建服务商连接")) }

    when (kind) {
        QuickTestKind.TEXT -> {
            runCatching {
                val resp = provider.sendMessage(
                    messages = listOf(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = "Hi! I'm setting you up in Mines. Say hello back in one short, friendly sentence.",
                        ),
                    ),
                    systemPrompt = null,
                    maxTokens = 128,
                    temperature = null,
                )
                val text = resp.text.trim()
                QuickTestState.TextReply(
                    text.ifEmpty { context.getString(R.string.quicktest_empty_reply) },
                )
            }.getOrElse { failure(zhTestError(it.message, "请求失败")) }
        }

        QuickTestKind.IMAGE_GEN -> {
            val openAI = provider as? OpenAIProvider
                ?: return@withContext failure(context.getString(R.string.quicktest_image_unsupported))
            runCatching {
                val resp = openAI.generateImage(
                    prompt = "A friendly cute mascot logo for an app called Mines, minimalist, centered, soft colors",
                    n = 1,
                    size = "1024x1024",
                    quality = null,
                )
                val img = resp.mediaAttachments.firstOrNull {
                    it.type == LLMMediaAttachment.MediaType.IMAGE
                } ?: return@runCatching failure(context.getString(R.string.quicktest_no_image))
                QuickTestState.ImageReply(img.data)
            }.getOrElse { failure(zhTestError(it.message, "图片请求失败")) }
        }

        // Handled above.
        QuickTestKind.SPEECH_OUT, QuickTestKind.TRANSCRIPTION ->
            failure(context.getString(R.string.quicktest_speech_unavailable))
    }
}

/**
 * [T-android-provider-voice] Synthesize a short English test clip as a WAV via
 * the system TextToSpeech engine (no bundled audio asset). Android equivalent
 * of iOS synthesizeTestWAV (AVSpeechSynthesizer buffer capture); Android's
 * synthesizeToFile already writes a WAV container. Returns null on any
 * engine/init failure (caller reports a friendly message). 15 s guard so a
 * stuck engine can't hang the test card forever.
 */
private suspend fun synthesizeTestClip(context: android.content.Context, text: String): ByteArray? {
    val file = java.io.File(context.cacheDir, "quicktest-clip-${System.currentTimeMillis()}.wav")
    return try {
        kotlinx.coroutines.withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var tts: android.speech.tts.TextToSpeech? = null
                fun finish(ok: Boolean) {
                    runCatching { tts?.shutdown() }
                    if (cont.isActive) cont.resumeWith(Result.success(ok))
                }
                tts = android.speech.tts.TextToSpeech(context) { status ->
                    if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                        finish(false)
                        return@TextToSpeech
                    }
                    tts?.language = java.util.Locale.US
                    tts?.setOnUtteranceProgressListener(
                        object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) = finish(true)
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) = finish(false)
                            override fun onError(utteranceId: String?, errorCode: Int) = finish(false)
                        },
                    )
                    val result = tts?.synthesizeToFile(text, android.os.Bundle(), file, "quicktest-clip")
                    if (result != android.speech.tts.TextToSpeech.SUCCESS) finish(false)
                }
                cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
            }
        }?.takeIf { it }?.let { file.readBytes() }
    } catch (e: Exception) {
        AppLogger.warning(TAG, "[QuickTest] test clip synthesis failed: ${e.message}")
        null
    } finally {
        runCatching { file.delete() }
    }
}

/** [Mines-38.9.6 i14] quick-test errors in Chinese (user: no English in this sheet); unknown text kept after a Chinese lead. */
internal fun zhTestError(msg: String?, lead: String): String {
    val m0 = msg?.trim().orEmpty()
    if (m0.isEmpty()) return lead
    // [Mines-38.9.11 i32] the provider already said why in Chinese (Copilot / GitHub Models / Cloudflare reasons ride inside
    // 「Invalid API key: …」) — keep that sentence instead of collapsing it to 「API Key 无效（401）」
    val m = m0.replace(Regex("^(Invalid API key|Provider error|Transient error|Rate limited): "), "")
    if (m != m0 && m.any { it.code in 0x4E00..0x9FFF }) return "$lead：$m"
    val low = m.lowercase()
    return when {
        "401" in low || "unauthorized" in low || "invalid api key" in low || "incorrect api key" in low -> "$lead：API Key 无效（401）"
        "403" in low || "forbidden" in low -> "$lead：没有权限（403）"
        "404" in low || "not found" in low -> "$lead：模型或地址不存在（404）"
        "429" in low || "rate limit" in low || "too many requests" in low -> "$lead：请求太频繁或额度用完（429）"
        com.openminis.app.provider.openai.ConnRetry.isDroppedText(m) && m.none { it.code in 0x4E00..0x9FFF } -> "$lead：" + com.openminis.app.provider.openai.ConnRetry.zhText(m)
        "timeout" in low || "timed out" in low -> "$lead：连接超时"
        "unable to resolve host" in low || "unknownhost" in low -> "$lead：连不上服务器（域名解析失败）"
        "failed to connect" in low || "connection refused" in low || "connect" in low && "fail" in low -> "$lead：连不上服务器"
        m.any { it.code in 0x4E00..0x9FFF } -> m
        else -> "$lead：$m"
    }
}

/** [Mines-38.9.6 i14] smoke: the next model detail page opens its 快速测试 sheet at once */
object QuickTestAuto {
    @Volatile var armed: Boolean = false
    /** [Mines-38.9.8] smoke: open the chat's model picker once, and (optionally) the 快速测试 of this model id over it */
    @Volatile var pickerArmed: Boolean = false
    @Volatile var pickerQuickModel: String? = null
    fun consume(): Boolean = armed.also { armed = false }
}
