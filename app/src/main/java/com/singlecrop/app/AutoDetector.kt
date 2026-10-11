package com.singlecrop.app

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 古籍单字自动检测器（传统图像处理，离线，无第三方依赖）。
 *
 * 处理流程：
 *   1. 下采样（长边 <= 800）以提速
 *   2. 灰度化（BT.601）
 *   3. 局部自适应二值化（积分图均值阈值），前景 = 笔画
 *   4. 反相保护：若前景占比过低说明是白字黑底，做反相
 *   5. 长竖线检测 + 移除（版面分隔线/框线干扰）
 *   6. 形态学闭运算（膨胀 -> 腐蚀）连接断笔
 *   7. 4-邻域连通域分析
 *   8. 面积 / 宽高比 / 填充率过滤
 *   9. 综合打分（面积大 + 居中 + 接近方形 + 填充率合理）取最高
 *  10. 映射回原图坐标并外扩少量边距
 */
object AutoDetector {

    private const val MAX_DIM = 800
    private const val PAD = 3

    fun detect(src: Bitmap): RectF? {
        if (src.width < 4 || src.height < 4) return null

        // ---------- 1. 缩放 ----------
        val scale = min(1f, MAX_DIM.toFloat() / max(src.width, src.height))
        val w = max(2, (src.width * scale).roundToInt())
        val h = max(2, (src.height * scale).roundToInt())
        val small: Bitmap = if (w == src.width && h == src.height) src
        else Bitmap.createScaledBitmap(src, w, h, true)

        // ---------- 2. 灰度 ----------
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // ---------- 3. 自适应二值化 ----------
        val bin = adaptiveThreshold(gray, w, h)

        // ---------- 4. 反相保护 ----------
        var fg = 0
        for (v in bin) if (v.toInt() == 1) fg++
        val ratio = fg.toDouble() / bin.size
        if (ratio < 0.015) {
            for (i in bin.indices) bin[i] = if (bin[i].toInt() == 1) 0 else 1
            fg = bin.size - fg
        }
        if (fg < 10) return null

        // ---------- 5. 去竖线 ----------
        val lineMask = detectVerticalLineMask(bin, w, h)
        if (lineMask != null) {
            for (i in bin.indices) {
                if (lineMask[i].toInt() == 1 && bin[i].toInt() == 1) bin[i] = 0
            }
        }

        // ---------- 6. 闭运算 ----------
        val closed = erode(dilate(bin, w, h, 2), w, h, 2)

        // ---------- 7. 连通域 ----------
        val comps = connectedComponents(closed, w, h)
        if (comps.isEmpty()) return null

        // ---------- 8 + 9. 过滤与打分 ----------
        val total = w * h
        val cx = w / 2.0
        val cy = h / 2.0
        var best: Component? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (c in comps) {
            if (c.area < total * 0.004) continue
            val cw = c.maxX - c.minX + 1
            val ch = c.maxY - c.minY + 1
            val ar = cw.toDouble() / ch
            if (ar < 0.25 || ar > 4.0) continue
            val fill = c.area.toDouble() / (cw.toDouble() * ch)
            if (fill < 0.10) continue

            val ccx = (c.minX + c.maxX) / 2.0
            val ccy = (c.minY + c.maxY) / 2.0
            val dx = (ccx - cx) / w
            val dy = (ccy - cy) / h
            val dist = hypot(dx, dy)

            val areaScore = c.area.toDouble() / total
            val centerScore = 1.0 - (dist / 0.7).coerceAtMost(1.0)
            val arScore = 1.0 - (abs(ln(ar)) / 1.4).coerceAtMost(1.0)
            val fillScore = when {
                fill < 0.15 -> fill / 0.15
                fill > 0.85 -> (1.0 - fill) / 0.15
                else -> 1.0
            }

            val score = areaScore * 0.45 +
                    centerScore * 0.25 +
                    arScore * 0.15 +
                    fillScore * 0.15

            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }

        val b = best ?: return null

        // ---------- 10. 映射回原图 ----------
        val inv = 1.0f / scale
        val l = ((b.minX - PAD) * inv).roundToInt().coerceIn(0, src.width - 1)
        val t = ((b.minY - PAD) * inv).roundToInt().coerceIn(0, src.height - 1)
        val r = ((b.maxX + 1 + PAD) * inv).roundToInt().coerceIn(l + 1, src.width)
        val bot = ((b.maxY + 1 + PAD) * inv).roundToInt().coerceIn(t + 1, src.height)

        val rw = r - l
        val rh = bot - t
        if (rw < 6 || rh < 6) return null
        if (rw.toDouble() / src.width > 0.98 && rh.toDouble() / src.height > 0.98) return null

        return RectF(l.toFloat(), t.toFloat(), r.toFloat(), bot.toFloat())
    }

    // ======================= 内部算法 =======================

    private fun adaptiveThreshold(gray: IntArray, w: Int, h: Int): ByteArray {
        val iw = w + 1
        val integral = LongArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            val base = y * w
            val iRow = (y + 1) * iw
            val iPrev = y * iw
            for (x in 0 until w) {
                rowSum += gray[base + x].toLong()
                integral[iRow + x + 1] = integral[iPrev + x + 1] + rowSum
            }
        }

        val out = ByteArray(w * h)
        val win = max(21, min(w, h) / 16)
        val half = win / 2
        val C = 8

        for (y in 0 until h) {
            val y1 = max(0, y - half)
            val y2 = min(h - 1, y + half)
            val iy1 = y1 * iw
            val iy2 = (y2 + 1) * iw
            val rowBase = y * w
            for (x in 0 until w) {
                val x1 = max(0, x - half)
                val x2 = min(w - 1, x + half)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[iy2 + x2 + 1] -
                        integral[iy1 + x2 + 1] -
                        integral[iy2 + x1] +
                        integral[iy1 + x1]
                val mean = (sum / area).toInt()
                out[rowBase + x] = if (gray[rowBase + x] < mean - C) 1 else 0
            }
        }
        return out
    }

    /** 检测长竖线（版面分隔线/框线）并返回其覆盖掩码，1 = 属于竖线 */
    private fun detectVerticalLineMask(bin: ByteArray, w: Int, h: Int): ByteArray? {
        val minRun = max(20, (h * 0.35).toInt())
        val raw = ByteArray(w * h)
        var any = false

        for (x in 0 until w) {
            var runStart = -1
            for (y in 0..h) {
                val on = y < h && bin[y * w + x].toInt() == 1
                if (on && runStart < 0) runStart = y
                if (!on && runStart >= 0) {
                    val len = y - runStart
                    if (len >= minRun) {
                        any = true
                        for (yy in runStart until y) raw[yy * w + x] = 1
                    }
                    runStart = -1
                }
            }
        }
        if (!any) return null

        // 水平膨胀，覆盖整条竖线的宽度
        val r = max(1, w / 200)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                var hit = false
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                for (xx in x0..x1) {
                    if (raw[rowBase + xx].toInt() == 1) {
                        hit = true; break
                    }
                }
                if (hit) out[rowBase + x] = 1
            }
        }
        return out
    }

    private fun dilate(bin: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            val rowBase = y * w
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                var found = false
                outer@ for (yy in y0..y1) {
                    val b = yy * w
                    for (xx in x0..x1) {
                        if (bin[b + xx].toInt() == 1) {
                            found = true; break@outer
                        }
                    }
                }
                if (found) out[rowBase + x] = 1
            }
        }
        return out
    }

    private fun erode(bin: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            val rowBase = y * w
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                var allOn = true
                outer@ for (yy in y0..y1) {
                    val b = yy * w
                    for (xx in x0..x1) {
                        if (bin[b + xx].toInt() == 0) {
                            allOn = false; break@outer
                        }
                    }
                }
                if (allOn) out[rowBase + x] = 1
            }
        }
        return out
    }

    private class Component(
        var minX: Int, var minY: Int,
        var maxX: Int, var maxY: Int,
        var area: Int
    )

    private fun connectedComponents(bin: ByteArray, w: Int, h: Int): List<Component> {
        val n = w * h
        val labels = IntArray(n)
        val stack = IntArray(n)
        val out = ArrayList<Component>()
        var label = 1

        for (start in 0 until n) {
            if (bin[start].toInt() != 1 || labels[start] != 0) continue
            var sp = 0
            stack[sp++] = start
            labels[start] = label
            var minX = start % w
            var maxX = minX
            var minY = start / w
            var maxY = minY
            var area = 0

            while (sp > 0) {
                val p = stack[--sp]
                area++
                val px = p % w
                val py = p / w
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py

                if (px > 0) {
                    val q = p - 1
                    if (bin[q].toInt() == 1 && labels[q] == 0) {
                        labels[q] = label; stack[sp++] = q
                    }
                }
                if (px < w - 1) {
                    val q = p + 1
                    if (bin[q].toInt() == 1 && labels[q] == 0) {
                        labels[q] = label; stack[sp++] = q
                    }
                }
                if (py > 0) {
                    val q = p - w
                    if (bin[q].toInt() == 1 && labels[q] == 0) {
                        labels[q] = label; stack[sp++] = q
                    }
                }
                if (py < h - 1) {
                    val q = p + w
                    if (bin[q].toInt() == 1 && labels[q] == 0) {
                        labels[q] = label; stack[sp++] = q
                    }
                }
            }
            out.add(Component(minX, minY, maxX, maxY, area))
            label++
        }
        return out
    }
}
