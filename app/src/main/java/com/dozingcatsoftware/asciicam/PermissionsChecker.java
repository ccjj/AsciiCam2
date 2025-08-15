package com.dozingcatsoftware.asciicam;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

@TargetApi(23)
public class PermissionsChecker {
    private static final String TAG = "PermissionsChecker";
    
    public static final int CAMERA_AND_STORAGE_REQUEST_CODE = 1001;
    public static final int STORAGE_FOR_PHOTO_REQUEST_CODE = 1002;
    public static final int STORAGE_FOR_LIBRARY_REQUEST_CODE = 1003;
    public static final int CAMERA_ONLY_REQUEST_CODE = 1004;
    
    // Track permission request attempts to avoid infinite loops
    private static int cameraPermissionAttempts = 0;
    private static int storagePermissionAttempts = 0;
    private static final int MAX_PERMISSION_ATTEMPTS = 2;

    static boolean hasPermission(Activity activity, String perm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true; // Permissions granted at install time for pre-M
        }
        try {
            return activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.e(TAG, "Error checking permission: " + perm, e);
            return false;
        }
    }

    public static boolean hasCameraPermission(Activity activity) {
        return hasPermission(activity, Manifest.permission.CAMERA);
    }

    public static boolean hasStoragePermission(Activity activity) {
        // For Android 13+ (API 33), use READ_MEDIA_IMAGES instead of READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
            return hasPermission(activity, "android.permission.READ_MEDIA_IMAGES");
        }
        // For Android 10+ (API 29), we don't need WRITE_EXTERNAL_STORAGE for app-specific directories
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return hasPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return hasPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                hasPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }
    
    public static boolean shouldShowCameraPermissionRationale(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
    }
    
    public static boolean shouldShowStoragePermissionRationale(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        // For Android 13+ (API 33), check READ_MEDIA_IMAGES permission
        if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
            return activity.shouldShowRequestPermissionRationale("android.permission.READ_MEDIA_IMAGES");
        }
        return activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) ||
               (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && 
                activity.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE));
    }

    public static void requestCameraPermissionOnly(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return; // No runtime permissions needed
        }
        
        if (cameraPermissionAttempts >= MAX_PERMISSION_ATTEMPTS) {
            showPermissionSettingsDialog(activity, "Camera permission is required for this app to function. Please enable it in Settings.");
            return;
        }
        
        if (shouldShowCameraPermissionRationale(activity)) {
            showCameraPermissionExplanationDialog(activity);
        } else {
            cameraPermissionAttempts++;
            Log.d(TAG, "Requesting camera permission (attempt " + cameraPermissionAttempts + ")");
            activity.requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_ONLY_REQUEST_CODE);
        }
    }

    public static void requestCameraAndStoragePermissions(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return; // No runtime permissions needed
        }
        
        if (cameraPermissionAttempts >= MAX_PERMISSION_ATTEMPTS) {
            showPermissionSettingsDialog(activity, "Camera and storage permissions are required for this app to function. Please enable them in Settings.");
            return;
        }
        
        if (shouldShowCameraPermissionRationale(activity) || shouldShowStoragePermissionRationale(activity)) {
            showCameraAndStoragePermissionExplanationDialog(activity);
        } else {
            cameraPermissionAttempts++;
            Log.d(TAG, "Requesting camera and storage permissions (attempt " + cameraPermissionAttempts + ")");
            
            String[] permissions;
            if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
                // Android 13+ uses READ_MEDIA_IMAGES instead of READ_EXTERNAL_STORAGE
                permissions = new String[]{
                        Manifest.permission.CAMERA,
                        "android.permission.READ_MEDIA_IMAGES"
                };
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ doesn't need WRITE_EXTERNAL_STORAGE for app-specific directories
                permissions = new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                };
            } else {
                permissions = new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                };
            }
            
            activity.requestPermissions(permissions, CAMERA_AND_STORAGE_REQUEST_CODE);
        }
    }

    public static void requestStoragePermissionsToTakePhoto(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return; // No runtime permissions needed
        }
        
        if (storagePermissionAttempts >= MAX_PERMISSION_ATTEMPTS) {
            showPermissionSettingsDialog(activity, "Storage permission is required to save photos. Please enable it in Settings.");
            return;
        }
        
        if (shouldShowStoragePermissionRationale(activity)) {
            showStoragePermissionExplanationDialog(activity, "to save photos", STORAGE_FOR_PHOTO_REQUEST_CODE);
        } else {
            storagePermissionAttempts++;
            Log.d(TAG, "Requesting storage permissions for photo (attempt " + storagePermissionAttempts + ")");
            
            String[] permissions;
            if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
                permissions = new String[]{"android.permission.READ_MEDIA_IMAGES"};
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
            } else {
                permissions = new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                };
            }
            
            activity.requestPermissions(permissions, STORAGE_FOR_PHOTO_REQUEST_CODE);
        }
    }

    public static void requestStoragePermissionsToGoToLibrary(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return; // No runtime permissions needed
        }
        
        if (storagePermissionAttempts >= MAX_PERMISSION_ATTEMPTS) {
            showPermissionSettingsDialog(activity, "Storage permission is required to access the photo library. Please enable it in Settings.");
            return;
        }
        
        if (shouldShowStoragePermissionRationale(activity)) {
            showStoragePermissionExplanationDialog(activity, "to access the photo library", STORAGE_FOR_LIBRARY_REQUEST_CODE);
        } else {
            storagePermissionAttempts++;
            Log.d(TAG, "Requesting storage permissions for library (attempt " + storagePermissionAttempts + ")");
            
            String[] permissions;
            if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
                permissions = new String[]{"android.permission.READ_MEDIA_IMAGES"};
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
            } else {
                permissions = new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                };
            }
            
            activity.requestPermissions(permissions, STORAGE_FOR_LIBRARY_REQUEST_CODE);
        }
    }
    
    private static void showCameraPermissionExplanationDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Camera Permission Required")
                .setMessage("AsciiCam needs camera access to convert your camera view into ASCII art. This is the core functionality of the app.")
                .setPositiveButton("Grant Permission", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cameraPermissionAttempts++;
                        activity.requestPermissions(
                                new String[]{Manifest.permission.CAMERA},
                                CAMERA_ONLY_REQUEST_CODE);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(activity, "Camera permission is required for this app to work", Toast.LENGTH_LONG).show();
                    }
                })
                .setCancelable(false)
                .show();
    }
    
    private static void showCameraAndStoragePermissionExplanationDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Permissions Required")
                .setMessage("AsciiCam needs:\n\n• Camera access to convert your camera view into ASCII art\n• Storage access to save your ASCII photos")
                .setPositiveButton("Grant Permissions", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cameraPermissionAttempts++;
                        
                        String[] permissions;
                        if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
                            permissions = new String[]{
                                    Manifest.permission.CAMERA,
                                    "android.permission.READ_MEDIA_IMAGES"
                            };
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            permissions = new String[]{
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                            };
                        } else {
                            permissions = new String[]{
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                            };
                        }
                        
                        activity.requestPermissions(permissions, CAMERA_AND_STORAGE_REQUEST_CODE);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(activity, "Permissions are required for this app to work", Toast.LENGTH_LONG).show();
                    }
                })
                .setCancelable(false)
                .show();
    }
    
    private static void showStoragePermissionExplanationDialog(Activity activity, String purpose, int requestCode) {
        new AlertDialog.Builder(activity)
                .setTitle("Storage Permission Required")
                .setMessage("AsciiCam needs storage access " + purpose + ". Your photos will be saved in a dedicated folder.")
                .setPositiveButton("Grant Permission", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        storagePermissionAttempts++;
                        
                        String[] permissions;
                        if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
                            permissions = new String[]{"android.permission.READ_MEDIA_IMAGES"};
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
                        } else {
                            permissions = new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                            };
                        }
                        
                        activity.requestPermissions(permissions, requestCode);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(activity, "Storage permission is required " + purpose, Toast.LENGTH_LONG).show();
                    }
                })
                .setCancelable(false)
                .show();
    }
    
    private static void showPermissionSettingsDialog(Activity activity, String message) {
        new AlertDialog.Builder(activity)
                .setTitle("Permission Required")
                .setMessage(message)
                .setPositiveButton("Open Settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openAppSettings(activity);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(activity, "App functionality will be limited without permissions", Toast.LENGTH_LONG).show();
                    }
                })
                .setCancelable(false)
                .show();
    }
    
    private static void openAppSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
            intent.setData(uri);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error opening app settings", e);
            Toast.makeText(activity, "Could not open settings. Please manually enable permissions in Settings > Apps", Toast.LENGTH_LONG).show();
        }
    }
    
    // Reset permission attempt counters (call when permissions are granted)
    public static void resetPermissionAttempts() {
        cameraPermissionAttempts = 0;
        storagePermissionAttempts = 0;
        Log.d(TAG, "Permission attempt counters reset");
    }
    
    // Check if all required permissions are granted
    public static boolean hasAllRequiredPermissions(Activity activity) {
        return hasCameraPermission(activity) && hasStoragePermission(activity);
    }
    
    // Handle permission result with improved logic
    public static boolean handlePermissionResult(Activity activity, int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length == 0) {
            Log.w(TAG, "Permission result with empty grantResults array");
            return false;
        }
        
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        
        if (allGranted) {
            resetPermissionAttempts();
            Log.d(TAG, "All permissions granted for request code: " + requestCode);
            return true;
        } else {
            Log.w(TAG, "Some permissions denied for request code: " + requestCode);
            
            // Check if user selected "Don't ask again"
            boolean shouldShowRationale = false;
            for (String permission : permissions) {
                if (activity.shouldShowRequestPermissionRationale(permission)) {
                    shouldShowRationale = true;
                    break;
                }
            }
            
            if (!shouldShowRationale && (cameraPermissionAttempts > 0 || storagePermissionAttempts > 0)) {
                // User likely selected "Don't ask again"
                showPermissionSettingsDialog(activity, "Permissions are required for this app to function. Please enable them in Settings.");
            }
            
            return false;
        }
    }
}
