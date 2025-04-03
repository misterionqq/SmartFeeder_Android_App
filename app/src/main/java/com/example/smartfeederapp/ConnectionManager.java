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

/**
 * Manages the Socket.IO connection lifecycle, events, and state.
 * Handles obtaining client ID, connecting with ID, disconnecting,
 * sending stream requests, and listening for server events like forced stream stop.
 * Provides LiveData for observing connection state and client ID.
 */
public class ConnectionManager {

    private static final String TAG = "ConnectionManager";
    private static volatile ConnectionManager instance;
    private Socket socket;
    private final Context appContext;
    private final Handler mainHandler;

    private final MutableLiveData<ConnectionState> connectionState = new MutableLiveData<>(ConnectionState.DISCONNECTED);
    private final MutableLiveData<String> clientIdLiveData = new MutableLiveData<>(null);
    private final MutableLiveData<String> forceStoppedFeederId = new MutableLiveData<>(null);

    /**
     * Represents the possible states of the Socket.IO connection.
     */
    public enum ConnectionState {
        DISCONNECTED, CONNECTING_FOR_ID, CONNECTING_WITH_ID, CONNECTED, ERROR
    }

    /**
     * Callback interface for connection-related operations (get ID, connect).
     */
    public interface ConnectionCallback {
        void onSuccess(String clientId);

        void onConnected();

        void onError(String message);
    }

    /**
     * Callback interface for stream request operations.
     */
    public interface StreamCallback {
        void onSuccess(String streamPath);

        void onError(String message);
    }

    /**
     * Callback interface for simple success/error operations (like stop stream).
     */
    public interface SimpleCallback {
        void onSuccess();

        void onError(String message);
    }

    /**
     * Private constructor for the Singleton pattern.
     *
     * @param context Application context.
     */
    private ConnectionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Gets the singleton instance of ConnectionManager.
     *
     * @param context Application context.
     * @return The singleton ConnectionManager instance.
     */
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

    /**
     * Returns LiveData representing the current connection state.
     *
     * @return LiveData<ConnectionState>
     */
    public LiveData<ConnectionState> getConnectionState() {
        return connectionState;
    }

    /**
     * Returns LiveData representing the current client ID (null if not connected or obtained).
     *
     * @return LiveData<String>
     */
    public LiveData<String> getClientIdLiveData() {
        return clientIdLiveData;
    }

    /**
     * Returns LiveData that emits the feeder ID when the server forces a stream stop.
     * Emits null otherwise or after being cleared.
     *
     * @return LiveData<String>
     */
    public LiveData<String> getForceStoppedFeederId() {
        return forceStoppedFeederId;
    }

    /**
     * Resets the forceStoppedFeederId LiveData to null.
     * Should be called by the observer after handling the event.
     */
    public void clearForceStopEvent() {
        mainHandler.post(() -> forceStoppedFeederId.setValue(null));
    }

    /**
     * Updates the connection state LiveData on the main thread.
     *
     * @param state The new connection state.
     */
    private void updateState(ConnectionState state) {
        Log.d(TAG, "Updating state from " + connectionState.getValue() + " to " + state);
        mainHandler.post(() -> connectionState.setValue(state));
    }

    /**
     * Updates the client ID LiveData on the main thread.
     *
     * @param clientId The new client ID, or null.
     */
    private void updateClientId(String clientId) {
        mainHandler.post(() -> clientIdLiveData.setValue(clientId));
    }

    /**
     * Returns the current Socket instance (use with caution).
     *
     * @return The current Socket instance, or null.
     */
    public Socket getSocketInstance() {
        return socket;
    }

    /**
     * Checks if the manager considers the socket to be actively connected.
     *
     * @return true if connected, false otherwise.
     */
    public boolean isConnected() {
        return socket != null && socket.connected() && connectionState.getValue() == ConnectionState.CONNECTED;
    }

    /**
     * Initiates the process to get a client ID from the server.
     * Connects temporarily, gets the ID, updates LiveData and calls onSuccess, then disconnects.
     * Does NOT establish a persistent connection afterward.
     * @param serverAddress The server address (ip:port).
     * @param callback Callback for success (ID received) or error.
     */
    public void getClientIdFromServer(String serverAddress, ConnectionCallback callback) {
        updateState(ConnectionState.CONNECTING_FOR_ID);
        mainHandler.post(() -> Toast.makeText(appContext, "Connecting to get ID...", Toast.LENGTH_SHORT).show());

        try {
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("need id", "true");
            options.auth = auth;

            final Socket oldSocket = socket;
            if (oldSocket != null) {
                Log.d(TAG, "getClientIdFromServer: Disconnecting previous socket (ID: " + oldSocket.id() + ") before requesting ID...");
                oldSocket.disconnect();
                cleanupListeners(oldSocket);
            }
            Log.d(TAG, "Creating new socket to request ID from " + serverAddress);

            final Socket tempSocket = IO.socket("http://" + serverAddress, options);

            final Emitter.Listener connectListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Connected to Socket.IO (for getting ID, socket: " + tempSocket.id() + ")");
            });

            final Emitter.Listener assignIdListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Received 'assign id' event. Args: " + Arrays.toString(args) + " on socket: " + tempSocket.id());
                String assignedClientId = parseClientId(args);

                cleanupListeners(tempSocket);

                if (assignedClientId != null) {
                    Log.d(TAG, "Successfully received ID: " + assignedClientId + " (socket: " + tempSocket.id() + ")");
                    updateClientId(assignedClientId);

                    if (callback != null) callback.onSuccess(assignedClientId);

                    Log.d(TAG, "Disconnecting temporary socket after getting ID...");
                    tempSocket.disconnect();
                    updateState(ConnectionState.DISCONNECTED);
                } else {
                    Log.e(TAG, "Failed to get ID from server (socket: " + tempSocket.id() + ")");
                    updateState(ConnectionState.ERROR);
                    if (callback != null) callback.onError("Failed to get ID from server");
                    tempSocket.disconnect();
                    updateState(ConnectionState.ERROR);
                }
            });

            final Emitter.Listener connectErrorListener = args -> mainHandler.post(() -> {
                updateState(ConnectionState.ERROR);
                String errorMsg = args.length > 0 ? args[0].toString() : "Unknown error";
                Log.e(TAG, "Connection error (Get ID, socket: " + tempSocket.id() + "): " + errorMsg);
                cleanupListeners(tempSocket);
                if (callback != null) callback.onError("Connection error: " + errorMsg);

                updateState(ConnectionState.ERROR);
            });

            final Emitter.Listener disconnectListener = args -> mainHandler.post(() -> {
                Log.d(TAG, "Temporary socket (Get ID, id: " + tempSocket.id() + ") disconnected. Reason: " + (args.length > 0 ? args[0] : "no data"));
            });

            tempSocket.on(Socket.EVENT_CONNECT, connectListener);
            tempSocket.on("assign id", assignIdListener);
            tempSocket.on(Socket.EVENT_CONNECT_ERROR, connectErrorListener);
            tempSocket.on(Socket.EVENT_DISCONNECT, disconnectListener);

            tempSocket.connect();

        } catch (URISyntaxException e) {
            updateState(ConnectionState.ERROR);
            mainHandler.post(() -> Toast.makeText(appContext, "URI Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Error creating socket (Get ID)", e);
            if (callback != null) callback.onError("URI Error: " + e.getMessage());
        }
    }

    /**
     * Establishes a persistent, authenticated connection to the server using the provided client ID.
     * Disconnects any existing socket before creating the new one.
     * @param serverAddress The server address (ip:port).
     * @param clientId The client ID to use for authentication.
     * @param callback Callback for connection success or error.
     */
    public void connectWithId(String serverAddress, String clientId, ConnectionCallback callback) {
        updateClientId(clientId);

        updateState(ConnectionState.CONNECTING_WITH_ID);
        mainHandler.post(() -> Toast.makeText(appContext, "Connecting with ID: " + clientId, Toast.LENGTH_SHORT).show());

        final Socket oldSocket = socket;
        if (oldSocket != null) {
            Log.d(TAG, "connectWithId: Disconnecting previous socket (ID: " + (oldSocket.id() != null ? oldSocket.id() : "null") + ")");
            oldSocket.disconnect();
            cleanupListeners(oldSocket);
        }

        try {
            IO.Options options = new IO.Options();
            Map<String, String> auth = new HashMap<>();
            auth.put("type", "client");
            auth.put("id", clientId);
            options.auth = auth;
            Log.d(TAG, "Creating NEW socket for CONNECTING WITH ID to " + serverAddress);
            Socket newSocket = IO.socket("http://" + serverAddress, options);
            this.socket = newSocket;

            addCoreListeners(callback);
            newSocket.connect();

        } catch (URISyntaxException e) {
            updateState(ConnectionState.ERROR);
            mainHandler.post(() -> Toast.makeText(appContext, "URI Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Error creating socket (Connect with ID)", e);
            if (callback != null) callback.onError("URI Error: " + e.getMessage());
            socket = null;
        }
    }

    /**
     * Adds the essential event listeners for the main, authenticated socket connection.
     *
     * @param callback Callback for connection events.
     */
    private void addCoreListeners(ConnectionCallback callback) {
        final Socket currentSocket = socket;
        if (currentSocket == null) {
            Log.e(TAG, "addCoreListeners called with null socket!");
            return;
        }
        Log.d(TAG, "Adding core listeners to socket: " + currentSocket.id());

        currentSocket.on(Socket.EVENT_CONNECT, args -> mainHandler.post(() -> {
            if (currentSocket == ConnectionManager.this.socket && connectionState.getValue() == ConnectionState.CONNECTING_WITH_ID) {
                updateState(ConnectionState.CONNECTED);
                Log.d(TAG, "Connected to Socket.IO server (Core, socket: " + currentSocket.id() + ")");
                if (callback != null) callback.onConnected();
            } else {
                Log.w(TAG, "Received CONNECT event for socket " + currentSocket.id() + ", but it's no longer the main socket or state is not CONNECTING_WITH_ID. Current state: " + connectionState.getValue());
            }
        }));

        currentSocket.on(Socket.EVENT_DISCONNECT, args -> mainHandler.post(() -> {
            if (currentSocket == ConnectionManager.this.socket) {
                String reason = args.length > 0 ? args[0].toString() : "no data";
                Log.d(TAG, "Disconnected from Socket.IO server (Core, socket: " + currentSocket.id() + "). Reason: " + reason);
                updateState(ConnectionState.DISCONNECTED);
                cleanupListeners(currentSocket);
                if (currentSocket == ConnectionManager.this.socket) {
                    socket = null;
                }
            } else {
                Log.d(TAG, "Received DISCONNECT event for an inactive socket: " + currentSocket.id());
            }
        }));

        currentSocket.on(Socket.EVENT_CONNECT_ERROR, args -> mainHandler.post(() -> {
            if (currentSocket == ConnectionManager.this.socket) {
                updateState(ConnectionState.ERROR);
                String errorMsg = args.length > 0 ? args[0].toString() : "Unknown error";
                Log.e(TAG, "Connection error on Socket.IO server (Core, socket: " + currentSocket.id() + "): " + errorMsg);
                if (callback != null) callback.onError("Connection error: " + errorMsg);
                cleanupListeners(currentSocket);
                if (currentSocket == ConnectionManager.this.socket) {
                    socket = null;
                }
            } else {
                Log.d(TAG, "Received CONNECT_ERROR event for an inactive socket: " + currentSocket.id());
            }
        }));

        currentSocket.on("stream stopped", args -> mainHandler.post(() -> {
            if (currentSocket != ConnectionManager.this.socket) {
                Log.w(TAG, "Received 'stream stopped' event for an inactive socket: " + currentSocket.id());
                return;
            }
            Log.d(TAG, "Received 'stream stopped' event from server. Args: " + Arrays.toString(args));
            if (args.length > 0 && args[0] instanceof JSONObject) {
                JSONObject data = (JSONObject) args[0];
                String stoppedFeederId = data.optString("feeder_id", null);
                if (stoppedFeederId != null) {
                    Log.i(TAG, "Server reported stream stopped for feeder: " + stoppedFeederId);
                    forceStoppedFeederId.setValue(stoppedFeederId);
                } else {
                    Log.w(TAG, "'feeder_id' missing in 'stream stopped' event");
                }
            } else {
                Log.w(TAG, "Incorrect data format in 'stream stopped' event");
            }
        }));
    }

    /**
     * Disconnects the current socket connection, if any.
     * Updates the connection state but KEEPS the client ID in LiveData.
     */
    public void disconnect() {
        Log.d(TAG, "disconnect() method called");
        final Socket oldSocket = socket;
        if (oldSocket != null) {
            if (oldSocket.connected()) {
                Log.d(TAG, "Disconnecting socket...");
                oldSocket.disconnect();
            } else {
                Log.d(TAG, "Socket not connected, cleaning up listeners and nullifying...");
                cleanupListeners(oldSocket);
                socket = null;
                updateState(ConnectionState.DISCONNECTED);
            }
        } else {
            Log.d(TAG, "Socket is already null.");
            updateState(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * Sends a request to the server to start a stream for the specified feeder.
     * Requires an active connection.
     *
     * @param feederId The ID of the feeder to stream from.
     * @param callback Callback for success (with stream path) or error.
     */
    public void requestStream(String feederId, StreamCallback callback) {
        if (!isConnected()) {
            if (callback != null) callback.onError("Not connected to server");
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("feeder_id", feederId);
            Log.d(TAG, "Sending stream start request: " + data.toString());
            socket.emit("stream start", new Object[]{data}, args -> {
                mainHandler.post(() -> {
                    try {
                        if (args.length > 0 && args[0] instanceof JSONObject) {
                            JSONObject responseData = (JSONObject) args[0];
                            Log.d(TAG, "Response for stream start request: " + responseData);
                            if (responseData.optBoolean("success", false) && responseData.has("path")) {
                                String streamPath = responseData.getString("path");
                                if (callback != null) callback.onSuccess(streamPath);
                            } else {
                                String errorMsg = responseData.optString("error", "Server returned error or path missing");
                                Log.e(TAG, "Server error on stream start request: " + errorMsg);
                                if (callback != null) callback.onError(errorMsg);
                            }
                        } else {
                            Log.e(TAG, "Unexpected response format for stream start request: " + (args.length > 0 ? args[0] : "no data"));
                            if (callback != null)
                                callback.onError("Unexpected server response format");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing stream start response", e);
                        if (callback != null)
                            callback.onError("Error processing response: " + e.getMessage());
                    }
                });
            });
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON for stream start request", e);
            if (callback != null) callback.onError("Error creating request: " + e.getMessage());
        }
    }

    /**
     * Sends a request to the server to stop a stream for the specified feeder.
     * Requires an active connection.
     *
     * @param feederId The ID of the feeder whose stream should be stopped.
     * @param callback Callback for success or error.
     */
    public void stopStream(String feederId, SimpleCallback callback) {
        if (!isConnected()) {
            if (callback != null) callback.onError("Not connected to server to stop stream");
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("feeder_id", feederId);
            Log.d(TAG, "Sending stream stop request: " + data.toString());
            socket.emit("stream stop", new Object[]{data}, args -> {
                mainHandler.post(() -> {
                    try {
                        if (args.length > 0 && args[0] instanceof JSONObject) {
                            JSONObject responseData = (JSONObject) args[0];
                            Log.d(TAG, "Response for stream stop request: " + responseData);
                            if (responseData.optBoolean("success", false)) {
                                if (callback != null) callback.onSuccess();
                            } else {
                                String errorMsg = responseData.optString("error", "Server returned error on stop");
                                Log.e(TAG, "Server error on stream stop request: " + errorMsg);
                                if (callback != null) callback.onError(errorMsg);
                            }
                        } else {
                            Log.e(TAG, "Unexpected response format for stream stop request: " + (args.length > 0 ? args[0] : "no data"));
                            if (callback != null)
                                callback.onError("Unexpected server response format");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing stream stop response", e);
                        if (callback != null)
                            callback.onError("Error processing response: " + e.getMessage());
                    }
                });
            });
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON for stream stop request", e);
            if (callback != null) callback.onError("Error creating request: " + e.getMessage());
        }
    }

    /**
     * Parses the client ID from the arguments received in the 'assign id' event.
     * Prioritizes parsing from args[1] as a JSONObject based on previous logs.
     * Includes fallbacks for other potential formats.
     *
     * @param args The arguments array from the Socket.IO event.
     * @return The parsed client ID string, or null if parsing fails.
     */
    private String parseClientId(Object[] args) {
        try {
            for (int i = 0; i < args.length; i++) {
                Log.d(TAG, "parseClientId - args[" + i + "]: " + (args[i] != null ? args[i].toString() : "null") + ", type: " + (args[i] != null ? args[i].getClass().getName() : "null"));
            }

            if (args.length > 1 && args[1] instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) args[1];
                if (jsonData.has("id")) {
                    String id = jsonData.getString("id");
                    Log.d(TAG, "Extracted ID from JSON (args[1]): " + id);
                    return id;
                }
            } else if (args.length > 0 && args[0] instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) args[0];
                if (jsonData.has("id")) {
                    String id = jsonData.getString("id");
                    Log.d(TAG, "Extracted ID from JSON (args[0]): " + id);
                    return id;
                }
            } else if (args.length > 0 && args[0] instanceof String) {
                String potentialId = (String) args[0];
                if (!"assign id".equals(potentialId)) {
                    Log.d(TAG, "Using string as ID: " + potentialId);
                    return potentialId;
                } else {
                    Log.w(TAG, "Received 'assign id' string as data, ignoring.");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing client ID", e);
        }
        Log.e(TAG, "Could not extract a valid client ID from the response");
        return null;
    }

    /**
     * Removes all event listeners from the given socket instance.
     *
     * @param sock The Socket instance to clean up.
     */
    private void cleanupListeners(Socket sock) {
        if (sock != null) {
            Log.d(TAG, "cleanupListeners: Cleaning up ALL listeners for socket: " + (sock.id() != null ? sock.id() : "null"));
            sock.off();
        }
    }
}