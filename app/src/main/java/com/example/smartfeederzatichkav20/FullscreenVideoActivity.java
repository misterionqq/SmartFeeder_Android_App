package com.example.smartfeederzatichkav20;

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

@UnstableApi public class FullscreenVideoActivity extends AppCompatActivity {

    private PlayerView playerView;
    private ExoPlayer player;
    private Uri videoUri;
    private long startPosition = 0;
    private boolean startPlayWhenReady = true;

    public static final String EXTRA_VIDEO_URI = "extra_video_uri";
    public static final String EXTRA_VIDEO_POSITION = "extra_video_position";
    public static final String EXTRA_PLAY_WHEN_READY = "extra_play_when_ready";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_video);

        playerView = findViewById(R.id.fullscreenPlayerView);

        // Получаем данные из Intent
        if (getIntent() != null) {
            String uriString = getIntent().getStringExtra(EXTRA_VIDEO_URI);
            if (uriString != null) {
                videoUri = Uri.parse(uriString);
            }
            startPosition = getIntent().getLongExtra(EXTRA_VIDEO_POSITION, 0);
            startPlayWhenReady = getIntent().getBooleanExtra(EXTRA_PLAY_WHEN_READY, true);
        }

        // Скрываем системные панели для полноэкранного режима
        hideSystemUi();

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

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
                // Пользователь нажал стандартную кнопку для ВЫХОДА
                finishActivityWithResult();
            }
        });
    }
    private void releasePlayer() {
        if (player != null) {
            // Сохраняем текущую позицию и состояние для возможного возврата
            startPosition = player.getCurrentPosition();
            startPlayWhenReady = player.getPlayWhenReady();
            player.release();
            player = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Util.SDK_INT > 23) {
            initializePlayer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (Util.SDK_INT <= 23 || player == null) {
            initializePlayer();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Util.SDK_INT <= 23) {
            releasePlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Util.SDK_INT > 23) {
            releasePlayer();
        }
    }

    // Метод для скрытия системных панелей (статус-бар, навигация)
    private void hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    // Обработка нажатия кнопки "Назад" - просто закрываем Activity
    @Override
    public void onBackPressed() {
        finishActivityWithResult();
        super.onBackPressed();
    }

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
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}