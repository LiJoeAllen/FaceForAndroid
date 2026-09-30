// IdentityTab.kt —— 身份 Tab：1:1 比对、1:N 底库检索和人脸底库管理
// （注册/重命名/删除/清空）三大块。

package cn.heavenlybook.facemirror

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 一条 1:N 命中：底库条目（人像 + 名字）加相似度，按相似度从高到低排列。 */
private data class SearchHit(val entry: FaceGalleryStore.Entry, val similarity: Float)

/** 候选列表的展示下限（比判定阈值低）：判定是否同一人仍以检索阈值为准。 */
private const val CANDIDATE_FLOOR = 0.30f
private const val CANDIDATE_LIMIT = 5

private const val TAG = "IdentityTab"

/** Tab 2「身份」：1:1 比对、1:N 检索和人脸底库管理。 */
@Composable
internal fun IdentityTab(engine: FaceSdk, settings: AppSettings, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var portrait by remember { mutableStateOf<Bitmap?>(null) }
    var probe by remember { mutableStateOf<Bitmap?>(null) }
    var compareSimilarity by remember { mutableStateOf<Float?>(null) }
    var candidates by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var galleryEntries by remember { mutableStateOf<List<FaceGalleryStore.Entry>>(emptyList()) }
    var galleryCount by remember { mutableStateOf(engine.database.count) }
    var busy by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FaceGalleryStore.Entry?>(null) }
    var deleteTarget by remember { mutableStateOf<FaceGalleryStore.Entry?>(null) }

    fun refreshGallery() {
        galleryEntries = engine.gallery.entries()
        galleryCount = engine.database.count
    }

    // 首次进入 Tab 就把已注册的档案加载出来 —— 此前只在注册/删除后刷新,
    // 冷启动直接看列表会误显示"底库为空"。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        refreshGallery()
    }

    /** 本 Tab 所有引擎操作的统一外壳：上 busy 锁、切到推理线程池、失败转 snackbar。 */
    fun onEngineThread(block: suspend () -> Unit) {
        busy = true
        scope.launch(engine.inferenceDispatcher) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "身份 Tab 引擎操作失败", e)
                withContext(Dispatchers.Main) { onMessage("操作失败：${e.message}") }
            } finally {
                busy = false
            }
        }
    }

    val pickPortrait = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            onEngineThread {
                val bitmap = decodeScaled(context, uri)
                withContext(Dispatchers.Main) {
                    portrait = bitmap
                    compareSimilarity = null
                }
            }
        }
    }
    val pickProbe = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            onEngineThread {
                val bitmap = decodeScaled(context, uri)
                withContext(Dispatchers.Main) {
                    probe = bitmap
                    compareSimilarity = null
                    candidates = emptyList()
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // -------------------------------------------------- 1:1 比对
        item {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1:1 人脸比对", style = MiuixTheme.textStyles.title3)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PortraitSlot(
                            label = "图 A",
                            bitmap = portrait,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { pickPortrait.launch(pickRequest()) }
                        PortraitSlot(
                            label = "图 B",
                            bitmap = probe,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { pickProbe.launch(pickRequest()) }
                    }
                    Button(
                        onClick = {
                            val a = portrait
                            val b = probe
                            if (a == null || b == null) {
                                onMessage("1:1 比对需要同时选择图 A 和图 B")
                            } else {
                                onEngineThread {
                                    val similarity = engine.database.compare(a, b)
                                    Log.d(TAG, "1:1 比对完成 相似度=$similarity")
                                    withContext(Dispatchers.Main) {
                                        compareSimilarity = similarity
                                        if (similarity == null) onMessage("至少一张图中未检测到人脸")
                                    }
                                }
                            }
                        },
                        enabled = !busy,
                    ) { Text(if (busy) "处理中…" else "A ↔ B 比对") }
                    compareSimilarity?.let {
                        SimilarityVerdict(it, settings.verifyThreshold)
                    }
                }
            }
        }

        // -------------------------------------------------- 1:N 检索
        item {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1:N 底库检索", style = MiuixTheme.textStyles.title3)
                    Text(
                        "对图 B 在底库中找出最相似的人;底片请先在下方注册。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Button(
                        onClick = {
                            val b = probe
                            if (b == null) {
                                onMessage("请先选择图 B")
                            } else if (engine.database.count == 0L) {
                                onMessage("底库为空,请先注册图 A")
                            } else {
                                onEngineThread {
                                    if (synchronized(engine.nativeLock) { engine.pipeline.detectLargest(b) } == null) {
                                        withContext(Dispatchers.Main) { onMessage("图 B 中未检测到人脸") }
                                        return@onEngineThread
                                    }
                                    // 展示用 top-K 候选（带展示下限）；是否判定为
                                    // 同一人仍以检索阈值单独衡量。
                                    val matches = engine.database.searchAll(b, CANDIDATE_FLOOR, CANDIDATE_LIMIT)
                                    Log.d(TAG, "1:N 检索完成 候选数=${matches.size} 库容=${engine.database.count}")
                                    withContext(Dispatchers.Main) {
                                        candidates = matches.map { SearchHit(engine.gallery.entry(it.index), it.similarity) }
                                        if (matches.isEmpty()) onMessage("无相似候选(图 B 与底库均需含人脸)")
                                    }
                                }
                            }
                        },
                        enabled = !busy,
                    ) { Text("用图 B 检索") }
                    candidates.forEachIndexed { i, hit ->
                        val pass = hit.similarity >= settings.searchThreshold
                        if (i == 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                GalleryAvatar(hit.entry.photo, 64.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "最相似:${hit.entry.name}",
                                        style = MiuixTheme.textStyles.title4,
                                    )
                                    Text(
                                        "相似度 ${"%.3f".format(hit.similarity)} · " +
                                            (if (pass) "达到阈值" else "低于阈值"),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = if (pass) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                GalleryAvatar(hit.entry.photo, 36.dp)
                                Text(hit.entry.name, style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
                                Text(
                                    "%.3f".format(hit.similarity),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (pass) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------- 底库管理
        item {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("人脸底库($galleryCount)", style = MiuixTheme.textStyles.title3)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val a = portrait
                                if (a == null) {
                                    onMessage("请先选择图 A")
                                } else {
                                    onEngineThread {
                                        val face = synchronized(engine.nativeLock) { engine.pipeline.detectLargest(a) }
                                        val index = face?.landmarks?.let {
                                            engine.database.add(a, face.rect, it)
                                        }
                                        if (index != null) {
                                            engine.gallery.savePortrait(index, a, face.rect)
                                            // 默认名也入注册表:改名时才有初值,条目才会被列出。
                                            engine.gallery.setName(index, engine.gallery.name(index))
                                            engine.saveGallery()
                                            Log.i(TAG, "注册成功 index=$index model=${settings.recognizerModel}")
                                            withContext(Dispatchers.Main) {
                                                refreshGallery()
                                                onMessage("已注册:${engine.gallery.name(index)}")
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) { onMessage("图 A 中未检测到人脸") }
                                        }
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text("注册图 A") }
                        TextButton(
                            text = "清空",
                            onClick = { showClearConfirm = true },
                            enabled = !busy && galleryCount > 0,
                        )
                    }
                    if (galleryEntries.isEmpty()) {
                        EmptyHint("底库为空;注册后可在此重命名或删除")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(galleryEntries, key = { it.index }) { entry ->
                                GalleryEntryCard(
                                    entry = entry,
                                    enabled = !busy,
                                    onRename = { renameTarget = entry },
                                    onDelete = { deleteTarget = entry },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ConfirmDialog(
        show = showClearConfirm,
        title = "清空人脸底库",
        message = "将删除全部 ${galleryCount} 条人脸特征与档案图片,确定继续?",
        confirmLabel = "清空",
        onConfirm = {
            showClearConfirm = false
            onEngineThread {
                Log.i(TAG, "清空底库（原库容 $galleryCount）")
                engine.database.clear()
                engine.gallery.clear()
                engine.saveGallery()
                withContext(Dispatchers.Main) {
                    refreshGallery()
                    candidates = emptyList()
                    onMessage("底库已清空")
                }
            }
        },
        onDismiss = { showClearConfirm = false },
    )

    deleteTarget?.let { entry ->
        ConfirmDialog(
            show = true,
            title = "删除条目",
            message = "将删除「${entry.name}」的人脸特征与档案图片,确定继续?",
            onConfirm = {
                deleteTarget = null
                onEngineThread {
                    Log.i(TAG, "删除条目 index=${entry.index}")
                    engine.database.remove(entry.index)
                    engine.gallery.remove(entry.index)
                    engine.saveGallery()
                    withContext(Dispatchers.Main) {
                        refreshGallery()
                        candidates = candidates.filter { it.entry.index != entry.index }
                        onMessage("已删除:${entry.name}")
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    renameTarget?.let { entry ->
        RenameDialog(
            initial = entry.name,
            onConfirm = { name ->
                renameTarget = null
                onEngineThread {
                    engine.gallery.setName(entry.index, name)
                    withContext(Dispatchers.Main) { refreshGallery() }
                }
            },
            onDismiss = { renameTarget = null },
        )
    }
}

/** 重命名弹窗：输入备注名并回传。 */
@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    WindowDialog(
        show = true,
        title = "重命名",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = "备注名",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onConfirm(text) },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }
}

/** 可点击的人像槽位：已选图时显示图片，未选时显示选取提示。 */
@Composable
private fun PortraitSlot(
    label: String,
    bitmap: Bitmap?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPick: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(if (enabled) Modifier.clickable(onClick = onPick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "点击选择$label",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(label, style = MiuixTheme.textStyles.footnote1)
    }
}

/** 底库条目小卡：人像（或占位）、名字，附改名/删除操作。 */
@Composable
private fun GalleryEntryCard(
    entry: FaceGalleryStore.Entry,
    enabled: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.width(132.dp),
    ) {
        GalleryAvatar(entry.photo, 72.dp)
        Text(
            entry.name,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "改名",
                style = MiuixTheme.textStyles.footnote2,
                color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledOnSurface,
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onRename)
                    .padding(4.dp),
            )
            Text(
                "删除",
                style = MiuixTheme.textStyles.footnote2,
                color = if (enabled) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.disabledOnSurface,
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onDelete)
                    .padding(4.dp),
            )
        }
    }
}

/** 圆角方形人像；档案图缺失时显示占位。 */
@Composable
internal fun GalleryAvatar(photo: java.io.File?, size: Dp) {
    val bitmap = remember(photo?.path) {
        photo?.let {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(it.absolutePath)
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "档案头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("无档案", style = MiuixTheme.textStyles.footnote2)
        }
    }
}

private fun pickRequest() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
