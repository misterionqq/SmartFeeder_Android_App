// Файл: SettingsActivity.java
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

        settingsManager = SettingsManager.getInstance(this);
        // Pass application context to avoid leaks
        connectionManager = ConnectionManager.getInstance(getApplicationContext());

        loadSettings();
        updateStatus(); // Initial status update

        btnConnectAndSave.setOnClickListener(v -> connectAndSave());

        // Observe connection state changes from the ConnectionManager
        connectionManager.getConnectionState().observe(this, state -> updateStatus());
        connectionManager.getClientIdLiveData().observe(this, clientId -> {
            if (clientId != null) {
                etClientId.setText(clientId);
                // Save automatically when ID is assigned during the connection process
                settingsManager.saveClientId(clientId);
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
        boolean inProgress = false; // Флаг для ProgressBar

        if (state != null) {
            switch (state) {
                case CONNECTED:
                    statusText += "Подключено";
                    break;
                case CONNECTING_FOR_ID: // Новое состояние
                    statusText += "Получение ID...";
                    inProgress = true;
                    break;
                case CONNECTING_WITH_ID: // Новое состояние
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
    }


    private void connectAndSave() {
        String serverAddress = etServerAddress.getText().toString().trim();

        if (TextUtils.isEmpty(serverAddress)) {
            Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show();
            return;
        }

        // Менеджер сам обработает отключение предыдущего сокета, если нужно
        // connectionManager.disconnect(); // Убрали явный disconnect отсюда

        // progressBar и tvStatus обновятся через LiveData при смене состояния в ConnectionManager

        connectionManager.getClientIdFromServer(serverAddress, new ConnectionManager.ConnectionCallback() {
            @Override
            public void onSuccess(String clientId) {
                // Этот метод вызывается СРАЗУ после получения ID, ДО финального подключения
                runOnUiThread(() -> {
                    Log.d(TAG, "ID получен: " + clientId + ", сохраняем адрес сервера.");
                    settingsManager.saveServerAddress(serverAddress); // Сохраняем адрес
                    settingsManager.saveClientId(clientId);      // Сохраняем ID
                    etClientId.setText(clientId);                // Обновляем поле
                    // Статус обновится на CONNECTING_WITH_ID автоматически
                });
            }

            @Override
            public void onConnected() {
                // Этот метод вызывается ПОСЛЕ успешного подключения С ID
                runOnUiThread(() -> {
                    Log.d(TAG, "Финальное подключение успешно.");
                    Toast.makeText(SettingsActivity.this, "Подключено и сохранено!", Toast.LENGTH_SHORT).show();
                    // Статус обновится на CONNECTED автоматически
                    // finish(); // Можно закрыть активность при успехе
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Log.e(TAG, "Ошибка в процессе подключения: " + message);
                    // Статус обновится на ERROR автоматически
                    Toast.makeText(SettingsActivity.this, "Ошибка: " + message, Toast.LENGTH_LONG).show();
                    // Очищать ли здесь сохраненные настройки? Возможно, нет.
                    // settingsManager.clearSettings();
                    // etClientId.setText("");
                });
            }
        });
    }
}