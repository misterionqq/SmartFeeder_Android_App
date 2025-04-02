package com.example.smartfeederapp;

import android.app.Activity;
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

    private void initializePlayer() {
        streamPlayer = new ExoPlayer.Builder(context).build();
        streamPlayerView.setPlayer(streamPlayer);
        streamPlayer.addListener(createStreamPlayerListener());
        streamPlayerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) openFullscreenActivity();
        });
        btnStopStream.setOnClickListener(v -> stopStream());
    }

    public void requestAndStartStream(String feederId) {
        if (feederId == null || feederId.isEmpty()) {
            Toast.makeText(context, "Не выбран ID кормушки", Toast.LENGTH_SHORT).show();
            return;
        }

        String clientId = settingsManager.getClientId();
        if (!connectionManager.isConnected() || clientId == null) {
            Toast.makeText(context, "Нет подключения к серверу или ID клиента. Проверьте настройки.", Toast.LENGTH_LONG).show();
            return;
        }

        if (currentStreamingFeederId != null && !currentStreamingFeederId.equals(feederId)) {
            Log.d(TAG, "Запрошен стрим с другой кормушки, останавливаем предыдущий (" + currentStreamingFeederId + ")");
            stopLocalPlaybackAndHideUI();
        }

        showProgress(true);
        Toast.makeText(context, "Запрос трансляции для " + feederId, Toast.LENGTH_SHORT).show();

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
                Log.e(TAG, "Ошибка запроса стрима: " + message);
                Toast.makeText(context, "Ошибка запроса стрима: " + message, Toast.LENGTH_LONG).show();
                currentStreamingFeederId = null;
            }
        });
    }

    private void startStreamPlayback(String streamPath) {
        String serverAddress = settingsManager.getServerAddress();
        if (serverAddress == null) {
            Toast.makeText(context, "Адрес сервера не найден", Toast.LENGTH_SHORT).show();
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
            Log.d(TAG, "Подключение к RTMP стриму: " + rtmpUrl);

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
            Toast.makeText(context, "Трансляция запущена", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Ошибка запуска воспроизведения стрима: " + e.getMessage(), e);
            Toast.makeText(context, "Ошибка запуска стрима: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            hideStreamUI();
        }
    }

    public void stopStream() {
        String feederIdToStop = currentStreamingFeederId;

        if (feederIdToStop == null) {
            Log.w(TAG, "Попытка остановить стрим, но ID текущей кормушки не известен.");
            stopLocalPlaybackAndHideUI();
            disconnectIfConnected();
            return;
        }

        if (!connectionManager.isConnected()) {
            Toast.makeText(context, "Нет подключения для остановки стрима", Toast.LENGTH_SHORT).show();
            stopLocalPlaybackAndHideUI();
            return;
        }

        showProgress(true);
        Log.d(TAG, "Запрос на остановку стрима для: " + feederIdToStop);

        connectionManager.stopStream(feederIdToStop, new ConnectionManager.SimpleCallback() {
            @Override
            public void onSuccess() {
                showProgress(false);
                stopLocalPlaybackAndHideUI();
                Toast.makeText(context, "Трансляция остановлена", Toast.LENGTH_SHORT).show();
                disconnectIfConnected();
            }

            @Override
            public void onError(String message) {
                showProgress(false);
                Log.e(TAG, "Ошибка остановки стрима сервером: " + message);
                Toast.makeText(context, "Ошибка остановки стрима: " + message, Toast.LENGTH_LONG).show();
                stopLocalPlaybackAndHideUI();
                disconnectIfConnected();
            }
        });
    }

    public void handleServerStreamStop(String stoppedFeederId) {
        if (stoppedFeederId != null && stoppedFeederId.equals(currentStreamingFeederId)) {
            Log.i(TAG, "Принудительно останавливаем текущий стрим по сигналу сервера.");
            Toast.makeText(context, "Стрим остановлен сервером", Toast.LENGTH_SHORT).show();
            stopLocalPlaybackAndHideUI();
        } else {
            Log.d(TAG, "Событие остановки от сервера для " + stoppedFeederId + " не совпадает с текущим стримом (" + currentStreamingFeederId + "), игнорируем.");
        }
    }


    private void stopLocalPlaybackAndHideUI() {
        if (streamPlayer != null) {
            streamPlayer.stop();
            streamPlayer.clearMediaItems();
        }
        hideStreamUI();
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
        currentStreamPath = null;
    }

    private void disconnectIfConnected() {
        if (connectionManager.isConnected()) {
            Log.d(TAG, "Отключаемся от сервера после операции со стримом");
            connectionManager.disconnect();
        }
    }

    private void openFullscreenActivity() {
        if (streamPlayer == null || streamPlayer.getCurrentMediaItem() == null) {
            Toast.makeText(context, "Нет активного стрима для полноэкранного режима", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri videoUri = streamPlayer.getCurrentMediaItem().localConfiguration != null ? streamPlayer.getCurrentMediaItem().localConfiguration.uri : null;
        if (videoUri == null) {
            MediaItem item = streamPlayer.getCurrentMediaItem();
            if (item != null && item.mediaId != null && item.mediaId.startsWith("rtmp")) {
                videoUri = Uri.parse(item.mediaId);
            } else {
                Toast.makeText(context, "Не удалось получить URI стрима", Toast.LENGTH_SHORT).show();
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

    public void handleFullscreenResult(ActivityResult result) {
        Log.d(TAG, "StreamPlaybackHandler: Fullscreen завершен, возобновляем стрим.");
        streamPlayer.play();
        streamPlayerView.setPlayer(null);
        streamPlayerView.setPlayer(streamPlayer);
    }

    public ExoPlayer getPlayer() {
        return streamPlayer;
    }

    public void pause() {
        if (streamPlayer != null) {
            streamPlayer.pause();
        }
    }

    public void releasePlayer() {
        if (streamPlayer != null) {
            streamPlayer.release();
            streamPlayer = null;
        }
    }

    private Player.Listener createStreamPlayerListener() {
        return new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Ошибка воспроизведения стрима: " + error.getMessage(), error);
                Toast.makeText(context, "Ошибка воспроизведения стрима", Toast.LENGTH_SHORT).show();
                hideStreamUI();
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                Log.d(TAG, "Stream Player state changed: " + state);
                if (state == Player.STATE_BUFFERING) {
                    Log.d(TAG, "Буферизация стрима...");
                } else if (state == Player.STATE_ENDED) {
                    Log.d(TAG, "Воспроизведение стрима завершено (или поток прерван)");
                    Toast.makeText(context, "Стрим завершен", Toast.LENGTH_SHORT).show();
                    hideStreamUI();
                } else if (state == Player.STATE_READY) {
                    Log.d(TAG, "Стрим готов к воспроизведению.");
                }
            }
        };
    }

    private void showProgress(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    public String getCurrentStreamingFeederId() {
        return currentStreamingFeederId;
    }
}