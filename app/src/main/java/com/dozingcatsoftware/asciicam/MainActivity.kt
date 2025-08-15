package com.dozingcatsoftware.asciicam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dozingcatsoftware.asciicam.camera.AsciiAnalyzer
import com.dozingcatsoftware.asciicam.io.AsciiImageWriter
import com.dozingcatsoftware.asciicam.ui.AsciiView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var asciiView: AsciiView
    private lateinit var btnToggle: Button
    private lateinit var btnSave: Button
    private lateinit var cameraExecutor: ExecutorService
    
    private var imageCapture: ImageCapture? = null
    private var isAsciiMode = true
    
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        previewView = findViewById(R.id.preview)
        asciiView = findViewById(R.id.asciiView)
        btnToggle = findViewById(R.id.btnToggle)
        btnSave = findViewById(R.id.btnSave)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        
        btnToggle.setOnClickListener {
            isAsciiMode = !isAsciiMode
            btnToggle.text = if (isAsciiMode) "ASCII ON" else "ASCII OFF"
            asciiView.visibility = if (isAsciiMode) android.view.View.VISIBLE else android.view.View.GONE
        }
        
        btnSave.setOnClickListener {
            captureAndSaveImage()
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            
            imageCapture = ImageCapture.Builder().build()
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, AsciiAnalyzer { frame ->
                        runOnUiThread {
                            if (isAsciiMode) {
                                asciiView.setFrame(frame)
                            }
                        }
                    })
                }
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun captureAndSaveImage() {
        // Create a bitmap from the current ASCII view
        val bitmap = asciiView.getBitmap()
        if (bitmap != null) {
            try {
                val fileName = AsciiImageWriter.saveJpeg(this, bitmap)
                Toast.makeText(this, "Image saved: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No ASCII image to save", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}