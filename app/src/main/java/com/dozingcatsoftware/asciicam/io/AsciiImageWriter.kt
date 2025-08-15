package com.dozingcatsoftware.asciicam.io

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

object AsciiImageWriter {
  @Throws(IOException::class)
  fun saveJpeg(ctx: Context, bmp: Bitmap, name: String? = null): String {
    val fileName = name ?: "ascii_${System.currentTimeMillis()}.jpg"
    val cv = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AsciiCam")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      }
    }
    val cr = ctx.contentResolver
    val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
      ?: throw IOException("MediaStore insert failed")
    cr.openOutputStream(uri).use { out ->
      if (out == null || !bmp.compress(Bitmap.CompressFormat.JPEG, 92, out))
        throw IOException("Bitmap write failed")
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
      cr.update(uri, cv, null, null)
    }
    return uri.toString()
  }
}