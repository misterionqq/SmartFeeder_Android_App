package com.example.smartfeederapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;

/**
 * Activity for managing connection settings (server address, client ID).
 * Allows users to input the server address, initiate the process to obtain
 * and save the client ID (without establishing a persistent connection),
 * disconnect any active persistent connection (managed by ConnectionManager),
 * and view the current connection status.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private static final String DEFAULT_SERVER_ADDRESS = "192.168.2.41:5000";

    private TextInputEditText etServerAddress;
    private TextInputEditText etClientId;
    private Button btnConnectAndSave;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private Button btnDisconnectSettings;
    private Button btnGoToMain;
    private SettingsManager settingsManager;
    private ConnectionManager connectionManager;

    /**
     * Called when the activity is first created. Initializes UI, managers,
     * loads settings, sets up listeners, and observes connection state.
     * @param savedInstanceState If the activity is being re-initialized.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_layout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        etServerAddress = findViewById(R.id.etServerAddressSettings);
        etClientId = findViewById(R.id.etClientIdSettings);
        btnConnectAndSave = findViewById(R.id.btnConnectAndSave);
        progressBar = findViewById(R.id.progressBarSettings);
        tvStatus = findViewById(R.id.tvStatusSettings);
        btnDisconnectSettings = findViewById(R.id.btnDisconnectSettings);
        btnGoToMain = findViewById(R.id.btnGoToMain);

        settingsManager = SettingsManager.getInstance(this);
        connectionManager = ConnectionManager.getInstance(getApplicationContext());

        loadSettings();
        updateStatus();

        btnConnectAndSave.setOnClickListener(v -> getIdAndSave());
        btnGoToMain.setOnClickListener(v -> finish());

        btnDisconnectSettings.setOnClickListener(v -> {
            Log.d(TAG, "Disconnect button clicked");
            connectionManager.disconnect();
            settingsManager.saveClientId(null);
            etClientId.setText("");
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
        });

        connectionManager.getConnectionState().observe(this, state -> updateStatus());

        connectionManager.getClientIdLiveData().observe(this, clientId -> {
            etClientId.setText(clientId != null ? clientId : "");
        });
    }

    /**
     * Loads the server address and client ID from SettingsManager and populates the input fields.
     * Uses default server address if none is saved.
     */
    private void loadSettings() {
        String savedAddress = settingsManager.getServerAddress();
        etServerAddress.setText(savedAddress != null ? savedAddress : DEFAULT_SERVER_ADDRESS);
        etClientId.setText(settingsManager.getClientId());
    }

    /**
     * Updates the status TextView and enables/disables buttons based on the current connection state
     * obtained from ConnectionManager.
     */
    private void updateStatus() {
        ConnectionManager.ConnectionState state = connectionManager.getConnectionState().getValue();
        String statusText = "Status: ";
        boolean inProgressGettingId = false;
        boolean isConnected = (state == ConnectionManager.ConnectionState.CONNECTED);

        if (state != null) {
            switch (state) {
                case CONNECTED:
                    statusText += "Connected";
                    break;
                case CONNECTING_FOR_ID:
                    statusText += "Getting ID...";
                    inProgressGettingId = true;
                    break;
                case CONNECTING_WITH_ID:
                    statusText += "Connecting...";
                    inProgressGettingId = true;
                    break;
                case DISCONNECTED:
                    statusText += "Disconnected";
                    break;
                case ERROR:
                    statusText += "Connection Error";
                    break;
                default:
                    statusText += "Unknown";
                    break;
            }
        } else {
            statusText += "Unknown";
        }
        tvStatus.setText(statusText);
        progressBar.setVisibility(inProgressGettingId ? View.VISIBLE : View.GONE);

        btnDisconnectSettings.setEnabled(isConnected);
        btnConnectAndSave.setEnabled(!isConnected && !inProgressGettingId);
    }

    /**
     * Initiates the process to obtain/update the client ID when the button is clicked.
     * Saves the entered server address first, then calls ConnectionManager to get the client ID.
     * This method does NOT establish a persistent connection.
     */
    private void getIdAndSave() {
        String serverAddress = etServerAddress.getText().toString().trim();

        if (TextUtils.isEmpty(serverAddress)) {
            Toast.makeText(this, "Please enter server address", Toast.LENGTH_SHORT).show();
            return;
        }

        settingsManager.saveServerAddress(serverAddress);
        Log.d(TAG, "Server address saved: " + serverAddress);

        connectionManager.getClientIdFromServer(serverAddress, new ConnectionManager.ConnectionCallback() {
            @Override
            public void onSuccess(String clientId) {
                runOnUiThread(() -> {
                    Log.d(TAG, "ID received/updated: " + clientId);
                    settingsManager.saveClientId(clientId);
                    Toast.makeText(SettingsActivity.this, "Client ID Saved!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onConnected() {
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Log.e(TAG, "Error getting/saving Client ID: " + message);
                    Toast.makeText(SettingsActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}