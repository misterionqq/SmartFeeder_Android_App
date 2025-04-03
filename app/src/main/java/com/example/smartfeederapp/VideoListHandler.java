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

/**
 * Handles loading the list of recorded videos from the API
 * and managing the RecyclerView and its adapter.
 */
public class VideoListHandler {

    private static final String TAG = "VideoListHandler";

    private final Context context;
    private final ApiClient apiClient;
    private final VideoAdapter videoAdapter;
    private final ProgressBar progressBar;
    private final RecyclerView rvVideoList;

    /**
     * Constructor for VideoListHandler.
     * @param context Context for displaying Toasts.
     * @param apiClient ApiClient instance for making network requests.
     * @param rv RecyclerView instance to display the video list.
     * @param adapter VideoAdapter instance associated with the RecyclerView.
     * @param pb ProgressBar instance to show loading state.
     */
    public VideoListHandler(Context context, ApiClient apiClient, RecyclerView rv, VideoAdapter adapter, ProgressBar pb) {
        this.context = context;
        this.apiClient = apiClient;
        this.rvVideoList = rv;
        this.videoAdapter = adapter;
        this.progressBar = pb;
        setupRecyclerView();
    }

    /**
     * Sets up the RecyclerView with a LinearLayoutManager and the VideoAdapter.
     */
    private void setupRecyclerView() {
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setInitialPrefetchItemCount(4);
        rvVideoList.setLayoutManager(layoutManager);
        rvVideoList.setNestedScrollingEnabled(false);
        rvVideoList.setAdapter(videoAdapter);
    }

    /**
     * Fetches the list of videos from the server using the ApiClient
     * and updates the VideoAdapter upon successful retrieval. Checks API availability.
     */
    public void loadVideos() {
        ApiService service = apiClient.getApiService();
        if (service == null) {
            Log.w(TAG, "ApiService not available, cannot load videos.");
            Toast.makeText(context, "API not available. Check server address in Settings.", Toast.LENGTH_SHORT).show();
            return;
        }

        showProgress(true);
        Log.d(TAG, "Requesting video list...");

        service.getVideos().enqueue(new Callback<List<VideoItem>>() {
            @Override
            public void onResponse(@NonNull Call<List<VideoItem>> call, @NonNull Response<List<VideoItem>> response) {
                showProgress(false);
                if (response.isSuccessful() && response.body() != null) {
                    List<VideoItem> videos = response.body();
                    videoAdapter.setVideoList(videos);
                    if (videos.isEmpty()) {
                        Toast.makeText(context, "Video list is empty", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Loaded " + videos.size() + " videos", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    handleApiError(response, "loading videos");
                }
            }

            @Override
            public void onFailure(@NonNull Call<List<VideoItem>> call, @NonNull Throwable t) {
                showProgress(false);
                Log.e(TAG, "Network error loading videos", t);
                Toast.makeText(context, "Network error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Sets the listener for actions performed on video items within the RecyclerView.
     * @param listener The listener implementing VideoAdapter.OnVideoActionListener.
     */
    public void setVideoActionListener(VideoAdapter.OnVideoActionListener listener) {
        videoAdapter.setOnVideoActionListener(listener);
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
     * Handles API errors by logging details and showing a generic error Toast.
     * @param response The Retrofit response object.
     * @param operationDescription Description of the failed operation for logging.
     */
    private void handleApiError(Response<?> response, String operationDescription) {
        try {
            String errorBody = response.errorBody() != null ? response.errorBody().string() : "No error body";
            Log.e(TAG, "Error " + operationDescription + ": " + response.code() + " - " + response.message() + " Body: " + errorBody);
            Toast.makeText(context, "Error " + operationDescription + ": " + response.code(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error reading error body (" + operationDescription + ")", e);
            Toast.makeText(context, "Error " + operationDescription + ": " + response.code(), Toast.LENGTH_SHORT).show();
        }
    }
}