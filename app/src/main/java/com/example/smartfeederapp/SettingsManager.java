// Файл: SettingsManager.java
package com.example.smartfeederapp;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_SERVER_ADDRESS = "server_address";
    private static final String KEY_CLIENT_ID = "client_id";

    private static volatile SettingsManager instance;
    private final SharedPreferences sharedPreferences;

    private SettingsManager(Context context) {
        // Use application context to prevent memory leaks
        sharedPreferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static SettingsManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SettingsManager.class) {
                if (instance == null) {
                    instance = new SettingsManager(context);
                }
            }
        }
        return instance;
    }

    public void saveServerAddress(String address) {
        sharedPreferences.edit().putString(KEY_SERVER_ADDRESS, address).apply();
    }

    public String getServerAddress() {
        return sharedPreferences.getString(KEY_SERVER_ADDRESS, null); // Return null if not set
    }

    public void saveClientId(String clientId) {
        sharedPreferences.edit().putString(KEY_CLIENT_ID, clientId).apply();
    }

    public String getClientId() {
        return sharedPreferences.getString(KEY_CLIENT_ID, null); // Return null if not set
    }

    public void clearSettings() {
        sharedPreferences.edit()
                .remove(KEY_SERVER_ADDRESS)
                .remove(KEY_CLIENT_ID)
                .apply();
    }

    public boolean areSettingsAvailable() {
        return getServerAddress() != null && getClientId() != null;
    }
}