package com.example.smartfeederapp;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Manages the creation and retrieval of the Retrofit ApiService instance.
 * Ensures that the ApiService is initialized with the correct server address
 * from SettingsManager and handles potential changes in the address.
 */
public class ApiClient {

    private static final String TAG = "ApiClient";
    private ApiService apiService;
    private String currentBaseUrl = null;
    private final Context context;
    private final SettingsManager settingsManager;

    /**
     * Constructor for ApiClient.
     * Initializes dependencies and performs the initial ApiService setup.
     *
     * @param context The application context.
     */
    public ApiClient(Context context) {
        this.context = context.getApplicationContext();
        this.settingsManager = SettingsManager.getInstance(context);
        initializeApiService();
    }

    /**
     * Gets the singleton instance of ApiService.
     * If the server address has changed since the last initialization,
     * or if the service hasn't been created yet, it attempts to re-initialize.
     * Returns null if the server address is not configured or invalid.
     * This method is synchronized to handle concurrent access safely.
     *
     * @return The ApiService instance or null if unavailable.
     */
    public synchronized ApiService getApiService() {
        String serverAddress = settingsManager.getServerAddress();
        String baseUrl = (serverAddress != null) ? "http://" + serverAddress + "/" : null;

        if (apiService == null || (baseUrl != null && !baseUrl.equals(currentBaseUrl))) {
            Log.d(TAG, "ApiService is not ready or URL changed. Attempting initialization...");
            initializeApiService();
        } else if (baseUrl == null) {
            Log.w(TAG, "Server address is not set. ApiService cannot be used.");
            apiService = null;
        }
        return apiService;
    }

    /**
     * Initializes or re-initializes the Retrofit ApiService instance.
     * Reads the server address from SettingsManager, builds the Retrofit client,
     * and creates the ApiService interface. Handles invalid server addresses.
     * This method is synchronized to prevent race conditions during initialization.
     */
    private synchronized void initializeApiService() {
        String serverAddress = settingsManager.getServerAddress();
        if (serverAddress != null && !serverAddress.isEmpty()) {
            currentBaseUrl = "http://" + serverAddress + "/";
            try {
                Retrofit retrofit = new Retrofit.Builder().baseUrl(currentBaseUrl).addConverterFactory(GsonConverterFactory.create()).build();
                apiService = retrofit.create(ApiService.class);
                Log.d(TAG, "ApiService initialized successfully for URL: " + currentBaseUrl);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid server address format during ApiService initialization: " + serverAddress, e);
                apiService = null;
                currentBaseUrl = null;
            }
        } else {
            Log.w(TAG, "Server address not configured. ApiService not initialized.");
            apiService = null;
            currentBaseUrl = null;
        }
    }
}