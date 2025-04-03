package com.example.smartfeederapp;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.content.SharedPreferences;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

/**
 * Handles the logic for downloading video files, including permission checks
 * for notifications on newer Android versions.
 */
public class DownloadHandler {

    private static final String TAG = "DownloadHandler";
    private static final String PREFS_NAME = "DownloadHandlerPrefs";

    private final Activity activity;
    private final ActivityResultLauncher<String> requestPermissionLauncher;
    private VideoItem pendingDownloadItem = null;

    /**
     * Constructor for DownloadHandler.
     * @param activity The Activity context.
     * @param permissionLauncher The ActivityResultLauncher for requesting permissions.
     */
    public DownloadHandler(Activity activity, ActivityResultLauncher<String> permissionLauncher) {
        this.activity = activity;
        this.requestPermissionLauncher = permissionLauncher;
    }

    /**
     * Checks for necessary permissions (POST_NOTIFICATIONS on Android 13+)
     * and initiates the download process for the given video item.
     * @param videoItem The video item to download.
     */
    public void checkPermissionsAndDownload(VideoItem videoItem) {
        pendingDownloadItem = videoItem;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "POST_NOTIFICATIONS permission already granted.");
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null;
            } else if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                showPermissionRationaleDialog();
            } else {
                requestNotificationPermission();
            }
        } else {
            Log.d(TAG, "Android version below 13, POST_NOTIFICATIONS permission not required.");
            startDownload(pendingDownloadItem);
            pendingDownloadItem = null;
        }
    }

    /**
     * Displays a dialog explaining why the notification permission is needed.
     */
    private void showPermissionRationaleDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("Permission Needed")
                .setMessage("Notification permission is required to show download progress and completion status.")
                .setPositiveButton("OK", (dialog, which) -> requestNotificationPermission())
                .setNegativeButton("Cancel", (dialog, which) -> {
                    //Toast.makeText(activity, "Download will proceed without notifications.", Toast.LENGTH_SHORT).show();
                    startDownload(pendingDownloadItem);
                    pendingDownloadItem = null;
                })
                .show();
    }

    /**
     * Requests the POST_NOTIFICATIONS permission using the provided ActivityResultLauncher.
     */
    private void requestNotificationPermission() {
        Log.d(TAG, "Requesting POST_NOTIFICATIONS permission...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /**
     * Handles the result of the permission request.
     * Called by MainActivity after the user responds to the permission dialog.
     * @param isGranted True if the permission was granted, false otherwise.
     */
    public void onPermissionResult(boolean isGranted) {
        if (isGranted) {
            if (pendingDownloadItem != null) {
                Log.d(TAG, "POST_NOTIFICATIONS permission granted, starting download for: " + pendingDownloadItem.getFilename());
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null;
            }
        } else {
            // Toast.makeText(activity, "Notifications permission was not granted.", Toast.LENGTH_LONG).show();
            if (pendingDownloadItem != null) {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied, but attempting download anyway for: " + pendingDownloadItem.getFilename());
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null;
            }
        }
    }


    /**
     * Initiates the video download using Android's DownloadManager.
     * @param videoItem The video item containing the URL and filename.
     */
    public void startDownload(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null || videoItem.getFilename() == null) {
            Log.e(TAG, "Cannot start download: invalid VideoItem data.");
            Toast.makeText(activity, "Video data error for download", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = videoItem.getUrl();
        String filename = videoItem.getFilename().replaceAll("[\\\\/:*?\"<>|]", "_");

        try {
            DownloadManager downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) {
                Log.e(TAG, "DownloadManager service is not available.");
                Toast.makeText(activity, "Download service unavailable", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri downloadUri = Uri.parse(url);
            DownloadManager.Request request = new DownloadManager.Request(downloadUri);

            String mimeType = getMimeType(url);
            request.setMimeType(mimeType != null ? mimeType : "video/*");
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
                    .setTitle(filename)
                    .setDescription("Downloading video...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

            long downloadId = downloadManager.enqueue(request);

            Log.i(TAG, "Starting download for file: " + filename + " (ID: " + downloadId + ") from URL: " + url);
            Toast.makeText(activity, "Starting download: " + filename, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error starting download for file " + filename, e);
            Toast.makeText(activity, "Failed to start download", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Determines the MIME type of a file based on its URL's extension.
     * @param url The URL of the file.
     * @return The determined MIME type string, or null if not found.
     */
    private String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        Log.d(TAG, "Determined MIME type for " + url + ": " + type);
        return type;
    }
}