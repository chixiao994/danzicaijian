package com.singlecrop.app.ui

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singlecrop.app.AppViewModel

@Composable
fun CropScreen(vm: AppViewModel) {
    val ctx = LocalContext.current

    val inputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.setInputFolder(it) } }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.setOutputFolder(it) } }

    val toastMsg = vm.toast
    LaunchedEffect(toastMsg) {
        if (!toastMsg.isNullOrEmpty()) {
            Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
            vm.toast = null
        }
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            Surface(color = Color(0xFF1E1E1E)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    // 第一行：输入 / 输出 / 保存 / 自动
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { inputPicker.launch(null) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)
                        ) { Text("输入", fontSize = 13.sp) }

                        Button(
                            onClick = { outputPicker.launch(null) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)
                        ) { Text("输出", fontSize = 13.sp) }

                        Button(
                            onClick = { vm.saveAllFolder() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)
                        ) { Text("保存", fontSize = 13.sp) }

                        Button(
                            onClick = { vm.autoDetectCurrent() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)
                        ) { Text("自动", fontSize = 13.sp) }
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "输入: ${treeDisplayName(vm.inputTreeUri)}",
                            fontSize = 10.sp,
                            color = Color(0xFF9E9E9E),
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "输出: ${treeDisplayName(vm.outputTreeUri)}",
                            fontSize = 10.sp,
                            color = Color(0xFF9E9E9E),
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val pos = if (vm.images.isEmpty()) "0 / 0"
                        else "${vm.index + 1} / ${vm.images.size}"
                        Text(pos, fontSize = 11.sp, color = Color(0xFFBDBDBD))
                        Text(
                            "已记录: ${vm.recordCount}",
                            fontSize = 11.sp,
                            color = Color(0xFFBDBDBD)
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = Color(0xFF1E1E1E)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { vm.prev() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) { Text("上一张", fontSize = 15.sp) }

                    OutlinedButton(
                        onClick = { vm.skip() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) { Text("跳过", fontSize = 15.sp) }

                    Button(
                        onClick = { vm.next() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("下一张", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
        ) {
            val bmp = vm.bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                CropOverlay(
                    imageWidth = bmp.width,
                    imageHeight = bmp.height,
                    rect = vm.cropRect,
                    autoRect = vm.autoRect,
                    onCommit = { vm.commitCropRect(it) },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    "请选择输入文件夹",
                    color = Color(0xFF757575),
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 顶部小提示：自动 / 手动
            if (bmp != null) {
                val tip = when {
                    vm.detecting -> "自动识别中…"
                    vm.rectDirty -> "手动框选"
                    vm.autoRect != null -> "自动框选（可拖拽覆盖）"
                    else -> ""
                }
                if (tip.isNotEmpty()) {
                    Text(
                        tip,
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                    )
                }
            }

            if (vm.loading || vm.busy || vm.detecting) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFFFFD54F)
                )
            }

            vm.batchProgress?.let { (cur, total) ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在处理整个文件夹", color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { if (total > 0) cur.toFloat() / total else 0f },
                            modifier = Modifier.width(220.dp),
                            color = Color(0xFFFFD54F)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("$cur / $total", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun treeDisplayName(uri: Uri?): String {
    if (uri == null) return "未选择"
    return try {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val p = id.substringAfterLast(':', id)
        p.substringAfterLast('/').ifEmpty { id }
    } catch (e: Exception) {
        uri.lastPathSegment ?: "已选择"
    }
}
