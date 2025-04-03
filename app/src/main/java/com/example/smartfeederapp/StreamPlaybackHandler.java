package com.example.smartfeederapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Handles the logic related to requesting, playing, stopping,
 * and managing the fullscreen state of live streams.
 */
public class StreamPlaybackHandler {

    private static final String TAG = "StreamPlaybackHandler";

    private final Context context;
    private final PlayerView streamPlayerView;
    private final TextView tvStreamTitle;
    private final Button btnStopStream;
    private final ProgressBar progressBar;
    private final ConnectionManager connectionManager;
    private final SettingsManager settingsManager;
    private final ActivityResultLauncher<Intent> fullscreenLauncher;

    private ExoPlayer streamPlayer;
    private String currentStreamingFeederId = null;
    private String currentStreamPath = null;

    /**
     * Constructor for StreamPlaybackHandler.
     * @param context Context for creating Toasts and Players.
     * @param spv PlayerView for displaying the stream.
     * @param titleTv TextView for the stream title.
     * @param stopBtn Button to stop the stream.
     * @param pb ProgressBar for indicating loading states.
     * @param cm ConnectionManager for socket communication.
     * @param sm SettingsManager for accessing server address.
     * @param launcher ActivityResultLauncher for handling fullscreen results.
     */
    public StreamPlaybackHandler(Context context, PlayerView spv, TextView titleTv, Button stopBtn, ProgressBar pb,
                                 ConnectionManager cm, SettingsManager sm, ActivityResultLauncher<Intent> launcher) {
        this.context = context;
        this.streamPlayerView = spv;
        this.tvStreamTitle = titleTv;
        this.btnStopStream = stopBtn;
        this.progressBar = pb;
        this.connectionManager = cm;
        this.settingsManager = sm;
        this.fullscreenLauncher = launcher;
        initializePlayer();
    }

    /**
     * Initializes the ExoPlayer instance for streaming and sets up listeners.
     */
    private void initializePlayer() {
        streamPlayer = new ExoPlayer.Builder(context).build();
        streamPlayerView.setPlayer(streamPlayer);
        streamPlayer.addListener(createStreamPlayerListener());
        streamPlayerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) openFullscreenActivity();
        });
        btnStopStream.setOnClickListener(v -> stopStream());
    }

    /**
     * Requests the stream path from the server for the given feeder ID
     * and starts playback if successful.
     * @param feederId The ID of the feeder to request the stream from.
     */
    public void requestAndStartStream(String feederId) {
        if (feederId == null || feederId.isEmpty()) {
            Toast.makeText(context, "Feeder ID not selected", Toast.LENGTH_SHORT).show();
            return;
        }

        /*
        String clientId = settingsManager.getClientId();
        if (!connectionManager.isConnected() || clientId == null) {
            Toast.makeText(context, "Not connected to server or Client ID missing. Check settings.", Toast.LENGTH_LONG).show();
            return;
        }
        */

        if (currentStreamingFeederId != null && !currentStreamingFeederId.equals(feederId)) {
            Log.d(TAG, "Requested stream from different feeder, stopping previous (" + currentStreamingFeederId + ")");
            stopLocalPlaybackAndHideUI();
        }

        showProgress(true);
        Toast.makeText(context, "Requesting stream for " + feederId, Toast.LENGTH_SHORT).show();

        connectionManager.requestStream(feederId, new ConnectionManager.StreamCallback() {
            @Override
            public void onSuccess(String receivedStreamPath) {
                showProgress(false);
                currentStreamPath = receivedStreamPath;
                currentStreamingFeederId = feederId;
                startStreamPlayback(currentStreamPath);
            }

            @Override
            public void onError(String message) {
                showProgress(false);
                Log.e(TAG, "Stream request error: " + message);
                Toast.makeText(context, "Stream request error: " + message, Toast.LENGTH_LONG).show();
                currentStreamingFeederId = null;
            }
        });
    }

    /**
     * Starts the actual RTMP stream playback using the provided path.
     * @param streamPath The path component of the RTMP URL.
     */
    private void startStreamPlayback(String streamPath) {
        String serverAddress = settingsManager.getServerAddress();
        if (serverAddress == null) {
            Toast.makeText(context, "Server address not found", Toast.LENGTH_SHORT).show();
            hideStreamUI();
            return;
        }

        try {
            String serverIp = serverAddress;
            if (serverAddress.contains(":")) {
                serverIp = serverAddress.substring(0, serverAddress.indexOf(":"));
            }
            String path = streamPath;
            if (path.startsWith("/")) path = path.substring(1);

            String rtmpUrl = "rtmp://" + serverIp + ":1935/" + path;
            Log.d(TAG, "Connecting to RTMP stream: " + rtmpUrl);

            if (streamPlayer == null) {
                initializePlayer();
            }

            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(rtmpUrl));
            streamPlayer.stop();
            streamPlayer.clearMediaItems();
            streamPlayer.setMediaItem(mediaItem);
            streamPlayer.prepare();
            streamPlayer.play();

            showStreamUI();
            Toast.makeText(context, "Stream started", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error starting stream playback: " + e.getMessage(), e);
            Toast.makeText(context, "Error starting stream: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            hideStreamUI();
        }
    }

    /**
     * Sends a request to the server to stop the currently active stream
     * and disconnects the socket connection. Also stops local playback.
     */
    public void stopStream() {
        String feederIdToStop = currentStreamingFeederId;

        if (feederIdToStop == null) {
            Log.w(TAG, "Attempting to stop stream, but current feeder ID is unknown.");
            stopLocalPlaybackAndHideUI();
            return;
        }

        if (!connectionManager.isConnected()) {
            Toast.makeText(context, "Not connected to stop stream", Toast.LENGTH_SHORT).show();
            stopLocalPlaybackAndHideUI();
            return;
        }

        showProgress(true);
        Log.d(TAG, "Requesting stream stop for: " + feederIdToStop);

        connectionManager.stopStream(feederIdToStop, new ConnectionManager.SimpleCallback() {
            @Override
            public void onSuccess() {
                showProgress(false);
                stopLocalPlaybackAndHideUI();
                Toast.makeText(context, "Stream stopped", Toast.LENGTH_SHORT).show();
                connectionManager.disconnect();
            }

            @Override
            public void onError(String message) {
                showProgress(false);
                Log.e(TAG, "Server error stopping stream: " + message);
                Toast.makeText(context, "Error stopping stream: " + message, Toast.LENGTH_LONG).show();
                stopLocalPlaybackAndHideUI();
                connectionManager.disconnect();
            }
        });
    }

    /**
     * Handles the "stream stopped" event received from the server via ConnectionManager.
     * Stops local playback if the stopped feeder ID matches the current one.
     * @param stoppedFeederId The ID of the feeder whose stream was stopped by the server.
     */
    public void handleServerStreamStop(String stoppedFeederId) {
        if (stoppedFeederId != null && stoppedFeederId.equals(currentStreamingFeederId)) {
            Log.i(TAG, "Force stopping current stream due to server signal.");
            Toast.makeText(context, "Stream stopped by server", Toast.LENGTH_SHORT).show();
            stopLocalPlaybackAndHideUI();
        } else {
            Log.d(TAG, "Server stop event for " + stoppedFeederId + " does not match current stream (" + currentStreamingFeederId + "), ignoring.");
        }
    }

    /**
     * Stops the local ExoPlayer playback and hides the stream UI elements.
     */
    private void stopLocalPlaybackAndHideUI() {
        if (streamPlayer != null) {
            streamPlayer.stop();
            streamPlayer.clearMediaItems();
        }
        hideStreamUI();
    }

    /**
     * Makes the stream-related UI elements visible.
     */
    private void showStreamUI() {
        tvStreamTitle.setVisibility(View.VISIBLE);
        streamPlayerView.setVisibility(View.VISIBLE);
        btnStopStream.setVisibility(View.VISIBLE);
    }

    /**
     * Hides the stream-related UI elements and resets tracking variables.
     */
    private void hideStreamUI() {
        tvStreamTitle.setVisibility(View.GONE);
        streamPlayerView.setVisibility(View.GONE);
        btnStopStream.setVisibility(View.GONE);
        currentStreamingFeederId = null;
        currentStreamPath = null;
    }

    /**
     * Disconnects the socket connection via ConnectionManager if currently connected.
     */
    private void disconnectIfConnected() {
        if (connectionManager.isConnected()) {
            Log.d(TAG, "Disconnecting from server after stream operation");
            connectionManager.disconnect();
        }
    }

    /**
     * Initiates the transition to the FullscreenVideoActivity for the current stream.
     */
    private void openFullscreenActivity() {
        if (streamPlayer == null || streamPlayer.getCurrentMediaItem() == null) {
            Toast.makeText(context, "No active stream for fullscreen mode", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri videoUri = streamPlayer.getCurrentMediaItem().localConfiguration != null ? streamPlayer.getCurrentMediaItem().localConfiguration.uri : null;
        if (videoUri == null) {
            MediaItem item = streamPlayer.getCurrentMediaItem();
            if (item != null && item.mediaId != null && item.mediaId.startsWith("rtmp")) {
                videoUri = Uri.parse(item.mediaId);
            } else {
                Toast.makeText(context, "Could not get stream URI", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        long currentPosition = streamPlayer.getCurrentPosition();
        boolean playWhenReady = streamPlayer.getPlayWhenReady();
        streamPlayer.pause();

        Intent intent = new Intent(context, FullscreenVideoActivity.class);
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_URI, videoUri.toString());
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, C.TIME_UNSET);
        intent.putExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, playWhenReady);
        fullscreenLauncher.launch(intent);
    }

    /**
     * Handles the result returned from FullscreenVideoActivity when exiting fullscreen mode for a stream.
     * Simply resumes playback.
     * @param result The ActivityResult object.
     */
    public void handleFullscreenResult(ActivityResult result) {
        Log.d(TAG, "StreamPlaybackHandler: Fullscreen finished, resuming stream.");
        if (streamPlayer != null) {
            streamPlayer.play();
            streamPlayerView.setPlayer(null);
            streamPlayerView.setPlayer(streamPlayer);
        }
    }

    /**
     * Returns the ExoPlayer instance used for streaming.
     * @return The ExoPlayer instance.
     */
    public ExoPlayer getPlayer() {
        return streamPlayer;
    }

    /**
     * Pauses the stream playback.
     */
    public void pause() {
        if (streamPlayer != null) {
            streamPlayer.pause();
        }
    }

    /**
     * Releases the ExoPlayer instance used for streaming.
     */
    public void releasePlayer() {
        if (streamPlayer != null) {
            streamPlayer.release();
            streamPlayer = null;
        }
    }

    /**
     * Creates and returns a Player.Listener for handling stream player events.
     * @return A configured Player.Listener instance.
     */
    private Player.Listener createStreamPlayerListener() {
        return new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Stream playback error: " + error.getMessage(), error);
                Toast.makeText(context, "Stream playback error", Toast.LENGTH_SHORT).show();
                hideStreamUI();
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                Log.d(TAG, "Stream Player state changed: " + state);
                if (state == Player.STATE_BUFFERING) {
                    Log.d(TAG, "Stream buffering...");
                } else if (state == Player.STATE_ENDED) {
                    Log.d(TAG, "Stream playback ended (or stream stopped)");
                    Toast.makeText(context, "Stream finished", Toast.LENGTH_SHORT).show();
                    hideStreamUI();
                } else if (state == Player.STATE_READY) {
                    Log.d(TAG, "Stream ready for playback.");
                }
            }
        };
    }

    /**
     * Shows or hides the progress bar.
     * @param show True to show, false to hide.
     */
    private void showProgress(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Returns the ID of the feeder currently being streamed.
     * @return The current feeder ID, or null if no stream is active.
     */
    public String getCurrentStreamingFeederId() {
        return currentStreamingFeederId;
    }
}