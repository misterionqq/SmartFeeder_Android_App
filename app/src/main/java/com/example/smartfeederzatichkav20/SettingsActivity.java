package com.example.smartfeederzatichkav20;

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

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String DEFAULT_SERVER_ADDRESS = "192.168.2.41:5000";
    private static final String DEFAULT_FEEDER_ID = "750cec99-5311-4055-8da0-2aad1e531d6c";

    private TextInputEditText etServerAddress;
    private TextInputEditText etClientId;
    private Button btnConnectAndSave;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private Button btnDisconnectSettings;
    private Button btnGoToMain;
    private SettingsManager settingsManager;
    private ConnectionManager connectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Apply window insets like in MainActivity
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_layout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        etServerAddress = findViewById(R.id.etServerAddressSettings);
        etServerAddress.setText(DEFAULT_SERVER_ADDRESS); // Устанавливаем значение по умолчанию
        etClientId = findViewById(R.id.etClientIdSettings);
        btnConnectAndSave = findViewById(R.id.btnConnectAndSave);
        progressBar = findViewById(R.id.progressBarSettings);
        tvStatus = findViewById(R.id.tvStatusSettings);
        btnDisconnectSettings = findViewById(R.id.btnDisconnectSettings);
        btnGoToMain = findViewById(R.id.btnGoToMain);

        settingsManager = SettingsManager.getInstance(this);
        // Pass application context to avoid leaks
        connectionManager = ConnectionManager.getInstance(getApplicationContext());

        loadSettings();
        updateStatus(); // Initial status update

        btnConnectAndSave.setOnClickListener(v -> connectAndSave());
        btnGoToMain.setOnClickListener(v -> finish());

        btnDisconnectSettings.setOnClickListener(v -> {
            Log.d("SettingsActivity", "Нажата кнопка Отключиться");
            connectionManager.disconnect();
            settingsManager.saveClientId(null); // Очищаем сохраненный ID
            etClientId.setText(""); // Очищаем поле
            Toast.makeText(this, "Отключено", Toast.LENGTH_SHORT).show();
        });
        // Observe connection state changes from the ConnectionManager
        connectionManager.getConnectionState().observe(this, state -> updateStatus());
        connectionManager.getClientIdLiveData().observe(this, clientId -> {
            if (clientId != null) {
                etClientId.setText(clientId);
                // Save automatically when ID is assigned during the connection process
                settingsManager.saveClientId(clientId);
            } else {
                etClientId.setText("");
            }
        });
    }

    private void loadSettings() {
        etServerAddress.setText(settingsManager.getServerAddress());
        etClientId.setText(settingsManager.getClientId());
    }

    private void updateStatus() {
        ConnectionManager.ConnectionState state = connectionManager.getConnectionState().getValue();
        String statusText = "Статус: ";
        boolean inProgress = false;
        boolean isConnected = false;

        if (state != null) {
            switch (state) {
                case CONNECTED:
                    statusText += "Подключено";
                    isConnected = true;
                    break;
                case CONNECTING_FOR_ID:
                    statusText += "Получение ID...";
                    inProgress = true;
                    break;
                case CONNECTING_WITH_ID:
                    statusText += "Подключение с ID...";
                    inProgress = true;
                    break;
                case DISCONNECTED:
                    statusText += "Отключено";
                    break;
                case ERROR:
                    statusText += "Ошибка подключения";
                    break;
                default:
                    statusText += "Неизвестно";
                    break;
            }
        } else {
            statusText += "Неизвестно";
        }
        tvStatus.setText(statusText);
        progressBar.setVisibility(inProgress ? View.VISIBLE : View.GONE);


        btnDisconnectSettings.setEnabled(isConnected);

        btnConnectAndSave.setEnabled(!isConnected && !inProgress);
    }


    private void connectAndSave() {
        String serverAddress = etServerAddress.getText().toString().trim();

        if (TextUtils.isEmpty(serverAddress)) {
            Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show();
            return;
        }

        settingsManager.saveServerAddress(serverAddress);
        Log.d("SettingsActivity", "Адрес сервера сохранен: " + serverAddress);

        connectionManager.getClientIdFromServer(serverAddress, new ConnectionManager.ConnectionCallback() {
            @Override
            public void onSuccess(String clientId) {
                runOnUiThread(() -> {
                    Log.d("SettingsActivity", "ID получен: " + clientId);
                    settingsManager.saveClientId(clientId);
                });
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    Log.d("SettingsActivity", "Финальное подключение успешно.");
                    Toast.makeText(SettingsActivity.this, "Подключено!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Log.e("SettingsActivity", "Ошибка в процессе подключения: " + message);
                    Toast.makeText(SettingsActivity.this, "Ошибка: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}