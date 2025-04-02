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

public class VideoPlaybackHandler {

    private static final String TAG = "VideoPlaybackHandler";

    private final Context context;
    private final PlayerView playerView;
    private final TextView tvRecordedVideoTitle;
    private final ActivityResultLauncher<Intent> fullscreenLauncher;

    private ExoPlayer player;

    public VideoPlaybackHandler(Context context, PlayerView pv, TextView titleTv, ActivityResultLauncher<Intent> launcher) {
        this.context = context;
        this.playerView = pv;
        this.tvRecordedVideoTitle = titleTv;
        this.fullscreenLauncher = launcher;
        initializePlayer();
    }

    private void initializePlayer() {
        player = new ExoPlayer.Builder(context).build();
        playerView.setPlayer(player);
        player.addListener(createPlayerListener());
        playerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) openFullscreenActivity();
        });
    }

    public void startPlayback(VideoItem videoItem) {
        if (videoItem == null || videoItem.getUrl() == null) {
            Log.e(TAG, "Некорректный VideoItem для воспроизведения.");
            return;
        }

        Log.d(TAG, "Начало воспроизведения записи: " + videoItem.getFilename());
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
            Log.e(TAG, "Ошибка подготовки видеозаписи: " + videoItem.getUrl(), e);
            Toast.makeText(context, "Не удалось воспроизвести видео", Toast.LENGTH_SHORT).show();
            playerView.setVisibility(View.GONE);
            tvRecordedVideoTitle.setVisibility(View.GONE);
        }
    }

    private void openFullscreenActivity() {
        if (player == null || player.getCurrentMediaItem() == null) {
            Toast.makeText(context, "Нет активного видео для полноэкранного режима", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri videoUri = player.getCurrentMediaItem().localConfiguration != null ? player.getCurrentMediaItem().localConfiguration.uri : null;
        if (videoUri == null) {
            Toast.makeText(context, "Не удалось получить URI видео", Toast.LENGTH_SHORT).show();
            return;
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

    public void handleFullscreenResult(ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            long returnedPosition = result.getData().getLongExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, C.TIME_UNSET);
            boolean playWhenReady = result.getData().getBooleanExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, true);
            if (returnedPosition != C.TIME_UNSET) {
                Log.d(TAG, "VideoPlaybackHandler: Восстанавливаем позицию: " + returnedPosition + ", playWhenReady: " + playWhenReady);
                player.seekTo(returnedPosition);
                player.setPlayWhenReady(playWhenReady);
                if (playWhenReady) player.play();
            } else {
                Log.w(TAG, "VideoPlaybackHandler: Некорректная позиция возвращена, просто возобновляем.");
                player.play();
            }
        } else {
            Log.d(TAG, "VideoPlaybackHandler: Fullscreen отменен или ошибка, просто возобновляем.");
            player.play();
        }
        playerView.setPlayer(null);
        playerView.setPlayer(player);
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
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
                Toast.makeText(context, "Ошибка воспроизведения записи", Toast.LENGTH_SHORT).show();
                playerView.setVisibility(View.GONE);
                tvRecordedVideoTitle.setVisibility(View.GONE);
            }
        };
    }
}