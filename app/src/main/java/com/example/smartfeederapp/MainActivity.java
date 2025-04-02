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

    // UI Elements
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

    // Adapters and Data
    private VideoAdapter videoAdapter;
    private List<String> availableFeederIds = new ArrayList<>();
    private ArrayAdapter<String> feederAdapter;

    // Managers and Handlers
    private SettingsManager settingsManager;
    private ConnectionManager connectionManager;
    private ApiClient apiClient;
    private VideoListHandler videoListHandler;
    private VideoPlaybackHandler videoPlaybackHandler;
    private StreamPlaybackHandler streamPlaybackHandler;
    private DownloadHandler downloadHandler;
    private Object activeFullscreenHandler = null;

    // Activity Result Launchers
    private ActivityResultLauncher<Intent> fullscreenLauncher;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;


    private final BroadcastReceiver downloadCompleteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != -1) {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) return;
                android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    try {
                        int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        String title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Toast.makeText(context, "Файл '" + title + "' успешно скачан.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Ошибка скачивания файла '" + title + "'.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка при обработке завершения загрузки", e);
                    } finally {
                        c.close();
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

        setupWindowInsets();
        initializeViews();
        initializeLaunchers();
        initializeManagersAndHandlers();
        setupFeederDropdown();
        setupButtonClickListeners();
        registerDownloadReceiver();
        setupObservers();
        performInitialLoad();
    }

    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void initializeViews() {
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
    }

    private void initializeLaunchers() {
        fullscreenLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleFullscreenResult);
        settingsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Log.d(TAG, "Вернулись из Настроек, код результата: " + result.getResultCode());
            updateConnectionStatusDisplay();
            apiClient.getApiService();
            if (connectionManager.isConnected()) {
                loadFeederList();
            }
        });
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> downloadHandler.onPermissionResult(isGranted));
    }

    private void initializeManagersAndHandlers() {
        settingsManager = SettingsManager.getInstance(this);
        connectionManager = ConnectionManager.getInstance(getApplicationContext());
        apiClient = new ApiClient(this);

        videoAdapter = new VideoAdapter();

        videoListHandler = new VideoListHandler(this, apiClient, rvVideoList, videoAdapter, progressBar);
        videoListHandler.setVideoActionListener(this);

        videoPlaybackHandler = new VideoPlaybackHandler(this, playerView, tvRecordedVideoTitle, fullscreenLauncher);
        streamPlaybackHandler = new StreamPlaybackHandler(this, streamPlayerView, tvStreamTitle, btnStopStream, progressBar, connectionManager, settingsManager, fullscreenLauncher);
        downloadHandler = new DownloadHandler(this, requestPermissionLauncher);

        playerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) handlePlayerFullscreenRequest(videoPlaybackHandler);
        });
        streamPlayerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) handlePlayerFullscreenRequest(streamPlaybackHandler);
        });
    }

    private void setupFeederDropdown() {
        feederAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, availableFeederIds);
        actvFeederId.setAdapter(feederAdapter);
    }

    private void setupButtonClickListeners() {
        btnLoadVideos.setOnClickListener(v -> videoListHandler.loadVideos());
        btnRequestStream.setOnClickListener(v -> {
            String selectedFeederId = actvFeederId.getText().toString().trim();
            if (TextUtils.isEmpty(selectedFeederId) || !availableFeederIds.contains(selectedFeederId)) {
                tilFeederId.setError("Пожалуйста, выберите кормушку из списка");
                actvFeederId.requestFocus();
                return;
            } else {
                tilFeederId.setError(null);
            }
            streamPlaybackHandler.requestAndStartStream(selectedFeederId);
        });

        btnSettings.setOnClickListener(v -> openSettings());
        btnRefreshFeeders.setOnClickListener(v -> loadFeederList());
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadCompleteReceiver, filter);
        }
    }

    private void setupObservers() {
        // Observer for Connection State
        connectionManager.getConnectionState().observe(this, state -> {
            updateConnectionStatusDisplay();
            boolean connected = state == ConnectionManager.ConnectionState.CONNECTED;

            btnRequestStream.setEnabled(connected);
            btnRefreshFeeders.setEnabled(connected);
            btnLoadVideos.setEnabled(connected);

            if (connected && availableFeederIds.isEmpty()) {
                loadFeederList();
            }
        });

        // Observer for Force Stream Stop
        connectionManager.getForceStoppedFeederId().observe(this, stoppedFeederId -> {
            if (stoppedFeederId != null) {
                Log.d(TAG, "Получено событие принудительной остановки для feederId: " + stoppedFeederId);

                streamPlaybackHandler.handleServerStreamStop(stoppedFeederId);
                connectionManager.clearForceStopEvent();
            }
        });
    }

    private void performInitialLoad() {
        updateConnectionStatusDisplay();
        if (settingsManager.areSettingsAvailable() && !connectionManager.isConnected()) {
            attemptAutoConnect();
        } else if (connectionManager.isConnected()) {
            loadFeederList();
        }
    }


    private void loadFeederList() {
        ApiService service = apiClient.getApiService();
        if (service == null) {
            Log.w(TAG, "ApiService не доступен, не могу загрузить кормушки.");
            Toast.makeText(this, "Не удалось подключиться к API. Проверьте адрес сервера.", Toast.LENGTH_SHORT).show();
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        Log.d(TAG, "Загрузка списка кормушек...");

        service.getFeeders().enqueue(new Callback<List<String>>() {
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
                    handleApiError(response, "загрузки кормушек");
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

    @Override
    public void onVideoPlayClick(VideoItem videoItem) {
        activeFullscreenHandler = videoPlaybackHandler;
        videoPlaybackHandler.startPlayback(videoItem);
    }

    @Override
    public void onVideoDownloadClick(VideoItem videoItem) {
        downloadHandler.checkPermissionsAndDownload(videoItem);
    }

    private void handlePlayerFullscreenRequest(Object handler) {
        if (handler == videoPlaybackHandler) {
            Log.d(TAG, "Fullscreen запрошен для плеера записей");
            activeFullscreenHandler = videoPlaybackHandler;
            startActivityForFullscreen(videoPlaybackHandler.getPlayer());
        } else if (handler == streamPlaybackHandler) {
            Log.d(TAG, "Fullscreen запрошен для плеера стрима");
            activeFullscreenHandler = streamPlaybackHandler;
            startActivityForFullscreen(streamPlaybackHandler.getPlayer());
        } else {
            Toast.makeText(this, "Неизвестный источник запроса fullscreen", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleFullscreenResult(ActivityResult result) {
        if (activeFullscreenHandler == videoPlaybackHandler) {
            videoPlaybackHandler.handleFullscreenResult(result);
        } else if (activeFullscreenHandler == streamPlaybackHandler) {
            streamPlaybackHandler.handleFullscreenResult(result);
        } else {
            Log.w(TAG, "handleFullscreenResult: No active handler was tracked.");
        }
        activeFullscreenHandler = null;
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

    @OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void openFullscreenActivity(ExoPlayer currentPlayer) {
        if (currentPlayer == videoPlaybackHandler.getPlayer()) {
            activeFullscreenHandler = videoPlaybackHandler;
            Log.d(TAG, "Fullscreen запрошен для плеера записей");
            startActivityForFullscreen(videoPlaybackHandler.getPlayer());
        } else if (currentPlayer == streamPlaybackHandler.getPlayer()) {
            activeFullscreenHandler = streamPlaybackHandler;
            Log.d(TAG, "Fullscreen запрошен для плеера стрима");
            startActivityForFullscreen(streamPlaybackHandler.getPlayer());
        } else {
            Toast.makeText(this, "Неизвестный плеер для полноэкранного режима", Toast.LENGTH_SHORT).show();
        }
    }

    private void startActivityForFullscreen(ExoPlayer player) {
        if (player == null || player.getCurrentMediaItem() == null) {
            Toast.makeText(this, "Нет данных для полноэкранного режима", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri videoUri = null;
        try {
            videoUri = player.getCurrentMediaItem().localConfiguration != null ? player.getCurrentMediaItem().localConfiguration.uri : Uri.parse(player.getCurrentMediaItem().mediaId);
        } catch (Exception e) {
            Log.e(TAG, "Не удалось получить URI для fullscreen", e);
        }

        if (videoUri == null) {
            return;
        }

        long currentPosition = player.getCurrentPosition();
        if (activeFullscreenHandler == streamPlaybackHandler) {
            currentPosition = C.TIME_UNSET;
        }
        boolean playWhenReady = player.getPlayWhenReady();
        player.pause();

        Intent intent = new Intent(this, FullscreenVideoActivity.class);
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_URI, videoUri.toString());
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, currentPosition);
        intent.putExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, playWhenReady);
        fullscreenLauncher.launch(intent);
    }

    private void handleApiError(Response<?> response, String operationDescription) {
        try {
            String errorBody = response.errorBody() != null ? response.errorBody().string() : "Нет тела ошибки";
            Log.e(TAG, "Ошибка " + operationDescription + ": " + response.code() + " - " + response.message() + " Body: " + errorBody);
            Toast.makeText(this, "Ошибка " + operationDescription + ": " + response.code(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Ошибка чтения тела ошибки (" + operationDescription + ")", e);
            Toast.makeText(this, "Ошибка " + operationDescription + ": " + response.code(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        videoPlaybackHandler.pause();
        streamPlaybackHandler.pause();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadCompleteReceiver);
            Log.d(TAG, "DownloadCompleteReceiver успешно отменен.");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Приемник downloadCompleteReceiver не был зарегистрирован или уже отменен.");
        }

        super.onDestroy();

        videoPlaybackHandler.releasePlayer();
        streamPlaybackHandler.releasePlayer();
        Log.d(TAG, "onDestroy MainActivity");
    }
}