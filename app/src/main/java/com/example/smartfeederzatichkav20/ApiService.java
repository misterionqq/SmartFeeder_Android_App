// Файл: ApiService.java
package com.example.smartfeederzatichkav20;

import java.util.List;
import retrofit2.Call;
import retrofit2.http.GET;

public interface ApiService {
    /**
     * Метод для получения списка видео с сервера
     * @return Список объектов VideoItem
     */
    @GET("videos")
    Call<List<VideoItem>> getVideos();

    /**
     * Метод для получения списка активных кормушек
     * @return Список строковых идентификаторов кормушек
     */
    @GET("feeders") // Указываем путь к API на сервере
    Call<List<String>> getFeeders(); // Ожидаем список строк
}