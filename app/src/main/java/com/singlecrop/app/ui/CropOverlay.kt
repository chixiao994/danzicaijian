package com.singlecrop.app.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

data class FitInfo(val scale: Float, val offsetX: Float, val offsetY: Float)

fun computeFit(boxW: Float, boxH: Float, imgW: Float, imgH: Float): FitInfo {
    if (boxW <= 0f || boxH <= 0f || imgW <= 0f || imgH <= 0f) return FitInfo(0f, 0f, 0f)
    val s = minOf(boxW / imgW, boxH / imgH)
    val dw = imgW * s
    val dh = imgH * s
    return FitInfo(s, (boxW - dw) / 2f, (boxH - dh) / 2f)
}

fun screenToImageRect(
    a: Offset,
    b: Offset,
    fit: FitInfo,
    imgW: Int,
    imgH: Int
): RectF {
    if (fit.scale <= 0f) return RectF()
    val x1 = ((a.x - fit.offsetX) / fit.scale).coerceIn(0f, imgW.toFloat())
    val y1 = ((a.y - fit.offsetY) / fit.scale).coerceIn(0f, imgH.toFloat())
    val x2 = ((b.x - fit.offsetX) / fit.scale).coerceIn(0f, imgW.toFloat())
    val y2 = ((b.y - fit.offsetY) / fit.scale).coerceIn(0f, imgH.toFloat())
    return RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
}

@Composable
fun CropOverlay(
    imageWidth: Int,
    imageHeight: Int,
    rect: RectF?,
    autoRect: RectF?,
    onCommit: (RectF) -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var dragging by remember { mutableStateOf(false) }

    val fit = remember(boxSize, imageWidth, imageHeight) {
        computeFit(
            boxSize.width.toFloat(),
            boxSize.height.toFloat(),
            imageWidth.toFloat(),
            imageHeight.toFloat()
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(imageWidth, imageHeight, fit) {
                detectDragGestures(
                    onDragStart = { off ->
                        dragStart = off
                        dragEnd = off
                        dragging = true
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragEnd = change.position
                    },
                    onDragEnd = {
                        val a = dragStart
                        val b = dragEnd
                        dragging = false
                        dragStart = null
                        dragEnd = null
                        if (a != null && b != null) {
                            val r = screenToImageRect(a, b, fit, imageWidth, imageHeight)
                            if (r.width() >= 3f && r.height() >= 3f) onCommit(r)
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        dragStart = null
                        dragEnd = null
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (fit.scale <= 0f) return@Canvas

            val imgL = fit.offsetX
            val imgT = fit.offsetY
            val imgR = imgL + imageWidth * fit.scale
            val imgB = imgT + imageHeight * fit.scale

            val mask = Color(0xAA000000)

            val preview: RectF? = if (dragging) {
                val a = dragStart
                val b = dragEnd
                if (a != null && b != null) {
                    screenToImageRect(a, b, fit, imageWidth, imageHeight)
                } else null
            } else rect

            if (preview == null || preview.width() < 1f || preview.height() < 1f) {
                drawRect(
                    color = mask,
                    topLeft = Offset(imgL, imgT),
                    size = Size(max(0f, imgR - imgL), max(0f, imgB - imgT))
                )
                return@Canvas
            }

            val l = imgL + preview.left * fit.scale
            val t = imgT + preview.top * fit.scale
            val r = imgL + preview.right * fit.scale
            val b = imgT + preview.bottom * fit.scale

            // 四周遮罩
            drawRect(mask, Offset(imgL, imgT), Size(max(0f, imgR - imgL), max(0f, t - imgT)))
            drawRect(mask, Offset(imgL, b), Size(max(0f, imgR - imgL), max(0f, imgB - b)))
            drawRect(mask, Offset(imgL, t), Size(max(0f, l - imgL), max(0f, b - t)))
            drawRect(mask, Offset(r, t), Size(max(0f, imgR - r), max(0f, b - t)))

            // 边框
            val accent = Color(0xFFFFD54F)
            val rw = max(0f, r - l)
            val rh = max(0f, b - t)
            drawRect(
                color = accent,
                topLeft = Offset(l, t),
                size = Size(rw, rh),
                style = Stroke(width = 2.dp.toPx())
            )

            // 四角标记
            val corner = min(22f, min(rw, rh) * 0.28f).coerceAtLeast(6f)
            val cs = 4.dp.toPx()
            drawLine(accent, Offset(l, t), Offset(l + corner, t), cs)
            drawLine(accent, Offset(l, t), Offset(l, t + corner), cs)
            drawLine(accent, Offset(r, t), Offset(r - corner, t), cs)
            drawLine(accent, Offset(r, t), Offset(r, t + corner), cs)
            drawLine(accent, Offset(l, b), Offset(l + corner, b), cs)
            drawLine(accent, Offset(l, b), Offset(l, b - corner), cs)
            drawLine(accent, Offset(r, b), Offset(r - corner, b), cs)
            drawLine(accent, Offset(r, b), Offset(r, b - corner), cs)
        }
    }
}
