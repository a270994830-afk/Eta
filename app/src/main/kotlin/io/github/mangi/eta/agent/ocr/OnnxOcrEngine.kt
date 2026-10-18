package io.github.mangi.eta.agent.ocr

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.mangi.eta.core.AndroidAgentLogger
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 离线中文 OCR 引擎：PP-OCRv6 mobile 的 small 档（det + rec），模型与参数取自 RapidOCR（Apache-2.0）。
 *
 * 布局假设：屏幕截图以正向横排文字为主；后处理用连通域矩形近似 DB 输出的旋转框，
 * 不做角度分类（cls）。首次调用时从 assets 加载模型（约 31MB），之后复用会话。
 */
object OnnxOcrEngine {

    data class OcrLine(
        val text: String,
        val score: Float,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    private class Sessions(
        val env: OrtEnvironment,
        val det: OrtSession,
        val rec: OrtSession,
        val detInputName: String,
        val recInputName: String,
        val dict: List<String>,
    )

    @Volatile
    private var sessions: Sessions? = null

    fun isReady(): Boolean = sessions != null

    fun recognize(context: Context, bitmap: Bitmap): List<OcrLine> {
        val engine = ensure(context)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val boxes = detectBoxes(engine, bitmap)
        val detMs = android.os.SystemClock.elapsedRealtime() - startedAt
        if (boxes.isEmpty()) {
            AndroidAgentLogger.info("OCR det: boxes=0 det_ms=$detMs")
            return emptyList()
        }
        val lines = recognizeBoxes(engine, bitmap, boxes)
        AndroidAgentLogger.info(
            "OCR done: boxes=${boxes.size} lines=${lines.size} det_ms=$detMs " +
                "total_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}",
        )
        return lines
    }

    private fun ensure(context: Context): Sessions {
        sessions?.let { return it }
        synchronized(this) {
            sessions?.let { return it }
            val appContext = context.applicationContext
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                setInterOpNumThreads(1)
            }
            val initStartedAt = android.os.SystemClock.elapsedRealtime()
            val detBytes = appContext.assets.open(DET_ASSET).use { it.readBytes() }
            val recBytes = appContext.assets.open(REC_ASSET).use { it.readBytes() }
            val det = env.createSession(detBytes, options)
            val rec = env.createSession(recBytes, options)
            val dict = loadDict(appContext)
            val created = Sessions(
                env = env,
                det = det,
                rec = rec,
                detInputName = det.inputNames.first(),
                recInputName = rec.inputNames.first(),
                dict = dict,
            )
            sessions = created
            AndroidAgentLogger.info(
                "OCR engine ready: dict=${dict.size} init_ms=" +
                    (android.os.SystemClock.elapsedRealtime() - initStartedAt),
            )
            return created
        }
    }

    private fun loadDict(context: Context): List<String> {
        val lines = context.assets.open(DICT_ASSET).use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readLines()
        }
        val chars = ArrayList<String>(lines.size + 2)
        chars.add("") // CTC blank 占位（索引 0）
        chars.addAll(lines)
        chars.add(" ") // 与训练词典一致：末尾补一个空格项
        return chars
    }

    // ── 文本检测 ─────────────────────────────────────────────────────────────

    private data class DetBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun detectBoxes(engine: Sessions, bitmap: Bitmap): List<DetBox> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return emptyList()

        // RapidOCR 默认 limit_type=min / 736；额外把最长边限制到 1600 以控制手机端耗时。
        var ratio = 1f
        val maxSide = max(srcW, srcH)
        val minSide = min(srcW, srcH)
        if (maxSide > DET_MAX_SIDE) {
            ratio = DET_MAX_SIDE.toFloat() / maxSide
        } else if (minSide < DET_LIMIT_SIDE) {
            ratio = DET_LIMIT_SIDE.toFloat() / minSide
        }
        val detW = roundTo32((srcW * ratio).roundToInt())
        val detH = roundTo32((srcH * ratio).roundToInt())
        val scaled = if (detW != srcW || detH != srcH) {
            Bitmap.createScaledBitmap(bitmap, detW, detH, true)
        } else {
            null
        }
        val source = scaled ?: bitmap
        try {
            val count = detW * detH
            val pixels = IntArray(count)
            source.getPixels(pixels, 0, detW, 0, 0, detW, detH)
            val input = FloatArray(3 * count)
            for (i in 0 until count) {
                val px = pixels[i]
                input[i] = norm(px and 0xFF) // B
                input[count + i] = norm((px shr 8) and 0xFF) // G
                input[2 * count + i] = norm((px shr 16) and 0xFF) // R
            }
            val tensor = OnnxTensor.createTensor(
                engine.env,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, detH.toLong(), detW.toLong()),
            )
            val output = tensor.use { t ->
                engine.det.run(mapOf(engine.detInputName to t)).use { result ->
                    val value = result[0].value as Array<Array<Array<FloatArray>>>
                    value[0][0]
                }
            }
            return boxesFromProbabilityMap(output, detW, detH, srcW, srcH)
        } finally {
            scaled?.recycle()
        }
    }

    private fun boxesFromProbabilityMap(
        prob: Array<FloatArray>,
        detW: Int,
        detH: Int,
        srcW: Int,
        srcH: Int,
    ): List<DetBox> {
        val mask = BooleanArray(detW * detH)
        for (y in 0 until detH) {
            val row = prob[y]
            val base = y * detW
            for (x in 0 until detW) {
                mask[base + x] = row[x] > DET_THRESH
            }
        }
        dilate(mask, detW, detH)

        val visited = BooleanArray(detW * detH)
        val queue = IntArray(detW * detH)
        val boxes = ArrayList<DetBox>()
        val scaleX = srcW.toFloat() / detW
        val scaleY = srcH.toFloat() / detH
        val total = detW * detH
        for (start in 0 until total) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = detW
            var maxX = -1
            var minY = detH
            var maxY = -1
            while (head < tail) {
                val idx = queue[head++]
                val y = idx / detW
                val x = idx - y * detW
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) {
                    val n = idx - 1
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (x < detW - 1) {
                    val n = idx + 1
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (y > 0) {
                    val n = idx - detW
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (y < detH - 1) {
                    val n = idx + detW
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (min(boxW, boxH) < MIN_BOX_SIDE) continue
            var sum = 0f
            var samples = 0
            for (yy in minY..maxY) {
                val row = prob[yy]
                for (xx in minX..maxX) {
                    sum += row[xx]
                    samples++
                }
            }
            val score = if (samples > 0) sum / samples else 0f
            if (score < BOX_THRESH) continue
            val unclip = (boxW.toFloat() * boxH * UNCLIP_RATIO) / (2f * (boxW + boxH))
            val left = ((minX - unclip) * scaleX).roundToInt().coerceIn(0, srcW - 1)
            val top = ((minY - unclip) * scaleY).roundToInt().coerceIn(0, srcH - 1)
            val right = ((maxX + 1 + unclip) * scaleX).roundToInt().coerceIn(left + 1, srcW)
            val bottom = ((maxY + 1 + unclip) * scaleY).roundToInt().coerceIn(top + 1, srcH)
            if (right - left < 4 || bottom - top < 4) continue
            boxes += DetBox(left, top, right, bottom)
        }
        return boxes
            .sortedWith(compareBy({ it.top / 24 }, { it.left }))
            .take(MAX_LINES)
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int) {
        val copy = mask.copyOf()
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val idx = base + x
                if (copy[idx]) continue
                val up = y > 0 && copy[idx - width]
                val down = y < height - 1 && copy[idx + width]
                val left = x > 0 && copy[idx - 1]
                val right = x < width - 1 && copy[idx + 1]
                if (up || down || left || right) mask[idx] = true
            }
        }
    }

    private fun roundTo32(value: Int): Int = ((value / 32f).roundToInt() * 32).coerceAtLeast(32)

    private fun norm(channel: Int): Float = (channel / 255f - 0.5f) / 0.5f

    // ── 文本识别 ─────────────────────────────────────────────────────────────

    private fun recognizeBoxes(engine: Sessions, bitmap: Bitmap, boxes: List<DetBox>): List<OcrLine> {
        val results = ArrayList<OcrLine>(boxes.size)
        for (box in boxes) {
            val cropW = box.right - box.left
            val cropH = box.bottom - box.top
            if (cropW < 2 || cropH < 2) continue
            val ratio = cropW.toFloat() / cropH
            val canvasW = max((REC_HEIGHT * max(REC_MIN_WH_RATIO, ratio)).toInt(), REC_HEIGHT)
            val resizedW = min(ceil(REC_HEIGHT * ratio).toInt(), canvasW).coerceAtLeast(1)
            val crop = Bitmap.createBitmap(bitmap, box.left, box.top, cropW, cropH)
            val resized = Bitmap.createScaledBitmap(crop, resizedW, REC_HEIGHT, true)
            crop.recycle()
            val pixels = IntArray(resizedW * REC_HEIGHT)
            resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, REC_HEIGHT)
            resized.recycle()
            val planeSize = REC_HEIGHT * canvasW
            val input = FloatArray(3 * planeSize)
            for (y in 0 until REC_HEIGHT) {
                val rowOffset = y * canvasW
                val pixelOffset = y * resizedW
                for (x in 0 until resizedW) {
                    val px = pixels[pixelOffset + x]
                    val offset = rowOffset + x
                    input[offset] = norm(px and 0xFF) // B
                    input[planeSize + offset] = norm((px shr 8) and 0xFF) // G
                    input[2 * planeSize + offset] = norm((px shr 16) and 0xFF) // R
                }
            }
            val tensor = OnnxTensor.createTensor(
                engine.env,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, REC_HEIGHT.toLong(), canvasW.toLong()),
            )
            val decoded = tensor.use { t ->
                engine.rec.run(mapOf(engine.recInputName to t)).use { result ->
                    val value = result[0].value as Array<Array<FloatArray>>
                    ctcDecode(value[0], engine.dict)
                }
            }
            if (decoded.first.isNotBlank()) {
                results += OcrLine(decoded.first, decoded.second, box.left, box.top, box.right, box.bottom)
            }
        }
        return results
    }

    private fun ctcDecode(logits: Array<FloatArray>, dict: List<String>): Pair<String, Float> {
        val text = StringBuilder()
        var confSum = 0f
        var confCount = 0
        var previous = -1
        for (t in logits.indices) {
            val row = logits[t]
            var bestIndex = 0
            var bestScore = row[0]
            for (c in 1 until row.size) {
                if (row[c] > bestScore) {
                    bestScore = row[c]
                    bestIndex = c
                }
            }
            if (bestIndex != CTC_BLANK && bestIndex != previous) {
                if (bestIndex < dict.size) text.append(dict[bestIndex])
                confSum += bestScore
                confCount++
            }
            previous = bestIndex
        }
        val confidence = if (confCount > 0) confSum / confCount else 0f
        return text.toString() to confidence
    }

    private const val DET_ASSET = "ocr/PP-OCRv6_det_small.onnx"
    private const val REC_ASSET = "ocr/PP-OCRv6_rec_small.onnx"
    private const val DICT_ASSET = "ocr/ppocrv6_dict.txt"
    private const val DET_LIMIT_SIDE = 736
    private const val DET_MAX_SIDE = 1600
    private const val DET_THRESH = 0.3f
    private const val BOX_THRESH = 0.7f
    private const val UNCLIP_RATIO = 2.0f
    private const val MIN_BOX_SIDE = 3
    private const val REC_HEIGHT = 48
    private const val REC_MIN_WH_RATIO = 320f / 48f
    private const val CTC_BLANK = 0
    private const val MAX_LINES = 80
}
