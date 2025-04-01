package com.example.smartfeederapp;

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
import java.util.Arrays;
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
    private final MutableLiveData<String> forceStoppedFeederId = new MutableLiveData<>(null);

    public enum ConnectionState {
        DISCONNECTED, CONNECTING_FOR_ID, CONNECTING_WITH_ID, CONNECTED, ERROR
    }

    public interface ConnectionCallback {
        void onSuccess(String clientId);

        void onConnected();

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

    public LiveData<String> getForceStoppedFeederId() {
        return forceStoppedFeederId;
    }

    public void clearForceStopEvent() {
        mainHandler.post(() -> forceStoppedFeederId.setValue(null));
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


    public void getClientIdFromServer(String serverAddress, ConnectionCallback callback) {
        updateState(ConnectionState.CONNECTING_FOR_ID);
        mainHandler.post(() -> Toast.makeText(appContext, "Подключение для получения ID...", Toast.LENGTH_SHORT).show());

        try {
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("need id", "true");
            options.auth = auth;


            final Socket oldSocket = socket;
            if (oldSocket != null) {
                Log.d(TAG, "getClientIdFromServer: Отключаем предыдущий сокет (ID: " + oldSocket.id() + ") перед запросом ID...");
                oldSocket.disconnect();
                cleanupListeners(oldSocket);
            }

            Log.d(TAG, "Создание нового сокета для запроса ID к " + serverAddress);

            final Socket tempSocket = IO.socket("http://" + serverAddress, options);
            this.socket = tempSocket;


            final Emitter.Listener connectListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Подключено к Socket.IO (для получения ID, сокет: " + tempSocket.id() + ")");
            });


            final Emitter.Listener assignIdListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Получено событие 'assign id'. Аргументы: " + Arrays.toString(args) + " на сокете: " + tempSocket.id());
                String assignedClientId = parseClientId(args);


                cleanupListeners(tempSocket);

                if (assignedClientId != null) {
                    Log.d(TAG, "Успешно получен ID: " + assignedClientId + " (сокет: " + tempSocket.id() + ")");
                    updateClientId(assignedClientId);


                    if (callback != null) callback.onSuccess(assignedClientId);


                    Log.d(TAG, "Сразу запускаем connectWithId после получения ID...");

                    connectWithId(serverAddress, assignedClientId, callback);


                } else {
                    Log.e(TAG, "Не удалось получить ID от сервера (сокет: " + tempSocket.id() + ")");
                    updateState(ConnectionState.ERROR);
                    if (callback != null) callback.onError("Не удалось получить ID от сервера");
                    disconnect();
                }
            });


            final Emitter.Listener connectErrorListener = args -> mainHandler.post(() -> {

                if (tempSocket == ConnectionManager.this.socket) {
                    updateState(ConnectionState.ERROR);
                    String errorMsg = args.length > 0 ? args[0].toString() : "Неизвестная ошибка";
                    Log.e(TAG, "Ошибка подключения (Get ID, сокет: " + tempSocket.id() + "): " + errorMsg);
                    cleanupListeners(tempSocket);
                    if (callback != null) callback.onError("Ошибка подключения: " + errorMsg);
                    disconnect();
                } else {
                    Log.w(TAG, "Получена ошибка подключения для уже неактуального сокета.");
                }
            });


            final Emitter.Listener disconnectListener = args -> mainHandler.post(() -> {

                if (tempSocket == ConnectionManager.this.socket && connectionState.getValue() == ConnectionState.CONNECTING_FOR_ID) {
                    Log.w(TAG, "Временный сокет (Get ID, id: " + tempSocket.id() + ") отключился до получения ID. Причина: " + (args.length > 0 ? args[0] : "нет данных"));
                    updateState(ConnectionState.ERROR);
                    if (callback != null) callback.onError("Отключено до получения ID");
                    cleanupListeners(tempSocket);
                    socket = null;
                } else {
                    Log.d(TAG, "Получено событие disconnect для сокета " + tempSocket.id() + ", но он уже не актуален или стадия пройдена.");
                }
            });


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


    public void connectWithId(String serverAddress, String clientId, ConnectionCallback callback) {
        updateClientId(clientId);

        updateState(ConnectionState.CONNECTING_WITH_ID);
        mainHandler.post(() -> Toast.makeText(appContext, "Подключение с ID: " + clientId, Toast.LENGTH_SHORT).show());

        final Socket oldSocket = socket;
        if (oldSocket != null) {
            Log.d(TAG, "connectWithId: Отключаем предыдущий сокет (ID: " + (oldSocket.id() != null ? oldSocket.id() : "null") + ")");
            oldSocket.disconnect();
            cleanupListeners(oldSocket);
        }

        try {
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("id", clientId);
            options.auth = auth;

            Log.d(TAG, "Создание НОВОГО сокета для ПОДКЛЮЧЕНИЯ С ID к " + serverAddress);
            Socket newSocket = IO.socket("http://" + serverAddress, options);
            this.socket = newSocket;

            addCoreListeners(callback);
            newSocket.connect();

        } catch (URISyntaxException e) {
            updateState(ConnectionState.ERROR);
            updateClientId(null);
            mainHandler.post(() -> Toast.makeText(appContext, "Ошибка URI: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Ошибка создания сокета (Connect with ID)", e);
            if (callback != null) callback.onError("Ошибка URI: " + e.getMessage());
            socket = null;
        }
    }


    private void addCoreListeners(ConnectionCallback callback) {
        final Socket currentSocket = socket;
        if (currentSocket == null) {
            Log.e(TAG, "addCoreListeners вызван с null сокетом!");
            return;
        }

        Log.d(TAG, "Добавление основных слушателей к сокету: " + currentSocket.id());

        currentSocket.on(Socket.EVENT_CONNECT, args -> mainHandler.post(() -> {
            if (currentSocket == ConnectionManager.this.socket && connectionState.getValue() == ConnectionState.CONNECTING_WITH_ID) {
                updateState(ConnectionState.CONNECTED);
                Log.d(TAG, "Подключено к Socket.IO серверу (Core, сокет: " + currentSocket.id() + ")");
                if (callback != null) callback.onConnected();
            } else {
                Log.w(TAG, "Получено событие CONNECT для сокета " + currentSocket.id() + ", но он уже не основной или состояние не CONNECTING_WITH_ID. Текущее: " + connectionState.getValue());
            }
        }));

        currentSocket.on(Socket.EVENT_DISCONNECT, args -> mainHandler.post(() -> {
            if (currentSocket == ConnectionManager.this.socket) {
                String reason = args.length > 0 ? args[0].toString() : "нет данных";
                Log.d(TAG, "Отключено от Socket.IO сервера (Core, сокет: " + currentSocket.id() + "). Причина: " + reason);
                updateState(ConnectionState.DISCONNECTED);
                cleanupListeners(currentSocket);

                if (currentSocket == ConnectionManager.this.socket) {
                    socket = null;
                }
            } else {
                Log.d(TAG, "Получено событие DISCONNECT для неактуального сокета: " + currentSocket.id());
            }
        }));

        currentSocket.on(Socket.EVENT_CONNECT_ERROR, args -> mainHandler.post(() -> {

            if (currentSocket == ConnectionManager.this.socket) {
                updateState(ConnectionState.ERROR);
                String errorMsg = args.length > 0 ? args[0].toString() : "Неизвестная ошибка";
                Log.e(TAG, "Ошибка подключения к Socket.IO серверу (Core, сокет: " + currentSocket.id() + "): " + errorMsg);
                if (callback != null) callback.onError("Ошибка подключения: " + errorMsg);
                cleanupListeners(currentSocket);

                if (currentSocket == ConnectionManager.this.socket) {
                    socket = null;
                }
            } else {
                Log.d(TAG, "Получено событие CONNECT_ERROR для неактуального сокета: " + currentSocket.id());
            }
        }));

        currentSocket.on("stream stopped", args -> mainHandler.post(() -> {
            if (currentSocket != ConnectionManager.this.socket) {
                Log.w(TAG, "Получено событие 'stream stopped' для неактуального сокета: " + currentSocket.id());
                return;
            }
            Log.d(TAG, "Получено событие 'stream stopped' от сервера. Args: " + Arrays.toString(args));
            if (args.length > 0 && args[0] instanceof JSONObject) {
                JSONObject data = (JSONObject) args[0];
                String stoppedFeederId = data.optString("feeder_id", null);
                if (stoppedFeederId != null) {
                    Log.i(TAG, "Сервер сообщил об остановке стрима для кормушки: " + stoppedFeederId);

                    forceStoppedFeederId.setValue(stoppedFeederId);
                } else {
                    Log.w(TAG, "В событии 'stream stopped' отсутствует feeder_id");
                }
            } else {
                Log.w(TAG, "Некорректный формат данных в событии 'stream stopped'");
            }
        }));


    }

    public void disconnect() {
        Log.d(TAG, "Вызван метод disconnect()");
        final Socket oldSocket = socket;
        if (oldSocket != null) {
            if (oldSocket.connected()) {
                Log.d(TAG, "Отключение сокета...");
                oldSocket.disconnect();
            } else {
                Log.d(TAG, "Сокет не подключен, очистка слушателей и обнуление...");
                cleanupListeners(oldSocket);
                socket = null;
            }
        } else {
            Log.d(TAG, "Сокет уже null.");
        }
        updateClientId(null);
        updateState(ConnectionState.DISCONNECTED);
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
                            if (callback != null)
                                callback.onError("Неожиданный формат ответа сервера");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка обработки ответа на запрос стрима", e);
                        if (callback != null)
                            callback.onError("Ошибка обработки ответа: " + e.getMessage());
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
            if (callback != null)
                callback.onError("Нет подключения к серверу для остановки стрима");
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
                            if (callback != null)
                                callback.onError("Неожиданный формат ответа сервера");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка обработки ответа на остановку стрима", e);
                        if (callback != null)
                            callback.onError("Ошибка обработки ответа: " + e.getMessage());
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
                Log.d(TAG, "parseClientId - args[" + i + "]: " + (args[i] != null ? args[i].toString() : "null") + ", тип: " + (args[i] != null ? args[i].getClass().getName() : "null"));
            }


            if (args.length > 1 && args[1] instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) args[1];
                if (jsonData.has("id")) {
                    String id = jsonData.getString("id");
                    Log.d(TAG, "Извлечен ID из JSON (args[1]): " + id);
                    return id;
                }
            } else if (args.length > 0 && args[0] instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) args[0];
                if (jsonData.has("id")) {
                    String id = jsonData.getString("id");
                    Log.d(TAG, "Извлечен ID из JSON (args[0]): " + id);
                    return id;
                }
            } else if (args.length > 0 && args[0] instanceof String) {
                String potentialId = (String) args[0];
                if (!"assign id".equals(potentialId)) {
                    Log.d(TAG, "Используем строку как ID: " + potentialId);
                    return potentialId;
                } else {
                    Log.w(TAG, "Получена строка 'assign id' как данные, игнорируем.");
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
            sock.off();
        }
    }
}