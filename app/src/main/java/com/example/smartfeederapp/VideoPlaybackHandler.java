package com.example.smartfeederapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
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
 * Handles the playback of recorded video files using ExoPlayer.
 * Manages the player lifecycle, UI visibility, and fullscreen transitions for recorded videos.
 */
public class VideoPlaybackHandler {

    private static final String TAG = "VideoPlaybackHandler";

    private final Context context;
    private final PlayerView playerView;
    private final TextView tvRecordedVideoTitle;
    private final ActivityResultLauncher<Intent> fullscreenLauncher;

    private ExoPlayer player;

    /**
     * Constructor for VideoPlaybackHandler.
     * @param context Context for creating Toasts and Players.
     * @param pv PlayerView for displaying the recorded video.
     * @param titleTv TextView for the recorded video title.
     * @param launcher ActivityResultLauncher for handling fullscreen results.
     */
    public VideoPlaybackHandler(Context context, PlayerView pv, TextView titleTv, ActivityResultLauncher<Intent> launcher) {
        this.context = context;
        this.playerView = pv;
        this.tvRecordedVideoTitle = titleTv;
        this.fullscreenLauncher = launcher;
        initializePlayer();
    }

    /**
     * Initializes the ExoPlayer instance for recorded video playback and sets up listeners.
     */
    private void initializePlayer() {
        player = new ExoPlayer.Builder(context).build();
        playerView.setPlayer(player);
        player.addListener(createPlayerListener());
        playerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) openFullscreenActivity();
        });
    }

    /**
     * Starts or restarts playback for the given recorded video item.
     * @param videoItem The VideoItem containing the URL to play.
     */
    public void startPlayback(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null) {
            Log.e(TAG, "Invalid VideoItem for playback.");
            return;
        }

        Log.d(TAG, "Starting recorded video playback: " + videoItem.getFilename());
        playerView.setVisibility(View.VISIBLE);
        tvRecordedVideoTitle.setVisibility(View.VISIBLE);

        try {
            Uri videoUri = Uri.parse(videoItem.getUrl());
            MediaItem mediaItem = MediaItem.fromUri(videoUri);

            if (player == null) {
                initializePlayer();
            }

            player.stop();
            player.clearMediaItems();
            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();
        } catch (Exception e) {
            Log.e(TAG, "Error preparing recorded video: " + videoItem.getUrl(), e);
            Toast.makeText(context, "Failed to play video", Toast.LENGTH_SHORT).show();
            playerView.setVisibility(View.GONE);
            tvRecordedVideoTitle.setVisibility(View.GONE);
        }
    }

    /**
     * Initiates the transition to the FullscreenVideoActivity for the current recorded video.
     */
    private void openFullscreenActivity() {
        if (player == null || player.getCurrentMediaItem() == null) {
            Toast.makeText(context, "No active video for fullscreen mode", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri videoUri = player.getCurrentMediaItem().localConfiguration != null ? player.getCurrentMediaItem().localConfiguration.uri : null;
        if (videoUri == null) {
            try {
                videoUri = Uri.parse(player.getCurrentMediaItem().mediaId);
            } catch (Exception e) {
                Log.e(TAG, "Could not get video URI for fullscreen");
                Toast.makeText(context, "Could not get video URI", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        long currentPosition = player.getCurrentPosition();
        boolean playWhenReady = player.getPlayWhenReady();
        player.pause();

        Intent intent = new Intent(context, FullscreenVideoActivity.class);
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_URI, videoUri.toString());
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, currentPosition);
        intent.putExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, playWhenReady);
        fullscreenLauncher.launch(intent);
    }

    /**
     * Handles the result returned from FullscreenVideoActivity when exiting fullscreen mode.
     * Resumes playback at the correct position.
     * @param result The ActivityResult object containing return data.
     */
    public void handleFullscreenResult(ActivityResult result) {
        if (player == null) {
            Log.w(TAG, "Player is null upon returning from fullscreen.");
            return;
        }

        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            long returnedPosition = result.getData().getLongExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, C.TIME_UNSET);
            boolean playWhenReady = result.getData().getBooleanExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, true);
            if (returnedPosition != C.TIME_UNSET) {
                Log.d(TAG, "VideoPlaybackHandler: Resuming position: " + returnedPosition + ", playWhenReady: " + playWhenReady);
                player.seekTo(returnedPosition);
                player.setPlayWhenReady(playWhenReady);
                if (playWhenReady) player.play();
            } else {
                Log.w(TAG, "VideoPlaybackHandler: Invalid position returned, resuming playback.");
                player.play();
            }
        } else {
            Log.d(TAG, "VideoPlaybackHandler: Fullscreen cancelled or error, resuming playback.");
            player.play();
        }

        playerView.setPlayer(null);
        playerView.setPlayer(player);
    }

    /**
     * Returns the ExoPlayer instance used for recorded video playback.
     * @return The ExoPlayer instance.
     */
    public ExoPlayer getPlayer() {
        return player;
    }

    /**
     * Pauses the recorded video playback.
     */
    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    /**
     * Releases the ExoPlayer instance used for recorded video playback.
     */
    public void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /**
     * Creates and returns a Player.Listener for handling recorded video player events.
     * @return A configured Player.Listener instance.
     */
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
                Log.e(TAG, "Recorded video playback error: " + error.getMessage());
                Toast.makeText(context, "Recorded video playback error", Toast.LENGTH_SHORT).show();
                playerView.setVisibility(View.GONE);
                tvRecordedVideoTitle.setVisibility(View.GONE);
            }
        };
    }
}