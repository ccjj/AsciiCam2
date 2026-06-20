package com.dozingcatsoftware.asciicam.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.roundToInt

data class AsciiFrame(val cols: Int, val rows: Int, val chars: CharArray, val colors: IntArray?)

class AsciiAnalyzer(
    private val targetCols: Int = 100,
    private val colorType: com.dozingcatsoftware.asciicam.AsciiConverter.ColorType = com.dozingcatsoftware.asciicam.AsciiConverter.ColorType.ANSI_COLOR,
    private val asciiRamp: String = " .'`^\",:;Il!i~+_-?][}{1)(|\\/*tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$",
    private val callback: (AsciiFrame) -> Unit
) : ImageAnalysis.Analyzer {
    
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
            val asciiColors = if (colorType.isMonochrome()) null else IntArray(cols * rows)
            val ramp = asciiRamp.ifBlank { " .'`^\",:;Il!i~+_-?][}{1)(|\\/*tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$" }
            var charIndex = 0
            var currentY = 0f
            
            fun byteAt(buffer: ByteBuffer, index: Int): Int {
                val byte = buffer.get(index)
                return if (byte < 0) byte + 256 else byte.toInt()
            }

            fun getPixelLuminance(x: Int, y: Int): Int {
                return byteAt(buffer, y * rowStride + x * pixelStride)
            }

            fun getPixelColor(x: Int, y: Int): Int {
                val yValue = getPixelLuminance(x, y)
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]
                val uvX = x / 2
                val uvY = y / 2
                val u = byteAt(uPlane.buffer, uvY * uPlane.rowStride + uvX * uPlane.pixelStride) - 128
                val v = byteAt(vPlane.buffer, uvY * vPlane.rowStride + uvX * vPlane.pixelStride) - 128
                val yf = yValue.toFloat()
                var red = (yf + 1.402f * v).roundToInt().coerceIn(0, 255)
                var green = (yf - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
                var blue = (yf + 1.772f * u).roundToInt().coerceIn(0, 255)

                if (colorType == com.dozingcatsoftware.asciicam.AsciiConverter.ColorType.ANSI_COLOR) {
                    val maxColor = maxOf(red, green, blue)
                    if (maxColor > 0) {
                        val threshold = (maxColor * 7) / 8
                        red = if (red >= threshold) 255 else 0
                        green = if (green >= threshold) 255 else 0
                        blue = if (blue >= threshold) 255 else 0
                    }
                }

                return android.graphics.Color.rgb(red, green, blue)
            }
            
            for (row in 0 until rows) {
                val pixelY = currentY.roundToInt().coerceIn(0, height - 1)
                var currentX = 0f
                
                for (col in 0 until cols) {
                    val pixelX = currentX.roundToInt().coerceIn(0, width - 1)
                    val luminance = getPixelLuminance(pixelX, pixelY)
                    
                    val charIndex2 = (luminance * (ramp.length - 1)) / 255
                    asciiChars[charIndex] = ramp[charIndex2]
                    asciiColors?.set(charIndex, getPixelColor(pixelX, pixelY))
                    charIndex++
                    
                    currentX += stepX
                }
                currentY += stepY
            }
            
            callback(AsciiFrame(cols, rows, asciiChars, asciiColors))
        } finally {
            image.close()
        }
    }
}