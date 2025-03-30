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

    // Добавим для хранения данных между шагами getID -> connect
    private String pendingServerAddress = null;
    private String pendingClientId = null;
    private ConnectionCallback pendingCallback = null;


    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING_FOR_ID, // Новое состояние для ясности
        CONNECTING_WITH_ID, // Новое состояние для ясности
        CONNECTED,
        ERROR
    }

    public interface ConnectionCallback {
        void onSuccess(String clientId); // For Get ID specifically
        void onConnected();             // For general connection success
        void onError(String message);
    }

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
        // Log state changes
        Log.d(TAG, "Updating state from " + connectionState.getValue() + " to " + state);
        mainHandler.post(() -> connectionState.setValue(state));
    }
    private void updateClientId(String clientId) {
        mainHandler.post(() -> clientIdLiveData.setValue(clientId));
    }

    public Socket getSocketInstance() {
        // Be cautious returning the raw socket, manager should handle interactions
        return socket;
    }

    public boolean isConnected() {
        return socket != null && socket.connected() && connectionState.getValue() == ConnectionState.CONNECTED;
    }

    // --- Core Connection Logic ---

    public void getClientIdFromServer(String serverAddress, ConnectionCallback callback) {
        updateState(ConnectionState.CONNECTING_FOR_ID); // Используем новое состояние
        mainHandler.post(() -> Toast.makeText(appContext, "Подключение для получения ID...", Toast.LENGTH_SHORT).show());

        try {
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("need id", "true");
            options.auth = auth;

            // Всегда отключаем предыдущий сокет, если он был
            if (socket != null) {
                Log.d(TAG, "Отключаем предыдущий сокет перед запросом ID...");
                socket.disconnect();
                cleanupListeners(socket); // Очищаем слушателей старого сокета
            }
            Log.d(TAG,"Создание нового сокета для запроса ID к " + serverAddress);
            socket = IO.socket("http://" + serverAddress, options);

            // --- Define listeners specific to this operation ---
            final Emitter.Listener connectListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Подключено к Socket.IO (для получения ID)");
                // Waiting for "assign id"
            });

            // --- НОВЫЙ ВАРИАНТ assignIdListener ---
            final Emitter.Listener assignIdListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Получено событие 'assign id'. Аргументы: " + Arrays.toString(args));
                String assignedClientId = parseClientId(args);

                // Важно: Удаляем временные слушатели СРАЗУ после получения ответа
                cleanupGetIdListeners(socket, connectListener, (Emitter.Listener) socket.listeners(Socket.EVENT_CONNECT_ERROR).get(0), (Emitter.Listener) socket.listeners(Socket.EVENT_DISCONNECT).get(0)); // Передаем ссылки на слушателей

                if (assignedClientId != null) {
                    Log.d(TAG, "Успешно получен ID: " + assignedClientId);
                    updateClientId(assignedClientId); // Update LiveData
                    // Сохраняем данные для следующего шага
                    pendingServerAddress = serverAddress;
                    pendingClientId = assignedClientId;
                    pendingCallback = callback;

                    if (callback != null) callback.onSuccess(assignedClientId);

                    // --- КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: Отключаемся после получения ID ---
                    Log.d(TAG, "Отключаемся после получения ID...");
                    socket.disconnect(); // Сокет будет nullified в onDisconnect
                    // connectWithId будет вызван из обработчика onDisconnect этого временного сокета

                } else {
                    Log.e(TAG, "Не удалось получить ID от сервера");
                    updateState(ConnectionState.ERROR);
                    if (callback != null) callback.onError("Не удалось получить ID от сервера");
                    disconnect(); // Полностью отключаемся при ошибке
                }
                // --- БОЛЬШЕ НЕ УДАЛЯЕМ СЛУШАТЕЛЯ ЗДЕСЬ ---
                // socket.off("assign id", ...); // НЕ ДЕЛАТЬ ЭТО ЗДЕСЬ
            });
            // --- КОНЕЦ НОВОГО ВАРИАНТА ---

            final Emitter.Listener connectErrorListener = args -> mainHandler.post(() -> {
                updateState(ConnectionState.ERROR);
                String errorMsg = args.length > 0 ? args[0].toString() : "Неизвестная ошибка";
                Log.e(TAG, "Ошибка подключения (Get ID): " + errorMsg);

                cleanupGetIdListeners(socket, connectListener, (Emitter.Listener) socket.listeners(Socket.EVENT_CONNECT_ERROR).get(0), (Emitter.Listener) socket.listeners(Socket.EVENT_DISCONNECT).get(0)); // Очистка

                if (callback != null) callback.onError("Ошибка подключения: " + errorMsg);
                disconnect(); // Ensure full cleanup
            });

            final Emitter.Listener disconnectListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Отключено (Get ID flow). Причина: " + (args.length > 0 ? args[0] : "нет данных"));
                // Если мы отключаемся ПОСЛЕ успешного получения ID, запускаем connectWithId
                if (pendingClientId != null && pendingServerAddress != null) {
                    Log.d(TAG,"Запускаем connectWithId после отключения...");
                    String addr = pendingServerAddress;
                    String id = pendingClientId;
                    ConnectionCallback cb = pendingCallback;
                    // Сбрасываем временные переменные
                    pendingServerAddress = null;
                    pendingClientId = null;
                    pendingCallback = null;
                    // Запускаем подключение с ID
                    connectWithId(addr, id, cb);
                } else if (connectionState.getValue() == ConnectionState.CONNECTING_FOR_ID) { // Если disconnect случился ДО получения ID
                    updateState(ConnectionState.ERROR);
                    if (callback != null) callback.onError("Отключено во время получения ID");
                    cleanupGetIdListeners(socket, connectListener, connectErrorListener, (Emitter.Listener) socket.listeners(Socket.EVENT_DISCONNECT).get(0)); // Очистка
                    socket = null; // Явно обнуляем сокет
                } else {
                    // Обычный disconnect, не связанный с ошибкой получения ID
                    updateState(ConnectionState.DISCONNECTED);
                    cleanupListeners(socket); // Общая очистка
                    socket = null;
                }
            });
            // --- End Listeners ---

            // Add listeners
            socket.on(Socket.EVENT_CONNECT, connectListener);
            socket.on("assign id", assignIdListener);
            socket.on(Socket.EVENT_CONNECT_ERROR, connectErrorListener);
            socket.on(Socket.EVENT_DISCONNECT, disconnectListener); // Используем новый disconnectListener


            socket.connect();

        } catch (URISyntaxException e) {
            updateState(ConnectionState.ERROR);
            mainHandler.post(() -> Toast.makeText(appContext, "Ошибка URI: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Ошибка создания сокета (Get ID)", e);
            if (callback != null) callback.onError("Ошибка URI: " + e.getMessage());
            socket = null; // Убедимся, что сокет null
        }
    }

    // --- НОВЫЙ connectWithId ---
    public void connectWithId(String serverAddress, String clientId, ConnectionCallback callback) {
        updateState(ConnectionState.CONNECTING_WITH_ID); // Новое состояние
        mainHandler.post(() -> Toast.makeText(appContext, "Подключение с ID: " + clientId, Toast.LENGTH_SHORT).show());

        // Отключаем старый сокет, если вдруг он еще есть (хотя не должен)
        if (socket != null) {
            Log.w(TAG, "Найден существующий сокет при вызове connectWithId, отключаем...");
            socket.disconnect();
            cleanupListeners(socket);
        }

        try {
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("id", clientId); // Используем полученный ID для аутентификации
            options.auth = auth;

            Log.d(TAG,"Создание нового сокета для ПОДКЛЮЧЕНИЯ С ID к " + serverAddress);
            socket = IO.socket("http://" + serverAddress, options);

            // Добавляем основные рабочие слушатели
            addCoreListeners(callback); // Передаем callback для onConnected/onError

            socket.connect();

        } catch (URISyntaxException e) {
            updateState(ConnectionState.ERROR);
            mainHandler.post(() -> Toast.makeText(appContext, "Ошибка URI: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Ошибка создания сокета (Connect with ID)", e);
            if (callback != null) callback.onError("Ошибка URI: " + e.getMessage());
            socket = null; // Убедимся, что сокет null
        }
    }
    // --- КОНЕЦ НОВОГО connectWithId ---


    // Helper to add listeners used for general operation after connection
    private void addCoreListeners(ConnectionCallback callback) {
        if (socket == null) return;

        // --- НЕ НАДО очищать здесь, т.к. это НОВЫЙ сокет ---
        // cleanupListeners(socket);

        socket.on(Socket.EVENT_CONNECT, args -> mainHandler.post(() -> {
            // Проверяем текущее состояние, чтобы избежать ложного срабатывания CONNECTED
            if (connectionState.getValue() == ConnectionState.CONNECTING_WITH_ID) {
                updateState(ConnectionState.CONNECTED);
                Log.d(TAG, "Подключено к Socket.IO серверу (Core)");
                if (callback != null) callback.onConnected();
            } else {
                Log.w(TAG,"Получено событие CONNECT, но состояние не CONNECTING_WITH_ID. Текущее: " + connectionState.getValue());
            }
        }));

        socket.on(Socket.EVENT_DISCONNECT, args -> mainHandler.post(() -> {
            String reason = args.length > 0 ? args[0].toString() : "нет данных";
            Log.d(TAG, "Отключено от Socket.IO сервера (Core). Причина: " + reason);
            // Только меняем статус, не вызываем callback.onError здесь
            // Если это была ошибка, EVENT_CONNECT_ERROR сработает отдельно
            updateState(ConnectionState.DISCONNECTED);
            // Не обнуляем clientIdLiveData здесь, т.к. он может понадобиться для авто-реконнекта
            cleanupListeners(socket); // Очищаем слушатели при дисконнекте
            socket = null; // Обнуляем сокет
        }));

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> mainHandler.post(() -> {
            updateState(ConnectionState.ERROR);
            String errorMsg = args.length > 0 ? args[0].toString() : "Неизвестная ошибка";
            Log.e(TAG, "Ошибка подключения к Socket.IO серверу (Core): " + errorMsg);
            if (callback != null) callback.onError("Ошибка подключения: " + errorMsg);
            cleanupListeners(socket); // Очищаем при ошибке
            socket = null; // Обнуляем сокет
        }));

        // Add other necessary listeners for stream responses etc. here if needed globally
    }


    public void disconnect() {
        Log.d(TAG, "Вызван метод disconnect()");
        // Сбрасываем pending операции, если они были
        pendingServerAddress = null;
        pendingClientId = null;
        pendingCallback = null;

        if (socket != null) {
            if (socket.connected()) {
                Log.d(TAG, "Отключение сокета...");
                socket.disconnect(); // onDisconnect обработает очистку и обнуление
            } else {
                Log.d(TAG, "Сокет не подключен, очистка слушателей и обнуление...");
                cleanupListeners(socket); // Clean up listeners even if not connected
                socket = null; // Nullify the socket reference
                updateState(ConnectionState.DISCONNECTED); // Устанавливаем статус вручную, т.к. onDisconnect не сработает
            }
        } else {
            Log.d(TAG, "Сокет уже null.");
            updateState(ConnectionState.DISCONNECTED); // Убедимся, что статус верный
        }
        // updateClientId(null); // Не сбрасываем ID здесь, он нужен для реконнекта
    }


    // --- Stream Control Logic (без изменений) ---
    public void requestStream(String feederId, StreamCallback callback) {
        // ... (код requestStream как был)
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
        // ... (код stopStream как был)
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


    // --- Utility Methods ---

    private String parseClientId(Object[] args) {
        try {
            // Логируем все аргументы
            for (int i = 0; i < args.length; i++) {
                Log.d(TAG, "parseClientId - args[" + i + "]: " + (args[i] != null ? args[i].toString() : "null") +
                        ", тип: " + (args[i] != null ? args[i].getClass().getName() : "null"));
            }

            // Проверяем первый аргумент args[0] на JSONObject с полем "id"
            if (args.length > 0 && args[0] instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) args[0];
                if (jsonData.has("id")) {
                    String id = jsonData.getString("id");
                    Log.d(TAG, "Извлечен ID из JSON: " + id);
                    return id;
                }
            }
            // Если первый не подошел, проверяем args[1] (как было в старом коде на всякий случай)
            else if (args.length > 1 && args[1] instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) args[1];
                if (jsonData.has("id")) {
                    String id = jsonData.getString("id");
                    Log.d(TAG, "Извлечен ID из JSON (args[1]): " + id);
                    return id;
                }
            }
            // Проверяем первый аргумент на строку (если сервер вдруг шлет просто ID)
            else if (args.length > 0 && args[0] instanceof String) {
                // Добавим проверку, что это не имя события "assign id"
                String potentialId = (String) args[0];
                if (!"assign id".equals(potentialId)) {
                    Log.d(TAG, "Используем строку как ID: " + potentialId);
                    return potentialId;
                } else {
                    Log.w(TAG,"Получена строка 'assign id' как данные, игнорируем.");
                    // Можно проверить args[1] на строку здесь, если нужно
                }
            }
            // Можно добавить парсинг JSON из строки, если необходимо
            // else if (args.length > 0 && args[0] instanceof String) { try { JSONObject... } catch ... }


        } catch (Exception e) {
            Log.e(TAG, "Ошибка разбора ID клиента", e);
        }
        Log.e(TAG, "Не удалось извлечь валидный ID клиента из ответа");
        return null;
    }

    // Общая очистка всех слушателей
    private void cleanupListeners(Socket sock) {
        if (sock != null) {
            Log.d(TAG, "cleanupListeners: Очистка ВСЕХ слушателей для сокета: " + sock.id());
            sock.off(); // Удаляет ВСЕ слушатели
        }
    }

    // Очистка только временных слушателей фазы получения ID
    private void cleanupGetIdListeners(Socket sock, Emitter.Listener connect, Emitter.Listener connectError, Emitter.Listener disconnect) {
        if (sock != null) {
            Log.d(TAG, "cleanupGetIdListeners: Очистка временных слушателей для сокета: " + sock.id());
            if (connect != null) sock.off(Socket.EVENT_CONNECT, connect);
            if (connectError != null) sock.off(Socket.EVENT_CONNECT_ERROR, connectError);
            // Не удаляем 'assign id' здесь, т.к. он может быть удален в cleanupListeners если что-то пошло не так раньше
            if (disconnect != null) sock.off(Socket.EVENT_DISCONNECT, disconnect);
        }
    }
}