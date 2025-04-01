package com.example.smartfeederzatichkav20;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.lifecycle.Observer;
import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputEditText;

import android.widget.ArrayAdapter; // Для выпадающего списка
import android.widget.AutoCompleteTextView; // Для выпадающего списка
import com.google.android.material.textfield.TextInputLayout; // Для контейнера списка

import java.util.ArrayList; // Для хранения списка кормушек


import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends AppCompatActivity implements VideoAdapter.OnVideoActionListener {

    private static final String TAG = "MainActivity";
    private static final String DEFAULT_FEEDER_ID = "750cec99-5311-4055-8da0-2aad1e531d6c"; // Keep default feeder ID

    // --- Изменено UI для выбора кормушки ---
    // private TextInputEditText etFeederId; // Удалено
    private TextInputLayout tilFeederId;        // Добавлен контейнер
    private AutoCompleteTextView actvFeederId; // Добавлен AutoCompleteTextView
    private Button btnRefreshFeeders;         // Добавлена кнопка обновления
    private Button btnLoadVideos;
    private Button btnRequestStream;
    private Button btnStopStream;
    private Button btnSettings; // Added settings button
    private TextView tvStreamTitle;
    private TextView tvRecordedVideoTitle; // Added title for recorded video player
    private TextView tvConnectionStatusMain; // Added status text view
    private PlayerView streamPlayerView;
    private RecyclerView rvVideoList;
    private ProgressBar progressBar;
    private PlayerView playerView;

    private VideoAdapter videoAdapter;
    private ExoPlayer player;
    private ExoPlayer streamPlayer;
    private ApiService apiService;
    private String streamPath;
    private String currentStreamingFeederId = null;
    private VideoItem pendingDownloadItem = null;
    private ExoPlayer activePlayerForFullscreen = null;

    // Managers
    private SettingsManager settingsManager;
    private ConnectionManager connectionManager;

    // Данные для списка кормушек
    private List<String> availableFeederIds = new ArrayList<>();
    private ArrayAdapter<String> feederAdapter;


    // Activity Result Launcher for Fullscreen
    private final ActivityResultLauncher<Intent> fullscreenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleFullscreenResult
    );

    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Log.d(TAG, "Вернулись из Настроек, код результата: " + result.getResultCode());
                updateConnectionStatusDisplay();
                // Пересоздаем ApiService на случай, если адрес сервера изменился
                initializeApiService();
                // Пытаемся загрузить список кормушек с (возможно) новым адресом
                if (apiService != null && connectionManager.isConnected()) {
                    loadFeederList();
                }
            }
    );


    private void handleFullscreenResult(ActivityResult result) {
        // Existing fullscreen result handling logic...
        if (activePlayerForFullscreen == null) {
            Log.w(TAG, "handleFullscreenResult: No active player was tracked.");
            return;
        }
        PlayerView relevantPlayerView = (activePlayerForFullscreen == player) ? playerView : streamPlayerView;
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            long returnedPosition = result.getData().getLongExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, C.TIME_UNSET);
            boolean playWhenReady = result.getData().getBooleanExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, true);
            if (returnedPosition != C.TIME_UNSET) {
                Log.d(TAG, "Resuming playback at position: " + returnedPosition + " playWhenReady: " + playWhenReady);
                activePlayerForFullscreen.seekTo(returnedPosition);
                activePlayerForFullscreen.setPlayWhenReady(playWhenReady);
                if (playWhenReady) activePlayerForFullscreen.play();
            } else {
                Log.w(TAG, "Returned position is TIME_UNSET, resuming from previous state.");
                activePlayerForFullscreen.play();
            }
        } else {
            Log.d(TAG, "Fullscreen cancelled or failed, resuming playback.");
            activePlayerForFullscreen.play();
        }
        if (relevantPlayerView != null) {
            relevantPlayerView.setPlayer(null); // Detach
            relevantPlayerView.setPlayer(activePlayerForFullscreen); // Re-attach
        }
        activePlayerForFullscreen = null;
    }


    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new RequestPermission(), isGranted -> {
                if (isGranted) {
                    // Разрешение получено, начинаем скачивание, если есть ожидающий элемент
                    if (pendingDownloadItem != null) {
                        Log.d(TAG, "Разрешение POST_NOTIFICATIONS получено, начинаем скачивание для: " + pendingDownloadItem.getFilename());
                        startDownload(pendingDownloadItem);
                        pendingDownloadItem = null; // Сбрасываем ожидающий элемент
                    } else {
                        Log.d(TAG, "Разрешение POST_NOTIFICATIONS получено, но нет ожидающего скачивания.");
                    }
                } else {
                    if (pendingDownloadItem != null) {
                        Log.w(TAG, "Разрешение POST_NOTIFICATIONS не получено: " + pendingDownloadItem.getFilename());
                        startDownload(pendingDownloadItem);
                        pendingDownloadItem = null;
                    }
                }
            });

    private final BroadcastReceiver downloadCompleteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != -1) {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    String title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                    c.close();

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Toast.makeText(context, "Файл '" + title + "' успешно скачан.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Ошибка скачивания файла '" + title + "'.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Apply window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize Managers
        settingsManager = SettingsManager.getInstance(this);
        connectionManager = ConnectionManager.getInstance(getApplicationContext());

        // Initialize Views
        // --- Инициализация новых элементов ---
        tilFeederId = findViewById(R.id.tilFeederId);
        actvFeederId = findViewById(R.id.actvFeederId);
        btnRefreshFeeders = findViewById(R.id.btnRefreshFeeders);
        // --- Остальные как были ---
        btnLoadVideos = findViewById(R.id.btnLoadVideos);
        btnRequestStream = findViewById(R.id.btnRequestStream);
        btnStopStream = findViewById(R.id.btnStopStream);
        btnSettings = findViewById(R.id.btnSettings);
        tvStreamTitle = findViewById(R.id.tvStreamTitle);
        tvRecordedVideoTitle = findViewById(R.id.tvRecordedVideoTitle);
        tvConnectionStatusMain = findViewById(R.id.tvConnectionStatusMain);
        streamPlayerView = findViewById(R.id.streamPlayerView);
        rvVideoList = findViewById(R.id.rvVideoList);
        progressBar = findViewById(R.id.progressBar);
        playerView = findViewById(R.id.playerView);

        // Setup RecyclerView
        setupRecyclerView();

        // Setup ExoPlayers
        setupPlayers();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(downloadCompleteReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED); // Use RECEIVER_EXPORTED for API 34+
        } else {
            registerReceiver(downloadCompleteReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)); // Deprecated method for older APIs
        }

        // Setup Button Listeners
        setupButtonClickListeners();

        // --- Настройка адаптера для списка кормушек ---
        setupFeederDropdown();

        // Observe Connection State
        connectionManager.getConnectionState().observe(this, state -> {
            updateConnectionStatusDisplay();
            boolean connected = state == ConnectionManager.ConnectionState.CONNECTED;
            btnRequestStream.setEnabled(connected);
            btnRefreshFeeders.setEnabled(connected); // Можно обновлять список только при подключении
            btnLoadVideos.setEnabled(connected);     // Загрузка видео тоже требует подключения (валидного адреса сервера)

            // Если подключились, пытаемся загрузить список кормушек
            if(connected && availableFeederIds.isEmpty()) {
                initializeApiService(); // Убедимся, что ApiService готов
                if (apiService != null) {
                    loadFeederList();
                }
            }
        });


        // Observe Force Stream Stop
        connectionManager.getForceStoppedFeederId().observe(this, stoppedFeederId -> {
            if (stoppedFeederId != null) {
                Log.d(TAG, "Получено событие принудительной остановки для feederId: " + stoppedFeederId);
                // Проверяем, совпадает ли с текущим стримом
                if (stoppedFeederId.equals(currentStreamingFeederId)) {
                    Log.i(TAG, "Принудительно останавливаем текущий стрим по сигналу сервера.");
                    Toast.makeText(MainActivity.this, "Стрим остановлен сервером", Toast.LENGTH_SHORT).show();
                    stopStreamPlayback();
                    hideStreamUI(); // Скроет UI и сбросит currentStreamingFeederId
                    connectionManager.disconnect();
                } else {
                    Log.d(TAG, "Событие остановки для " + stoppedFeederId + " не совпадает с текущим стримом (" + currentStreamingFeederId + "), игнорируем.");
                }
                // Сбрасываем событие в менеджере, чтобы не реагировать повторно
                connectionManager.clearForceStopEvent();
            }
        });

        // Попытка авто-подключения и инициализация ApiService
        initializeApiService(); // Инициализируем сразу
        if (settingsManager.areSettingsAvailable() && !connectionManager.isConnected()) {
            attemptAutoConnect(); // Авто-подключение вызовет обновление статуса и загрузку списка
        } else {
            updateConnectionStatusDisplay(); // Обновить статус в любом случае
            // Если уже подключены (например, активность пересоздалась), но списка нет
            if (connectionManager.isConnected() && availableFeederIds.isEmpty() && apiService != null) {
                loadFeederList();
            }
        }
    }

    private void setupRecyclerView() {
        videoAdapter = new VideoAdapter();
        videoAdapter.setOnVideoActionListener(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setInitialPrefetchItemCount(4);
        rvVideoList.setLayoutManager(layoutManager);
        rvVideoList.setNestedScrollingEnabled(false);
        rvVideoList.setAdapter(videoAdapter);
    }

    private void initializeApiService() {
        String serverAddress = settingsManager.getServerAddress();
        if (!TextUtils.isEmpty(serverAddress)) {
            try {
                // Создаем или пересоздаем Retrofit клиент
                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl("http://" + serverAddress + "/") // Убедитесь, что адрес корректный
                        .addConverterFactory(GsonConverterFactory.create())
                        .build();
                apiService = retrofit.create(ApiService.class);
                Log.d(TAG, "ApiService инициализирован для адреса: " + serverAddress);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Неверный формат адреса сервера при инициализации ApiService: " + serverAddress, e);
                apiService = null; // Сбрасываем, если адрес некорректен
                Toast.makeText(this, "Неверный формат адреса сервера в настройках.", Toast.LENGTH_LONG).show();
            }
        } else {
            Log.w(TAG, "Адрес сервера не настроен, ApiService не инициализирован.");
            apiService = null;
        }
    }


    private void setupFeederDropdown() {
        // Инициализируем адаптер (сначала пустой или с сообщением)
        feederAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, availableFeederIds);
        actvFeederId.setAdapter(feederAdapter);

        // Опционально: Установить текст по умолчанию, если список пуст
        if (availableFeederIds.isEmpty()) {
            // actvFeederId.setText("Нажмите Обновить", false); // Можно так, но может мешать выбору
        }

        // Опционально: Загрузить и установить ранее выбранную кормушку
        // loadAndSetLastFeeder();
    }

    private void setupPlayers() {
        // Initialize ExoPlayer for recordings
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d(TAG, "Record Player state changed: " + playbackState);
                if(playbackState == Player.STATE_ENDED) {
                    playerView.setVisibility(View.GONE);
                    tvRecordedVideoTitle.setVisibility(View.GONE);
                }
            }
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Ошибка воспроизведения записи: " + error.getMessage());
                Toast.makeText(MainActivity.this, "Ошибка воспроизведения записи", Toast.LENGTH_SHORT).show();
                playerView.setVisibility(View.GONE);
                tvRecordedVideoTitle.setVisibility(View.GONE);
            }
        });

        // Initialize ExoPlayer for stream
        streamPlayer = new ExoPlayer.Builder(this).build();
        streamPlayerView.setPlayer(streamPlayer);
        streamPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Ошибка воспроизведения стрима: " + error.getMessage(), error);
                Toast.makeText(MainActivity.this, "Ошибка воспроизведения стрима", Toast.LENGTH_SHORT).show();
                hideStreamUI();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                Log.d(TAG,"Stream Player state changed: " + state);
                if (state == Player.STATE_BUFFERING) {
                    Log.d(TAG, "Буферизация стрима...");
                } else if (state == Player.STATE_ENDED) {
                    Log.d(TAG, "Воспроизведение стрима завершено (или поток прерван)");
                    Toast.makeText(MainActivity.this, "Стрим завершен", Toast.LENGTH_SHORT).show();
                    hideStreamUI();
                } else if (state == Player.STATE_READY) {
                    Log.d(TAG, "Стрим готов к воспроизведению.");
                }
            }
        });
    }


    private void setupButtonClickListeners() {
        btnLoadVideos.setOnClickListener(v -> loadVideos());
        btnRequestStream.setOnClickListener(v -> requestStream());
        btnStopStream.setOnClickListener(v -> stopStream());
        btnSettings.setOnClickListener(v -> openSettings());
        btnRefreshFeeders.setOnClickListener(v -> loadFeederList()); // Устанавливаем слушатель на новую кнопку

        // Fullscreen listeners (как были)
        playerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) openFullscreenActivity(player);
        });
        streamPlayerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) openFullscreenActivity(streamPlayer);
        });
    }

    // ... (updateConnectionStatusDisplay, attemptAutoConnect, openSettings, openFullscreenActivity как были) ...
    private void updateConnectionStatusDisplay() {
        ConnectionManager.ConnectionState state = connectionManager.getConnectionState().getValue();
        String statusText = "Статус: ";
        if (state != null) {
            switch (state) {
                case CONNECTED: statusText += "Подключено"; break;
                case CONNECTING_FOR_ID: statusText += "Получение ID..."; break;
                case CONNECTING_WITH_ID: statusText += "Подключение..."; break;
                case DISCONNECTED: statusText += "Отключено"; break;
                case ERROR: statusText += "Ошибка"; break;
                default: statusText += "Неизвестно"; break;
            }
        } else {
            statusText += "Неизвестно";
        }
        tvConnectionStatusMain.setText(statusText);
    }

    private void attemptAutoConnect() {
        String serverAddress = settingsManager.getServerAddress();
        String clientId = settingsManager.getClientId();

        if (serverAddress != null && clientId != null) {
            Log.d(TAG, "Попытка авто-подключения...");
            Toast.makeText(this, "Авто-подключение...", Toast.LENGTH_SHORT).show();
            connectionManager.connectWithId(serverAddress, clientId, new ConnectionManager.ConnectionCallback() {
                @Override
                public void onSuccess(String clientId) { /* Not used */ }

                @Override
                public void onConnected() {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Авто-подключение успешно", Toast.LENGTH_SHORT).show();
                        // После успешного подключения пытаемся загрузить кормушки
                        initializeApiService(); // На всякий случай переинициализируем
                        if (apiService != null) {
                            loadFeederList();
                        }
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ошибка авто-подключения: " + message, Toast.LENGTH_LONG).show());
                }
            });
        } else {
            Log.d(TAG, "Нет сохраненных настроек для авто-подключения.");
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        settingsLauncher.launch(intent);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void openFullscreenActivity(ExoPlayer currentPlayer) {
        // Existing fullscreen logic...
        if (currentPlayer == null || currentPlayer.getCurrentMediaItem() == null) {
            Toast.makeText(this, "Нет активного видео для полноэкранного режима", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri videoUri = currentPlayer.getCurrentMediaItem().localConfiguration != null
                ? currentPlayer.getCurrentMediaItem().localConfiguration.uri
                : null;
        if (videoUri == null) {
            Toast.makeText(this, "Не удалось получить URI видео", Toast.LENGTH_SHORT).show();
            return;
        }
        long currentPosition = currentPlayer.getCurrentPosition();
        boolean playWhenReady = currentPlayer.getPlayWhenReady();
        currentPlayer.pause();
        activePlayerForFullscreen = currentPlayer;
        Intent intent = new Intent(this, FullscreenVideoActivity.class);
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_URI, videoUri.toString());
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, currentPosition);
        intent.putExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, playWhenReady);
        fullscreenLauncher.launch(intent);
    }

    // --- НОВЫЙ МЕТОД: Загрузка списка кормушек ---
    private void loadFeederList() {
        if (apiService == null) {
            Log.w(TAG, "ApiService не инициализирован, не могу загрузить кормушки.");
            // Попробовать инициализировать снова?
            initializeApiService();
            if (apiService == null) {
                Toast.makeText(this, "Не удалось инициализировать API. Проверьте адрес сервера.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        progressBar.setVisibility(View.VISIBLE);
        Log.d(TAG, "Загрузка списка кормушек...");

        apiService.getFeeders().enqueue(new Callback<List<String>>() {
            @Override
            public void onResponse(@NonNull Call<List<String>> call, @NonNull Response<List<String>> response) {
                progressBar.setVisibility(View.GONE);
                if (response.isSuccessful() && response.body() != null) {
                    List<String> feeders = response.body();
                    availableFeederIds.clear();
                    if (feeders.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Активных кормушек не найдено", Toast.LENGTH_SHORT).show();
                        // Добавляем сообщение в адаптер
                        availableFeederIds.add("Нет активных кормушек");
                    } else {
                        availableFeederIds.addAll(feeders);
                        Toast.makeText(MainActivity.this, "Загружено " + feeders.size() + " кормушек", Toast.LENGTH_SHORT).show();
                    }
                    // Обновляем адаптер
                    feederAdapter.notifyDataSetChanged();

                    // Опционально: Попробовать выбрать ранее сохраненную кормушку
                    // String lastFeeder = settingsManager.getLastSelectedFeeder();
                    // if (lastFeeder != null && availableFeederIds.contains(lastFeeder)) {
                    //     actvFeederId.setText(lastFeeder, false);
                    // } else if (!availableFeederIds.isEmpty() && !availableFeederIds.get(0).equals("Нет активных кормушек")) {
                    // Выбрать первую доступную, если нет сохраненной
                    //     actvFeederId.setText(availableFeederIds.get(0), false);
                    //} else {
                    //      actvFeederId.setText("", false); // Очистить, если список пуст
                    //}

                } else {
                    try {
                        String errorBody = response.errorBody() != null ? response.errorBody().string() : "Нет тела ошибки";
                        Log.e(TAG, "Ошибка загрузки кормушек: " + response.code() + " - " + response.message() + " Body: " + errorBody);
                        Toast.makeText(MainActivity.this, "Ошибка загрузки кормушек: " + response.code(), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка чтения тела ошибки (кормушки)", e);
                        Toast.makeText(MainActivity.this, "Ошибка загрузки кормушек: " + response.code(), Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<List<String>> call, @NonNull Throwable t) {
                progressBar.setVisibility(View.GONE);
                Log.e(TAG, "Сетевая ошибка загрузки кормушек", t);
                Toast.makeText(MainActivity.this, "Ошибка сети при загрузке кормушек: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ... (loadVideos как был, но использует уже инициализированный apiService) ...
    private void loadVideos() {
        if (apiService == null) {
            Log.w(TAG, "ApiService не инициализирован, не могу загрузить видео.");
            Toast.makeText(this, "Не удалось подключиться к API. Проверьте адрес сервера.", Toast.LENGTH_SHORT).show();
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        apiService.getVideos().enqueue(new Callback<List<VideoItem>>() {
            @Override
            public void onResponse(@NonNull Call<List<VideoItem>> call, @NonNull Response<List<VideoItem>> response) {
                progressBar.setVisibility(View.GONE);
                if (response.isSuccessful() && response.body() != null) {
                    List<VideoItem> videos = response.body();
                    if (videos.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Список видео пуст", Toast.LENGTH_SHORT).show();
                    } else {
                        videoAdapter.setVideoList(videos);
                        Toast.makeText(MainActivity.this, "Загружено " + videos.size() + " видео", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    try {
                        String errorBody = response.errorBody() != null ? response.errorBody().string() : "Нет тела ошибки";
                        Log.e(TAG, "Ошибка загрузки видео: " + response.code() + " - " + response.message() + " Body: " + errorBody);
                        Toast.makeText(MainActivity.this, "Ошибка загрузки видео: " + response.code(), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка чтения тела ошибки", e);
                        Toast.makeText(MainActivity.this, "Ошибка загрузки видео: " + response.code(), Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<List<VideoItem>> call, @NonNull Throwable t) {
                progressBar.setVisibility(View.GONE);
                Log.e(TAG, "Сетевая ошибка загрузки видео", t);
                Toast.makeText(MainActivity.this, "Ошибка сети: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }


    // --- ИЗМЕНЕНО: requestStream ---
    private void requestStream() {
        // Получаем выбранное значение из AutoCompleteTextView
        String selectedFeederId = actvFeederId.getText().toString().trim();
        String clientId = settingsManager.getClientId(); // ID клиента все еще нужен

        if (!connectionManager.isConnected() || clientId == null) {
            Toast.makeText(this, "Нет подключения к серверу или отсутствует ID клиента. Проверьте настройки.", Toast.LENGTH_LONG).show();
            return;
        }

        // Проверяем, выбрана ли кормушка
        if (TextUtils.isEmpty(selectedFeederId) || !availableFeederIds.contains(selectedFeederId)) {
            // Устанавливаем ошибку для TextInputLayout, а не для AutoCompleteTextView
            tilFeederId.setError("Пожалуйста, выберите кормушку из списка");
            actvFeederId.requestFocus();
            // Toast.makeText(this, "Выберите кормушку из списка", Toast.LENGTH_SHORT).show();
            return;
        } else {
            tilFeederId.setError(null); // Убираем ошибку, если она была
        }

        // Если уже идет стрим с другой кормушки, останавливаем его
        if (currentStreamingFeederId != null && !currentStreamingFeederId.equals(selectedFeederId)) {
            Log.d(TAG, "Запрошен стрим с другой кормушки, останавливаем предыдущий (" + currentStreamingFeederId + ")");
            stopStreamPlayback(); // Останавливаем локальное воспроизведение
            hideStreamUI();
            // Не отправляем stopStream на сервер, так как сервер сам остановит, когда последний зритель уйдет
        }

        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Запрос трансляции для " + selectedFeederId, Toast.LENGTH_SHORT).show();

        connectionManager.requestStream(selectedFeederId, new ConnectionManager.StreamCallback() {
            @Override
            public void onSuccess(String receivedStreamPath) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    streamPath = receivedStreamPath;
                    currentStreamingFeederId = selectedFeederId; // Сохраняем ID текущей стримящей кормушки
                    startStreamPlayback(streamPath);
                    // Опционально: Сохранить выбранную кормушку
                    // settingsManager.saveLastSelectedFeeder(selectedFeederId);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Log.e(TAG, "Ошибка запроса стрима: " + message);
                    Toast.makeText(MainActivity.this, "Ошибка запроса стрима: " + message, Toast.LENGTH_LONG).show();
                    currentStreamingFeederId = null; // Сбрасываем, если ошибка
                });
            }
        });
    }

    // ... (startStreamPlayback как был) ...
    private void startStreamPlayback(String streamPath) {
        String serverAddress = settingsManager.getServerAddress();
        if (serverAddress == null) {
            Toast.makeText(this, "Адрес сервера не найден", Toast.LENGTH_SHORT).show();
            hideStreamUI(); // Скрываем UI если не можем начать
            return;
        }
        try {
            String serverIp = serverAddress;
            if (serverAddress.contains(":")) {
                serverIp = serverAddress.substring(0, serverAddress.indexOf(":"));
            }
            String path = streamPath;
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            String rtmpUrl = "rtmp://" + serverIp + ":1935/" + path;
            Log.d(TAG, "Подключение к RTMP стриму: " + rtmpUrl);

            if (streamPlayer != null) {
                streamPlayer.stop();
                streamPlayer.clearMediaItems();
            } else {
                streamPlayer = new ExoPlayer.Builder(this).build();
                streamPlayerView.setPlayer(streamPlayer);
                // re-add listener
                streamPlayer.addListener(new Player.Listener() {
                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        Log.e(TAG, "Ошибка воспроизведения стрима: " + error.getMessage(), error);
                        Toast.makeText(MainActivity.this, "Ошибка воспроизведения стрима", Toast.LENGTH_SHORT).show();
                        hideStreamUI();
                    }
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        Log.d(TAG,"Stream Player state changed: " + state);
                        if (state == Player.STATE_BUFFERING) {
                            Log.d(TAG, "Буферизация стрима...");
                        } else if (state == Player.STATE_ENDED) {
                            Log.d(TAG, "Воспроизведение стрима завершено (или поток прерван)");
                            Toast.makeText(MainActivity.this, "Стрим завершен", Toast.LENGTH_SHORT).show();
                            hideStreamUI();
                        } else if (state == Player.STATE_READY) {
                            Log.d(TAG, "Стрим готов к воспроизведению.");
                        }
                    }
                });
            }

            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(rtmpUrl));
            streamPlayer.setMediaItem(mediaItem);
            streamPlayer.prepare();
            streamPlayer.play();

            showStreamUI();
            Toast.makeText(this, "Трансляция запущена", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Ошибка запуска воспроизведения стрима: " + e.getMessage(), e);
            Toast.makeText(MainActivity.this, "Ошибка запуска стрима: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            hideStreamUI();
        }
    }


    private void stopStream() {
        String feederIdToStop = currentStreamingFeederId;

        if (feederIdToStop == null) {
            Log.w(TAG, "Попытка остановить стрим, но ID текущей кормушки не известен.");
            stopStreamPlayback();
            hideStreamUI();
            if (connectionManager.isConnected()) {
                Log.d(TAG,"Отключаемся от сервера после нажатия Стоп (ID стрима неизвестен)");
                connectionManager.disconnect();
            }
            return;
        }

        // Проверяем подключение ДО отправки команды серверу
        if (!connectionManager.isConnected()) {
            Toast.makeText(this, "Нет подключения для остановки стрима", Toast.LENGTH_SHORT).show();
            stopStreamPlayback();
            hideStreamUI();
            // Не можем отправить команду, но раз пользователь нажал стоп, видимо, и не надо
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        Log.d(TAG, "Запрос на остановку стрима для: " + feederIdToStop);

        connectionManager.stopStream(feederIdToStop, new ConnectionManager.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    stopStreamPlayback();
                    hideStreamUI();
                    Toast.makeText(MainActivity.this, "Трансляция остановлена", Toast.LENGTH_SHORT).show(); // Убрали "сервером"
                    // currentStreamingFeederId сбросится в hideStreamUI()

                    // --- ДОБАВЛЕНО: Отключение от сокета после успешной остановки ---
                    Log.d(TAG,"Отключаемся от сервера после успешной остановки стрима");
                    connectionManager.disconnect();
                    // -------------------------------------------------------------
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Log.e(TAG, "Ошибка остановки стрима сервером: " + message);
                    Toast.makeText(MainActivity.this, "Ошибка остановки стрима: " + message, Toast.LENGTH_LONG).show();
                    stopStreamPlayback();
                    hideStreamUI();
                    // currentStreamingFeederId сбросится в hideStreamUI()

                    // --- ДОБАВЛЕНО: Отключение от сокета даже при ошибке остановки ---
                    Log.d(TAG,"Отключаемся от сервера после ошибки остановки стрима (на всякий случай)");
                    connectionManager.disconnect();
                    // -------------------------------------------------------------
                });
            }
        });
    }

    // ... (stopStreamPlayback, showStreamUI, hideStreamUI как были) ...
    private void stopStreamPlayback() {
        if (streamPlayer != null) {
            streamPlayer.stop();
            streamPlayer.clearMediaItems();
        }
    }
    private void showStreamUI() {
        tvStreamTitle.setVisibility(View.VISIBLE);
        streamPlayerView.setVisibility(View.VISIBLE);
        btnStopStream.setVisibility(View.VISIBLE);
    }
    private void hideStreamUI() {
        tvStreamTitle.setVisibility(View.GONE);
        streamPlayerView.setVisibility(View.GONE);
        btnStopStream.setVisibility(View.GONE);
        currentStreamingFeederId = null; // Сбрасываем ID при скрытии UI
    }

    /*
    @Override
    public void onVideoClick(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null) {
            Log.e(TAG, "Некорректный VideoItem в onVideoClick");
            return;
        }

        CharSequence[] items = {"Воспроизвести", "Скачать"};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Выберите действие для\n'" + videoItem.getFilename() + "'");
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) { // Воспроизвести
                startVideoPlayback(videoItem);
            } else if (which == 1) { // Скачать
                checkPermissionsAndDownload(videoItem);
            }
        });
        builder.setNegativeButton("Отмена", null); // Кнопка отмены
        builder.show();
    }

     */

    @Override
    public void onVideoPlayClick(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null) {
            Log.e(TAG, "Некорректный VideoItem в onVideoPlayClick");
            return;
        }
        Log.d(TAG, "Запрос воспроизведения для: " + videoItem.getFilename());
        startVideoPlayback(videoItem); // Вызываем метод воспроизведения
    }

    @Override
    public void onVideoDownloadClick(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null) {
            Log.e(TAG, "Некорректный VideoItem в onVideoDownloadClick");
            return;
        }
        Log.d(TAG, "Запрос скачивания для: " + videoItem.getFilename());
        checkPermissionsAndDownload(videoItem); // Вызываем метод скачивания
    }

    private void startVideoPlayback(VideoItem videoItem) {
        playerView.setVisibility(View.VISIBLE);
        tvRecordedVideoTitle.setVisibility(View.VISIBLE);

        try {
            Uri videoUri = Uri.parse(videoItem.getUrl());
            MediaItem mediaItem = MediaItem.fromUri(videoUri);

            if (player != null) {
                player.stop();
                player.clearMediaItems();
            } else {
                player = new ExoPlayer.Builder(this).build();
                playerView.setPlayer(player);
                player.addListener(createPlayerListener()); // Используем хелпер для слушателя
            }

            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();
        } catch (Exception e) {
            Log.e(TAG, "Ошибка подготовки видеозаписи: " + videoItem.getUrl(), e);
            Toast.makeText(this, "Не удалось воспроизвести видео", Toast.LENGTH_SHORT).show();
            playerView.setVisibility(View.GONE);
            tvRecordedVideoTitle.setVisibility(View.GONE);
        }
    }

    private Player.Listener createPlayerListener() {
        return new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d(TAG, "Record Player state changed: " + playbackState);
                if(playbackState == Player.STATE_ENDED) {
                    playerView.setVisibility(View.GONE);
                    tvRecordedVideoTitle.setVisibility(View.GONE);
                }
            }
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Ошибка воспроизведения записи: " + error.getMessage());
                Toast.makeText(MainActivity.this, "Ошибка воспроизведения записи", Toast.LENGTH_SHORT).show();
                playerView.setVisibility(View.GONE);
                tvRecordedVideoTitle.setVisibility(View.GONE);
            }
        };
    }

    private void checkPermissionsAndDownload(VideoItem videoItem) {
        pendingDownloadItem = videoItem; // Сохраняем на случай запроса разрешения

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                // Разрешение уже есть
                Log.d(TAG, "Разрешение POST_NOTIFICATIONS уже есть.");
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null; // Сбрасываем
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Показать объяснение, зачем нужно разрешение (если пользователь уже отказал)
                new AlertDialog.Builder(this)
                        .setTitle("Нужно разрешение")
                        .setMessage("Для отображения статуса скачивания в уведомлениях требуется разрешение.")
                        .setPositiveButton("OK", (dialog, which) -> requestNotificationPermission())
                        .setNegativeButton("Отмена", (dialog, which) -> {
                            Toast.makeText(this, "Скачивание будет выполнено без уведомлений.", Toast.LENGTH_SHORT).show();
                            startDownload(pendingDownloadItem); // Все равно качаем
                            pendingDownloadItem = null;
                        })
                        .show();
            } else {
                // Запрашиваем разрешение
                requestNotificationPermission();
            }
        } else {
            // Для версий ниже Android 13 разрешение не требуется для DownloadManager
            Log.d(TAG, "Версия Android ниже 13, разрешение POST_NOTIFICATIONS не требуется.");
            startDownload(pendingDownloadItem);
            pendingDownloadItem = null;
        }
    }
    // ----------------------------------------------------------

    // --- ДОБАВЛЕНО: Запрос разрешения на уведомления ---
    private void requestNotificationPermission() {
        Log.d(TAG, "Запрос разрешения POST_NOTIFICATIONS...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }
    // ---------------------------------------------------

    // --- ДОБАВЛЕНО: Метод для старта скачивания ---
    private void startDownload(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null || videoItem.getFilename() == null) {
            Log.e(TAG, "Невозможно начать скачивание: неверные данные VideoItem.");
            Toast.makeText(this, "Ошибка данных видео для скачивания", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = videoItem.getUrl();
        String filename = videoItem.getFilename();
        // Простая очистка имени файла (можно улучшить)
        filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        try {
            DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Uri downloadUri = Uri.parse(url);

            DownloadManager.Request request = new DownloadManager.Request(downloadUri);

            // Определение MIME типа
            String mimeType = getMimeType(url);
            if (mimeType != null) {
                request.setMimeType(mimeType);
            } else {
                request.setMimeType("video/*"); // Общий тип, если не удалось определить
            }


            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
                    .setTitle(filename) // Имя файла в уведомлении
                    .setDescription("Скачивание видео...") // Описание в уведомлении
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED) // Показывать уведомление
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename); // Сохранить в папку Downloads

            long downloadId = downloadManager.enqueue(request); // Запускаем скачивание

            Log.i(TAG, "Начало скачивания файла: " + filename + " (ID: " + downloadId + ") с URL: " + url);
            Toast.makeText(this, "Начало скачивания: " + filename, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Ошибка при старте скачивания файла " + filename, e);
            Toast.makeText(this, "Не удалось начать скачивание", Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
        if (streamPlayer != null) {
            streamPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadCompleteReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG,"Приемник downloadCompleteReceiver не был зарегистрирован или уже отменен.");
        }

        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        if (streamPlayer != null) {
            streamPlayer.release();
            streamPlayer = null;
        }
        Log.d(TAG,"onDestroy MainActivity");
    }
}