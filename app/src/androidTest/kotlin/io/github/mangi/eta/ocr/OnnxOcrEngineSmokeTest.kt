package io.github.mangi.eta.agent.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mangi.eta.agent.ocr.OnnxOcrEngine
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备端 OCR 冒烟测试：用纯本地 OnnxOcrEngine 识别一张合成的中文/数字图片，
 * 验证 PP-OCRv6 模型资产能加载、推理并输出文字。
 */
@RunWith(AndroidJUnit4::class)
class OnnxOcrEngineSmokeTest {

    @Test
    fun recognize_synthetic_chinese_text() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 合成一张白底、含一段数字 + 中文的图（字体足够大，保证可识别）
        val w = 900
        val h = 260
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.BLACK
        paint.textSize = 72f
        canvas.drawText("Eta 2026 OCR v6", 40f, 120f, paint)
        canvas.drawText("测试屏幕识别", 40f, 220f, paint)

        val lines = OnnxOcrEngine.recognize(ctx, bmp)
        bmp.recycle()

        // 把识别结果写到外部文件，便于验证
        val file = java.io.File(ctx.getExternalFilesDir(null), "ocr_result.txt")
        file.parentFile?.mkdirs()
        file.writeText("OCR_LINES=${lines.size}\n")
        lines.forEach { file.appendText("OCR_LINE text=${it.text} score=%.3f x=%d y=%d\n".format(it.score, it.centerX, it.centerY)) }
        println("OCR_RESULT_FILE=${file.absolutePath}")
        lines.forEach { println("OCR_LINE text=${it.text} score=${it.score}") }
        org.junit.Assert.assertTrue(
            "OCR 应识别出文字，实际 lines=${lines.size}",
            lines.isNotEmpty(),
        )
        val joined = lines.joinToString("|") { it.text }
        org.junit.Assert.assertTrue(
            "期望识别到 Eta 或 2026 或 OCR，实际=$joined",
            joined.contains("Eta") || joined.contains("2026") || joined.contains("OCR") || joined.contains("O7") || joined.contains("eta"),
        )
    }
}