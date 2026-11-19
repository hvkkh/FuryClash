package com.furyclash.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/** Shared Canvas drawing helpers for menus and HUD. */
object Ui {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val srcRect = android.graphics.Rect()
    private val dstRect = RectF()
    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD)

    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
             align: Paint.Align = Paint.Align.CENTER, font: Typeface = condensed, spacing: Float = 0f, alpha: Int = 255) {
        paint.typeface = font
        paint.textSize = size
        paint.color = color
        paint.alpha = alpha
        paint.textAlign = align
        paint.letterSpacing = spacing
        paint.style = Paint.Style.FILL
        paint.setShadowLayer(0f, 0f, 0f, 0)
        c.drawText(s, x, y, paint)
        paint.letterSpacing = 0f
    }

    fun textShadow(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
                   align: Paint.Align = Paint.Align.CENTER, font: Typeface = condensed, spacing: Float = 0f, alpha: Int = 255) {
        paint.typeface = font
        paint.textSize = size
        paint.textAlign = align
        paint.letterSpacing = spacing
        paint.style = Paint.Style.FILL
        paint.setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.argb(180, 0, 0, 0))
        paint.color = color
        paint.alpha = alpha
        c.drawText(s, x, y, paint)
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.letterSpacing = 0f
        paint.alpha = 255
    }

    fun rr(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.setShadowLayer(0f, 0f, 0f, 0)
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, r, r, paint)
    }

    fun rrStroke(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawCircle(x, y, r, paint)
    }

    fun circleStroke(c: Canvas, x: Float, y: Float, r: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.FILL
    }

    /** Health/energy bar with optional right-to-left fill. */
    fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, color: Int, bg: Int, rtl: Boolean = false, radius: Float = h / 2f) {
        rr(c, x, y, w, h, radius, bg)
        val fw = w * frac.coerceIn(0f, 1f)
        if (fw > 1f) {
            if (rtl) rr(c, x + w - fw, y, fw, h, radius, color)
            else rr(c, x, y, fw, h, radius, color)
        }
    }

    fun portrait(c: Canvas, bmp: Bitmap?, x: Float, y: Float, size: Float, ring: Int = 0, ko: Boolean = false) {
        val r = size / 2f
        if (bmp != null) {
            c.save()
            android.graphics.Path().let { path ->
                path.addCircle(x + r, y + r, r, android.graphics.Path.Direction.CW)
                c.clipPath(path)
                srcRect.set(0, 0, bmp.width, bmp.height)
                dstRect.set(x, y, x + size, y + size)
                paint.color = if (ko) Color.DKGRAY else Color.WHITE
                paint.alpha = 255
                c.drawBitmap(bmp, srcRect, dstRect, if (ko) dimPaint else paint)
            }
            c.restore()
        } else {
            circle(c, x + r, y + r, r, 0xFF2A2E3A.toInt())
        }
        if (ring != 0) circleStroke(c, x + r, y + r, r - 1f, ring, size * 0.045f)
        if (ko) {
            paint.strokeWidth = size * 0.07f
            paint.color = 0xE5FF3D3D.toInt()
            paint.style = Paint.Style.STROKE
            c.drawLine(x + size * 0.2f, y + size * 0.2f, x + size * 0.8f, y + size * 0.8f, paint)
            c.drawLine(x + size * 0.8f, y + size * 0.2f, x + size * 0.2f, y + size * 0.8f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix().apply { setSaturation(0f) }
        )
        alpha = 160
    }

    fun button(c: Canvas, label: String, x: Float, y: Float, w: Float, h: Float, accent: Int, enabled: Boolean = true, sub: String? = null): RectF {
        val bg = if (enabled) 0xE61A2030.toInt() else 0xE610131A.toInt()
        rr(c, x, y, w, h, h * 0.18f, bg)
        rrStroke(c, x, y, w, h, h * 0.18f, if (enabled) accent else 0xFF3A3F4A.toInt(), h * 0.035f)
        val tc = if (enabled) 0xFFF2F4FA.toInt() else 0xFF5A6070.toInt()
        text(c, label, x + w / 2f, y + h * (if (sub == null) 0.62f else 0.5f), h * (if (sub == null) 0.34f else 0.28f), tc, spacing = 0.06f)
        if (sub != null) text(c, sub, x + w / 2f, y + h * 0.8f, h * 0.17f, if (enabled) accent else 0xFF5A6070.toInt(), spacing = 0.04f)
        return RectF(x, y, x + w, y + h)
    }

    fun unit(w: Int, h: Int) = min(w, h) / 100f
}
