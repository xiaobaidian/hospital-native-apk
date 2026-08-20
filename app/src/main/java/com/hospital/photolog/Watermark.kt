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
 * 把「数量：X」+「时间」烧录到照片左上角（深色半透明底 + 白字描边）。
 * 原生实现，拍完回调里调用，比网页 canvas 更可控、更快。
 */
object Watermark {

    fun burn(src: File, quantity: Int, scene: String, time: String): File {
        val raw = loadRotated(src)
        val bmp = raw.copy(Bitmap.Config.ARGB_8888, true)
        raw.recycle()

        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val ts = w * 0.045f

        val paint = Paint().apply {
            color = Color.WHITE
            textSize = ts
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val stroke = Paint().apply {
            color = Color.BLACK
            textSize = ts
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = ts * 0.06f
        }

        val l1 = "数量：$quantity"
        val l2 = "时间：$time"
        val pad = w * 0.03f
        val lh = ts * 1.25f
        val boxW = paint.measureText(l1).coerceAtLeast(paint.measureText(l2)) + pad * 2
        val boxH = lh * 2 + pad

        val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        canvas.drawRect(pad, pad, pad + boxW, pad + boxH, bg)

        val x = pad + pad * 0.6f
        var y = pad + pad * 0.7f + ts
        for (line in arrayOf(l1, l2)) {
            canvas.drawText(line, x, y, stroke)
            canvas.drawText(line, x, y, paint)
            y += lh
        }

        val out = File(src.parent, "wm_${src.name}")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
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
