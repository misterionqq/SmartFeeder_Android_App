package com.example.smartfeederapp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages persistent storage of application settings (server address, client ID)
 * using SharedPreferences. Implemented as a Singleton.
 */
public class SettingsManager {

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_SERVER_ADDRESS = "server_address";
    private static final String KEY_CLIENT_ID = "client_id";

    private static volatile SettingsManager instance;
    private final SharedPreferences sharedPreferences;

    /**
     * Private constructor for Singleton pattern.
     * @param context Application context.
     */
    private SettingsManager(Context context) {
        sharedPreferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Gets the singleton instance of SettingsManager.
     * @param context Application context.
     * @return The singleton SettingsManager instance.
     */
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

    /**
     * Saves the server address to SharedPreferences.
     * @param address The server address string (e.g., "ip:port").
     */
    public void saveServerAddress(String address) {
        sharedPreferences.edit().putString(KEY_SERVER_ADDRESS, address).apply();
    }

    /**
     * Retrieves the saved server address from SharedPreferences.
     * @return The saved server address, or null if not set.
     */
    public String getServerAddress() {
        return sharedPreferences.getString(KEY_SERVER_ADDRESS, null);
    }

    /**
     * Saves the client ID to SharedPreferences.
     * @param clientId The client ID string, or null to clear it.
     */
    public void saveClientId(String clientId) {
        sharedPreferences.edit().putString(KEY_CLIENT_ID, clientId).apply();
    }

    /**
     * Retrieves the saved client ID from SharedPreferences.
     * @return The saved client ID, or null if not set.
     */
    public String getClientId() {
        return sharedPreferences.getString(KEY_CLIENT_ID, null);
    }

    /**
     * Clears both the server address and client ID from SharedPreferences.
     */
    public void clearSettings() {
        sharedPreferences.edit()
                .remove(KEY_SERVER_ADDRESS)
                .remove(KEY_CLIENT_ID)
                .apply();
    }

    /**
     * Checks if both server address and client ID are currently saved.
     * Useful for determining if auto-connect can be attempted.
     * @return true if both settings are available, false otherwise.
     */
    public boolean areSettingsAvailable() {
        return getServerAddress() != null && getClientId() != null;
    }
}