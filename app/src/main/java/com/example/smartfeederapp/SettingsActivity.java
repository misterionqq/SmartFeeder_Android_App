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
 * Allows users to input the server address, initiate the connection process
 * (which obtains and saves the client ID), disconnect, and view the current status.
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
        etServerAddress.setText(DEFAULT_SERVER_ADDRESS);
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

        btnConnectAndSave.setOnClickListener(v -> connectAndSave());
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
            if (clientId != null) {
                etClientId.setText(clientId);
                settingsManager.saveClientId(clientId);
            } else {
                etClientId.setText("");
            }
        });
    }

    /**
     * Loads the server address and client ID from SettingsManager and populates the input fields.
     */
    private void loadSettings() {
        String savedAddress = settingsManager.getServerAddress();
        etServerAddress.setText(savedAddress != null ? savedAddress : DEFAULT_SERVER_ADDRESS);
        etClientId.setText(settingsManager.getClientId());
    }

    /**
     * Updates the status TextView and enables/disables buttons based on the current connection state.
     */
    private void updateStatus() {
        ConnectionManager.ConnectionState state = connectionManager.getConnectionState().getValue();
        String statusText = "Status: ";
        boolean inProgress = false;
        boolean isConnected = false;

        if (state != null) {
            switch (state) {
                case CONNECTED:
                    statusText += "Connected";
                    isConnected = true;
                    break;
                case CONNECTING_FOR_ID:
                    statusText += "Getting ID...";
                    inProgress = true;
                    break;
                case CONNECTING_WITH_ID:
                    statusText += "Connecting with ID...";
                    inProgress = true;
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
        progressBar.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        btnDisconnectSettings.setEnabled(isConnected);
        btnConnectAndSave.setEnabled(!isConnected && !inProgress);
    }

    /**
     * Initiates the connection process when the "Connect and Save" button is clicked.
     * Saves the entered server address and calls ConnectionManager to get the client ID.
     */
    private void connectAndSave() {
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
                    Log.d(TAG, "ID received: " + clientId);
                    settingsManager.saveClientId(clientId);
                });
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    Log.d(TAG, "Final connection successful.");
                    Toast.makeText(SettingsActivity.this, "Connected!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Log.e(TAG, "Error during connection process: " + message);
                    Toast.makeText(SettingsActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}