package com.example.smartfeederapp;

import java.util.List;
import retrofit2.Call;
import retrofit2.http.GET;

public interface ApiService {
    @GET("videos")
    Call<List<VideoItem>> getVideos();

    @GET("feeders")
    Call<List<String>> getFeeders();
}