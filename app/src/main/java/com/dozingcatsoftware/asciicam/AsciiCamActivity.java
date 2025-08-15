// Copyright (C) 2012-2016 Brian Nenninger

package com.dozingcatsoftware.asciicam;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import com.dozingcatsoftware.asciicam.AsciiConverter.ColorType;
import com.dozingcatsoftware.util.ARManager;
import com.dozingcatsoftware.util.AndroidUtils;
import com.dozingcatsoftware.util.AsyncProcessor;
import com.dozingcatsoftware.util.CameraUtils;
import com.dozingcatsoftware.util.ShutterButton;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.content.res.Configuration;

public class AsciiCamActivity extends Activity
implements Camera.PreviewCallback, ShutterButton.OnShutterButtonListener {

    private final static boolean DEBUG = true;
    private final static String TAG = "AsciiCamActivity";

    // Pixels and metadata for a preview frame.
    static class CameraPreviewData {
        public final Camera camera;
        public final CameraUtils.CameraInfo cameraInfo;
        public final byte[] pixelData;
        public final int width;
        public final int height;
        public final long timestamp;
        public CameraPreviewData(Camera c, CameraUtils.CameraInfo i, byte[] p, int w, int h, long t) {
            camera = c;
            cameraInfo = i;
            pixelData = p;
            width = w;
            height = h;
            timestamp = t;
        }
    }

    ARManager arManager;
    boolean hasCameraPermission = false;
    AsciiConverter asciiConverter = new AsciiConverter();
    AsciiConverter.Result asciiResult = new AsciiConverter.Result();

    AsciiConverter.ColorType colorType = AsciiConverter.ColorType.ANSI_COLOR;
    Map<AsciiConverter.ColorType, String> pixelCharsMap = new EnumMap<AsciiConverter.ColorType, String>(AsciiConverter.ColorType.class);

    final static int ACTIVITY_PREFERENCES = 1;
    final static int ACTIVITY_PICK_IMAGE = 2;

    ImageButton cycleColorButton;
    ImageButton switchCameraButton;
    ImageButton settingsButton;
    ImageButton galleryButton;
    ImageButton convertPictureButton;
    ImageButton helpButton;
    ShutterButton shutterButton;
    SurfaceView cameraView;
    OverlayView overlayView;
    LinearLayout verticalButtonBar;

    Handler handler = new Handler();
    boolean cameraViewReady = false;
    boolean appVisible = false;
    boolean saveInProgress = false;

    AsciiRenderer imageRenderer = new AsciiRenderer();
    AsciiImageWriter imageWriter = new AsciiImageWriter();

    AsyncProcessor<CameraPreviewData, Bitmap> imageProcessor;
    // If imageProcessor is busy when a preview frame arrives, store it here so that when it
    // finishes the current frame it can immediately process the next one without having to
    // wait until another frame arrives.
    CameraPreviewData nextPreviewData = null;
    private long lastProcessTime = 0;
    private static final long MIN_PROCESS_INTERVAL = 33; // ~30fps for smoother experience
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    
    // Enhanced debug logging metrics
    private long totalFramesProcessed = 0;
    private long totalProcessingTime = 0;
    private long lastDebugLogTime = 0;
    private static final long DEBUG_LOG_INTERVAL = 5000; // Log stats every 5 seconds
    private int droppedFrames = 0;
    private long minProcessingTime = Long.MAX_VALUE;
    private long maxProcessingTime = 0;
    
    // Touch-to-focus functionality
    private boolean isFocusing = false;
    private long lastFocusTime = 0;
    private static final long FOCUS_COOLDOWN = 1000; // 1 second cooldown between focus attempts

    /** Called when the activity is first created. */
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            this.requestWindowFeature(Window.FEATURE_NO_TITLE);
            
            // Hide status bar and navigation bar for true fullscreen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            
            setContentView(R.layout.main);

            cameraView = (SurfaceView)findViewById(R.id.cameraView);
            overlayView = (OverlayView)findViewById(R.id.overlayView);
            verticalButtonBar = (LinearLayout)findViewById(R.id.verticalButtonBar);
            cycleColorButton = (ImageButton)findViewById(R.id.cycleColorButton);
            switchCameraButton = (ImageButton)findViewById(R.id.switchCameraButton);
            settingsButton = (ImageButton)findViewById(R.id.settingsButton);
            galleryButton = (ImageButton)findViewById(R.id.galleryButton);
            convertPictureButton = (ImageButton)findViewById(R.id.convertPictureButton);
            helpButton = (ImageButton)findViewById(R.id.helpButton);
            shutterButton = (ShutterButton)findViewById(R.id.shutterButton);
            
            if (shutterButton != null) {
                shutterButton.setOnShutterButtonListener(this);
            }

            // Initialize camera with improved error handling and recovery
            try {
                arManager = ARManager.createAndSetupCameraView(this, cameraView, this);
                
                // Add test pattern to debug display pipeline
                Log.d(TAG, "Camera permission status: " + hasCameraPermission());
                if (!hasCameraPermission()) {
                    Log.e(TAG, "Camera permission not granted!");
                    Toast.makeText(this, "Camera permission required. Please grant permission in Settings.", Toast.LENGTH_LONG).show();
                    // Request camera permission
                    PermissionsChecker.requestCameraPermissionOnly(this);
                }
                createTestPattern();
                if (arManager != null) {
                    // Use more conservative preview size for better compatibility
                    arManager.setPreferredPreviewSize(640, 480);
                    // Increase buffers for smoother processing (LibreCamera fix)
                    arManager.setNumberOfPreviewCallbackBuffers(3);
                    
                    // Set proper camera display orientation
                    setCameraDisplayOrientation(arManager.getCameraId());
                    
                    // Initialize auto-focus capabilities
                    initializeAutoFocus();
                    
                    Log.d(TAG, "Camera initialized successfully with proper orientation and auto-focus");
                } else {
                    Log.w(TAG, "ARManager is null after creation");
                }
            } catch (Exception cameraException) {
                Log.w(TAG, "Camera initialization failed, continuing without camera", cameraException);
                arManager = null;
                // Show user-friendly message
                Toast.makeText(this, "Camera not available. Some features may be limited.", Toast.LENGTH_SHORT).show();
            }

            int numCameras = 0;
            try {
                numCameras = CameraUtils.numberOfCameras();
            } catch (Exception e) {
                Log.w(TAG, "Failed to get camera count", e);
            }
            
            if (switchCameraButton != null) {
                switchCameraButton.setVisibility(numCameras > 1 ? View.VISIBLE : View.GONE);
            }
            updateFromPreferences();
            updateButtonsAndBackground();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            // Show a toast to inform user about the error
            Toast.makeText(this, "App initialization error. Please restart.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void onPause() {
        appVisible = false;
        try {
            if (arManager != null) {
                arManager.stopCamera();
            }
            if (asciiConverter != null) {
                asciiConverter.destroyThreadPool();
            }
            if (imageRenderer != null) {
                imageRenderer.destroyThreadPool();
            }
            if (imageProcessor != null) {
                imageProcessor.stop();
                imageProcessor = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error during pause", e);
        }
        super.onPause();
    }

    @Override public void onResume() {
        super.onResume();
        appVisible = true;
        try {
            updateFromPreferences(); // Initialize pixel character mappings
            updateButtonsAndBackground();
            imageProcessor = new AsyncProcessor<CameraPreviewData, Bitmap>();
            imageProcessor.start();
            AndroidUtils.setSystemUiLowProfile(cameraView);

            // Add test pattern to verify display pipeline
            createTestPattern();

            // Improved camera startup with retry mechanism
            if (hasCameraPermission() && arManager != null) {
                try {
                    arManager.startCameraIfVisible();
                    Log.d(TAG, "Camera started successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start camera, attempting recovery", e);
                    // Attempt to reinitialize camera
                    try {
                        arManager = ARManager.createAndSetupCameraView(this, cameraView, this);
                        if (arManager != null) {
                            arManager.setPreferredPreviewSize(640, 480);
                            arManager.setNumberOfPreviewCallbackBuffers(3);
                            arManager.startCameraIfVisible();
                            Log.d(TAG, "Camera recovery successful");
                        }
                    } catch (Exception recoveryException) {
                        Log.e(TAG, "Camera recovery failed", recoveryException);
                        arManager = null;
                        Toast.makeText(this, "Camera error. Please restart app.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            else if (arManager != null) {
                // Check if we have all required permissions
                if (!PermissionsChecker.hasAllRequiredPermissions(this)) {
                    Log.d(TAG, "Missing permissions, requesting camera and storage permissions");
                    PermissionsChecker.requestCameraAndStoragePermissions(this);
                } else {
                    Log.d(TAG, "All permissions available, but camera not started - attempting start");
                    try {
                        arManager.startCameraIfVisible();
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting camera with permissions", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during resume", e);
        }
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        Log.d(TAG, "Configuration changed - orientation: " + newConfig.orientation + 
                ", screenLayout: " + newConfig.screenLayout);
        
        // Handle orientation and screen size changes
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    // Recalculate screen dimensions and update renderer
                    if (overlayView != null && imageRenderer != null) {
                        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                        getWindowManager().getDefaultDisplay().getMetrics(metrics);
                        
                        int newWidth = metrics.widthPixels;
                        int newHeight = metrics.heightPixels;
                        
                        // Account for system bars
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                            newWidth = metrics.widthPixels;
                            newHeight = metrics.heightPixels;
                        }
                        
                        imageRenderer.setMaximumImageSize(newWidth, newHeight);
                        
                        // Update camera orientation if needed
                        if (arManager != null) {
                            setCameraDisplayOrientation(arManager.getCameraId());
                        }
                        
                        Log.d(TAG, "Updated renderer for new screen size: " + newWidth + "x" + newHeight);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling configuration change", e);
                }
            }
        }, 100); // Small delay to ensure layout is complete
    }
        
        // If the user needs to grant runtime permissions (in Android M or later), we'll get
        // notified in onRequestPermissionsResult.

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String permissions[], int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult - requestCode: " + requestCode + 
                ", permissions: " + java.util.Arrays.toString(permissions) + 
                ", grantResults: " + java.util.Arrays.toString(grantResults));
        
        // Use enhanced permission handling from PermissionsChecker
        boolean permissionsGranted = PermissionsChecker.handlePermissionResult(
                this, requestCode, permissions, grantResults);
        
        switch (requestCode) {
            case PermissionsChecker.CAMERA_ONLY_REQUEST_CODE:
                if (permissionsGranted) {
                    Log.d(TAG, "Camera permission granted, starting camera");
                    if (arManager != null) {
                        try {
                            arManager.startCameraIfVisible();
                        } catch (Exception e) {
                            Log.e(TAG, "Error starting camera after permission grant", e);
                            Toast.makeText(this, "Error starting camera", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    Log.w(TAG, "Camera permission denied");
                    // Enhanced error handling is already done in PermissionsChecker.handlePermissionResult
                }
                break;
                
            case PermissionsChecker.CAMERA_AND_STORAGE_REQUEST_CODE:
                if (permissionsGranted) {
                    Log.d(TAG, "Camera and storage permissions granted, starting camera");
                    if (arManager != null) {
                        try {
                            arManager.startCameraIfVisible();
                        } catch (Exception e) {
                            Log.e(TAG, "Error starting camera after permission grant", e);
                            Toast.makeText(this, "Error starting camera", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    Log.w(TAG, "Camera and/or storage permissions denied");
                    // Check which specific permissions were denied for better user feedback
                    if (!PermissionsChecker.hasCameraPermission(this)) {
                        Log.w(TAG, "Camera permission specifically denied");
                    }
                    if (!PermissionsChecker.hasStoragePermission(this)) {
                        Log.w(TAG, "Storage permission specifically denied");
                    }
                }
                break;
                
            case PermissionsChecker.STORAGE_FOR_PHOTO_REQUEST_CODE:
                if (permissionsGranted) {
                    Log.d(TAG, "Storage permission granted for photo, attempting to take picture");
                    // Retry taking the picture now that we have permission
                    takePicture();
                } else {
                    Log.w(TAG, "Storage permission denied for photo");
                    // Enhanced error handling is already done in PermissionsChecker.handlePermissionResult
                }
                break;
                
            case PermissionsChecker.STORAGE_FOR_LIBRARY_REQUEST_CODE:
                if (permissionsGranted) {
                    Log.d(TAG, "Storage permission granted for library, opening gallery");
                    // Retry opening gallery now that we have permission
                    this.onClick_gotoGallery(galleryButton);
                } else {
                    Log.w(TAG, "Storage permission denied for library");
                    // Enhanced error handling is already done in PermissionsChecker.handlePermissionResult
                }
                break;
                
            default:
                Log.w(TAG, "Unknown permission request code: " + requestCode);
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
                break;
        }
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                PermissionsChecker.hasCameraPermission(this);
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                PermissionsChecker.hasStoragePermission(this);
    }

    private void showPermissionNeededDialog(String msg) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setMessage(msg);
        dialog.setNeutralButton("OK", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    @SuppressLint("RtlHardcoded")
    void updateFromPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        for(AsciiConverter.ColorType colorType : AsciiConverter.ColorType.values()) {
            String prefsKey = getString(R.string.pixelCharsPrefIdPrefix) + colorType.name();
            String prefValue = prefs.getString(prefsKey, "");
            // If preference is empty, use the default characters from the ColorType enum
            if (prefValue == null || prefValue.trim().isEmpty()) {
                // Fix: getDefaultPixelChars() returns String[], need to concatenate properly
                StringBuilder sb = new StringBuilder();
                String[] defaultChars = colorType.getDefaultPixelChars();
                for (String ch : defaultChars) {
                    sb.append(ch);
                }
                prefValue = sb.toString();
                Log.d(TAG, "Using default chars for " + colorType + ": '" + prefValue + "' (length: " + prefValue.length() + ")");
            }
            pixelCharsMap.put(colorType, prefValue);
        }

        String colorTypeName = prefs.getString("colorType", null);
        if (colorTypeName!=null) {
            try {
                this.colorType = AsciiConverter.ColorType.valueOf(colorTypeName);
            }
            catch(Exception ignored) {}
        }
        if (colorType==null) {
            colorType = AsciiConverter.ColorType.ANSI_COLOR;
        }

        AsciiCamPreferences.setAutoConvertEnabled(this, prefs.getBoolean(getString(R.string.autoConvertPicturesPrefId), false));

        boolean controlsOnLeft = prefs.getBoolean(getString(R.string.controlsOnLeftPrefId), false);
        FrameLayout.LayoutParams controlLayout = (FrameLayout.LayoutParams)verticalButtonBar.getLayoutParams();
        controlLayout.gravity = controlsOnLeft ? Gravity.LEFT : Gravity.RIGHT;
        verticalButtonBar.setLayoutParams(controlLayout);
    }

    void saveColorStyleToPreferences() {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(getBaseContext()).edit();
        editor.putString("colorType", colorType.name());
        editor.commit();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        switch(requestCode) {
            case ACTIVITY_PREFERENCES:
                updateFromPreferences();
                break;
            case ACTIVITY_PICK_IMAGE:
                if (resultCode==RESULT_OK) {
                    (new Thread() {
                        @Override public void run() {
                            try {
                                final String imagePath = (new ProcessImageOperation()).
                                        processImage(AsciiCamActivity.this, intent.getData());
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        ViewImageActivity.startActivityWithImageURI(AsciiCamActivity.this,
                                                Uri.fromFile(new File(imagePath)), "image/png");
                                    }
                                });
                            }
                            catch(Exception ex) {
                                Log.e(TAG, "Failed converting image", ex);
                            }
                        }
                    }).start();
                }
                break;
        }
    }

    void takePictureThreadEntry(final AsciiConverter.Result result) {
        try {
            final String pngPath = imageWriter.saveImageAndThumbnail(
                    imageRenderer.getVisibleBitmap(),
                    imageRenderer.createThumbnailBitmap(result),
                    result);
            AndroidUtils.scanSavedMediaFile(this, pngPath);
            handler.post(new Runnable() {
                @Override public void run() {
                    bitmapSaved(pngPath, "image/png");
                }
            });
        }
        catch(IOException ex) {
            Log.e(TAG, "Error saving picture", ex);
        }
        finally {
            handler.post(new Runnable() {
                @Override public void run() {
                    saveInProgress = false;
                }
            });
        }
    }

    void takePicture() {
        if (!hasStoragePermission()) {
            PermissionsChecker.requestStoragePermissionsToTakePhoto(this);
            return;
        }
        saveInProgress = true;
        // Use a separate thread to write the PNG and HTML files, so the UI doesn't block.
        (new Thread() {
            @Override public void run() {
                AsciiConverter.Result savePictureResult = null;
                synchronized (asciiResult) {
                    savePictureResult = asciiResult.copy();
                }
                takePictureThreadEntry(savePictureResult);
            }
        }).start();
    }

    void bitmapSaved(String path, String mimeType) {
        if (!appVisible) return;
        if (path==null) {
            Toast.makeText(getApplicationContext(), getString(R.string.errorSavingPicture), Toast.LENGTH_SHORT).show();
        }
        else {
            ViewImageActivity.startActivityWithImageURI(this, Uri.fromFile(new File(path)), mimeType);
        }
    }

    private void setImageResource(ImageButton button, String resourceName)
            throws IllegalAccessException, NoSuchFieldException {
        Integer resId = (Integer)R.drawable.class.getField(resourceName).get(null);
        button.setImageResource(resId);
    }

    void updateButtonsAndBackground() {
        try {
            String resName = "btn_color_" + this.colorType.name().toLowerCase();
            setImageResource(cycleColorButton, resName);

            String colorSuffix = (this.colorType == ColorType.BLACK_ON_WHITE) ? "black" : "white";
            setImageResource(settingsButton, "ic_settings_" + colorSuffix + "_36dp");
            setImageResource(galleryButton, "ic_photo_library_" + colorSuffix + "_36dp");
            setImageResource(settingsButton, "ic_settings_" + colorSuffix + "_36dp");
            setImageResource(helpButton, "ic_help_outline_" + colorSuffix + "_36dp");

            if (switchCameraButton.getVisibility() != View.GONE) {
                boolean isFrontFacing = CameraUtils.getCameraInfo(arManager.getCameraId()).isFrontFacing();
                setImageResource(switchCameraButton, isFrontFacing ?
                        "ic_camera_front_" + colorSuffix + "_36dp" :
                        "ic_camera_rear_" + colorSuffix + "_36dp");
            }

            this.overlayView.setBackgroundFillColor((this.colorType == ColorType.BLACK_ON_WHITE) ?
                    Color.argb(255, 255, 255, 255) : Color.argb(255, 0, 0, 0));
        }
        catch(Exception ex) {
            Log.e(TAG, "Error updating color button", ex);
        }
    }

    // onClick_ methods are assigned as onclick handlers in the main.xml layout
    public void onClick_cycleColorMode(View view) {
        AsciiConverter.ColorType[] colorTypeValues = AsciiConverter.ColorType.values();
        this.colorType = colorTypeValues[(this.colorType.ordinal() + 1) % colorTypeValues.length];
        saveColorStyleToPreferences();
        updateButtonsAndBackground();
    }

    public void onClick_gotoGallery(View view) {
        if (!hasStoragePermission()) {
            PermissionsChecker.requestStoragePermissionsToGoToLibrary(this);
            return;
        }
        Intent intent = LibraryActivity.intentWithImageDirectory(this,
                imageWriter.getBasePictureDirectory(), imageWriter.getThumbnailDirectory());
        startActivity(intent);
    }

    public void onClick_gotoAbout(View view) {
        AboutActivity.startIntent(this);
    }

    public void onClick_gotoPreferences(View view) {
        AsciiCamPreferences.startIntent(this, ACTIVITY_PREFERENCES);
    }

    public void onClick_switchCamera(View view) {
        try {
            Log.d(TAG, "Switching camera...");
            if (arManager != null) {
                // Enhanced camera switching with Instagram replica techniques
                
                // 1. Stop current processing and clear state
                if (imageProcessor != null) {
                    imageProcessor.stop();
                    imageProcessor = null;
                }
                
                // Clear any pending preview data to prevent flickering
                nextPreviewData = null;
                
                // 2. Stop camera preview properly (Instagram replica approach)
                try {
                    arManager.stopCamera();
                    Log.d(TAG, "Camera preview stopped for switching");
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping camera preview during switch", e);
                }
                
                // 3. Switch to next camera with enhanced error handling
                int previousCameraId = arManager.getCameraId();
                arManager.switchToNextCamera();
                int newCameraId = arManager.getCameraId();
                
                Log.d(TAG, "Camera switched from " + previousCameraId + " to " + newCameraId);
                
                // 4. Set proper orientation for the new camera (Instagram replica technique)
                setCameraDisplayOrientation(newCameraId);
                
                // 5. Refresh character mappings to ensure proper ASCII conversion
                updateFromPreferences();
                
                // 6. Restart camera with enhanced delay and error handling
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Start camera preview with new camera
                            arManager.startCameraIfVisible();
                            
                            // Restart image processing
                            imageProcessor = new AsyncProcessor<CameraPreviewData, Bitmap>();
                            imageProcessor.start();
                            
                            updateButtonsAndBackground();
                            Log.d(TAG, "Camera switched successfully with enhanced handling - new camera ID: " + newCameraId);
                        } catch (Exception e) {
                            Log.e(TAG, "Error restarting camera after switch", e);
                            // Attempt to recover by switching back
                            attemptCameraSwitchRecovery(previousCameraId);
                        }
                    }
                }, 150); // Slightly longer delay for better stability
                
            } else {
                Log.w(TAG, "ARManager is null, cannot switch camera");
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during camera switch", e);
            attemptCameraSwitchRecovery(-1); // Full recovery
        }
    }
    
    private void attemptCameraSwitchRecovery(int fallbackCameraId) {
        Log.d(TAG, "Attempting camera switch recovery...");
        try {
            // Stop everything first
            if (arManager != null) {
                arManager.stopCamera();
            }
            if (imageProcessor != null) {
                imageProcessor.stop();
                imageProcessor = null;
            }
            
            // Reinitialize camera system
            arManager = ARManager.createAndSetupCameraView(this, cameraView, this);
            if (arManager != null) {
                // If we have a specific fallback camera, try to use it
                if (fallbackCameraId >= 0) {
                    try {
                        // Note: ARManager may not have switchToCamera method, use switchToNextCamera instead
                        int currentId = arManager.getCameraId();
                        if (currentId != fallbackCameraId) {
                            arManager.switchToNextCamera();
                        }
                        Log.d(TAG, "Attempted recovery to camera: " + arManager.getCameraId());
                    } catch (Exception e) {
                        Log.w(TAG, "Could not switch to fallback camera, using default", e);
                    }
                }
                
                arManager.setPreferredPreviewSize(640, 480);
                arManager.setNumberOfPreviewCallbackBuffers(3);
                setCameraDisplayOrientation(arManager.getCameraId());
                arManager.startCameraIfVisible();
                
                // Restart processing
                imageProcessor = new AsyncProcessor<CameraPreviewData, Bitmap>();
                imageProcessor.start();
                
                updateFromPreferences();
                updateButtonsAndBackground();
                Log.d(TAG, "Camera switch recovery successful");
                Toast.makeText(this, "Camera recovered successfully", Toast.LENGTH_SHORT).show();
            } else {
                throw new RuntimeException("Failed to reinitialize ARManager");
            }
        } catch (Exception recoveryException) {
            Log.e(TAG, "Camera switch recovery failed", recoveryException);
            Toast.makeText(this, "Camera switch failed. Please restart the app.", Toast.LENGTH_LONG).show();
            
            // Disable camera switch button to prevent further issues
            if (switchCameraButton != null) {
                switchCameraButton.setEnabled(false);
            }
        }
    }

    // Comprehensive camera recovery method with Instagram replica techniques
    private void attemptCameraRecovery(String reason) {
        Log.w(TAG, "Attempting comprehensive camera recovery due to: " + reason);
        consecutiveErrors = 0; // Reset error counter
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // Step 1: Stop all camera-related operations
                    if (imageProcessor != null) {
                        imageProcessor.stop();
                        imageProcessor = null;
                        Log.d(TAG, "Image processor stopped for recovery");
                    }
                    
                    // Clear any pending preview data
                    nextPreviewData = null;
                    
                    // Step 2: Stop camera with proper error handling
                    if (arManager != null) {
                        try {
                            arManager.stopCamera();
                            Log.d(TAG, "Camera stopped for recovery");
                        } catch (Exception stopException) {
                            Log.w(TAG, "Error stopping camera during recovery", stopException);
                        }
                        
                        // Step 3: Brief pause to allow camera resources to be released
                        Thread.sleep(200); // Longer pause for better stability
                        
                        // Step 4: Attempt to restart camera
                        try {
                            arManager.startCameraIfVisible();
                            
                            // Step 5: Reinitialize camera settings
                            setCameraDisplayOrientation(arManager.getCameraId());
                            
                            // Step 6: Restart image processing
                            imageProcessor = new AsyncProcessor<CameraPreviewData, Bitmap>();
                            imageProcessor.start();
                            
                            // Step 7: Update UI and preferences
                            updateFromPreferences();
                            updateButtonsAndBackground();
                            
                            Log.d(TAG, "Comprehensive camera recovery successful");
                            Toast.makeText(AsciiCamActivity.this, "Camera recovered", Toast.LENGTH_SHORT).show();
                            
                        } catch (Exception restartException) {
                            Log.e(TAG, "Failed to restart camera during recovery", restartException);
                            
                            // Step 8: Last resort - try to reinitialize ARManager completely
                            try {
                                Log.w(TAG, "Attempting complete ARManager reinitialization");
                                arManager = ARManager.createAndSetupCameraView(AsciiCamActivity.this, cameraView, AsciiCamActivity.this);
                                
                                if (arManager != null) {
                                    arManager.setPreferredPreviewSize(640, 480);
                                    arManager.setNumberOfPreviewCallbackBuffers(3);
                                    setCameraDisplayOrientation(arManager.getCameraId());
                                    arManager.startCameraIfVisible();
                                    
                                    // Restart processing
                                    imageProcessor = new AsyncProcessor<CameraPreviewData, Bitmap>();
                                    imageProcessor.start();
                                    
                                    updateFromPreferences();
                                    updateButtonsAndBackground();
                                    
                                    Log.d(TAG, "Complete camera reinitialization successful");
                                    Toast.makeText(AsciiCamActivity.this, "Camera fully recovered", Toast.LENGTH_SHORT).show();
                                } else {
                                    throw new RuntimeException("Failed to reinitialize ARManager");
                                }
                            } catch (Exception reinitException) {
                                Log.e(TAG, "Complete camera recovery failed", reinitException);
                                Toast.makeText(AsciiCamActivity.this, "Camera recovery failed. Please restart the app.", Toast.LENGTH_LONG).show();
                                
                                // Disable camera-related buttons to prevent further issues
                                if (switchCameraButton != null) {
                                    switchCameraButton.setEnabled(false);
                                }
                                if (shutterButton != null) {
                                    shutterButton.setEnabled(false);
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "ARManager is null during recovery attempt");
                        Toast.makeText(AsciiCamActivity.this, "Camera not available", Toast.LENGTH_SHORT).show();
                    }
                    
                } catch (Exception recoveryException) {
                    Log.e(TAG, "Unexpected error during camera recovery", recoveryException);
                    Toast.makeText(AsciiCamActivity.this, "Camera recovery failed", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public void onClick_convertPicture(View view) {
        Intent i = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        startActivityForResult(i, ACTIVITY_PICK_IMAGE);
    }

    private void setCameraDisplayOrientation(int cameraId) {
        try {
            android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(cameraId, info);
            
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case android.view.Surface.ROTATION_0: degrees = 0; break;
                case android.view.Surface.ROTATION_90: degrees = 90; break;
                case android.view.Surface.ROTATION_180: degrees = 180; break;
                case android.view.Surface.ROTATION_270: degrees = 270; break;
            }
            
            int result;
            if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360;
                result = (360 - result) % 360; // compensate the mirror
            } else { // back-facing
                result = (info.orientation - degrees + 360) % 360;
            }
            
            Log.d(TAG, "Setting camera display orientation to: " + result);
            
            // Set the display orientation on the camera
            if (arManager != null && arManager.getCamera() != null) {
                arManager.getCamera().setDisplayOrientation(result);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting camera display orientation", e);
        }
    }

    // Determines the correct orientation for ASCII conversion based on camera info
    private AsciiConverter.Orientation getOrientationForCamera(CameraUtils.CameraInfo cameraInfo) {
        try {
            // Get device rotation using proper Android API
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int deviceOrientationDegrees = 0;
            switch (rotation) {
                case android.view.Surface.ROTATION_0: deviceOrientationDegrees = 0; break;
                case android.view.Surface.ROTATION_90: deviceOrientationDegrees = 90; break;
                case android.view.Surface.ROTATION_180: deviceOrientationDegrees = 180; break;
                case android.view.Surface.ROTATION_270: deviceOrientationDegrees = 270; break;
            }
            
            Log.d(TAG, "Camera sensor orientation: " + cameraInfo.orientation + ", device rotation: " + deviceOrientationDegrees);
            
            // Use Android's official formula for camera orientation
            // rotation = (sensorOrientationDegrees - deviceOrientationDegrees * sign + 360) % 360
            int sign = cameraInfo.isFrontFacing() ? 1 : -1;
            int result = (cameraInfo.orientation - deviceOrientationDegrees * sign + 360) % 360;
            
            Log.d(TAG, "Calculated orientation result: " + result + " (sign: " + sign + 
                    ", isFront: " + cameraInfo.isFrontFacing() + ")");
            
            // Convert to AsciiConverter.Orientation with proper mapping
            switch (result) {
                case 0: return AsciiConverter.Orientation.NORMAL;
                case 90: return AsciiConverter.Orientation.ROTATED_90;
                case 180: return AsciiConverter.Orientation.ROTATED_180;
                case 270: return AsciiConverter.Orientation.ROTATED_270;
                default: 
                    Log.w(TAG, "Unexpected orientation result: " + result + ", defaulting to NORMAL");
                    return AsciiConverter.Orientation.NORMAL;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating camera orientation, using NORMAL", e);
            return AsciiConverter.Orientation.NORMAL;
        }
    }

    // Creates the bitmap to display from a camera preview frame.
    AsyncProcessor.Producer<CameraPreviewData, Bitmap> asciiProducer =
            new AsyncProcessor.Producer<CameraPreviewData, Bitmap>() {
        @Override public Bitmap processInput(CameraPreviewData input) {
            try {
                Log.d(TAG, "asciiProducer.processInput called");
                if (input == null || input.pixelData == null || input.cameraInfo == null) {
                    Log.w(TAG, "Invalid input data for processing - input: " + (input != null) + 
                            ", pixelData: " + (input != null && input.pixelData != null) + 
                            ", cameraInfo: " + (input != null && input.cameraInfo != null));
                    return null;
                }
                
                // Dynamic orientation based on actual camera info
                AsciiConverter.Orientation orientation = getOrientationForCamera(input.cameraInfo);

                if (asciiConverter == null) {
                    Log.w(TAG, "AsciiConverter is null");
                    return null;
                }

                synchronized (asciiResult) {
                     long conversionStartTime = System.currentTimeMillis();
                     
                     // Enhanced screen dimension handling for all device types
                     int screenWidth = overlayView.getWidth();
                     int screenHeight = overlayView.getHeight();
                     
                     // Robust fallback to display metrics with orientation awareness
                     if (screenWidth <= 0 || screenHeight <= 0) {
                          Log.d(TAG, "OverlayView dimensions not ready, using display metrics");
                         android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                         getWindowManager().getDefaultDisplay().getMetrics(metrics);
                         
                         // Get real screen dimensions accounting for system bars
                         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                             getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                         }
                         
                         screenWidth = metrics.widthPixels;
                         screenHeight = metrics.heightPixels;
                         
                         // Account for navigation bar and status bar on different devices
                         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                             // Subtract navigation bar height if present
                             int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                             if (resourceId > 0) {
                                 int navBarHeight = getResources().getDimensionPixelSize(resourceId);
                                 screenHeight -= navBarHeight;
                             }
                         }
                         
                         Log.d(TAG, "Using display metrics: " + screenWidth + "x" + screenHeight + 
                                 ", density: " + metrics.density + ", densityDpi: " + metrics.densityDpi);
                     }
                     
                     imageRenderer.setMaximumImageSize(screenWidth, screenHeight);
                     imageRenderer.setCameraImageSize(input.width, input.height);
                     
                     // Use full ASCII grid for fullscreen coverage
                     int rows = imageRenderer.asciiRows();
                     int cols = imageRenderer.asciiColumns();
                     
                     if (rows <= 0 || cols <= 0) {
                         Log.w(TAG, "Invalid ASCII grid dimensions: " + rows + "x" + cols);
                         return null;
                     }
                     
                     // Get pixel chars string from map
                     String pixelCharsString = pixelCharsMap.get(colorType);
                     if (pixelCharsString == null || pixelCharsString.isEmpty()) {
                         // Use default characters if none found - fix String[] concatenation
                         StringBuilder sb = new StringBuilder();
                         String[] defaultChars = colorType.getDefaultPixelChars();
                         for (String ch : defaultChars) {
                             sb.append(ch);
                         }
                         pixelCharsString = sb.toString();
                         Log.d(TAG, "Fallback to default chars for " + colorType + ": '" + pixelCharsString + "' (length: " + pixelCharsString.length() + ")");
                     }
                     
                     Log.d(TAG, "ASCII Conversion - Input: " + input.width + "x" + input.height + 
                             ", Output: " + rows + "x" + cols + ", Orientation: " + orientation + 
                             ", ColorType: " + colorType + ", PixelChars: " + 
                             (pixelCharsString != null ? pixelCharsString.length() : "null") + " chars");
                     
                     long asciiStartTime = System.currentTimeMillis();
                     asciiConverter.computeResultForCameraData(input.pixelData, input.width, input.height,
                             rows, cols, colorType, pixelCharsString, orientation, asciiResult);
                     long asciiEndTime = System.currentTimeMillis();
                     
                     // Apply front camera mirroring to fix text orientation
                     asciiResult.adjustForFrontCamera(input.cameraInfo.isFrontFacing());
                     
                     long bitmapStartTime = System.currentTimeMillis();
                     Bitmap result = imageRenderer.createBitmap(asciiResult);
                     long bitmapEndTime = System.currentTimeMillis();
                     
                     long totalConversionTime = bitmapEndTime - conversionStartTime;
                     long asciiTime = asciiEndTime - asciiStartTime;
                     long bitmapTime = bitmapEndTime - bitmapStartTime;
                     
                     Log.d(TAG, "ASCII Performance - Total: " + totalConversionTime + "ms, " +
                             "ASCII: " + asciiTime + "ms, Bitmap: " + bitmapTime + "ms, " +
                             "Result: " + (result != null ? result.getWidth() + "x" + result.getHeight() : "null"));
                     
                     return result;
                 }
            } catch (Exception e) {
                Log.e(TAG, "Error processing input", e);
                return null;
            }
        }
    };

    // Callback to display the produced Bitmap.
    AsyncProcessor.SuccessCallback<CameraPreviewData, Bitmap> successCallback =
            new AsyncProcessor.SuccessCallback<CameraPreviewData, Bitmap>() {
        @Override public void handleResult(CameraPreviewData input, Bitmap output) {
            long currentTime = System.currentTimeMillis();
            long processingMillis = currentTime - input.timestamp;
            
            // Update performance metrics
            totalFramesProcessed++;
            totalProcessingTime += processingMillis;
            minProcessingTime = Math.min(minProcessingTime, processingMillis);
            maxProcessingTime = Math.max(maxProcessingTime, processingMillis);
            
            Log.d(TAG, "SUCCESS CALLBACK - Frame #" + totalFramesProcessed + 
                    ", Processing: " + processingMillis + "ms" +
                    ", Input: " + (input != null ? input.width + "x" + input.height : "null") +
                    ", Output: " + (output != null ? output.getWidth() + "x" + output.getHeight() : "null") + 
                    ", OverlayView: " + (overlayView != null));
            
            if (overlayView != null) {
                overlayView.setFlipHorizontal(false); // Mirroring handled at ASCII level
                overlayView.setBitmap(output);
                overlayView.invalidate();
                Log.v(TAG, "Bitmap successfully set on overlayView and invalidated");
            } else {
                Log.e(TAG, "overlayView is null in success callback!");
            }
            
            // Periodic performance statistics logging
            if (currentTime - lastDebugLogTime >= DEBUG_LOG_INTERVAL) {
                double avgProcessingTime = totalFramesProcessed > 0 ? 
                    (double) totalProcessingTime / totalFramesProcessed : 0;
                double currentFps = totalFramesProcessed > 0 ? 
                    1000.0 / avgProcessingTime : 0;
                
                Log.i(TAG, "=== PERFORMANCE STATS ===");
                Log.i(TAG, "Total Frames: " + totalFramesProcessed + ", Dropped: " + droppedFrames);
                Log.i(TAG, "Processing Time - Avg: " + String.format("%.1f", avgProcessingTime) + "ms" +
                        ", Min: " + minProcessingTime + "ms, Max: " + maxProcessingTime + "ms");
                Log.i(TAG, "Effective FPS: " + String.format("%.1f", currentFps));
                Log.i(TAG, "Consecutive Errors: " + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS);
                Log.i(TAG, "=========================");
                
                lastDebugLogTime = currentTime;
            }
            
            finishFrame(input);
        }
    };

    // Callback to handle an error producing a Bitmap.
    AsyncProcessor.ErrorCallback<CameraPreviewData> errorCallback =
            new AsyncProcessor.ErrorCallback<CameraPreviewData>() {
        @Override public void handleException(CameraPreviewData input, Exception ex) {
            Log.e(TAG, "ERROR CALLBACK - Exception creating ascii image", ex);
            Log.e(TAG, "Error details - input: " + (input != null) + 
                    ", exception type: " + ex.getClass().getSimpleName() + 
                    ", message: " + ex.getMessage());
            
            // Enhanced error recovery with Instagram replica techniques
            consecutiveErrors++;
            Log.w(TAG, "Consecutive processing errors: " + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS);
            
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                Log.e(TAG, "Too many consecutive processing errors, attempting comprehensive recovery");
                attemptCameraRecovery("ASCII processing errors: " + ex.getMessage());
            } else {
                // For fewer errors, just log and continue
                Log.w(TAG, "Processing error, continuing with next frame");
            }
            
            finishFrame(input);
        }
    };

    void finishFrame(CameraPreviewData previewData) {
        CameraUtils.addPreviewCallbackBuffer(previewData.camera, previewData.pixelData);
        if (imageProcessor != null && nextPreviewData != null) {
            if (DEBUG) Log.i(TAG, "Processing previously queued data");
            imageProcessor.processInputAsync(asciiProducer, nextPreviewData, successCallback, errorCallback, handler);
            nextPreviewData = null;
        }
    }

    @Override public void onPreviewFrame(byte[] data, Camera camera) {
        try {
            Log.d(TAG, "onPreviewFrame called - data: " + (data != null ? data.length + " bytes" : "null") + 
                    ", camera: " + (camera != null) + ", saveInProgress: " + saveInProgress + 
                    ", imageProcessor: " + (imageProcessor != null) + ", appVisible: " + appVisible + 
                    ", arManager: " + (arManager != null));
            
            if (saveInProgress || imageProcessor == null || !appVisible || arManager == null) {
                Log.d(TAG, "Skipping frame processing due to conditions");
                CameraUtils.addPreviewCallbackBuffer(camera, data);
                return;
            }
            
            // Enhanced frame rate limiting with detailed logging
            long currentTime = System.currentTimeMillis();
            long timeSinceLastFrame = currentTime - lastProcessTime;
            
            if (timeSinceLastFrame < MIN_PROCESS_INTERVAL) {
                // Store the frame data for later processing if we're not currently processing
                if (imageProcessor.getStatus() == AsyncProcessor.Status.IDLE) {
                    Camera.Size size = camera.getParameters().getPreviewSize();
                    nextPreviewData = new CameraPreviewData(
                            camera, CameraUtils.getCameraInfo(arManager.getCameraId()), data.clone(),
                            size.width, size.height, currentTime);
                    Log.v(TAG, "Frame queued for later processing (rate limited by " + 
                            (MIN_PROCESS_INTERVAL - timeSinceLastFrame) + "ms)");
                } else {
                    droppedFrames++;
                    Log.v(TAG, "Frame dropped - rate limited and processor busy (dropped: " + droppedFrames + ")");
                }
                CameraUtils.addPreviewCallbackBuffer(camera, data);
                return;
            }
            lastProcessTime = currentTime;
            
            // Reset error counter on successful frame processing
            if (consecutiveErrors > 0) {
                Log.d(TAG, "Resetting error counter after successful frame");
                consecutiveErrors = 0;
            }
            
            Camera.Size size = camera.getParameters().getPreviewSize();
            Log.d(TAG, "Camera preview size: " + size.width + "x" + size.height);
            
            CameraPreviewData previewData = new CameraPreviewData(
                    camera, CameraUtils.getCameraInfo(arManager.getCameraId()), data,
                    size.width, size.height,
                    currentTime);
            
            Log.d(TAG, "Created CameraPreviewData - width: " + previewData.width + ", height: " + previewData.height + 
                    ", dataLength: " + (previewData.pixelData != null ? previewData.pixelData.length : "null") + 
                    ", cameraInfo: " + (previewData.cameraInfo != null));
            
            if (imageProcessor.getStatus() == AsyncProcessor.Status.IDLE) {
                Log.d(TAG, "Processing frame immediately - Size: " + previewData.width + "x" + previewData.height + 
                        ", Data: " + previewData.pixelData.length + " bytes, Camera: " + arManager.getCameraId());
                imageProcessor.processInputAsync(asciiProducer, previewData, successCallback, errorCallback, handler);
            }
            else {
                Log.d(TAG, "AsyncProcessor busy, queueing frame data");
                if (nextPreviewData != null) {
                    // This will normally only happen if there are at least 3 preview buffers: one for
                    // the image currently being processed, one held in nextPreviewData, and the third
                    // incoming buffer. With only 2 buffers, we'll stop receiving previews until the
                    // current image is completed and its buffer is released.
                    droppedFrames++;
                    Log.d(TAG, "Replacing previous queued data (frame dropped: " + droppedFrames + ")");
                    CameraUtils.addPreviewCallbackBuffer(nextPreviewData.camera, nextPreviewData.pixelData);
                }
                nextPreviewData = previewData;
                Log.v(TAG, "Frame queued for processing when processor becomes idle");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing preview frame: " + e.getMessage(), e);
            consecutiveErrors++;
            
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                Log.e(TAG, "Too many consecutive preview errors (" + consecutiveErrors + "), attempting comprehensive recovery");
                attemptCameraRecovery("Preview frame processing errors: " + e.getMessage());
            }
            
            if (camera != null && data != null) {
                CameraUtils.addPreviewCallbackBuffer(camera, data);
            }
        }
    }

    @Override public void onShutterButtonFocus(boolean pressed) {
        shutterButton.setImageResource(pressed ? R.drawable.btn_camera_shutter_new :
            R.drawable.btn_camera_shutter_new);
    }

    @Override public void onShutterButtonClick() {
        takePicture();
    }
    
    // Touch-to-focus functionality inspired by Instagram replica
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return handleTouchToFocus(event.getX(), event.getY());
        }
        return super.onTouchEvent(event);
    }
    
    private boolean handleTouchToFocus(float x, float y) {
        try {
            // Check cooldown to prevent excessive focus attempts
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFocusTime < FOCUS_COOLDOWN || isFocusing) {
                Log.v(TAG, "Touch-to-focus ignored due to cooldown or ongoing focus");
                return true;
            }
            
            if (arManager == null || arManager.getCamera() == null) {
                Log.w(TAG, "Cannot focus: camera not available");
                return false;
            }
            
            Camera camera = arManager.getCamera();
            Camera.Parameters parameters = camera.getParameters();
            
            // Check if camera supports auto-focus
            List<String> focusModes = parameters.getSupportedFocusModes();
            if (focusModes == null || !focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                Log.d(TAG, "Camera does not support auto-focus");
                return false;
            }
            
            // Calculate focus area based on touch coordinates
            Rect focusRect = calculateFocusArea(x, y);
            
            // Set focus and metering areas
            if (parameters.getMaxNumFocusAreas() > 0) {
                List<Camera.Area> focusAreas = new ArrayList<Camera.Area>();
                focusAreas.add(new Camera.Area(focusRect, 1000));
                parameters.setFocusAreas(focusAreas);
                Log.d(TAG, "Focus area set to: " + focusRect.toString());
            }
            
            if (parameters.getMaxNumMeteringAreas() > 0) {
                List<Camera.Area> meteringAreas = new ArrayList<Camera.Area>();
                meteringAreas.add(new Camera.Area(focusRect, 1000));
                parameters.setMeteringAreas(meteringAreas);
                Log.d(TAG, "Metering area set to: " + focusRect.toString());
            }
            
            // Set focus mode to auto
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(parameters);
            
            // Start auto-focus
            isFocusing = true;
            lastFocusTime = currentTime;
            
            camera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    isFocusing = false;
                    if (success) {
                        Log.d(TAG, "Auto-focus successful");
                        // Optionally show focus indicator or play sound
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Could add visual feedback here
                                Toast.makeText(AsciiCamActivity.this, "Focus locked", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        Log.d(TAG, "Auto-focus failed");
                    }
                    
                    // Reset focus mode to continuous if supported
                    try {
                        Camera.Parameters params = camera.getParameters();
                        List<String> modes = params.getSupportedFocusModes();
                        if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                            camera.setParameters(params);
                            Log.d(TAG, "Reset to continuous focus mode");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error resetting focus mode", e);
                    }
                }
            });
            
            Log.d(TAG, "Touch-to-focus initiated at (" + x + ", " + y + ")");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error during touch-to-focus", e);
            isFocusing = false;
            return false;
        }
    }
    
    private Rect calculateFocusArea(float x, float y) {
        // Convert touch coordinates to camera focus area coordinates (-1000 to 1000)
        int viewWidth = cameraView.getWidth();
        int viewHeight = cameraView.getHeight();
        
        if (viewWidth == 0 || viewHeight == 0) {
            // Fallback to center focus
            return new Rect(-100, -100, 100, 100);
        }
        
        // Convert to camera coordinate system (-1000 to 1000)
        int focusX = (int) ((x / viewWidth) * 2000 - 1000);
        int focusY = (int) ((y / viewHeight) * 2000 - 1000);
        
        // Create focus area (200x200 pixel area around touch point)
        int focusSize = 100; // Half the focus area size
        
        int left = Math.max(-1000, focusX - focusSize);
        int top = Math.max(-1000, focusY - focusSize);
        int right = Math.min(1000, focusX + focusSize);
        int bottom = Math.min(1000, focusY + focusSize);
        
        return new Rect(left, top, right, bottom);
    }
    
    private void initializeAutoFocus() {
        try {
            if (arManager == null || arManager.getCamera() == null) {
                Log.w(TAG, "Cannot initialize auto-focus: camera not available");
                return;
            }
            
            Camera camera = arManager.getCamera();
            Camera.Parameters parameters = camera.getParameters();
            List<String> focusModes = parameters.getSupportedFocusModes();
            
            if (focusModes == null) {
                Log.d(TAG, "No focus modes supported by camera");
                return;
            }
            
            // Set the best available focus mode
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                Log.d(TAG, "Set focus mode to continuous picture");
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                Log.d(TAG, "Set focus mode to auto");
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_MACRO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_MACRO);
                Log.d(TAG, "Set focus mode to macro");
            } else {
                Log.d(TAG, "Using default focus mode: " + parameters.getFocusMode());
            }
            
            // Apply device-specific camera optimizations
            applyDeviceSpecificCameraOptimizations(parameters);
            
            camera.setParameters(parameters);
            Log.d(TAG, "Auto-focus initialized successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing auto-focus", e);
        }
    }
    
    private void applyDeviceSpecificCameraOptimizations(Camera.Parameters parameters) {
        try {
            String manufacturer = android.os.Build.MANUFACTURER.toLowerCase();
            String model = android.os.Build.MODEL.toLowerCase();
            
            Log.d(TAG, "Applying optimizations for device: " + manufacturer + " " + model);
            
            // Oppo device optimizations
            if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                Log.d(TAG, "Applying Oppo/OnePlus specific optimizations");
                
                // Disable video stabilization which can cause issues on Oppo devices
                if (parameters.isVideoStabilizationSupported()) {
                    parameters.setVideoStabilization(false);
                    Log.d(TAG, "Disabled video stabilization for Oppo device");
                }
                
                // Use more conservative preview format
                List<Integer> supportedFormats = parameters.getSupportedPreviewFormats();
                if (supportedFormats != null && supportedFormats.contains(ImageFormat.NV21)) {
                    parameters.setPreviewFormat(ImageFormat.NV21);
                    Log.d(TAG, "Set preview format to NV21 for Oppo device");
                }
                
                // Set conservative frame rate for stability
                List<int[]> supportedFpsRanges = parameters.getSupportedPreviewFpsRange();
                if (supportedFpsRanges != null && !supportedFpsRanges.isEmpty()) {
                    // Find a stable 15-30 fps range
                    for (int[] range : supportedFpsRanges) {
                        if (range[0] >= 15000 && range[1] <= 30000) {
                            parameters.setPreviewFpsRange(range[0], range[1]);
                            Log.d(TAG, "Set FPS range to " + range[0]/1000 + "-" + range[1]/1000 + " for Oppo device");
                            break;
                        }
                    }
                }
            }
            
            // Xiaomi device optimizations
            else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                Log.d(TAG, "Applying Xiaomi/Redmi specific optimizations");
                
                // Xiaomi devices often have issues with continuous focus
                List<String> focusModes = parameters.getSupportedFocusModes();
                if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    Log.d(TAG, "Using AUTO focus mode for Xiaomi device");
                }
                
                // Disable scene detection which can interfere
                if (parameters.isAutoWhiteBalanceLockSupported()) {
                    parameters.setAutoWhiteBalanceLock(false);
                }
            }
            
            // Samsung device optimizations
            else if (manufacturer.contains("samsung")) {
                Log.d(TAG, "Applying Samsung specific optimizations");
                
                // Samsung devices work well with continuous focus
                List<String> focusModes = parameters.getSupportedFocusModes();
                if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    Log.d(TAG, "Using CONTINUOUS_PICTURE focus mode for Samsung device");
                }
            }
            
            // General optimizations for all devices
            // Disable video stabilization by default as it can cause compatibility issues
            if (parameters.isVideoStabilizationSupported()) {
                parameters.setVideoStabilization(false);
                Log.d(TAG, "Disabled video stabilization for general compatibility");
            }
            
            // Set antibanding to reduce flicker
            List<String> antibandingModes = parameters.getSupportedAntibanding();
            if (antibandingModes != null) {
                if (antibandingModes.contains(Camera.Parameters.ANTIBANDING_AUTO)) {
                    parameters.setAntibanding(Camera.Parameters.ANTIBANDING_AUTO);
                    Log.d(TAG, "Set antibanding to AUTO");
                } else if (antibandingModes.contains(Camera.Parameters.ANTIBANDING_50HZ)) {
                    parameters.setAntibanding(Camera.Parameters.ANTIBANDING_50HZ);
                    Log.d(TAG, "Set antibanding to 50HZ");
                }
            }
            
            // Set white balance to auto for better color accuracy
            List<String> whiteBalanceModes = parameters.getSupportedWhiteBalance();
            if (whiteBalanceModes != null && whiteBalanceModes.contains(Camera.Parameters.WHITE_BALANCE_AUTO)) {
                parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
                Log.d(TAG, "Set white balance to AUTO");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error applying device-specific optimizations", e);
        }
    }

    // Test pattern to verify display pipeline works
    void createTestPattern() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Log.d(TAG, "Creating test pattern to verify display pipeline");
                    
                    // Create a simple test ASCII result
                    AsciiConverter.Result testResult = new AsciiConverter.Result();
                    testResult.rows = 20;
                    testResult.columns = 30;
                    testResult.colorType = colorType;
                    
                    // Create simple test pattern
                    testResult.asciiIndexes = new int[testResult.rows * testResult.columns];
                    testResult.asciiColors = new int[testResult.rows * testResult.columns];
                    
                    // Get pixel chars from map or use default
                    String pixelChars = pixelCharsMap.get(colorType);
                    if (pixelChars == null || pixelChars.isEmpty()) {
                        // Use default characters from ColorType - fix String[] concatenation
                        StringBuilder sb = new StringBuilder();
                        String[] defaultChars = colorType.getDefaultPixelChars();
                        for (String ch : defaultChars) {
                            sb.append(ch);
                        }
                        pixelChars = sb.toString();
                        Log.d(TAG, "Test pattern using default chars: '" + pixelChars + "' (length: " + pixelChars.length() + ")");
                    }
                    
                    // Set pixel chars array in result
                    testResult.pixelChars = new String[pixelChars.length()];
                    for (int i = 0; i < pixelChars.length(); i++) {
                        testResult.pixelChars[i] = String.valueOf(pixelChars.charAt(i));
                    }
                    
                    // Fill with a clear test pattern showing "CAMERA NOT WORKING"
                    String testMessage = "CAMERA NOT WORKING - CHECK PERMISSIONS";
                    for (int i = 0; i < testResult.asciiIndexes.length; i++) {
                        int row = i / testResult.columns;
                        int col = i % testResult.columns;
                        
                        // Show test message in the middle rows
                        if (row >= 8 && row <= 12 && col < testMessage.length()) {
                            // Use different characters to spell out the message
                            char c = testMessage.charAt(col);
                            if (c == ' ') {
                                testResult.asciiIndexes[i] = 0; // space
                            } else {
                                testResult.asciiIndexes[i] = pixelChars.length() - 1; // '@' for visible chars
                            }
                            testResult.asciiColors[i] = 0xFFFFFFFF; // White
                        } else {
                            // Fill background with dots
                            testResult.asciiIndexes[i] = 1; // '.' character
                            testResult.asciiColors[i] = 0xFF666666; // Gray
                        }
                    }
                    
                    Log.d(TAG, "Test pattern created with " + testResult.rows + "x" + testResult.columns + " characters");
                    
                    // Create bitmap from test result
                    Bitmap testBitmap = imageRenderer.createBitmap(testResult);
                    Log.d(TAG, "Test bitmap created: " + (testBitmap != null ? testBitmap.getWidth() + "x" + testBitmap.getHeight() : "null"));
                    
                    if (testBitmap != null && overlayView != null) {
                        overlayView.setBitmap(testBitmap);
                        overlayView.invalidate();
                        Log.d(TAG, "Test pattern displayed on overlayView");
                    } else {
                        Log.e(TAG, "Failed to display test pattern - bitmap: " + (testBitmap != null) + ", overlayView: " + (overlayView != null));
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error creating test pattern", e);
                }
            }
        }, 1000); // Delay to ensure UI is ready
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // take picture when pushing hardware camera button or trackball center
        if ((keyCode==KeyEvent.KEYCODE_CAMERA || keyCode==KeyEvent.KEYCODE_DPAD_CENTER) && event.getRepeatCount()==0) {
            handler.post(new Runnable() {
                @Override public void run() {
                    takePicture();
                }
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
