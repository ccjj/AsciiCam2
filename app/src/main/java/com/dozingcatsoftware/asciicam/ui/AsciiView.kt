package com.dozingcatsoftware.asciicam.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.dozingcatsoftware.asciicam.camera.AsciiFrame

class AsciiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f
        typeface = Typeface.MONOSPACE
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
    }
    
    @Volatile
    private var currentFrame: AsciiFrame? = null
    
    fun setFrame(frame: AsciiFrame?) {
        currentFrame = frame
        postInvalidateOnAnimation()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw black background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        val frame = currentFrame ?: return
        
        val charHeight = textPaint.fontMetrics.run { bottom - top }
        val startY = -textPaint.fontMetrics.top
        
        var charIndex = 0
        var currentY = startY
        
        for (row in 0 until frame.rows) {
            canvas.drawText(frame.chars, charIndex, frame.cols, 0f, currentY, textPaint)
            charIndex += frame.cols
            currentY += charHeight
            
            if (currentY > height) break
        }
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
        
        // Draw black background
        canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), backgroundPaint)
        
        val startY = -textPaint.fontMetrics.top
        var charIndex = 0
        var currentY = startY
        
        for (row in 0 until frame.rows) {
            canvas.drawText(frame.chars, charIndex, frame.cols, 0f, currentY, textPaint)
            charIndex += frame.cols
            currentY += charHeight
        }
        
        return bitmap
    }
}