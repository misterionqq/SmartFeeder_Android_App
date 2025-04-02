package com.example.smartfeederapp;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

public class DownloadHandler {

    private static final String TAG = "DownloadHandler";

    private final Activity activity;
    private final ActivityResultLauncher<String> requestPermissionLauncher;
    private VideoItem pendingDownloadItem = null;

    public DownloadHandler(Activity activity, ActivityResultLauncher<String> permissionLauncher) {
        this.activity = activity;
        this.requestPermissionLauncher = permissionLauncher;
    }

    public void checkPermissionsAndDownload(VideoItem videoItem) {
        pendingDownloadItem = videoItem;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Разрешение POST_NOTIFICATIONS уже есть.");
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null;
            } else if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                showPermissionRationaleDialog();
            } else {
                requestNotificationPermission();
            }
        } else {
            Log.d(TAG, "Версия Android ниже 13, разрешение POST_NOTIFICATIONS не требуется.");
            startDownload(pendingDownloadItem);
            pendingDownloadItem = null;
        }
    }

    private void showPermissionRationaleDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("Нужно разрешение")
                .setMessage("Для отображения статуса скачивания в уведомлениях требуется разрешение.")
                .setPositiveButton("OK", (dialog, which) -> requestNotificationPermission())
                .setNegativeButton("Отмена", (dialog, which) -> {
                    Toast.makeText(activity, "Скачивание будет выполнено без уведомлений.", Toast.LENGTH_SHORT).show();
                    startDownload(pendingDownloadItem);
                    pendingDownloadItem = null;
                })
                .show();
    }

    private void requestNotificationPermission() {
        Log.d(TAG, "Запрос разрешения POST_NOTIFICATIONS...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    public void onPermissionResult(boolean isGranted) {
        if (isGranted) {
            if (pendingDownloadItem != null) {
                Log.d(TAG, "Разрешение POST_NOTIFICATIONS получено, начинаем скачивание для: " + pendingDownloadItem.getFilename());
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null;
            }
        } else {
            Toast.makeText(activity, "Разрешение на уведомления не предоставлено.", Toast.LENGTH_LONG).show();
            if (pendingDownloadItem != null) {
                Log.w(TAG, "Разрешение POST_NOTIFICATIONS не получено, но пытаемся скачать: " + pendingDownloadItem.getFilename());
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null;
            }
        }
    }


    public void startDownload(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null || videoItem.getFilename() == null) {
            Log.e(TAG, "Невозможно начать скачивание: неверные данные VideoItem.");
            Toast.makeText(activity, "Ошибка данных видео для скачивания", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = videoItem.getUrl();
        String filename = videoItem.getFilename().replaceAll("[\\\\/:*?\"<>|]", "_");

        try {
            DownloadManager downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) {
                Log.e(TAG, "DownloadManager не доступен.");
                Toast.makeText(activity, "Сервис загрузок недоступен", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri downloadUri = Uri.parse(url);
            DownloadManager.Request request = new DownloadManager.Request(downloadUri);

            String mimeType = getMimeType(url);
            request.setMimeType(mimeType != null ? mimeType : "video/*");
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
                    .setTitle(filename)
                    .setDescription("Скачивание видео...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

            long downloadId = downloadManager.enqueue(request);

            Log.i(TAG, "Начало скачивания файла: " + filename + " (ID: " + downloadId + ") с URL: " + url);
            Toast.makeText(activity, "Начало скачивания: " + filename, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Ошибка при старте скачивания файла " + filename, e);
            Toast.makeText(activity, "Не удалось начать скачивание", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        Log.d(TAG, "Определен MIME тип для " + url + ": " + type);
        return type;
    }
}