package com.hospital.photolog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * 把「数量：X」+「时间」烧录到照片左上角（近不透明深色圆角底 + 纯白粗体，专为 OCR 优化）。
 *
 * OCR 友好设计要点：
 *  - 高对比：深色近不透明底 + 纯白字，无论照片背景明暗，OCR 都能稳定识别；
 *  - 不用描边：描边会让字形边缘发虚，反而降低 OCR 准确率；
 *  - 字号随图宽放大并设下限，保证小图也清晰；
 *  - 圆角底 + 充足留白，行距分明，数字/中文互不粘连。
 */
object Watermark {

    fun burn(src: File, quantity: Int, time: String, outName: String): File {
        val raw = loadRotated(src)
        val bmp = raw.copy(Bitmap.Config.ARGB_8888, true)
        raw.recycle()

        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()

        // 字号随图片宽度，设下限保证可辨（OCR 对小字很敏感）
        val ts = (w * 0.05f).coerceAtLeast(36f)

        val l1 = "数量：$quantity"
        val l2 = "时间：$time"

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = ts
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }

        val padX = w * 0.035f
        val padY = h * 0.022f
        val lineH = ts * 1.35f
        val textW = textPaint.measureText(l1).coerceAtLeast(textPaint.measureText(l2))
        val boxW = textW + padX * 2f
        val boxH = lineH * 2f + padY * 2f

        // 左上角固定区（锁定规范：左上角）
        val left = padX * 0.6f
        val top = padY * 0.6f

        // 近不透明深色圆角底：高对比，保证 OCR 稳定
        val bg = Paint().apply {
            color = Color.argb(245, 0, 0, 0)
            isAntiAlias = true
        }
        canvas.drawRoundRect(left, top, left + boxW, top + boxH, ts * 0.22f, ts * 0.22f, bg)

        // 纯白粗体，无描边（描边会伤 OCR）
        var y = top + padY + ts
        for (line in arrayOf(l1, l2)) {
            canvas.drawText(line, left + padX, y, textPaint)
            y += lineH
        }

        val out = File(src.parent, outName)
        // 质量 92：减少压缩模糊，文字边缘更锐利，利于 OCR
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return out
    }

    private fun loadRotated(file: File): Bitmap {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw RuntimeException("decode failed: ${file.absolutePath}")
        val exif = ExifInterface(file.absolutePath)
        val rot = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val m = Matrix()
        when (rot) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        if (m.isIdentity) return bmp
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        bmp.recycle()
        return r
    }
}
