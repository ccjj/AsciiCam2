package com.dozingcatsoftware.asciicam.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.dozingcatsoftware.asciicam.AsciiConverter
import com.dozingcatsoftware.asciicam.camera.AsciiFrame

class AsciiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = DEFAULT_TEXT_SIZE
        typeface = Typeface.MONOSPACE
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
    }
    
    @Volatile
    private var currentFrame: AsciiFrame? = null
    private var colorType = AsciiConverter.ColorType.ANSI_COLOR
    
    fun setFrame(frame: AsciiFrame?) {
        currentFrame = frame
        postInvalidateOnAnimation()
    }

    fun applyPreferences(colorType: AsciiConverter.ColorType, characterSizePercent: Int) {
        this.colorType = colorType
        textPaint.textSize = DEFAULT_TEXT_SIZE * characterSizePercent.coerceIn(50, 200) / 100f
        when (colorType) {
            AsciiConverter.ColorType.BLACK_ON_WHITE -> {
                backgroundPaint.color = Color.WHITE
                textPaint.color = Color.BLACK
            }
            else -> {
                backgroundPaint.color = Color.BLACK
                textPaint.color = Color.WHITE
            }
        }
        postInvalidateOnAnimation()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawAscii(canvas, width.toFloat(), height.toFloat())
    }
    
    fun getBitmap(): Bitmap? {
        val frame = currentFrame ?: return null
        
        val charHeight = textPaint.fontMetrics.run { bottom - top }
        val charWidth = textPaint.measureText("M") // Use 'M' as reference for monospace width
        
        val bitmapWidth = (frame.cols * charWidth).toInt()
        val bitmapHeight = (frame.rows * charHeight).toInt()
        
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawAscii(canvas, bitmapWidth.toFloat(), bitmapHeight.toFloat())
        
        return bitmap
    }

    private fun drawAscii(canvas: Canvas, drawWidth: Float, drawHeight: Float) {
        canvas.drawRect(0f, 0f, drawWidth, drawHeight, backgroundPaint)

        val frame = currentFrame ?: return
        val charHeight = textPaint.fontMetrics.run { bottom - top }
        val startY = -textPaint.fontMetrics.top
        val colors = frame.colors

        var charIndex = 0
        var currentY = startY

        for (row in 0 until frame.rows) {
            if (colors == null) {
                textPaint.color = if (colorType == AsciiConverter.ColorType.BLACK_ON_WHITE) Color.BLACK else Color.WHITE
                canvas.drawText(frame.chars, charIndex, frame.cols, 0f, currentY, textPaint)
                charIndex += frame.cols
            }
            else {
                var currentX = 0f
                for (col in 0 until frame.cols) {
                    textPaint.color = colors[charIndex]
                    canvas.drawText(frame.chars, charIndex, 1, currentX, currentY, textPaint)
                    currentX += textPaint.measureText(frame.chars, charIndex, 1)
                    charIndex++
                }
            }
            currentY += charHeight

            if (currentY > drawHeight) break
        }
    }

    companion object {
        private const val DEFAULT_TEXT_SIZE = 14f
    }
}
