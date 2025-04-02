package com.example.smartfeederapp;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;

/**
 * Retrofit service interface defining API endpoints.
 */
public interface ApiService {
    /**
     * Retrieves a list of available videos from the server.
     *
     * @return A Call object for the list of VideoItem.
     */
    @GET("videos")
    Call<List<VideoItem>> getVideos();

    /**
     * Retrieves a list of currently active feeders from the server.
     *
     * @return A Call object for a list of feeder ID strings.
     */
    @GET("feeders")
    Call<List<String>> getFeeders();
}