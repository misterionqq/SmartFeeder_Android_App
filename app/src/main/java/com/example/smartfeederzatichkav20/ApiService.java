package com.example.smartfeederzatichkav20;

import java.util.List;
import retrofit2.Call;
import retrofit2.http.GET;

/**
 * Интерфейс для API-запросов с помощью Retrofit
 */
public interface ApiService {
    /**
     * Метод для получения списка видео с сервера
     * @return Список объектов VideoItem
     */
    @GET("videos")
    Call<List<VideoItem>> getVideos();
} 