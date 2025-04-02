package com.example.smartfeederapp;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {

    private static final String TAG = "ApiClient";
    private ApiService apiService;
    private String currentBaseUrl = null;
    private final Context context;
    private final SettingsManager settingsManager;

    public ApiClient(Context context) {
        this.context = context.getApplicationContext();
        this.settingsManager = SettingsManager.getInstance(context);
        initializeApiService();
    }

    public synchronized ApiService getApiService() {

        String serverAddress = settingsManager.getServerAddress();
        String baseUrl = (serverAddress != null) ? "http://" + serverAddress + "/" : null;

        if (apiService == null || (baseUrl != null && !baseUrl.equals(currentBaseUrl))) {
            Log.d(TAG, "ApiService не готов или URL изменился. Попытка инициализации...");
            initializeApiService();
        } else if (baseUrl == null) {
            Log.w(TAG, "Адрес сервера не задан. ApiService не может быть использован.");
            apiService = null;
        }
        return apiService;
    }

    private synchronized void initializeApiService() {
        String serverAddress = settingsManager.getServerAddress();
        if (!TextUtils.isEmpty(serverAddress)) {
            currentBaseUrl = "http://" + serverAddress + "/";
            try {
                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(currentBaseUrl)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build();
                apiService = retrofit.create(ApiService.class);
                Log.d(TAG, "ApiService успешно инициализирован для URL: " + currentBaseUrl);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Неверный формат адреса сервера при инициализации ApiService: " + serverAddress, e);
                apiService = null;
                currentBaseUrl = null;


            }
        } else {
            Log.w(TAG, "Адрес сервера не настроен, ApiService не инициализирован.");
            apiService = null;
            currentBaseUrl = null;
        }
    }
}