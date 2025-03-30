// Файл: MainActivity.java
package com.example.smartfeederzatichkav20;

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

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends AppCompatActivity implements VideoAdapter.OnVideoClickListener {

    private static final String TAG = "MainActivity";
    private static final String DEFAULT_FEEDER_ID = "750cec99-5311-4055-8da0-2aad1e531d6c"; // Keep default feeder ID

    // --- Removed UI elements for server/client ID ---
    private TextInputEditText etFeederId;
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
    // --- Removed Socket --- private Socket socket;
    private String streamPath;

    private ExoPlayer activePlayerForFullscreen = null;

    // Managers
    private SettingsManager settingsManager;
    private ConnectionManager connectionManager;

    // Activity Result Launcher for Fullscreen
    private final ActivityResultLauncher<Intent> fullscreenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleFullscreenResult
    );
    // Activity Result Launcher for Settings (Optional: if you need result back)
    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // Check if settings were successfully saved or connection established
                // For now, we rely on ConnectionManager's LiveData and SettingsManager
                Log.d(TAG, "Вернулись из Настроек, код результата: " + result.getResultCode());
                // Re-check connection status or attempt auto-connect again if needed
                updateConnectionStatusDisplay();
                attemptAutoConnect(); // Try connecting if settings might have changed
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
        connectionManager = ConnectionManager.getInstance(getApplicationContext()); // Use ApplicationContext

        // Initialize Views
        etFeederId = findViewById(R.id.etFeederId);
        etFeederId.setText(DEFAULT_FEEDER_ID); // Keep default feeder ID
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

        // Setup Button Listeners
        setupButtonClickListeners();

        // Observe Connection State from ConnectionManager
        connectionManager.getConnectionState().observe(this, state -> {
            updateConnectionStatusDisplay();
            // Enable/disable buttons based on connection state if needed
            boolean connected = state == ConnectionManager.ConnectionState.CONNECTED;
            // btnLoadVideos.setEnabled(connected); // Example: Requires connection to load videos? Maybe not.
            btnRequestStream.setEnabled(connected);
            // btnStopStream is handled internally by its visibility
        });


        // Attempt auto-connect on startup if settings are available
        if (settingsManager.areSettingsAvailable() && !connectionManager.isConnected()) {
            attemptAutoConnect();
        } else {
            updateConnectionStatusDisplay(); // Update display even if not auto-connecting
        }
    }

    private void setupRecyclerView() {
        videoAdapter = new VideoAdapter();
        videoAdapter.setOnVideoClickListener(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setInitialPrefetchItemCount(4);
        rvVideoList.setLayoutManager(layoutManager);
        rvVideoList.setNestedScrollingEnabled(false); // Keep this
        rvVideoList.setAdapter(videoAdapter);
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
                    // Optionally hide player or show message when recording finishes
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
                hideStreamUI(); // Hide UI on stream error
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                Log.d(TAG,"Stream Player state changed: " + state);
                if (state == Player.STATE_BUFFERING) {
                    Log.d(TAG, "Буферизация стрима...");
                    // Show buffering indicator? PlayerView might do this automatically.
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

        // Fullscreen listeners
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
                case CONNECTED: statusText += "Подключено"; break;
                case CONNECTING_FOR_ID: statusText += "Получение ID..."; break; // Обновлено
                case CONNECTING_WITH_ID: statusText += "Подключение..."; break; // Обновлено
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
                public void onSuccess(String clientId) { /* Not used in connectWithId */ }

                @Override
                public void onConnected() {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Авто-подключение успешно", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Ошибка авто-подключения: " + message, Toast.LENGTH_LONG).show();
                        // Optional: Prompt user to check settings
                        // showGoToSettingsDialog("Не удалось автоматически подключиться. Проверить настройки?");
                    });
                }
            });
        } else {
            Log.d(TAG, "Нет сохраненных настроек для авто-подключения.");
            // Optional: Prompt user to go to settings if they haven't before
            // if (!settingsManager.wereSettingsEverSaved()) { // Need to add this flag to SettingsManager if desired
            //    showGoToSettingsDialog("Пожалуйста, настройте подключение к серверу.");
            // }
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


    private void loadVideos() {
        String serverAddress = settingsManager.getServerAddress();

        if (TextUtils.isEmpty(serverAddress)) {
            Toast.makeText(this, "Адрес сервера не настроен. Зайдите в Настройки.", Toast.LENGTH_LONG).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);

        // Build Retrofit client each time? Or create once? For now, create each time.
        // Consider creating a dedicated ApiClient class later.
        try {
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl("http://" + serverAddress + "/") // Ensure address format is correct
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
            apiService = retrofit.create(ApiService.class);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Неверный формат адреса сервера: " + serverAddress, e);
            Toast.makeText(this, "Неверный формат адреса сервера.", Toast.LENGTH_LONG).show();
            progressBar.setVisibility(View.GONE);
            return;
        }


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
        String feederId = etFeederId.getText().toString().trim();
        String clientId = settingsManager.getClientId(); // Get client ID from settings

        if (!connectionManager.isConnected() || clientId == null) {
            Toast.makeText(this, "Нет подключения к серверу или отсутствует ID клиента. Проверьте настройки.", Toast.LENGTH_LONG).show();
            return;
        }

        if (TextUtils.isEmpty(feederId)) {
            etFeederId.setError("Введите ID кормушки");
            etFeederId.requestFocus();
            // Toast.makeText(this, "Введите идентификатор кормушки", Toast.LENGTH_SHORT).show();
            return;
        } else {
            etFeederId.setError(null); // Clear error if any
        }


        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Запрос трансляции...", Toast.LENGTH_SHORT).show();

        connectionManager.requestStream(feederId, new ConnectionManager.StreamCallback() {
            @Override
            public void onSuccess(String receivedStreamPath) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    streamPath = receivedStreamPath; // Store the path
                    startStreamPlayback(streamPath); // Start playback
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Log.e(TAG, "Ошибка запроса стрима: " + message);
                    Toast.makeText(MainActivity.this, "Ошибка запроса стрима: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // Renamed from startStream to avoid confusion with connectionManager.requestStream
    private void startStreamPlayback(String streamPath) {
        String serverAddress = settingsManager.getServerAddress();
        if (serverAddress == null) {
            Toast.makeText(this, "Адрес сервера не найден", Toast.LENGTH_SHORT).show();
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

            String rtmpUrl = "rtmp://" + serverIp + ":1935/" + path; // Standard RTMP port
            Log.d(TAG, "Подключение к RTMP стриму: " + rtmpUrl);

            if (streamPlayer != null) {
                streamPlayer.stop();
                streamPlayer.clearMediaItems();
            } else {
                // Reinitialize player if it was released
                streamPlayer = new ExoPlayer.Builder(this).build();
                streamPlayerView.setPlayer(streamPlayer);
                // Re-add listener if needed
                // streamPlayer.addListener(...);
            }


            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(rtmpUrl));
            streamPlayer.setMediaItem(mediaItem);
            streamPlayer.prepare();
            streamPlayer.play(); // Start playback

            showStreamUI(); // Show player and controls
            Toast.makeText(this, "Трансляция запущена", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Ошибка запуска воспроизведения стрима: " + e.getMessage(), e);
            Toast.makeText(MainActivity.this, "Ошибка запуска стрима: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            hideStreamUI();
        }
    }


    private void stopStream() {
        String feederId = etFeederId.getText().toString().trim();

        if (!connectionManager.isConnected()) {
            Toast.makeText(this, "Нет подключения для остановки стрима", Toast.LENGTH_SHORT).show();
            // Optionally try to connect first? For now, just stop local playback.
            stopStreamPlayback();
            hideStreamUI();
            return;
        }

        if (TextUtils.isEmpty(feederId)) {
            Toast.makeText(this, "Введите ID кормушки для остановки", Toast.LENGTH_SHORT).show();
            // Just stop local playback if feeder ID is missing?
            stopStreamPlayback();
            hideStreamUI();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        Log.d(TAG, "Запрос на остановку стрима для: " + feederId);

        connectionManager.stopStream(feederId, new ConnectionManager.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    stopStreamPlayback(); // Stop local playback on success
                    hideStreamUI();
                    Toast.makeText(MainActivity.this, "Трансляция остановлена сервером", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Log.e(TAG, "Ошибка остановки стрима: " + message);
                    Toast.makeText(MainActivity.this, "Ошибка остановки стрима: " + message, Toast.LENGTH_LONG).show();
                    // Still stop local playback even if server fails to confirm
                    stopStreamPlayback();
                    hideStreamUI();
                });
            }
        });
    }

    // Helper to stop local stream playback
    private void stopStreamPlayback() {
        if (streamPlayer != null) {
            streamPlayer.stop();
            streamPlayer.clearMediaItems();
        }
    }

    // Helper to show stream UI elements
    private void showStreamUI() {
        tvStreamTitle.setVisibility(View.VISIBLE);
        streamPlayerView.setVisibility(View.VISIBLE);
        btnStopStream.setVisibility(View.VISIBLE);
    }

    // Helper to hide stream UI elements
    private void hideStreamUI() {
        tvStreamTitle.setVisibility(View.GONE);
        streamPlayerView.setVisibility(View.GONE);
        btnStopStream.setVisibility(View.GONE);
    }

    // --- Removed getClientIdFromServer, connectToSocketServer, disconnectFromSocketServer ---
    // --- Removed onConnect, onDisconnect, onConnectError, onAssignId listeners ---
    // --- Removed connectAndStopStream ---


    /**
     * Обработчик нажатия на элемент видео
     * @param videoItem Выбранное видео
     */
    @Override
    public void onVideoClick(VideoItem videoItem) {
        playerView.setVisibility(View.VISIBLE);
        tvRecordedVideoTitle.setVisibility(View.VISIBLE); // Show title for player

        try {
            Uri videoUri = Uri.parse(videoItem.getUrl());
            MediaItem mediaItem = MediaItem.fromUri(videoUri);

            if (player != null) {
                player.stop(); // Stop previous playback if any
                player.clearMediaItems();
            } else {
                // Reinitialize player if needed
                player = new ExoPlayer.Builder(this).build();
                playerView.setPlayer(player);
                // Re-add listener
                // player.addListener(...);
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

    @Override
    protected void onStop() {
        super.onStop();
        // Pause players when activity is not visible
        if (player != null) {
            player.pause();
        }
        if (streamPlayer != null) {
            streamPlayer.pause();
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Release players
        if (player != null) {
            player.release();
            player = null;
        }
        if (streamPlayer != null) {
            streamPlayer.release();
            streamPlayer = null;
        }

        // Disconnect socket via ConnectionManager ONLY if app is fully closing
        // If just activity destruction, manager might keep connection alive.
        // For simplicity now, let's not disconnect here, let manager handle lifecycle if needed.
        // connectionManager.disconnect(); // Decide if disconnect is needed here
        Log.d(TAG,"onDestroy MainActivity");
    }
}