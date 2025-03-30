// Файл: ConnectionManager.java
package com.example.smartfeederzatichkav20;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.Arrays; // Импортируем Arrays для логирования
import java.util.HashMap;
import java.util.Map;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class ConnectionManager {

    private static final String TAG = "ConnectionManager";
    private static volatile ConnectionManager instance;
    private Socket socket;
    private final Context appContext;
    private final Handler mainHandler;

    private final MutableLiveData<ConnectionState> connectionState = new MutableLiveData<>(ConnectionState.DISCONNECTED);
    private final MutableLiveData<String> clientIdLiveData = new MutableLiveData<>(null);

    // Убираем pending переменные
    // private String pendingServerAddress = null;
    // private String pendingClientId = null;
    // private ConnectionCallback pendingCallback = null;

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING_FOR_ID,
        CONNECTING_WITH_ID,
        CONNECTED,
        ERROR
    }

    public interface ConnectionCallback {
        void onSuccess(String clientId);
        void onConnected();
        void onError(String message);
    }

    // ... (StreamCallback, SimpleCallback как были) ...
    public interface StreamCallback {
        void onSuccess(String streamPath);
        void onError(String message);
    }
    public interface SimpleCallback {
        void onSuccess();
        void onError(String message);
    }

    private ConnectionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static ConnectionManager getInstance(Context context) {
        // ... (как было)
        if (instance == null) {
            synchronized (ConnectionManager.class) {
                if (instance == null) {
                    instance = new ConnectionManager(context);
                }
            }
        }
        return instance;
    }

    public LiveData<ConnectionState> getConnectionState() {
        return connectionState;
    }
    public LiveData<String> getClientIdLiveData() {
        return clientIdLiveData;
    }

    private void updateState(ConnectionState state) {
        Log.d(TAG, "Updating state from " + connectionState.getValue() + " to " + state);
        mainHandler.post(() -> connectionState.setValue(state));
    }
    private void updateClientId(String clientId) {
        mainHandler.post(() -> clientIdLiveData.setValue(clientId));
    }

    public Socket getSocketInstance() {
        return socket;
    }

    public boolean isConnected() {
        return socket != null && socket.connected() && connectionState.getValue() == ConnectionState.CONNECTED;
    }

    // --- Core Connection Logic ---

    public void getClientIdFromServer(String serverAddress, ConnectionCallback callback) {
        updateState(ConnectionState.CONNECTING_FOR_ID);
        mainHandler.post(() -> Toast.makeText(appContext, "Подключение для получения ID...", Toast.LENGTH_SHORT).show());

        try {
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("need id", "true");
            options.auth = auth;

            // Отключаем предыдущий сокет, если он был
            final Socket oldSocket = socket; // Захватываем ссылку на старый сокет
            if (oldSocket != null) {
                Log.d(TAG, "getClientIdFromServer: Отключаем предыдущий сокет (ID: " + oldSocket.id() + ") перед запросом ID...");
                oldSocket.disconnect(); // Начинаем отключение
                cleanupListeners(oldSocket); // Очищаем его слушатели немедленно
            }

            Log.d(TAG,"Создание нового сокета для запроса ID к " + serverAddress);
            // Создаем НОВЫЙ сокет для временного подключения
            final Socket tempSocket = IO.socket("http://" + serverAddress, options);
            this.socket = tempSocket; // Обновляем глобальную ссылку НА ВРЕМЯ

            // --- Define listeners specific to this operation ---
            final Emitter.Listener connectListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Подключено к Socket.IO (для получения ID, сокет: " + tempSocket.id() + ")");
            });

            // --- ИЗМЕНЕННЫЙ assignIdListener ---
            final Emitter.Listener assignIdListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Получено событие 'assign id'. Аргументы: " + Arrays.toString(args) + " на сокете: " + tempSocket.id());
                String assignedClientId = parseClientId(args);

                // Очищаем ВСЕ слушатели временного сокета СРАЗУ
                cleanupListeners(tempSocket);

                if (assignedClientId != null) {
                    Log.d(TAG, "Успешно получен ID: " + assignedClientId + " (сокет: " + tempSocket.id() + ")");
                    updateClientId(assignedClientId); // Update LiveData

                    // Вызываем onSuccess колбэк
                    if (callback != null) callback.onSuccess(assignedClientId);

                    // --- СРАЗУ ЗАПУСКАЕМ ПОДКЛЮЧЕНИЕ С НОВЫМ ID ---
                    Log.d(TAG, "Сразу запускаем connectWithId после получения ID...");
                    // connectWithId отключит временный сокет tempSocket и создаст новый
                    connectWithId(serverAddress, assignedClientId, callback);
                    // -----------------------------------------

                } else {
                    Log.e(TAG, "Не удалось получить ID от сервера (сокет: " + tempSocket.id() + ")");
                    updateState(ConnectionState.ERROR);
                    if (callback != null) callback.onError("Не удалось получить ID от сервера");
                    disconnect(); // Полная очистка
                }
            });
            // --- КОНЕЦ ИЗМЕНЕНИЯ ---

            final Emitter.Listener connectErrorListener = args -> mainHandler.post(() -> {
                // Проверяем, что ошибка относится именно к этому временному сокету
                if (tempSocket == ConnectionManager.this.socket) {
                    updateState(ConnectionState.ERROR);
                    String errorMsg = args.length > 0 ? args[0].toString() : "Неизвестная ошибка";
                    Log.e(TAG, "Ошибка подключения (Get ID, сокет: " + tempSocket.id() + "): " + errorMsg);
                    cleanupListeners(tempSocket); // Очистка
                    if (callback != null) callback.onError("Ошибка подключения: " + errorMsg);
                    disconnect(); // Полная очистка
                } else {
                    Log.w(TAG, "Получена ошибка подключения для уже неактуального сокета.");
                }
            });

            // Упрощенный disconnect listener для временного сокета
            final Emitter.Listener disconnectListener = args -> mainHandler.post(() -> {
                // Проверяем, что событие относится к текущему временному сокету
                if (tempSocket == ConnectionManager.this.socket && connectionState.getValue() == ConnectionState.CONNECTING_FOR_ID) {
                    Log.w(TAG, "Временный сокет (Get ID, id: " + tempSocket.id() + ") отключился до получения ID. Причина: " + (args.length > 0 ? args[0] : "нет данных"));
                    updateState(ConnectionState.ERROR);
                    if (callback != null) callback.onError("Отключено до получения ID");
                    cleanupListeners(tempSocket);
                    socket = null; // Обнуляем ссылку
                } else {
                    Log.d(TAG, "Получено событие disconnect для сокета " + tempSocket.id() + ", но он уже не актуален или стадия пройдена.");
                }
            });
            // --- End Listeners ---

            // Add listeners to the temporary socket
            tempSocket.on(Socket.EVENT_CONNECT, connectListener);
            tempSocket.on("assign id", assignIdListener);
            tempSocket.on(Socket.EVENT_CONNECT_ERROR, connectErrorListener);
            tempSocket.on(Socket.EVENT_DISCONNECT, disconnectListener);

            tempSocket.connect();

        } catch (URISyntaxException e) {
            updateState(ConnectionState.ERROR);
            mainHandler.post(() -> Toast.makeText(appContext, "Ошибка URI: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Ошибка создания сокета (Get ID)", e);
            if (callback != null) callback.onError("Ошибка URI: " + e.getMessage());
            socket = null;
        }
    }


    // --- ИЗМЕНЕННЫЙ connectWithId ---
    public void connectWithId(String serverAddress, String clientId, ConnectionCallback callback) {
        updateState(ConnectionState.CONNECTING_WITH_ID);
        mainHandler.post(() -> Toast.makeText(appContext, "Подключение с ID: " + clientId, Toast.LENGTH_SHORT).show());

        // --- Отключаем и очищаем ЛЮБОЙ предыдущий сокет ---
        final Socket oldSocket = socket; // Захватываем текущую ссылку (может быть временный сокет)
        if (oldSocket != null) {
            Log.d(TAG, "connectWithId: Отключаем предыдущий сокет (ID: " + (oldSocket.id() != null ? oldSocket.id() : "null") + ")");
            oldSocket.disconnect();
            cleanupListeners(oldSocket);
        }
        // ----------------------------------------------------

        try {
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("id", clientId);
            options.auth = auth;

            Log.d(TAG,"Создание НОВОГО сокета для ПОДКЛЮЧЕНИЯ С ID к " + serverAddress);
            // Создаем и ПРИСВАИВАЕМ новый сокет глобальной переменной
            Socket newSocket = IO.socket("http://" + serverAddress, options);
            this.socket = newSocket; // <--- Обновляем глобальную ссылку

            // Добавляем основные рабочие слушатели к НОВОМУ сокету
            addCoreListeners(callback);
            newSocket.connect();

        } catch (URISyntaxException e) {
            updateState(ConnectionState.ERROR);
            mainHandler.post(() -> Toast.makeText(appContext, "Ошибка URI: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Ошибка создания сокета (Connect with ID)", e);
            if (callback != null) callback.onError("Ошибка URI: " + e.getMessage());
            socket = null; // Убедимся, что сокет null при ошибке
        }
    }
    // --- КОНЕЦ ИЗМЕНЕНИЯ ---


    private void addCoreListeners(ConnectionCallback callback) {
        // Этот метод теперь всегда вызывается для НОВОГО сокета в connectWithId
        final Socket currentSocket = socket; // Захватываем ссылку на текущий "основной" сокет
        if (currentSocket == null) {
            Log.e(TAG, "addCoreListeners вызван с null сокетом!");
            return;
        }

        Log.d(TAG, "Добавление основных слушателей к сокету: " + currentSocket.id());

        currentSocket.on(Socket.EVENT_CONNECT, args -> mainHandler.post(() -> {
            // Убедимся, что событие пришло для текущего активного сокета
            if (currentSocket == ConnectionManager.this.socket && connectionState.getValue() == ConnectionState.CONNECTING_WITH_ID) {
                updateState(ConnectionState.CONNECTED);
                Log.d(TAG, "Подключено к Socket.IO серверу (Core, сокет: " + currentSocket.id() + ")");
                if (callback != null) callback.onConnected();
            } else {
                Log.w(TAG,"Получено событие CONNECT для сокета " + currentSocket.id() + ", но он уже не основной или состояние не CONNECTING_WITH_ID. Текущее: " + connectionState.getValue());
            }
        }));

        currentSocket.on(Socket.EVENT_DISCONNECT, args -> mainHandler.post(() -> {
            // Убедимся, что событие пришло для текущего активного сокета
            if (currentSocket == ConnectionManager.this.socket) {
                String reason = args.length > 0 ? args[0].toString() : "нет данных";
                Log.d(TAG, "Отключено от Socket.IO сервера (Core, сокет: " + currentSocket.id() + "). Причина: " + reason);
                updateState(ConnectionState.DISCONNECTED);
                cleanupListeners(currentSocket);
                // Обнуляем ТОЛЬКО если это действительно текущий сокет
                if(currentSocket == ConnectionManager.this.socket) {
                    socket = null;
                }
            } else {
                Log.d(TAG, "Получено событие DISCONNECT для неактуального сокета: " + currentSocket.id());
            }
        }));

        currentSocket.on(Socket.EVENT_CONNECT_ERROR, args -> mainHandler.post(() -> {
            // Убедимся, что событие пришло для текущего активного сокета
            if (currentSocket == ConnectionManager.this.socket) {
                updateState(ConnectionState.ERROR);
                String errorMsg = args.length > 0 ? args[0].toString() : "Неизвестная ошибка";
                Log.e(TAG, "Ошибка подключения к Socket.IO серверу (Core, сокет: " + currentSocket.id() + "): " + errorMsg);
                if (callback != null) callback.onError("Ошибка подключения: " + errorMsg);
                cleanupListeners(currentSocket);
                // Обнуляем ТОЛЬКО если это действительно текущий сокет
                if(currentSocket == ConnectionManager.this.socket) {
                    socket = null;
                }
            } else {
                Log.d(TAG, "Получено событие CONNECT_ERROR для неактуального сокета: " + currentSocket.id());
            }
        }));
    }

    // disconnect, requestStream, stopStream, parseClientId, cleanupListeners - остаются как были в предыдущем ответе

    // ... остальной код ConnectionManager (disconnect, requestStream, stopStream, parseClientId, cleanupListeners) ...
    public void disconnect() {
        Log.d(TAG, "Вызван метод disconnect()");

        final Socket oldSocket = socket; // Захватываем текущую ссылку
        if (oldSocket != null) {
            if (oldSocket.connected()) {
                Log.d(TAG, "Отключение сокета...");
                oldSocket.disconnect(); // onDisconnect обработает очистку и обнуление
            } else {
                Log.d(TAG, "Сокет не подключен, очистка слушателей и обнуление...");
                cleanupListeners(oldSocket); // Clean up listeners even if not connected
                socket = null; // Nullify the socket reference
                updateState(ConnectionState.DISCONNECTED); // Устанавливаем статус вручную, т.к. onDisconnect не сработает
            }
        } else {
            Log.d(TAG, "Сокет уже null.");
            updateState(ConnectionState.DISCONNECTED); // Убедимся, что статус верный
        }
    }


    public void requestStream(String feederId, StreamCallback callback) {
        if (!isConnected()) {
            if (callback != null) callback.onError("Нет подключения к серверу");
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("feeder_id", feederId);
            Log.d(TAG, "Отправка запроса на стрим: " + data.toString());
            socket.emit("stream start", new Object[]{data}, args -> {
                mainHandler.post(() -> {
                    try {
                        if (args.length > 0 && args[0] instanceof JSONObject) {
                            JSONObject responseData = (JSONObject) args[0];
                            Log.d(TAG, "Ответ на запрос стрима: " + responseData);
                            if (responseData.optBoolean("success", false) && responseData.has("path")) {
                                String streamPath = responseData.getString("path");
                                if (callback != null) callback.onSuccess(streamPath);
                            } else {
                                String errorMsg = responseData.optString("error", "Сервер вернул ошибку или отсутствует путь");
                                Log.e(TAG, "Ошибка запроса стрима от сервера: " + errorMsg);
                                if (callback != null) callback.onError(errorMsg);
                            }
                        } else {
                            Log.e(TAG, "Неожиданный формат ответа на запрос стрима: " + (args.length > 0 ? args[0] : "нет данных"));
                            if (callback != null) callback.onError("Неожиданный формат ответа сервера");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка обработки ответа на запрос стрима", e);
                        if (callback != null) callback.onError("Ошибка обработки ответа: " + e.getMessage());
                    }
                });
            });
        } catch (JSONException e) {
            Log.e(TAG, "Ошибка создания JSON для запроса стрима", e);
            if (callback != null) callback.onError("Ошибка создания запроса: " + e.getMessage());
        }
    }

    public void stopStream(String feederId, SimpleCallback callback) {
        if (!isConnected()) {
            if (callback != null) callback.onError("Нет подключения к серверу для остановки стрима");
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("feeder_id", feederId);
            Log.d(TAG, "Отправка запроса на остановку стрима: " + data.toString());
            socket.emit("stream stop", new Object[]{data}, args -> {
                mainHandler.post(() -> {
                    try {
                        if (args.length > 0 && args[0] instanceof JSONObject) {
                            JSONObject responseData = (JSONObject) args[0];
                            Log.d(TAG, "Ответ на остановку стрима: " + responseData);
                            if (responseData.optBoolean("success", false)) {
                                if (callback != null) callback.onSuccess();
                            } else {
                                String errorMsg = responseData.optString("error", "Сервер вернул ошибку при остановке");
                                Log.e(TAG, "Ошибка остановки стрима от сервера: " + errorMsg);
                                if (callback != null) callback.onError(errorMsg);
                            }
                        } else {
                            Log.e(TAG, "Неожиданный формат ответа на остановку стрима: " + (args.length > 0 ? args[0] : "нет данных"));
                            if (callback != null) callback.onError("Неожиданный формат ответа сервера");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка обработки ответа на остановку стрима", e);
                        if (callback != null) callback.onError("Ошибка обработки ответа: " + e.getMessage());
                    }
                });
            });
        } catch (JSONException e) {
            Log.e(TAG, "Ошибка создания JSON для остановки стрима", e);
            if (callback != null) callback.onError("Ошибка создания запроса: " + e.getMessage());
        }
    }


    private String parseClientId(Object[] args) {
        try {
            for (int i = 0; i < args.length; i++) {
                Log.d(TAG, "parseClientId - args[" + i + "]: " + (args[i] != null ? args[i].toString() : "null") +
                        ", тип: " + (args[i] != null ? args[i].getClass().getName() : "null"));
            }

            // Проверяем args[1], так как логи показали, что ID там
            if (args.length > 1 && args[1] instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) args[1];
                if (jsonData.has("id")) {
                    String id = jsonData.getString("id");
                    Log.d(TAG, "Извлечен ID из JSON (args[1]): " + id);
                    return id;
                }
            }
            // Резервная проверка args[0] на JSONObject
            else if (args.length > 0 && args[0] instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) args[0];
                if (jsonData.has("id")) {
                    String id = jsonData.getString("id");
                    Log.d(TAG, "Извлечен ID из JSON (args[0]): " + id);
                    return id;
                }
            }
            // Резервная проверка args[0] на строку (не имя события)
            else if (args.length > 0 && args[0] instanceof String) {
                String potentialId = (String) args[0];
                if (!"assign id".equals(potentialId)) {
                    Log.d(TAG, "Используем строку как ID: " + potentialId);
                    return potentialId;
                } else {
                    Log.w(TAG,"Получена строка 'assign id' как данные, игнорируем.");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка разбора ID клиента", e);
        }
        Log.e(TAG, "Не удалось извлечь валидный ID клиента из ответа");
        return null;
    }

    private void cleanupListeners(Socket sock) {
        if (sock != null) {
            Log.d(TAG, "cleanupListeners: Очистка ВСЕХ слушателей для сокета: " + (sock.id() != null ? sock.id() : "null"));
            sock.off(); // Удаляет ВСЕ слушатели
        }
    }

}