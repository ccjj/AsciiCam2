// Copyright (C) 2012 Brian Nenninger

package com.dozingcatsoftware.util;

import android.app.Activity;
import android.hardware.Camera;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.util.Log;
import android.os.Build;
import java.util.List;

/**
 * This class handles initializing, starting, and stopping the camera. An Activity using this class must provide the
 * SurfaceView that the camera preview will draw into, and a Camera.PreviewCallback to process the data for each
 * preview frame. It also needs to call startCamera() and stopCamera() when needed, such as in onResume and onPause.
 */
public class ARManager implements SurfaceHolder.Callback {

    private static final String TAG = "ARManager";
    
    Activity activity;
    SurfaceView cameraView;
    Camera.PreviewCallback previewCallback;
    Runnable cameraOpenedCallback;

    Camera camera;
    boolean cameraViewReady = false;
    int cameraId = 0;

    int preferredPreviewWidth = 0, preferredPreviewHeight = 0;
    int numPreviewCallbackBuffers = 0;
    
    private boolean isInitialized = false;
    private int cameraOpenRetries = 0;
    private static final int MAX_CAMERA_RETRIES = 3;
    private long lastCameraFailureTime = 0;
    private static final long CAMERA_RETRY_COOLDOWN = 2000; // 2 seconds

    public ARManager(Activity _activity, SurfaceView _cameraView, Camera.PreviewCallback _previewCallback) {
        this.activity = _activity;
        this.cameraView = _cameraView;
        this.previewCallback = _previewCallback;
    }

    public void setupCameraView() {
        cameraView.getHolder().addCallback(this);
        cameraView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    public static ARManager createAndSetupCameraView(Activity _activity, SurfaceView _cameraView, Camera.PreviewCallback _previewCallback) {
        ARManager manager = new ARManager(_activity, _cameraView, _previewCallback);
        manager.setupCameraView();
        return manager;
    }

    public void setPreferredPreviewSize(int width, int height) {
        this.preferredPreviewWidth = width;
        this.preferredPreviewHeight = height;
    }

    public void setNumberOfPreviewCallbackBuffers(int n) {
        this.numPreviewCallbackBuffers = n;
    }

    public void setCameraOpenedCallback(Runnable callback) {
        cameraOpenedCallback = callback;
    }

    public boolean startCamera() {
        if (camera==null) {
            try {
                Log.d(TAG, "Starting camera " + cameraId + " on device: " + Build.MANUFACTURER + " " + Build.MODEL);
                
                camera = CameraUtils.openCamera(cameraId);
                if (camera == null) {
                    Log.e(TAG, "Failed to open camera " + cameraId);
                    return false;
                }
                
                if (cameraOpenedCallback!=null) {
                    cameraOpenedCallback.run();
                }
                
                // Set preview display with error handling
                try {
                    camera.setPreviewDisplay(cameraView.getHolder());
                } catch (Exception displayEx) {
                    Log.e(TAG, "Error setting preview display", displayEx);
                    releaseCamera();
                    return false;
                }
                
                // Configure camera parameters with device-specific handling
                if (!configureCameraParameters()) {
                    Log.e(TAG, "Failed to configure camera parameters");
                    releaseCamera();
                    return false;
                }

                // Set up preview callbacks
                if (numPreviewCallbackBuffers > 0) {
                    if (CameraUtils.createPreviewCallbackBuffers(camera, this.numPreviewCallbackBuffers)) {
                        CameraUtils.setPreviewCallbackWithBuffer(camera, this.previewCallback);
                    } else {
                        Log.w(TAG, "Failed to create preview buffers, using regular callback");
                        camera.setPreviewCallback(this.previewCallback);
                    }
                }
                else {
                    camera.setPreviewCallback(this.previewCallback);
                }
                
                // Start preview with error handling
                try {
                    camera.startPreview();
                    isInitialized = true;
                    cameraOpenRetries = 0;
                    Log.d(TAG, "Camera preview started successfully");
                } catch (Exception previewEx) {
                    Log.e(TAG, "Error starting camera preview", previewEx);
                    releaseCamera();
                    return false;
                }
                
            }
            catch(Exception ex) {
                Log.e(TAG, "Error in startCamera", ex);
                releaseCamera();
                
                // Enhanced retry logic with cooldown period
                long currentTime = System.currentTimeMillis();
                if (cameraOpenRetries < MAX_CAMERA_RETRIES && 
                    (currentTime - lastCameraFailureTime) > CAMERA_RETRY_COOLDOWN) {
                    
                    cameraOpenRetries++;
                    lastCameraFailureTime = currentTime;
                    Log.d(TAG, "Retrying camera initialization (attempt " + cameraOpenRetries + "/" + MAX_CAMERA_RETRIES + ")");
                    
                    try {
                        Thread.sleep(isProblematicDevice() ? 1000 : 500); // Longer wait for problematic devices
                        return startCamera();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else if (cameraOpenRetries >= MAX_CAMERA_RETRIES) {
                    Log.e(TAG, "Maximum camera retry attempts reached. Camera initialization failed permanently.");
                } else {
                    Log.d(TAG, "Camera retry cooldown active. Waiting before next attempt.");
                }
            }
        }
        return (camera!=null);
    }
    
    /** Configures camera parameters with device-specific optimizations */
    private boolean configureCameraParameters() {
        if (camera == null) return false;
        
        try {
            Camera.Parameters params = camera.getParameters();
            
            // Set preview size
            if (preferredPreviewWidth>0 && preferredPreviewHeight>0) {
                Camera.Size selectedSize = CameraUtils.setNearestCameraPreviewSize(camera, preferredPreviewWidth, preferredPreviewHeight);
                if (selectedSize != null) {
                    Log.d(TAG, "Set preview size to: " + selectedSize.width + "x" + selectedSize.height);
                }
            }
            
            // Apply device-specific parameter fixes
            if (isProblematicDevice()) {
                applyDeviceSpecificFixes(params);
            }
            
            return true;
        } catch (Exception ex) {
            Log.e(TAG, "Error configuring camera parameters", ex);
            return false;
        }
    }
    
    /** Applies device-specific camera parameter fixes */
    private void applyDeviceSpecificFixes(Camera.Parameters params) {
        try {
            // Disable video stabilization for problematic devices
            if (params.isVideoStabilizationSupported()) {
                params.setVideoStabilization(false);
                Log.d(TAG, "Disabled video stabilization for device compatibility");
            }
            
            // Set conservative frame rate
            List<int[]> supportedFpsRanges = params.getSupportedPreviewFpsRange();
            if (supportedFpsRanges != null && !supportedFpsRanges.isEmpty()) {
                // Find a conservative fps range (around 15-30 fps)
                for (int[] range : supportedFpsRanges) {
                    if (range[0] <= 15000 && range[1] >= 20000 && range[1] <= 30000) {
                        params.setPreviewFpsRange(range[0], range[1]);
                        Log.d(TAG, "Set FPS range: " + range[0]/1000 + "-" + range[1]/1000);
                        break;
                    }
                }
            }
            
            camera.setParameters(params);
        } catch (Exception ex) {
            Log.w(TAG, "Error applying device-specific fixes", ex);
        }
    }
    
    /** Checks if the current device is known to have camera issues */
    private boolean isProblematicDevice() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        
        return manufacturer.contains("xiaomi") ||
               manufacturer.contains("oppo") ||
               manufacturer.contains("vivo") ||
               manufacturer.contains("realme") ||
               manufacturer.contains("oneplus") ||
               model.contains("poco") ||
               model.contains("redmi");
    }
    
    /** Safely releases the camera */
    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ex) {
                Log.w(TAG, "Error releasing camera", ex);
            } finally {
                camera = null;
                isInitialized = false;
            }
        }
    }


    public void startCameraIfVisible() {
        if (cameraViewReady) {
            startCamera();
        }
    }

    public void stopCamera() {
        Log.d(TAG, "Stopping camera");
        releaseCamera();
    }

    public void switchToCamera(int _cameraId) {
        if (camera!=null) {
            stopCamera();
        }
        this.cameraId = _cameraId;
        startCameraIfVisible();
    }

    public void switchToNextCamera() {
        switchToCamera((cameraId + 1) % CameraUtils.numberOfCameras());
    }

    public boolean isCameraFrontFacing() {
        return CameraUtils.cameraIsFrontFacing(cameraId);
    }

    public int getCameraOrientation() {
        return CameraUtils.getCameraInfo(cameraId).orientation;
    }

    // SurfaceHolder callbacks
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        this.cameraViewReady = true;
        startCameraIfVisible();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // all done in surfaceChanged
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        this.cameraViewReady = false;
        stopCamera();
    }


    public Camera getCamera() {
        return camera;
    }
    public int getCameraId() {
        return cameraId;
    }

}
