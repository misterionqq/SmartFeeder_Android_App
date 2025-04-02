package com.example.smartfeederapp;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class VideoListHandler {

    private static final String TAG = "VideoListHandler";

    private final Context context;
    private final ApiClient apiClient;
    private final VideoAdapter videoAdapter;
    private final ProgressBar progressBar;
    private final RecyclerView rvVideoList;

    public VideoListHandler(Context context, ApiClient apiClient, RecyclerView rv, VideoAdapter adapter, ProgressBar pb) {
        this.context = context;
        this.apiClient = apiClient;
        this.rvVideoList = rv;
        this.videoAdapter = adapter;
        this.progressBar = pb;
        setupRecyclerView();
    }

    private void setupRecyclerView() {
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setInitialPrefetchItemCount(4);
        rvVideoList.setLayoutManager(layoutManager);
        rvVideoList.setNestedScrollingEnabled(false);
        rvVideoList.setAdapter(videoAdapter);
    }

    public void loadVideos() {
        ApiService service = apiClient.getApiService();
        if (service == null) {
            Log.w(TAG, "ApiService не доступен, не могу загрузить видео.");
            Toast.makeText(context, "Не удалось подключиться к API. Проверьте адрес сервера.", Toast.LENGTH_SHORT).show();
            return;
        }

        showProgress(true);
        Log.d(TAG, "Запрос списка видео...");

        service.getVideos().enqueue(new Callback<List<VideoItem>>() {
            @Override
            public void onResponse(@NonNull Call<List<VideoItem>> call, @NonNull Response<List<VideoItem>> response) {
                showProgress(false);
                if (response.isSuccessful() && response.body() != null) {
                    List<VideoItem> videos = response.body();
                    videoAdapter.setVideoList(videos);
                    if (videos.isEmpty()) {
                        Toast.makeText(context, "Список видео пуст", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Загружено " + videos.size() + " видео", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    handleApiError(response, "загрузки видео");
                }
            }

            @Override
            public void onFailure(@NonNull Call<List<VideoItem>> call, @NonNull Throwable t) {
                showProgress(false);
                Log.e(TAG, "Сетевая ошибка загрузки видео", t);
                Toast.makeText(context, "Ошибка сети: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void setVideoActionListener(VideoAdapter.OnVideoActionListener listener) {
        videoAdapter.setOnVideoActionListener(listener);
    }

    private void showProgress(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void handleApiError(Response<?> response, String operationDescription) {
        try {
            String errorBody = response.errorBody() != null ? response.errorBody().string() : "Нет тела ошибки";
            Log.e(TAG, "Ошибка " + operationDescription + ": " + response.code() + " - " + response.message() + " Body: " + errorBody);
            Toast.makeText(context, "Ошибка " + operationDescription + ": " + response.code(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Ошибка чтения тела ошибки (" + operationDescription + ")", e);
            Toast.makeText(context, "Ошибка " + operationDescription + ": " + response.code(), Toast.LENGTH_SHORT).show();
        }
    }
}