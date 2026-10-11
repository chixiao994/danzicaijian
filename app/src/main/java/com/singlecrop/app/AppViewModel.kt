package com.singlecrop.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: Application get() = getApplication()

    // ---------------- 状态 ----------------
    var inputTreeUri by mutableStateOf<Uri?>(null)
        private set

    var outputTreeUri by mutableStateOf<Uri?>(null)
        private set

    var images by mutableStateOf<List<ImageItem>>(emptyList())
        private set

    var index by mutableStateOf(0)
        private set

    var bitmap by mutableStateOf<Bitmap?>(null)
        private set

    /** 当前裁剪框（图像像素坐标） */
    var cropRect by mutableStateOf<RectF?>(null)
        private set

    /** 自动识别得到的框，仅作为 UI 提示用 */
    var autoRect by mutableStateOf<RectF?>(null)
        private set

    /** 用户是否重新框选过 */
    var rectDirty by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    var detecting by mutableStateOf(false)
        private set

    var toast by mutableStateOf<String?>(null)

    var recordCount by mutableStateOf(0)
        private set

    var batchProgress by mutableStateOf<Pair<Int, Int>?>(null)

    /** 打开图片时自动识别主体字 */
    var autoDetectOnLoad by mutableStateOf(true)

    /** 批量保存时，对未记录图片自动识别后再导出 */
    var autoDetectOnSave by mutableStateOf(true)

    // ---------------- 内部 ----------------
    private val project = Project()
    private var writer: OutputWriter? = null

    // ---------------- 文件夹选择 ----------------

    fun setInputFolder(uri: Uri) {
        viewModelScope.launch {
            loading = true
            try {
                takePermission(uri)
                inputTreeUri = uri

                val stored = withContext(Dispatchers.IO) { ProjectStore.load(appCtx) }
                if (stored.inputTreeUri == uri.toString()) {
                    project.inputTreeUri = stored.inputTreeUri
                    project.outputTreeUri = stored.outputTreeUri
                    project.lastIndex = stored.lastIndex
                    project.records.clear()
                    project.records.putAll(stored.records)
                } else {
                    project.reset(uri.toString())
                }
                recordCount = project.records.size

                stored.outputTreeUri?.let { s ->
                    try {
                        val u = Uri.parse(s)
                        if (hasPermission(u)) {
                            outputTreeUri = u
                            writer = OutputWriter(appCtx, u)
                        }
                    } catch (_: Exception) { }
                }

                val list = withContext(Dispatchers.IO) {
                    Storage.listImages(appCtx.contentResolver, uri)
                }
                images = list
                if (list.isEmpty()) {
                    toast = "该文件夹未找到图片"
                    index = 0
                    bitmap = null
                    cropRect = null
                    autoRect = null
                } else {
                    index = project.lastIndex.coerceIn(0, list.size - 1)
                    loadCurrent()
                }
            } catch (e: Exception) {
                toast = "打开输入文件夹失败: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun setOutputFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                takePermission(uri)
                outputTreeUri = uri
                writer = OutputWriter(appCtx, uri)
                project.outputTreeUri = uri.toString()
                persistProject()
                toast = "输出文件夹已设置"
            } catch (e: Exception) {
                toast = "打开输出文件夹失败: ${e.message}"
            }
        }
    }

    private fun takePermission(uri: Uri) {
        try {
            appCtx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            try {
                appCtx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
        }
    }

    private fun hasPermission(uri: Uri): Boolean {
        return try {
            appCtx.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        } catch (_: Exception) { false }
    }

    // ---------------- 翻页 ----------------

    fun next() {
        viewModelScope.launch {
            saveCurrentIfDirty()
            advance(1)
        }
    }

    fun prev() {
        viewModelScope.launch {
            saveCurrentIfDirty()
            advance(-1)
        }
    }

    fun skip() {
        viewModelScope.launch {
            rectDirty = false
            advance(1)
        }
    }

    private fun advance(delta: Int) {
        val ni = index + delta
        if (ni < 0) { toast = "已经是第一张"; return }
        if (ni >= images.size) { toast = "已经是最后一张"; return }
        index = ni
        project.lastIndex = ni
        persistProject()
        loadCurrent()
    }

    fun loadCurrent() {
        val item = images.getOrNull(index)
        if (item == null) {
            bitmap = null; cropRect = null; autoRect = null; rectDirty = false
            return
        }
        val capturedIndex = index
        viewModelScope.launch {
            loading = true
            val bmp = withContext(Dispatchers.IO) {
                Storage.decodeBitmap(appCtx, item.uri)
            }
            bitmap = bmp

            val rec = project.records[item.name]
            if (rec != null && bmp != null && rec.w > 0 && rec.h > 0) {
                cropRect = RectF(
                    rec.x.toFloat(), rec.y.toFloat(),
                    (rec.x + rec.w).toFloat(), (rec.y + rec.h).toFloat()
                )
                autoRect = null
                rectDirty = false
                loading = false
                return@launch
            }

            // 无记录：按需自动识别
            loading = false
            if (bmp != null && autoDetectOnLoad) {
                detecting = true
                val r = withContext(Dispatchers.IO) { AutoDetector.detect(bmp) }
                detecting = false
                if (index == capturedIndex) {
                    autoRect = r
                    cropRect = r
                    rectDirty = false
                }
            } else {
                autoRect = null
                cropRect = null
                rectDirty = false
            }
        }
    }

    /** 手动对当前图重新执行自动识别 */
    fun autoDetectCurrent() {
        val bmp = bitmap ?: return
        val capturedIndex = index
        viewModelScope.launch {
            detecting = true
            val r = withContext(Dispatchers.IO) { AutoDetector.detect(bmp) }
            detecting = false
            if (index != capturedIndex) return@launch
            if (r != null) {
                autoRect = r
                cropRect = r
                rectDirty = false
                toast = "已自动识别"
            } else {
                toast = "未识别到主体字"
            }
        }
    }

    /** 清除当前框 */
    fun clearRect() {
        cropRect = null
        autoRect = null
        rectDirty = true
    }

    // ---------------- 框选 ----------------

    fun commitCropRect(r: RectF) {
        cropRect = r
        rectDirty = true
    }

    // ---------------- 保存 ----------------

    private suspend fun saveCurrentIfDirty() {
        if (!rectDirty) return
        val item = images.getOrNull(index) ?: return
        val rect = cropRect ?: return
        if (rect.width() < 2f || rect.height() < 2f) return
        doSave(item, rect)
    }

    private suspend fun doSave(item: ImageItem, rect: RectF): Boolean {
        val outUri = outputTreeUri
        if (outUri == null) { toast = "请先选择输出文件夹"; return false }
        busy = true
        val result: Rect? = withContext(Dispatchers.IO) {
            try {
                val w = writer ?: OutputWriter(appCtx, outUri).also { writer = it }
                val src = Storage.decodeBitmap(appCtx, item.uri) ?: return@withContext null
                val r = clampRect(rect, src.width, src.height)
                val cropped = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                val outName = Storage.outputName(item.name)
                if (w.write(outName, cropped)) r else null
            } catch (e: Exception) {
                e.printStackTrace(); null
            }
        }
        busy = false

        if (result != null) {
            project.records[item.name] =
                CropRecord(result.left, result.top, result.width(), result.height())
            recordCount = project.records.size
            rectDirty = false
            persistProject()
            return true
        } else {
            toast = "保存失败: ${item.name}"
            return false
        }
    }

    private fun clampRect(rect: RectF, w: Int, h: Int): Rect {
        val l = rect.left.roundToInt().coerceIn(0, w - 1)
        val t = rect.top.roundToInt().coerceIn(0, h - 1)
        val r = rect.right.roundToInt().coerceIn(l + 1, w)
        val b = rect.bottom.roundToInt().coerceIn(t + 1, h)
        return Rect(l, t, r, b)
    }

    // ---------------- 整个文件夹处理 ----------------

    fun saveAllFolder() {
        viewModelScope.launch { saveAll() }
    }

    private suspend fun saveAll() {
        if (images.isEmpty()) { toast = "请先选择输入文件夹"; return }
        val outUri = outputTreeUri
        if (outUri == null) { toast = "请先选择输出文件夹"; return }

        busy = true
        val w = writer ?: OutputWriter(appCtx, outUri).also { writer = it }

        var done = 0        // 按记录导出
        var autoDone = 0    // 自动识别后导出
        var skipped = 0     // 已存在 / 跳过
        var failed = 0      // 失败

        val total = images.size
        for ((i, item) in images.withIndex()) {
            batchProgress = (i + 1) to total
            val outName = Storage.outputName(item.name)
            if (w.exists(outName)) { skipped++; continue }

            val rec = project.records[item.name]
            if (rec == null) {
                if (!autoDetectOnSave) { skipped++; continue }
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val src = Storage.decodeBitmap(appCtx, item.uri) ?: return@withContext false
                        val detected = AutoDetector.detect(src) ?: return@withContext false
                        val r = clampRect(detected, src.width, src.height)
                        val cropped = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                        if (w.write(outName, cropped)) {
                            project.records[item.name] =
                                CropRecord(r.left, r.top, r.width(), r.height())
                            true
                        } else false
                    } catch (e: Exception) {
                        e.printStackTrace(); false
                    }
                }
                if (ok) autoDone++ else failed++
                continue
            }

            val ok = withContext(Dispatchers.IO) {
                try {
                    val src = Storage.decodeBitmap(appCtx, item.uri) ?: return@withContext false
                    val rf = RectF(
                        rec.x.toFloat(), rec.y.toFloat(),
                        (rec.x + rec.w).toFloat(), (rec.y + rec.h).toFloat()
                    )
                    val r = clampRect(rf, src.width, src.height)
                    val cropped = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                    w.write(outName, cropped)
                } catch (e: Exception) {
                    e.printStackTrace(); false
                }
            }
            if (ok) done++ else failed++
        }

        recordCount = project.records.size
        batchProgress = null
        busy = false
        persistProject()
        toast = "完成：框选 $done，自动 $autoDone，跳过 $skipped，失败 $failed"
    }

    // ---------------- 项目持久化 ----------------

    private fun persistProject() {
        project.outputTreeUri = outputTreeUri?.toString()
        project.lastIndex = index
        val json = project.toJson()
        viewModelScope.launch(Dispatchers.IO) {
            ProjectStore.write(appCtx, json)
        }
    }
}
