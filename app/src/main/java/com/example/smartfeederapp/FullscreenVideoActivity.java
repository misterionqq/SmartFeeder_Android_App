package com.example.smartfeederapp;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Activity for displaying video playback in fullscreen landscape mode.
 * Receives video URI, start position, and playback state via Intent extras.
 * Returns the last playback position and state when finished.
 */
@UnstableApi
public class FullscreenVideoActivity extends AppCompatActivity {

    private PlayerView playerView;
    private ExoPlayer player;
    private Uri videoUri;
    private long startPosition = 0;
    private boolean startPlayWhenReady = true;

    public static final String EXTRA_VIDEO_URI = "extra_video_uri";
    public static final String EXTRA_VIDEO_POSITION = "extra_video_position";
    public static final String EXTRA_PLAY_WHEN_READY = "extra_play_when_ready";

    /**
     * Called when the activity is first created.
     * Sets up the layout, retrieves data from the intent, hides system UI,
     * and sets the orientation to landscape.
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_video);

        playerView = findViewById(R.id.fullscreenPlayerView);

        if (getIntent() != null) {
            String uriString = getIntent().getStringExtra(EXTRA_VIDEO_URI);
            if (uriString != null) {
                videoUri = Uri.parse(uriString);
            }
            startPosition = getIntent().getLongExtra(EXTRA_VIDEO_POSITION, 0);
            startPlayWhenReady = getIntent().getBooleanExtra(EXTRA_PLAY_WHEN_READY, true);
        }

        hideSystemUi();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /**
     * Initializes the ExoPlayer instance, sets up the PlayerView,
     * prepares the media item, and seeks to the starting position.
     */
    private void initializePlayer() {
        if (videoUri == null) {
            finish();
            return;
        }

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        MediaItem mediaItem = MediaItem.fromUri(videoUri);
        player.setMediaItem(mediaItem);
        player.seekTo(startPosition);
        player.setPlayWhenReady(startPlayWhenReady);
        player.prepare();

        playerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (!isFullscreen) {
                finishActivityWithResult();
            }
        });
    }

    /**
     * Releases the ExoPlayer instance to free up resources.
     * Saves the current playback position and state before releasing.
     */
    private void releasePlayer() {
        if (player != null) {
            startPosition = player.getCurrentPosition();
            startPlayWhenReady = player.getPlayWhenReady();
            player.release();
            player = null;
        }
    }

    /**
     * Initializes the player when the activity becomes visible (for API > 23).
     */
    @Override
    protected void onStart() {
        super.onStart();
        if (Util.SDK_INT > 23) {
            initializePlayer();
        }
    }

    /**
     * Initializes or reinitializes the player when the activity comes to the foreground.
     * Also ensures system UI remains hidden.
     */
    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (Util.SDK_INT <= 23 || player == null) {
            initializePlayer();
        }
    }

    /**
     * Releases the player when the activity is no longer visible (for API <= 23).
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (Util.SDK_INT <= 23) {
            releasePlayer();
        }
    }

    /**
     * Releases the player when the activity is stopped (for API > 23).
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (Util.SDK_INT > 23) {
            releasePlayer();
        }
    }

    /**
     * Configures the window for immersive fullscreen mode, hiding system bars.
     */
    private void hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    /**
     * Handles the back button press by finishing the activity with the result.
     */
    @Override
    public void onBackPressed() {
        finishActivityWithResult();
        super.onBackPressed();
    }

    /**
     * Prepares the result Intent with the current playback position and state,
     * sets the result code to RESULT_OK, and finishes the activity.
     */
    private void finishActivityWithResult() {
        Intent resultIntent = new Intent();
        long position = startPosition;
        boolean playWhenReady = this.startPlayWhenReady;

        if (player != null) {
            position = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
        }

        resultIntent.putExtra(EXTRA_VIDEO_POSITION, position);
        resultIntent.putExtra(EXTRA_PLAY_WHEN_READY, playWhenReady);
        setResult(Activity.RESULT_OK, resultIntent);
        finish();
    }
}