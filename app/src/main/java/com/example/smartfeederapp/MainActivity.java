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

/**
 * The main activity of the application.
 * Displays connection status, feeder selection, video list, and playback controls.
 * Coordinates interactions between UI elements and various handlers
 * (Connection, API, VideoList, Playback, Download).
 */
public class MainActivity extends AppCompatActivity implements VideoAdapter.OnVideoActionListener {

    private static final String TAG = "MainActivity";

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
    private List<String> availableFeederIds = new ArrayList<>();
    private ArrayAdapter<String> feederAdapter;

    private SettingsManager settingsManager;
    private ConnectionManager connectionManager;
    private ApiClient apiClient;
    private VideoListHandler videoListHandler;
    private VideoPlaybackHandler videoPlaybackHandler;
    private StreamPlaybackHandler streamPlaybackHandler;
    private DownloadHandler downloadHandler;
    private Object activeFullscreenHandler = null;
    private String pendingStreamFeederId = null;

    private ActivityResultLauncher<Intent> fullscreenLauncher;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;

    /**
     * BroadcastReceiver to listen for download completion events from DownloadManager.
     */
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
                            Toast.makeText(context, "File '" + title + "' downloaded successfully.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Error downloading file '" + title + "'.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing download completion", e);
                    } finally {
                        c.close();
                    }
                }
            }
        }
    };

    /**
     * Called when the activity is first created. Initializes UI, managers, handlers,
     * launchers, observers, and performs initial data loading and setup.
     *
     * @param savedInstanceState If the activity is being re-initialized.
     */
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

    /**
     * Sets up window insets for edge-to-edge display.
     */
    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    /**
     * Initializes references to UI elements from the layout.
     */
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

    /**
     * Initializes ActivityResultLaunchers for handling results from other activities
     * and permission requests.
     */
    private void initializeLaunchers() {
        fullscreenLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleFullscreenResult);
        settingsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Log.d(TAG, "Returned from Settings, result code: " + result.getResultCode());
            updateConnectionStatusDisplay();
            apiClient.getApiService();
            if (connectionManager.isConnected()) {
                loadFeederList();
            }
        });
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> downloadHandler.onPermissionResult(isGranted));
    }

    /**
     * Initializes singleton managers and creates handler instances, passing necessary dependencies.
     * Also sets up fullscreen button listeners after handlers are created.
     */
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

    /**
     * Sets up the ArrayAdapter for the feeder selection AutoCompleteTextView.
     */
    private void setupFeederDropdown() {
        feederAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, availableFeederIds);
        actvFeederId.setAdapter(feederAdapter);
    }

    /**
     * Sets OnClickListeners for the main control buttons.
     */
    private void setupButtonClickListeners() {
        btnLoadVideos.setOnClickListener(v -> {
            if (apiClient.getApiService() == null) {
                Toast.makeText(this, "API not available. Check server address in Settings.", Toast.LENGTH_SHORT).show();
                return;
            }
            videoListHandler.loadVideos();
        });

        btnRequestStream.setOnClickListener(v -> {
            String selectedFeederId = actvFeederId.getText().toString().trim();
            if (TextUtils.isEmpty(selectedFeederId) || !availableFeederIds.contains(selectedFeederId) || selectedFeederId.equals("No active feeders")) {
                tilFeederId.setError("Please select a feeder from the list");
                actvFeederId.requestFocus();
                return;
            } else {
                tilFeederId.setError(null);
            }
            connectAndRequestStreamIfNeeded(selectedFeederId);
        });

        btnSettings.setOnClickListener(v -> openSettings());
        btnRefreshFeeders.setOnClickListener(v -> {
            if (apiClient.getApiService() == null) {
                Toast.makeText(this, "API not available. Check server address in Settings.", Toast.LENGTH_SHORT).show();
                return;
            }
            /*
            if (!connectionManager.isConnected()) {
                Toast.makeText(this, "Not connected. Cannot refresh feeder list.", Toast.LENGTH_SHORT).show();
                return;
            }*/
            loadFeederList();
        });
    }


    /**
     * Registers the BroadcastReceiver for download completion events, handling SDK version differences.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadCompleteReceiver, filter);
        }
    }

    /**
     * Sets up LiveData observers to react to changes in connection state, client ID,
     * and forced stream stop events from ConnectionManager.
     */
    private void setupObservers() {
        connectionManager.getConnectionState().observe(this, state -> {
            updateConnectionStatusDisplay();

            boolean connected = state == ConnectionManager.ConnectionState.CONNECTED;
            boolean apiAvailable = apiClient.getApiService() != null;

            btnRequestStream.setEnabled(true);
            btnRefreshFeeders.setEnabled(apiAvailable);
            btnLoadVideos.setEnabled(apiAvailable);

            if (connected) {
                if (pendingStreamFeederId != null) {
                    Log.d(TAG, "Connection established, proceeding with pending stream request for: " + pendingStreamFeederId);
                    streamPlaybackHandler.requestAndStartStream(pendingStreamFeederId);
                    pendingStreamFeederId = null;
                }
            } else {
                if (pendingStreamFeederId != null &&
                        (state == ConnectionManager.ConnectionState.DISCONNECTED || state == ConnectionManager.ConnectionState.ERROR)) {
                    Log.w(TAG, "Connection failed or disconnected while attempting to request stream for: " + pendingStreamFeederId);
                    Toast.makeText(this, "Connection failed. Cannot request stream.", Toast.LENGTH_SHORT).show();
                    pendingStreamFeederId = null;
                    progressBar.setVisibility(View.GONE);
                }

                if (state == ConnectionManager.ConnectionState.DISCONNECTED || state == ConnectionManager.ConnectionState.ERROR) {
                    Log.d(TAG, "Connection lost or error state detected. Hiding stream");
                    streamPlaybackHandler.stopLocalPlaybackAndHideUI();
                }
            }
        });

        connectionManager.getForceStoppedFeederId().observe(this, stoppedFeederId -> {
            if (stoppedFeederId != null) {
                Log.d(TAG, "Received forced stop event for feederId: " + stoppedFeederId);
                streamPlaybackHandler.handleServerStreamStop(stoppedFeederId);
                connectionManager.clearForceStopEvent();
            }
        });

        connectionManager.getClientIdLiveData().observe(this, clientId -> {
            if (clientId != null) {
                Log.d(TAG, "Client ID updated/received in MainActivity: " + clientId);
                if (pendingStreamFeederId != null && (connectionManager.getConnectionState().getValue() == ConnectionManager.ConnectionState.CONNECTING_FOR_ID || connectionManager.getConnectionState().getValue() == ConnectionManager.ConnectionState.DISCONNECTED)) {
                    Log.d(TAG, "Client ID received while stream request was pending. Initiating connection...");
                    String serverAddress = settingsManager.getServerAddress();
                    if (serverAddress != null) {
                        connectionManager.connectWithId(serverAddress, clientId, null);
                    } else {
                        Log.e(TAG, "Server address missing, cannot connect after getting ID.");
                        Toast.makeText(this, "Server address missing. Go to Settings.", Toast.LENGTH_LONG).show();
                        pendingStreamFeederId = null;
                        progressBar.setVisibility(View.GONE);
                    }
                }
            } else {
                Log.d(TAG, "Client ID cleared in MainActivity observer.");
            }
        });
    }

    /**
     * Performs initial actions when the activity starts: updates status display
     * and attempts to load initial data like video/feeder lists if API is ready.
     */
    private void performInitialLoad() {
        updateConnectionStatusDisplay();

        if (apiClient.getApiService() != null) {
            videoListHandler.loadVideos();
            loadFeederList();
        } else {
            Log.w(TAG,"API Client not ready on initial load. Go to settings.");
        }
        if(settingsManager.getServerAddress() != null && settingsManager.getClientId() == null) {
            Log.d(TAG,"Server address set but no client ID. Getting ID on startup...");
            connectionManager.getClientIdFromServer(settingsManager.getServerAddress(), new ConnectionManager.ConnectionCallback() {
                @Override public void onSuccess(String clientId) { Log.i(TAG,"Client ID obtained successfully on startup."); settingsManager.saveClientId(clientId); }
                @Override public void onConnected() {}
                @Override public void onError(String message) { Log.e(TAG,"Failed to get Client ID on startup: " + message); }
            });
        }
    }

    /**
     * Loads the list of available feeders from the server via the API client.
     * Requires an active connection as per server logic.
     */
    private void loadFeederList() {
        ApiService service = apiClient.getApiService();
        if (service == null) {
            Log.w(TAG, "ApiService not available, cannot load feeders.");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        Log.d(TAG, "Loading feeder list...");

        service.getFeeders().enqueue(new Callback<List<String>>() {
            @Override
            public void onResponse(@NonNull Call<List<String>> call, @NonNull Response<List<String>> response) {
                progressBar.setVisibility(View.GONE);
                if (response.isSuccessful() && response.body() != null) {
                    List<String> feeders = response.body();
                    availableFeederIds.clear();
                    if (feeders.isEmpty()) {
                        Toast.makeText(MainActivity.this, "No active feeders found", Toast.LENGTH_SHORT).show();
                        availableFeederIds.add("No active feeders");
                    } else {
                        availableFeederIds.addAll(feeders);
                        Toast.makeText(MainActivity.this, "Loaded " + feeders.size() + " feeders", Toast.LENGTH_SHORT).show();
                    }
                    feederAdapter.notifyDataSetChanged();
                } else {
                    handleApiError(response, "loading feeders");
                }
            }

            @Override
            public void onFailure(@NonNull Call<List<String>> call, @NonNull Throwable t) {
                progressBar.setVisibility(View.GONE);
                Log.e(TAG, "Network error loading feeders", t);
                Toast.makeText(MainActivity.this, "Network error loading feeders: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Called when the video name/item is clicked in the list. Delegates playback to the handler.
     * @param videoItem The selected video item.
     */
    @Override
    public void onVideoPlayClick(VideoItem videoItem) {
        activeFullscreenHandler = videoPlaybackHandler;
        videoPlaybackHandler.startPlayback(videoItem);
    }

    /**
     * Called when the download button is clicked for a video item. Delegates download to the handler.
     * @param videoItem The selected video item.
     */
    @Override
    public void onVideoDownloadClick(VideoItem videoItem) {
        downloadHandler.checkPermissionsAndDownload(videoItem);
    }

    /**
     * Determines which handler initiated the fullscreen request and triggers the process.
     * @param handler The handler object (either VideoPlaybackHandler or StreamPlaybackHandler).
     */
    private void handlePlayerFullscreenRequest(Object handler) {
        if (handler == videoPlaybackHandler) {
            Log.d(TAG, "Fullscreen requested for recorded video player");
            activeFullscreenHandler = videoPlaybackHandler;
            startActivityForFullscreen(videoPlaybackHandler.getPlayer());
        } else if (handler == streamPlaybackHandler) {
            Log.d(TAG, "Fullscreen requested for stream player");
            activeFullscreenHandler = streamPlaybackHandler;
            startActivityForFullscreen(streamPlaybackHandler.getPlayer());
        } else {
            Toast.makeText(this, "Unknown source for fullscreen request", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handles the result returned from FullscreenVideoActivity.
     * Delegates the result processing to the handler that initiated the fullscreen mode.
     * @param result The ActivityResult containing data from the fullscreen activity.
     */
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

    /**
     * Updates the connection status TextView based on the current state from ConnectionManager.
     */
    private void updateConnectionStatusDisplay() {
        ConnectionManager.ConnectionState state = connectionManager.getConnectionState().getValue();
        String statusText = "Status: ";
        if (state != null) {
            switch (state) {
                case CONNECTED:         statusText += "Connected"; break;
                case CONNECTING_FOR_ID: statusText += "Getting ID..."; break;
                case CONNECTING_WITH_ID:statusText += "Connecting..."; break;
                case DISCONNECTED:      statusText += "Disconnected"; break;
                case ERROR:             statusText += "Error"; break;
                default:                statusText += "Unknown"; break;
            }
        } else {
            statusText += "Unknown";
        }
        tvConnectionStatusMain.setText(statusText);
    }

    /**
     * UNUSED - Attempts to automatically connect to the server using saved settings.
     */
    private void attemptAutoConnect() {
        String serverAddress = settingsManager.getServerAddress();
        String clientId = settingsManager.getClientId();

        if (serverAddress != null && clientId != null) {
            Log.d(TAG, "Attempting auto-connect...");
            Toast.makeText(this, "Auto-connecting...", Toast.LENGTH_SHORT).show();
            connectionManager.connectWithId(serverAddress, clientId, new ConnectionManager.ConnectionCallback() {
                @Override public void onSuccess(String clientId) { /* Not used */ }
                @Override public void onConnected() { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Auto-connect successful", Toast.LENGTH_SHORT).show()); }
                @Override public void onError(String message) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Auto-connect error: " + message, Toast.LENGTH_LONG).show()); }
            });
        } else {
            Log.d(TAG, "No saved settings for auto-connect.");
        }
    }

    /**
     * Opens the SettingsActivity.
     */
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        settingsLauncher.launch(intent);
    }

    /**
     * Initiates launching the FullscreenVideoActivity for the given player.
     * @param currentPlayer The ExoPlayer instance to be shown in fullscreen.
     */
    @OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void openFullscreenActivity(ExoPlayer currentPlayer) {
        if (currentPlayer == videoPlaybackHandler.getPlayer()) {
            handlePlayerFullscreenRequest(videoPlaybackHandler);
        } else if (currentPlayer == streamPlaybackHandler.getPlayer()) {
            handlePlayerFullscreenRequest(streamPlaybackHandler);
        } else {
            Toast.makeText(this, "Unknown player for fullscreen mode", Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * Starts the FullscreenVideoActivity with the necessary data from the player.
     * @param player The ExoPlayer whose content will be displayed fullscreen.
     */
    private void startActivityForFullscreen(ExoPlayer player) {
        if (player == null || player.getCurrentMediaItem() == null) {
            Toast.makeText(this, "No data for fullscreen mode", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri videoUri = null;
        try {
            videoUri = player.getCurrentMediaItem().localConfiguration != null ? player.getCurrentMediaItem().localConfiguration.uri : Uri.parse(player.getCurrentMediaItem().mediaId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get URI for fullscreen", e);
        }

        if (videoUri == null) {
            Toast.makeText(this, "Failed to get URI for video/stream", Toast.LENGTH_SHORT).show();
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

    /**
     * Utility method to handle and log API errors from Retrofit responses.
     * @param response The Retrofit response object.
     * @param operationDescription A description of the failed operation for logging.
     */
    private void handleApiError(Response<?> response, String operationDescription) {
        try {
            String errorBody = response.errorBody() != null ? response.errorBody().string() : "No error body";
            Log.e(TAG, "Error " + operationDescription + ": " + response.code() + " - " + response.message() + " Body: " + errorBody);
            Toast.makeText(this, "Error " + operationDescription + ": " + response.code(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error reading error body (" + operationDescription + ")", e);
            Toast.makeText(this, "Error " + operationDescription + ": " + response.code(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Pauses playback when the activity is stopped.
     */
    @Override
    protected void onStop() {
        super.onStop();
        videoPlaybackHandler.pause();
        streamPlaybackHandler.pause();
    }

    /**
     * Unregisters receivers and releases player resources when the activity is destroyed.
     */
    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadCompleteReceiver);
            Log.d(TAG, "DownloadCompleteReceiver unregistered successfully.");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "DownloadCompleteReceiver was not registered or already unregistered.");
        }
        super.onDestroy();

        videoPlaybackHandler.releasePlayer();
        streamPlaybackHandler.releasePlayer();
        Log.d(TAG, "onDestroy MainActivity");
    }

    /**
     * Checks connection and settings status, then initiates connection/ID retrieval
     * if necessary before requesting a stream. Stores the target feeder ID if connection
     * needs to be established first.
     * @param feederId The ID of the feeder for the desired stream request.
     */
    private void connectAndRequestStreamIfNeeded(String feederId) {
        if (connectionManager.isConnected()) {
            Log.d(TAG, "Already connected. Requesting stream directly for: " + feederId);
            streamPlaybackHandler.requestAndStartStream(feederId);
        } else {
            String serverAddress = settingsManager.getServerAddress();
            String clientId = settingsManager.getClientId();

            if (serverAddress != null) {
                if (clientId != null) {
                    Log.d(TAG, "Not connected, but settings available. Attempting connect before requesting stream for: " + feederId);
                    pendingStreamFeederId = feederId;
                    progressBar.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show();
                    connectionManager.connectWithId(serverAddress, clientId, new ConnectionManager.ConnectionCallback() {
                        @Override public void onSuccess(String id) { }
                        @Override public void onConnected() { runOnUiThread(() -> progressBar.setVisibility(View.GONE));}
                        @Override public void onError(String message) {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                Toast.makeText(MainActivity.this, "Connection failed: " + message, Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                } else {
                    Log.d(TAG, "Not connected and Client ID missing. Attempting to get ID before requesting stream for: " + feederId);
                    pendingStreamFeederId = feederId;
                    progressBar.setVisibility(View.VISIBLE);
                    connectionManager.getClientIdFromServer(serverAddress, new ConnectionManager.ConnectionCallback() {
                        @Override
                        public void onSuccess(String receivedClientId) {
                            runOnUiThread(() -> {
                                Log.d(TAG, "ID obtained successfully (" + receivedClientId + ") while requesting stream. Connection will follow.");
                                settingsManager.saveClientId(receivedClientId);
                            });
                        }
                        @Override public void onConnected() { /* Not called */ }
                        @Override
                        public void onError(String message) {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                Toast.makeText(MainActivity.this, "Failed to get Client ID: " + message, Toast.LENGTH_LONG).show();
                                pendingStreamFeederId = null;
                            });
                        }
                    });
                }
            } else {
                Log.w(TAG, "Cannot connect/get ID for stream: Server address not configured.");
                Toast.makeText(this, "Server address not configured. Please go to Settings.", Toast.LENGTH_LONG).show();
            }
        }
    }
}