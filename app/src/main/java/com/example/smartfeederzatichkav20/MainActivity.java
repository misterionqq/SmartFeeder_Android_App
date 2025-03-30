package com.example.smartfeederzatichkav20;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends AppCompatActivity implements VideoAdapter.OnVideoClickListener {

    private static final String TAG = "MainActivity";
    private static final String DEFAULT_SERVER_ADDRESS = "192.168.2.41:5000";
    private static final String DEFAULT_FEEDER_ID = "750cec99-5311-4055-8da0-2aad1e531d6c";

    private TextInputEditText etServerAddress;
    private TextInputEditText etFeederId;
    private TextInputEditText etClientId;
    private Button btnLoadVideos;
    private Button btnGetClientId;
    private Button btnConnectSocket;
    private Button btnDisconnectSocket;
    private Button btnRequestStream;
    private Button btnStopStream;
    private TextView tvStreamTitle;
    private PlayerView streamPlayerView;
    private RecyclerView rvVideoList;
    private ProgressBar progressBar;
    private PlayerView playerView;

    private VideoAdapter videoAdapter;
    private ExoPlayer player;
    private ExoPlayer streamPlayer;
    private ApiService apiService;
    private Socket socket;
    private String streamPath;

    private ExoPlayer activePlayerForFullscreen = null;

    private final ActivityResultLauncher<Intent> fullscreenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleFullscreenResult // Ссылка на метод обработки результата
    );

    private void handleFullscreenResult(ActivityResult result) {
        if (activePlayerForFullscreen == null) {
            Log.w(TAG, "handleFullscreenResult: No active player was tracked.");
            return; // Нечего делать, если не знаем, какой плеер возвращается
        }

        // Определяем, какой PlayerView был активен
        PlayerView relevantPlayerView = (activePlayerForFullscreen == player) ? playerView : streamPlayerView;

        // Проверяем, вернулся ли результат успешно и есть ли данные
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            long returnedPosition = result.getData().getLongExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, C.TIME_UNSET);
            boolean playWhenReady = result.getData().getBooleanExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, true);

            // Если вернулась валидная позиция
            if (returnedPosition != C.TIME_UNSET) {
                Log.d(TAG, "Resuming playback at position: " + returnedPosition + " playWhenReady: " + playWhenReady);
                activePlayerForFullscreen.seekTo(returnedPosition);
                activePlayerForFullscreen.setPlayWhenReady(playWhenReady);
                // Если нужно гарантированно начать играть, если playWhenReady = true
                if (playWhenReady) {
                    activePlayerForFullscreen.play(); // Начинаем играть, если нужно
                } else {
                    // Подготовка может быть нужна, если плеер не готов играть сразу после seekTo
                    // activePlayerForFullscreen.prepare();
                }
            } else {
                // Позиция не вернулась, просто продолжаем играть с того места, где остановились
                Log.w(TAG, "Returned position is TIME_UNSET, resuming from previous state.");
                activePlayerForFullscreen.play(); // Просто возобновляем
            }
        } else {
            // Пользователь вышел, не сохранив позицию, или произошла ошибка.
            // Просто возобновляем воспроизведение.
            Log.d(TAG, "Fullscreen cancelled or failed, resuming playback.");
            activePlayerForFullscreen.play(); // Просто возобновляем
        }

        // *** КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: Обновляем состояние PlayerView ***
        if (relevantPlayerView != null) {
            // Временно отсоединяем плеер
            relevantPlayerView.setPlayer(null);
            // Снова присоединяем тот же плеер. Это заставит PlayerView
            // перерисовать контроллер и обновить состояние кнопки fullscreen.
            relevantPlayerView.setPlayer(activePlayerForFullscreen);
        }
        // ***********************************************************

        activePlayerForFullscreen = null; // Сбрасываем трекер
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Инициализация представлений
        etServerAddress = findViewById(R.id.etServerAddress);
        etServerAddress.setText(DEFAULT_SERVER_ADDRESS); // Устанавливаем значение по умолчанию
        
        etFeederId = findViewById(R.id.etFeederId);
        etFeederId.setText(DEFAULT_FEEDER_ID); // Устанавливаем значение по умолчанию
        
        etClientId = findViewById(R.id.etClientId);
        btnLoadVideos = findViewById(R.id.btnLoadVideos);
        btnGetClientId = findViewById(R.id.btnGetClientId);
        btnConnectSocket = findViewById(R.id.btnConnectSocket);
        btnDisconnectSocket = findViewById(R.id.btnDisconnectSocket);
        btnRequestStream = findViewById(R.id.btnRequestStream);
        btnStopStream = findViewById(R.id.btnStopStream);
        tvStreamTitle = findViewById(R.id.tvStreamTitle);
        streamPlayerView = findViewById(R.id.streamPlayerView);
        rvVideoList = findViewById(R.id.rvVideoList);
        progressBar = findViewById(R.id.progressBar);
        playerView = findViewById(R.id.playerView);

        // Настройка RecyclerView
        videoAdapter = new VideoAdapter();
        videoAdapter.setOnVideoClickListener(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setInitialPrefetchItemCount(4); // Предзагрузка элементов для плавности
        rvVideoList.setLayoutManager(layoutManager);
        rvVideoList.setNestedScrollingEnabled(false); // Отключаем вложенную прокрутку
        rvVideoList.setAdapter(videoAdapter);

        // Инициализация ExoPlayer для видеозаписей
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        // Инициализация ExoPlayer для стрима
        streamPlayer = new ExoPlayer.Builder(this).build();
        streamPlayerView.setPlayer(streamPlayer);

        // Настройка обработчика ошибок для плеера записей
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d("MainActivity", "Playback state changed: " + playbackState); // ДОБАВИТЬ ЛОГИРОВАНИЕ
                if (playbackState == Player.STATE_READY) {
                    Log.d("MainActivity", "Player is READY"); // ДОБАВИТЬ ЛОГИРОВАНИЕ
                }
            }
            // ... (остальные методы слушателя)
        });

        // Настройка обработчика ошибок для плеера стрима
        streamPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                Log.e(TAG, "Ошибка воспроизведения стрима: " + error.getMessage());
                Toast.makeText(MainActivity.this, "Ошибка воспроизведения стрима", Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onPlaybackStateChanged(int state) {
                // Обработка изменений состояния воспроизведения
                if (state == Player.STATE_BUFFERING) {
                    Log.d(TAG, "Буферизация стрима...");
                }
            }
        });

        // Обработчики нажатий на кнопки
        btnLoadVideos.setOnClickListener(v -> loadVideos());
        btnGetClientId.setOnClickListener(v -> getClientIdFromServer());
        btnConnectSocket.setOnClickListener(v -> connectToSocketServer());
        btnDisconnectSocket.setOnClickListener(v -> disconnectFromSocketServer());
        btnRequestStream.setOnClickListener(v -> requestStream());
        btnStopStream.setOnClickListener(v -> stopStream());

        playerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) {
                openFullscreenActivity(player); // Передаем текущий плеер для получения данных
            }
        });

        streamPlayerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) {
                openFullscreenActivity(streamPlayer); // Передаем плеер стрима
            }
        });
    }

    @OptIn(markerClass = UnstableApi.class) // Добавь эту аннотацию
    private void openFullscreenActivity(ExoPlayer currentPlayer) {
        if (currentPlayer == null || currentPlayer.getCurrentMediaItem() == null) {
            Toast.makeText(this, "Нет активного видео для полноэкранного режима", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri videoUri = currentPlayer.getCurrentMediaItem().localConfiguration != null
                ? currentPlayer.getCurrentMediaItem().localConfiguration.uri
                : null; // Получаем URI текущего элемента

        if (videoUri == null) {
            Toast.makeText(this, "Не удалось получить URI видео", Toast.LENGTH_SHORT).show();
            return;
        }

        long currentPosition = currentPlayer.getCurrentPosition();
        boolean playWhenReady = currentPlayer.getPlayWhenReady();

        // Паузим плеер в MainActivity перед переходом
        currentPlayer.pause();

        activePlayerForFullscreen = currentPlayer; // Запоминаем, какой плеер ушел

        Intent intent = new Intent(this, FullscreenVideoActivity.class);
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_URI, videoUri.toString());
        intent.putExtra(FullscreenVideoActivity.EXTRA_VIDEO_POSITION, currentPosition);
        intent.putExtra(FullscreenVideoActivity.EXTRA_PLAY_WHEN_READY, playWhenReady);

        fullscreenLauncher.launch(intent); // Используем лаунчер для запуска и получения результата
    }

    /**
     * Загружает список видео с сервера
     */
    private void loadVideos() {
        String serverAddress = etServerAddress.getText().toString().trim();
        String feederId = etFeederId.getText().toString().trim();

        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show();
            return;
        }

        // Показать индикатор загрузки
        progressBar.setVisibility(View.VISIBLE);

        // Создание Retrofit клиента
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://" + serverAddress + "/")
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(ApiService.class);

        // Выполнение запроса
        apiService.getVideos().enqueue(new Callback<List<VideoItem>>() {
            @Override
            public void onResponse(Call<List<VideoItem>> call, Response<List<VideoItem>> response) {
                progressBar.setVisibility(View.GONE);
                if (response.isSuccessful() && response.body() != null) {
                    List<VideoItem> videos = response.body();
                    if (videos.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Список видео пуст", Toast.LENGTH_SHORT).show();
                    } else {
                        videoAdapter.setVideoList(videos);
                        Toast.makeText(MainActivity.this, "Загружено " + videos.size() + " видео", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Ошибка: " + response.code(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<VideoItem>> call, Throwable t) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "Ошибка: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Получает идентификатор клиента от сервера через Socket.IO
     */
    private void getClientIdFromServer() {
        String serverAddress = etServerAddress.getText().toString().trim();

        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Подключение к серверу...", Toast.LENGTH_SHORT).show();

        try {
            // Настройка Socket.IO
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("need id", "true");
            options.auth = auth;

            // Создание сокета
            if (socket != null) {
                socket.disconnect();
            }
            socket = IO.socket("http://" + serverAddress, options);

            // Настройка обработчиков событий
            socket.on(Socket.EVENT_CONNECT, onConnect);
            socket.on(Socket.EVENT_DISCONNECT, onDisconnect);
            socket.on(Socket.EVENT_CONNECT_ERROR, onConnectError);
            socket.on("assign id", onAssignId);

            // Подключение к серверу
            socket.connect();

        } catch (URISyntaxException e) {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Ошибка создания сокета", e);
        }
    }

    /**
     * Подключается к серверу через Socket.IO с существующим идентификатором клиента
     */
    private void connectToSocketServer() {
        String serverAddress = etServerAddress.getText().toString().trim();
        String clientId = etClientId.getText().toString().trim();

        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show();
            return;
        }

        if (clientId.isEmpty()) {
            Toast.makeText(this, "Сначала получите ID клиента", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Подключение к серверу...", Toast.LENGTH_SHORT).show();

        try {
            // Настройка Socket.IO
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("id", clientId);
            options.auth = auth;

            // Создание сокета
            if (socket != null) {
                socket.disconnect();
            }
            socket = IO.socket("http://" + serverAddress, options);

            // Настройка обработчиков событий
            socket.on(Socket.EVENT_CONNECT, args -> {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Подключено!", Toast.LENGTH_SHORT).show();
                    // Здесь можно добавить дополнительную логику после подключения
                });
            });
            socket.on(Socket.EVENT_DISCONNECT, onDisconnect);
            socket.on(Socket.EVENT_CONNECT_ERROR, onConnectError);

            // Подключение к серверу
            socket.connect();

        } catch (URISyntaxException e) {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Ошибка создания сокета", e);
        }
    }

    /**
     * Отключается от Socket.IO сервера
     */
    private void disconnectFromSocketServer() {
        if (socket != null && socket.connected()) {
            socket.disconnect();
            Toast.makeText(this, "Отключено от сервера", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Нет активного подключения", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Обработчик события подключения к серверу
     */
    private Emitter.Listener onConnect = args -> runOnUiThread(() -> {
        Log.d(TAG, "Подключено к Socket.IO серверу");
    });

    /**
     * Обработчик события отключения от сервера
     */
    private Emitter.Listener onDisconnect = args -> runOnUiThread(() -> {
        progressBar.setVisibility(View.GONE);
        Log.d(TAG, "Отключено от Socket.IO сервера");
    });

    /**
     * Обработчик ошибки подключения к серверу
     */
    private Emitter.Listener onConnectError = args -> runOnUiThread(() -> {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(MainActivity.this, "Ошибка подключения", Toast.LENGTH_SHORT).show();
        Log.e(TAG, "Ошибка подключения к Socket.IO серверу: " + args[0]);
    });

    /**
     * Обработчик события получения идентификатора от сервера
     */
    private Emitter.Listener onAssignId = args -> runOnUiThread(() -> {
        progressBar.setVisibility(View.GONE);
        
        try {
            // Логирование аргументов для отладки
            if (args.length > 0) {
                Log.d(TAG, "Количество аргументов: " + args.length);
                for (int i = 0; i < args.length; i++) {
                    Log.d(TAG, "args[" + i + "]: " + (args[i] != null ? args[i].toString() : "null") + 
                          ", тип: " + (args[i] != null ? args[i].getClass().getName() : "null"));
                }
            }
            
            // Получаем данные из args[1], так как args[0] - это название события
            Object data = args.length > 1 ? args[1] : args[0];
            
            // Проверка типа данных и извлечение ID
            String clientId = null;
            
            if (data instanceof JSONObject) {
                // Если сервер отправил JSONObject
                JSONObject jsonData = (JSONObject) data;
                Log.d(TAG, "Получен JSONObject: " + jsonData.toString());
                if (jsonData.has("id")) {
                    clientId = jsonData.getString("id");
                    Log.d(TAG, "Извлечен ID из JSON: " + clientId);
                }
            } else if (data instanceof String) {
                // Попробуем распарсить строку как JSON
                try {
                    JSONObject jsonData = new JSONObject((String) data);
                    if (jsonData.has("id")) {
                        clientId = jsonData.getString("id");
                        Log.d(TAG, "Извлечен ID из строки JSON: " + clientId);
                    } else {
                        // Если не получилось найти "id" в JSON, используем строку как есть
                        clientId = (String) data;
                        Log.d(TAG, "Используем строку как ID: " + clientId);
                    }
                } catch (JSONException e) {
                    // Если не получилось распарсить как JSON, используем строку как есть
                    clientId = (String) data;
                    Log.d(TAG, "Используем строку как ID: " + clientId);
                }
            } else if (data != null) {
                // Если тип данных неизвестен, логируем и пробуем преобразовать в строку
                Log.d(TAG, "Неизвестный тип данных: " + data.getClass().getName());
                clientId = data.toString();
            }
            
            if (clientId != null) {
                etClientId.setText(clientId);
                // Делаем поле доступным для редактирования (ID клиента можно будет изменить)
                Toast.makeText(MainActivity.this, "Получен ID клиента: " + clientId, Toast.LENGTH_SHORT).show();
                
                // Показываем кнопку запроса стрима, если есть clientId
                btnRequestStream.setVisibility(View.VISIBLE);
            } else {
                Log.e(TAG, "Не удалось извлечь ID из данных");
                Toast.makeText(MainActivity.this, "Ошибка получения ID", Toast.LENGTH_SHORT).show();
            }
            
            // Отключение от сервера после получения ID
            if (socket != null) {
                socket.disconnect();
            }
            
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "Ошибка при получении ID: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Ошибка разбора ответа сервера", e);
            e.printStackTrace();
        }
    });

    /**
     * Запрашивает стрим от сервера
     */
    private void requestStream() {
        String serverAddress = etServerAddress.getText().toString().trim();
        String clientId = etClientId.getText().toString().trim();
        String feederId = etFeederId.getText().toString().trim();

        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show();
            return;
        }

        if (clientId.isEmpty()) {
            Toast.makeText(this, "Сначала получите ID клиента", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (feederId.isEmpty()) {
            Toast.makeText(this, "Введите идентификатор кормушки", Toast.LENGTH_SHORT).show();
            return;
        }
        
        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Запрос трансляции...", Toast.LENGTH_SHORT).show();

        try {
            // Настройка Socket.IO
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("id", clientId);
            options.auth = auth;

            // Создание сокета
            if (socket != null) {
                socket.disconnect();
            }
            socket = IO.socket("http://" + serverAddress, options);

            // Настройка обработчиков событий
            socket.on(Socket.EVENT_CONNECT, args -> {
                runOnUiThread(() -> {
                    Log.d(TAG, "Подключено к сокету для запроса стрима");
                    try {
                        // Отправляем запрос на стрим и получаем ответ в callback
                        JSONObject data = new JSONObject();
                        data.put("feeder_id", feederId);
                        
                        // Правильная форма emit с Ack (callback)
                        socket.emit("stream start", new Object[]{data}, args1 -> {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                
                                try {
                                    // Логируем ответ
                                    if (args1.length > 0) {
                                        Log.d(TAG, "Ответ на запрос стрима, аргументов: " + args1.length);
                                        for (int i = 0; i < args1.length; i++) {
                                            Log.d(TAG, "args[" + i + "]: " + (args1[i] != null ? args1[i].toString() : "null"));
                                        }
                                    }
                                    
                                    // Получаем данные из ответа
                                    Object responseData = args1.length > 0 ? args1[0] : null;
                                    Log.d(TAG, "responseData: " + responseData);
                                    
                                    if (responseData instanceof JSONObject) {
                                        JSONObject jsonData = (JSONObject) responseData;
                                        Log.d(TAG, "Получен ответ на запрос стрима: " + jsonData.toString());
                                        
                                        if (jsonData.has("path")) {
                                            streamPath = jsonData.getString("path");
                                            Log.d(TAG, "Получен путь к стриму: " + streamPath);
                                            
                                            // Запускаем стрим
                                            startStream(serverAddress, streamPath);
                                        } else {
                                            Log.e(TAG, "В ответе сервера отсутствует путь к стриму");
                                            Toast.makeText(MainActivity.this, "Ошибка: в ответе сервера отсутствует путь к стриму", Toast.LENGTH_SHORT).show();
                                        }
                                    } else {
                                        Log.e(TAG, "Неожиданный формат ответа на запрос стрима");
                                        Toast.makeText(MainActivity.this, "Ошибка: неожиданный формат ответа сервера", Toast.LENGTH_SHORT).show();
                                    }
                                    
                                } catch (Exception e) {
                                    Log.e(TAG, "Ошибка обработки ответа на запрос стрима", e);
                                    Toast.makeText(MainActivity.this, "Ошибка обработки ответа: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                        
                        Log.d(TAG, "Отправлен запрос на стрим: " + data.toString());
                    } catch (JSONException e) {
                        progressBar.setVisibility(View.GONE);
                        Log.e(TAG, "Ошибка создания JSON для запроса стрима", e);
                        Toast.makeText(MainActivity.this, "Ошибка запроса стрима: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            });
            
            socket.on(Socket.EVENT_DISCONNECT, onDisconnect);
            socket.on(Socket.EVENT_CONNECT_ERROR, onConnectError);
            
            // Подключение к серверу
            socket.connect();

        } catch (URISyntaxException e) {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Ошибка создания сокета для запроса стрима", e);
        }
    }

    /**
     * Запускает воспроизведение RTMP-стрима
     * @param serverAddress адрес сервера
     * @param streamPath путь к стриму
     */
    private void startStream(String serverAddress, String streamPath) {
        try {
            // Извлекаем IP адрес из адреса сервера (убираем порт, если есть)
            String serverIp = serverAddress;
            if (serverAddress.contains(":")) {
                serverIp = serverAddress.substring(0, serverAddress.indexOf(":"));
            }
            
            // Проверяем и корректируем путь от сервера
            String path = streamPath;
            // Убираем начальный слеш, если он есть (поскольку он уже будет добавлен в URL)
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            
            // Формируем полный URL для RTMP-стрима (rtmp://server_ip:1935/path)
            String rtmpUrl = "rtmp://" + serverIp + ":1935/" + path;
            Log.d(TAG, "URL стрима: " + rtmpUrl);

            // Останавливаем и освобождаем ресурсы плеера, если он был использован ранее
            if (streamPlayer != null) {
                streamPlayer.stop();
                streamPlayer.clearMediaItems();
            }

            // Создаем MediaItem для RTMP-потока
            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(rtmpUrl));
            
            // Подготавливаем плеер к воспроизведению
            streamPlayer.setMediaItem(mediaItem);
            streamPlayer.prepare();
            streamPlayer.play();
            
            // Обновляем UI
            streamPlayerView.setVisibility(View.VISIBLE);
            tvStreamTitle.setVisibility(View.VISIBLE);
            btnStopStream.setVisibility(View.VISIBLE);
            
            Toast.makeText(this, "Трансляция запущена", Toast.LENGTH_SHORT).show();
            
            // Добавляем слушателя для обработки событий плеера
            streamPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    switch (state) {
                        case Player.STATE_READY:
                            Log.d(TAG, "Плеер готов к воспроизведению стрима");
                            break;
                        case Player.STATE_BUFFERING:
                            Log.d(TAG, "Буферизация стрима...");
                            break;
                        case Player.STATE_ENDED:
                            Log.d(TAG, "Воспроизведение стрима завершено");
                            Toast.makeText(MainActivity.this, "Стрим завершен", Toast.LENGTH_SHORT).show();
                            break;
                        case Player.STATE_IDLE:
                            Log.d(TAG, "Плеер в режиме ожидания");
                            break;
                    }
                }
                
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Ошибка воспроизведения стрима: " + error.getMessage());
                    Toast.makeText(MainActivity.this, 
                            "Ошибка воспроизведения стрима: " + error.getMessage(), 
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Ошибка запуска стрима: " + e.getMessage(), e);
            Toast.makeText(MainActivity.this, "Ошибка запуска стрима: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Останавливает стрим
     */
    private void stopStream() {
        if (socket == null || !socket.connected()) {
            // Если сокет не подключен, пытаемся подключиться снова
            connectAndStopStream();
            return;
        }
        
        try {
            String feederId = etFeederId.getText().toString().trim();
            
            if (feederId.isEmpty()) {
                Toast.makeText(this, "Введите идентификатор кормушки", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Отправляем запрос на остановку стрима и получаем ответ в callback
            JSONObject data = new JSONObject();
            data.put("feeder_id", feederId);
            
            progressBar.setVisibility(View.VISIBLE);
            Log.d(TAG, "Отправлен запрос на остановку стрима: " + data.toString());
            
            // Использование callback для получения ответа от сервера
            socket.emit("stream stop", new Object[]{data}, args -> {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    
                    try {
                        // Логируем ответ
                        if (args.length > 0) {
                            Log.d(TAG, "Ответ на остановку стрима, аргументов: " + args.length);
                            for (int i = 0; i < args.length; i++) {
                                Log.d(TAG, "args[" + i + "]: " + (args[i] != null ? args[i].toString() : "null"));
                            }
                        }
                        
                        // Получаем данные из ответа
                        Object responseData = args.length > 0 ? args[0] : null;
                        
                        if (responseData instanceof JSONObject) {
                            JSONObject jsonData = (JSONObject) responseData;
                            Log.d(TAG, "Получен ответ на остановку стрима: " + jsonData.toString());
                            
                            if (jsonData.has("success") && jsonData.getBoolean("success")) {
                                // Останавливаем воспроизведение
                                if (streamPlayer != null) {
                                    streamPlayer.stop();
                                    streamPlayer.clearMediaItems();
                                }
                                
                                // Скрываем элементы интерфейса стрима
                                tvStreamTitle.setVisibility(View.GONE);
                                streamPlayerView.setVisibility(View.GONE);
                                btnStopStream.setVisibility(View.GONE);
                                
                                Toast.makeText(MainActivity.this, "Трансляция остановлена", Toast.LENGTH_SHORT).show();
                            } else {
                                Log.e(TAG, "Сервер вернул ошибку при остановке стрима");
                                Toast.makeText(MainActivity.this, "Сервер вернул ошибку при остановке стрима", Toast.LENGTH_SHORT).show();
                                
                                // В любом случае останавливаем локальное воспроизведение
                                if (streamPlayer != null) {
                                    streamPlayer.stop();
                                    streamPlayer.clearMediaItems();
                                }
                                
                                // Скрываем элементы интерфейса стрима
                                tvStreamTitle.setVisibility(View.GONE);
                                streamPlayerView.setVisibility(View.GONE);
                                btnStopStream.setVisibility(View.GONE);
                            }
                        } else {
                            Log.e(TAG, "Неожиданный формат ответа на остановку стрима");
                            Toast.makeText(MainActivity.this, "Ошибка: неожиданный формат ответа сервера", Toast.LENGTH_SHORT).show();
                            
                            // В любом случае останавливаем локальное воспроизведение
                            if (streamPlayer != null) {
                                streamPlayer.stop();
                                streamPlayer.clearMediaItems();
                            }
                            
                            // Скрываем элементы интерфейса стрима
                            tvStreamTitle.setVisibility(View.GONE);
                            streamPlayerView.setVisibility(View.GONE);
                            btnStopStream.setVisibility(View.GONE);
                        }
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка обработки ответа на остановку стрима", e);
                        Toast.makeText(MainActivity.this, "Ошибка обработки ответа: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        
                        // В любом случае останавливаем локальное воспроизведение
                        if (streamPlayer != null) {
                            streamPlayer.stop();
                            streamPlayer.clearMediaItems();
                        }
                        
                        // Скрываем элементы интерфейса стрима
                        tvStreamTitle.setVisibility(View.GONE);
                        streamPlayerView.setVisibility(View.GONE);
                        btnStopStream.setVisibility(View.GONE);
                    }
                });
            });
            
        } catch (JSONException e) {
            progressBar.setVisibility(View.GONE);
            Log.e(TAG, "Ошибка создания JSON для остановки стрима", e);
            Toast.makeText(MainActivity.this, "Ошибка остановки стрима: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Подключается к серверу и останавливает стрим
     * (используется, если сокет не был подключен)
     */
    private void connectAndStopStream() {
        String serverAddress = etServerAddress.getText().toString().trim();
        String clientId = etClientId.getText().toString().trim();
        String feederId = etFeederId.getText().toString().trim();

        if (serverAddress.isEmpty() || clientId.isEmpty()) {
            Toast.makeText(this, "Требуется адрес сервера и ID клиента", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (feederId.isEmpty()) {
            Toast.makeText(this, "Введите идентификатор кормушки", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Подключение для остановки трансляции...", Toast.LENGTH_SHORT).show();

        try {
            // Настройка Socket.IO
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("id", clientId);
            options.auth = auth;

            // Создание сокета
            if (socket != null) {
                socket.disconnect();
            }
            socket = IO.socket("http://" + serverAddress, options);

            // Настройка обработчиков событий
            socket.on(Socket.EVENT_CONNECT, args -> {
                runOnUiThread(() -> {
                    Log.d(TAG, "Подключено к сокету для остановки стрима");
                    
                    try {
                        // Отправляем запрос на остановку стрима напрямую
                        JSONObject data = new JSONObject();
                        data.put("feeder_id", feederId);
                        
                        Log.d(TAG, "Отправлен запрос на остановку стрима: " + data.toString());
                        
                        // Использование callback для получения ответа от сервера
                        socket.emit("stream stop", new Object[]{data}, responseArgs -> {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                
                                try {
                                    // Логируем ответ
                                    if (responseArgs.length > 0) {
                                        Log.d(TAG, "Ответ на остановку стрима, аргументов: " + responseArgs.length);
                                        for (int i = 0; i < responseArgs.length; i++) {
                                            Log.d(TAG, "args[" + i + "]: " + (responseArgs[i] != null ? responseArgs[i].toString() : "null"));
                                        }
                                    }
                                    
                                    // Останавливаем воспроизведение в любом случае
                                    if (streamPlayer != null) {
                                        streamPlayer.stop();
                                        streamPlayer.clearMediaItems();
                                    }
                                    
                                    // Скрываем элементы интерфейса стрима
                                    tvStreamTitle.setVisibility(View.GONE);
                                    streamPlayerView.setVisibility(View.GONE);
                                    btnStopStream.setVisibility(View.GONE);
                                    
                                    Toast.makeText(MainActivity.this, "Трансляция остановлена", Toast.LENGTH_SHORT).show();
                                    
                                } catch (Exception e) {
                                    Log.e(TAG, "Ошибка обработки ответа на остановку стрима", e);
                                    Toast.makeText(MainActivity.this, "Ошибка обработки ответа: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    
                                    // В любом случае останавливаем локальное воспроизведение
                                    if (streamPlayer != null) {
                                        streamPlayer.stop();
                                        streamPlayer.clearMediaItems();
                                    }
                                    
                                    // Скрываем элементы интерфейса стрима
                                    tvStreamTitle.setVisibility(View.GONE);
                                    streamPlayerView.setVisibility(View.GONE);
                                    btnStopStream.setVisibility(View.GONE);
                                }
                            });
                        });
                    } catch (JSONException e) {
                        progressBar.setVisibility(View.GONE);
                        Log.e(TAG, "Ошибка создания JSON для остановки стрима", e);
                        Toast.makeText(MainActivity.this, "Ошибка остановки стрима: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            });
            
            socket.on(Socket.EVENT_DISCONNECT, onDisconnect);
            socket.on(Socket.EVENT_CONNECT_ERROR, onConnectError);

            // Подключение к серверу
            socket.connect();

        } catch (URISyntaxException e) {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Ошибка создания сокета для остановки стрима", e);
        }
    }

    /**
     * Обработчик нажатия на элемент видео
     * @param videoItem Выбранное видео
     */
    @Override
    public void onVideoClick(VideoItem videoItem) {
        playerView.setVisibility(View.VISIBLE);

        // Создание MediaItem из URL видео
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoItem.getUrl()));
        
        // Подготовка плеера и начало воспроизведения
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Останавливаем воспроизведение и освобождаем ресурсы
        if (player != null) {
            player.release();
            player = null;
        }
        
        if (streamPlayer != null) {
            streamPlayer.release();
            streamPlayer = null;
        }
        
        // Отключение от сервера
        if (socket != null) {
            socket.disconnect();
            socket.off();
            socket = null;
        }
    }
}
