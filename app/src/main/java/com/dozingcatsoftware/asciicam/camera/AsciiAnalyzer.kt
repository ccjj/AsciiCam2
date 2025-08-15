package com.dozingcatsoftware.asciicam.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.roundToInt

data class AsciiFrame(val cols: Int, val rows: Int, val chars: CharArray)

class AsciiAnalyzer(
    private val targetCols: Int = 100,
    private val callback: (AsciiFrame) -> Unit
) : ImageAnalysis.Analyzer {
    
    private val asciiRamp = " .'`^\",:;Il!i~+_-?][}{1)(|\\/*tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$"
    
    override fun analyze(image: ImageProxy) {
        try {
            val yPlane = image.planes[0]
            val width = image.width
            val height = image.height
            
            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            
            val stepX = (width.toFloat() / targetCols).coerceAtLeast(1f)
            val stepY = stepX * 2f // ASCII characters are taller than wide
            
            val cols = (width / stepX).roundToInt().coerceAtLeast(1)
            val rows = (height / stepY).roundToInt().coerceAtLeast(1)
            
            val asciiChars = CharArray(cols * rows)
            var charIndex = 0
            var currentY = 0f
            
            fun getPixelLuminance(x: Int, y: Int): Int {
                val index = y * rowStride + x * pixelStride
                val byte = buffer.get(index)
                return if (byte < 0) byte + 256 else byte.toInt()
            }
            
            for (row in 0 until rows) {
                val pixelY = currentY.roundToInt().coerceIn(0, height - 1)
                var currentX = 0f
                
                for (col in 0 until cols) {
                    val pixelX = currentX.roundToInt().coerceIn(0, width - 1)
                    val luminance = getPixelLuminance(pixelX, pixelY)
                    
                    val charIndex2 = (luminance * (asciiRamp.length - 1)) / 255
                    asciiChars[charIndex++] = asciiRamp[charIndex2]
                    
                    currentX += stepX
                }
                currentY += stepY
            }
            
            callback(AsciiFrame(cols, rows, asciiChars))
        } finally {
            image.close()
        }
    }
}