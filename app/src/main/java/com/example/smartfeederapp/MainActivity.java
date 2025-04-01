package com.example.smartfeederapp;

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

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.webkit.MimeTypeMap;

import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;


import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends AppCompatActivity implements VideoAdapter.OnVideoActionListener {

    private static final String TAG = "MainActivity";
    private static final String DEFAULT_FEEDER_ID = "750cec99-5311-4055-8da0-2aad1e531d6c";


    private TextInputLayout tilFeederId;
    private AutoCompleteTextView actvFeederId;
    private Button btnRefreshFeeders;
    private Button btnLoadVideos;
    private Button btnRequestStream;
    private Button btnStopStream;
    private Button btnSettings;
    private TextView tvStreamTitle;
    private TextView tvRecordedVideoTitle;
    private TextView tvConnectionStatusMain;
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


    private SettingsManager settingsManager;
    private ConnectionManager connectionManager;


    private List<String> availableFeederIds = new ArrayList<>();
    private ArrayAdapter<String> feederAdapter;


    private final ActivityResultLauncher<Intent> fullscreenLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleFullscreenResult);

    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        Log.d(TAG, "Вернулись из Настроек, код результата: " + result.getResultCode());
        updateConnectionStatusDisplay();

        initializeApiService();

        if (apiService != null && connectionManager.isConnected()) {
            loadFeederList();
        }
    });


    private void handleFullscreenResult(ActivityResult result) {

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
            relevantPlayerView.setPlayer(null);
            relevantPlayerView.setPlayer(activePlayerForFullscreen);
        }
        activePlayerForFullscreen = null;
    }


    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(new RequestPermission(), isGranted -> {
        if (isGranted) {

            if (pendingDownloadItem != null) {
                Log.d(TAG, "Разрешение POST_NOTIFICATIONS получено, начинаем скачивание для: " + pendingDownloadItem.getFilename());
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null;
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


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });


        settingsManager = SettingsManager.getInstance(this);
        connectionManager = ConnectionManager.getInstance(getApplicationContext());


        tilFeederId = findViewById(R.id.tilFeederId);
        actvFeederId = findViewById(R.id.actvFeederId);
        btnRefreshFeeders = findViewById(R.id.btnRefreshFeeders);

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


        setupRecyclerView();


        setupPlayers();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(downloadCompleteReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadCompleteReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }


        setupButtonClickListeners();


        setupFeederDropdown();


        connectionManager.getConnectionState().observe(this, state -> {
            updateConnectionStatusDisplay();
            boolean connected = state == ConnectionManager.ConnectionState.CONNECTED;
            btnRequestStream.setEnabled(connected);
            btnRefreshFeeders.setEnabled(connected);
            btnLoadVideos.setEnabled(connected);


            if (connected && availableFeederIds.isEmpty()) {
                initializeApiService();
                if (apiService != null) {
                    loadFeederList();
                }
            }
        });


        connectionManager.getForceStoppedFeederId().observe(this, stoppedFeederId -> {
            if (stoppedFeederId != null) {
                Log.d(TAG, "Получено событие принудительной остановки для feederId: " + stoppedFeederId);

                if (stoppedFeederId.equals(currentStreamingFeederId)) {
                    Log.i(TAG, "Принудительно останавливаем текущий стрим по сигналу сервера.");
                    Toast.makeText(MainActivity.this, "Стрим остановлен сервером", Toast.LENGTH_SHORT).show();
                    stopStreamPlayback();
                    hideStreamUI();
                    connectionManager.disconnect();
                } else {
                    Log.d(TAG, "Событие остановки для " + stoppedFeederId + " не совпадает с текущим стримом (" + currentStreamingFeederId + "), игнорируем.");
                }

                connectionManager.clearForceStopEvent();
            }
        });


        initializeApiService();
        if (settingsManager.areSettingsAvailable() && !connectionManager.isConnected()) {
            attemptAutoConnect();
        } else {
            updateConnectionStatusDisplay();

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

                Retrofit retrofit = new Retrofit.Builder().baseUrl("http://" + serverAddress + "/") // Убедитесь, что адрес корректный
                        .addConverterFactory(GsonConverterFactory.create()).build();
                apiService = retrofit.create(ApiService.class);
                Log.d(TAG, "ApiService инициализирован для адреса: " + serverAddress);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Неверный формат адреса сервера при инициализации ApiService: " + serverAddress, e);
                apiService = null;
                Toast.makeText(this, "Неверный формат адреса сервера в настройках.", Toast.LENGTH_LONG).show();
            }
        } else {
            Log.w(TAG, "Адрес сервера не настроен, ApiService не инициализирован.");
            apiService = null;
        }
    }


    private void setupFeederDropdown() {

        feederAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, availableFeederIds);
        actvFeederId.setAdapter(feederAdapter);
    }

    private void setupPlayers() {

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d(TAG, "Record Player state changed: " + playbackState);
                if (playbackState == Player.STATE_ENDED) {
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
                Log.d(TAG, "Stream Player state changed: " + state);
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
        btnRefreshFeeders.setOnClickListener(v -> loadFeederList());


        playerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) openFullscreenActivity(player);
        });
        streamPlayerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) openFullscreenActivity(streamPlayer);
        });
    }


    private void updateConnectionStatusDisplay() {
        ConnectionManager.ConnectionState state = connectionManager.getConnectionState().getValue();
        String statusText = "Статус: ";
        if (state != null) {
            switch (state) {
                case CONNECTED:
                    statusText += "Подключено";
                    break;
                case CONNECTING_FOR_ID:
                    statusText += "Получение ID...";
                    break;
                case CONNECTING_WITH_ID:
                    statusText += "Подключение...";
                    break;
                case DISCONNECTED:
                    statusText += "Отключено";
                    break;
                case ERROR:
                    statusText += "Ошибка";
                    break;
                default:
                    statusText += "Неизвестно";
                    break;
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

                        initializeApiService();
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

        if (currentPlayer == null || currentPlayer.getCurrentMediaItem() == null) {
            Toast.makeText(this, "Нет активного видео для полноэкранного режима", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri videoUri = currentPlayer.getCurrentMediaItem().localConfiguration != null ? currentPlayer.getCurrentMediaItem().localConfiguration.uri : null;
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


    private void loadFeederList() {
        if (apiService == null) {
            Log.w(TAG, "ApiService не инициализирован, не могу загрузить кормушки.");

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

                        availableFeederIds.add("Нет активных кормушек");
                    } else {
                        availableFeederIds.addAll(feeders);
                        Toast.makeText(MainActivity.this, "Загружено " + feeders.size() + " кормушек", Toast.LENGTH_SHORT).show();
                    }

                    feederAdapter.notifyDataSetChanged();


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


    private void requestStream() {

        String selectedFeederId = actvFeederId.getText().toString().trim();
        String clientId = settingsManager.getClientId();

        if (!connectionManager.isConnected() || clientId == null) {
            Toast.makeText(this, "Нет подключения к серверу или отсутствует ID клиента. Проверьте настройки.", Toast.LENGTH_LONG).show();
            return;
        }


        if (TextUtils.isEmpty(selectedFeederId) || !availableFeederIds.contains(selectedFeederId)) {

            tilFeederId.setError("Пожалуйста, выберите кормушку из списка");
            actvFeederId.requestFocus();

            return;
        } else {
            tilFeederId.setError(null);
        }


        if (currentStreamingFeederId != null && !currentStreamingFeederId.equals(selectedFeederId)) {
            Log.d(TAG, "Запрошен стрим с другой кормушки, останавливаем предыдущий (" + currentStreamingFeederId + ")");
            stopStreamPlayback();
            hideStreamUI();

        }

        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Запрос трансляции для " + selectedFeederId, Toast.LENGTH_SHORT).show();

        connectionManager.requestStream(selectedFeederId, new ConnectionManager.StreamCallback() {
            @Override
            public void onSuccess(String receivedStreamPath) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    streamPath = receivedStreamPath;
                    currentStreamingFeederId = selectedFeederId;
                    startStreamPlayback(streamPath);


                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Log.e(TAG, "Ошибка запроса стрима: " + message);
                    Toast.makeText(MainActivity.this, "Ошибка запроса стрима: " + message, Toast.LENGTH_LONG).show();
                    currentStreamingFeederId = null;
                });
            }
        });
    }


    private void startStreamPlayback(String streamPath) {
        String serverAddress = settingsManager.getServerAddress();
        if (serverAddress == null) {
            Toast.makeText(this, "Адрес сервера не найден", Toast.LENGTH_SHORT).show();
            hideStreamUI();
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

                streamPlayer.addListener(new Player.Listener() {
                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        Log.e(TAG, "Ошибка воспроизведения стрима: " + error.getMessage(), error);
                        Toast.makeText(MainActivity.this, "Ошибка воспроизведения стрима", Toast.LENGTH_SHORT).show();
                        hideStreamUI();
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        Log.d(TAG, "Stream Player state changed: " + state);
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
                Log.d(TAG, "Отключаемся от сервера после нажатия Стоп (ID стрима неизвестен)");
                connectionManager.disconnect();
            }
            return;
        }


        if (!connectionManager.isConnected()) {
            Toast.makeText(this, "Нет подключения для остановки стрима", Toast.LENGTH_SHORT).show();
            stopStreamPlayback();
            hideStreamUI();

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
                    Toast.makeText(MainActivity.this, "Трансляция остановлена", Toast.LENGTH_SHORT).show();


                    Log.d(TAG, "Отключаемся от сервера после успешной остановки стрима");
                    connectionManager.disconnect();

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


                    Log.d(TAG, "Отключаемся от сервера после ошибки остановки стрима (на всякий случай)");
                    connectionManager.disconnect();

                });
            }
        });
    }


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
        currentStreamingFeederId = null;
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
            if (which == 0) { 
                startVideoPlayback(videoItem);
            } else if (which == 1) { 
                checkPermissionsAndDownload(videoItem);
            }
        });
        builder.setNegativeButton("Отмена", null); 
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
        startVideoPlayback(videoItem);
    }

    @Override
    public void onVideoDownloadClick(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null) {
            Log.e(TAG, "Некорректный VideoItem в onVideoDownloadClick");
            return;
        }
        Log.d(TAG, "Запрос скачивания для: " + videoItem.getFilename());
        checkPermissionsAndDownload(videoItem);
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
                player.addListener(createPlayerListener());
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
                if (playbackState == Player.STATE_ENDED) {
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
        pendingDownloadItem = videoItem;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {

                Log.d(TAG, "Разрешение POST_NOTIFICATIONS уже есть.");
                startDownload(pendingDownloadItem);
                pendingDownloadItem = null;
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {

                new AlertDialog.Builder(this).setTitle("Нужно разрешение").setMessage("Для отображения статуса скачивания в уведомлениях требуется разрешение.").setPositiveButton("OK", (dialog, which) -> requestNotificationPermission()).setNegativeButton("Отмена", (dialog, which) -> {
                    Toast.makeText(this, "Скачивание будет выполнено без уведомлений.", Toast.LENGTH_SHORT).show();
                    startDownload(pendingDownloadItem);
                    pendingDownloadItem = null;
                }).show();
            } else {

                requestNotificationPermission();
            }
        } else {

            Log.d(TAG, "Версия Android ниже 13, разрешение POST_NOTIFICATIONS не требуется.");
            startDownload(pendingDownloadItem);
            pendingDownloadItem = null;
        }
    }


    private void requestNotificationPermission() {
        Log.d(TAG, "Запрос разрешения POST_NOTIFICATIONS...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }


    private void startDownload(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null || videoItem.getFilename() == null) {
            Log.e(TAG, "Невозможно начать скачивание: неверные данные VideoItem.");
            Toast.makeText(this, "Ошибка данных видео для скачивания", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = videoItem.getUrl();
        String filename = videoItem.getFilename();

        filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        try {
            DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Uri downloadUri = Uri.parse(url);

            DownloadManager.Request request = new DownloadManager.Request(downloadUri);


            String mimeType = getMimeType(url);
            if (mimeType != null) {
                request.setMimeType(mimeType);
            } else {
                request.setMimeType("video/*");
            }


            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE).setTitle(filename).setDescription("Скачивание видео...").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

            long downloadId = downloadManager.enqueue(request);

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
            Log.w(TAG, "Приемник downloadCompleteReceiver не был зарегистрирован или уже отменен.");
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
        Log.d(TAG, "onDestroy MainActivity");
    }
}