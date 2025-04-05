package com.example.smartfeederapp;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

@RunWith(MockitoJUnitRunner.class)
public class ConnectionManagerTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private Context mockContext;
    @Mock
    private Socket mockSocket;
    @Mock
    private Observer<ConnectionManager.ConnectionState> mockStateObserver;
    @Mock
    private Observer<String> mockClientIdObserver;
    @Mock
    private Observer<String> mockForceStopObserver;
    @Mock
    private ConnectionManager.ConnectionCallback mockConnectionCallback;
    @Mock
    private ConnectionManager.StreamCallback mockStreamCallback;
    @Mock
    private ConnectionManager.SimpleCallback mockSimpleCallback;
    @Mock
    private Handler mockMainHandler;

    @Captor
    private ArgumentCaptor<ConnectionManager.ConnectionState> stateCaptor;
    @Captor
    private ArgumentCaptor<String> stringCaptor;
    @Captor
    private ArgumentCaptor<Emitter.Listener> listenerCaptor;
    @Captor
    private ArgumentCaptor<Object[]> emitArgsCaptor;
    @Captor
    private ArgumentCaptor<io.socket.client.Ack> ackCaptor;

    private ConnectionManager connectionManager;


    private MockedStatic<IO> mockedStaticIO;
    private MockedStatic<Looper> mockedStaticLooper;
    private MockedStatic<Log> mockedStaticLog;
    private MockedStatic<Toast> mockedToast;

    private static final String TEST_SERVER_ADDRESS = "192.168.0.1:5000";
    private static final String TEST_CLIENT_ID = "client-123";
    private static final String TEST_FEEDER_ID = "feeder-xyz";
    private static final String TEST_STREAM_PATH = "live/stream1";

    @Before
    public void setUp() throws Exception {
        resetSingletonInstance();

        mockedStaticIO = Mockito.mockStatic(IO.class);
        mockedStaticIO.when(() -> IO.socket(anyString(), any(IO.Options.class))).thenReturn(mockSocket);

        when(mockMainHandler.post(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return true;
        });

        Looper mockMainLooper = mock(Looper.class);
        mockedStaticLooper = Mockito.mockStatic(Looper.class);
        mockedStaticLooper.when(Looper::getMainLooper).thenReturn(mockMainLooper);

        mockedStaticLog = Mockito.mockStatic(Log.class);
        mockedStaticLog.when(() -> Log.d(anyString(), anyString())).thenReturn(0);
        mockedStaticLog.when(() -> Log.e(anyString(), anyString())).thenReturn(0);
        mockedStaticLog.when(() -> Log.e(anyString(), anyString(), any(Throwable.class))).thenReturn(0);
        mockedStaticLog.when(() -> Log.i(anyString(), anyString())).thenReturn(0);
        mockedStaticLog.when(() -> Log.w(anyString(), anyString())).thenReturn(0);
        mockedStaticLog.when(() -> Log.w(anyString(), anyString(), any(Throwable.class))).thenReturn(0);
        mockedStaticLog.when(() -> Log.v(anyString(), anyString())).thenReturn(0);

        final Toast mockToastInstance = mock(Toast.class);

        mockedToast = Mockito.mockStatic(Toast.class, Mockito.withSettings()


        );

        assertNotNull("mockToastInstance should not be null", mockToastInstance);
        Answer<Toast> returnMockInstance = invocation -> mockToastInstance;

        mockedToast.when(() -> Toast.makeText(any(Context.class), any(CharSequence.class), anyInt())).thenAnswer(returnMockInstance);
        mockedToast.when(() -> Toast.makeText(any(Context.class), anyInt(), anyInt())).thenAnswer(returnMockInstance);

        connectionManager = ConnectionManager.getInstance(mockContext);

        Field handlerField = ConnectionManager.class.getDeclaredField("mainHandler");
        handlerField.setAccessible(true);
        handlerField.set(connectionManager, mockMainHandler);

        connectionManager.getConnectionState().observeForever(mockStateObserver);
        connectionManager.getClientIdLiveData().observeForever(mockClientIdObserver);
        connectionManager.getForceStoppedFeederId().observeForever(mockForceStopObserver);
    }

    private void resetSingletonInstance() throws Exception {
        Field instanceField = ConnectionManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @After
    public void tearDown() throws Exception {
        connectionManager.getConnectionState().removeObserver(mockStateObserver);
        connectionManager.getClientIdLiveData().removeObserver(mockClientIdObserver);
        connectionManager.getForceStoppedFeederId().removeObserver(mockForceStopObserver);

        mockedStaticIO.close();
        mockedStaticLooper.close();
        if (mockedStaticLog != null) {
            mockedStaticLog.close();
        }
        if (mockedToast != null) {
            mockedToast.close();
        }

        resetSingletonInstance();
    }

    private void simulateSocketEvent(String event, Object... args) {
        verify(mockSocket, atLeastOnce()).on(eq(event), listenerCaptor.capture());
        Emitter.Listener listener = null;

        for (int i = listenerCaptor.getAllValues().size() - 1; i >= 0; i--) {
            listener = listenerCaptor.getAllValues().get(i);
            break;
        }

        if (listener != null) {
            if (args == null) {
                listener.call();
            } else {
                listener.call(args);
            }
        } else {

            System.err.println("Warning: Listener for event '" + event + "' was not captured or setup before simulation.");
        }
    }


    @Test
    public void initialState_isDisconnected() {
        verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.DISCONNECTED);
    }

    /*
        @Test
        public void getClientIdFromServer_success() throws JSONException {
            JSONObject idResponse = new JSONObject().put("id", TEST_CLIENT_ID);
            connectionManager.getClientIdFromServer(TEST_SERVER_ADDRESS, mockConnectionCallback);
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.CONNECTING_FOR_ID);
            simulateSocketEvent("assign id", null, idResponse);
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.DISCONNECTED);
            verify(mockClientIdObserver).onChanged(TEST_CLIENT_ID);
            verify(mockConnectionCallback).onSuccess(TEST_CLIENT_ID);
            verify(mockSocket).disconnect();
            verify(mockSocket, atLeastOnce()).off();
        }

        @Test
        public void getClientIdFromServer_connectionError() {
            connectionManager.getClientIdFromServer(TEST_SERVER_ADDRESS, mockConnectionCallback);
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.CONNECTING_FOR_ID);
            simulateSocketEvent(Socket.EVENT_CONNECT_ERROR, "Connection refused");
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.ERROR);
            verify(mockConnectionCallback).onError(contains("Connection error:"));
            verify(mockSocket, atLeastOnce()).off();
        }

        @Test
        public void connectWithId_success() {
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.CONNECTING_WITH_ID);
            verify(mockClientIdObserver).onChanged(TEST_CLIENT_ID);
            simulateSocketEvent(Socket.EVENT_CONNECT);
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.CONNECTED);
            verify(mockConnectionCallback).onConnected();
            verify(mockSocket, atLeastOnce()).on(eq(Socket.EVENT_DISCONNECT), any(Emitter.Listener.class));
        }

        @Test
        public void connectWithId_connectionError() {
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.CONNECTING_WITH_ID);
            simulateSocketEvent(Socket.EVENT_CONNECT_ERROR, "Auth failed");
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.ERROR);
            verify(mockConnectionCallback).onError(contains("Connection error: Auth failed"));
            verify(mockSocket, atLeastOnce()).off();
        }

        @Test
        public void disconnect_whenConnected_disconnectsAndChangesState() {
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            simulateSocketEvent(Socket.EVENT_CONNECT);
            reset(mockStateObserver);
            when(mockSocket.connected()).thenReturn(true);
            connectionManager.disconnect();
            verify(mockSocket).disconnect();
            simulateSocketEvent(Socket.EVENT_DISCONNECT, "client namespace disconnect");
            verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.DISCONNECTED);
            verify(mockSocket, atLeastOnce()).off();
        }
    */
    @Test
    public void disconnect_whenNotConnected_changesState() {
//        when(mockSocket.connected()).thenReturn(false);
        reset(mockStateObserver);
        connectionManager.disconnect();
        verify(mockSocket, never()).disconnect();
        verify(mockStateObserver).onChanged(ConnectionManager.ConnectionState.DISCONNECTED);
    }

    /*
        @Test
        public void isConnected_returnsTrueWhenConnected() {
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            simulateSocketEvent(Socket.EVENT_CONNECT);
            when(mockSocket.connected()).thenReturn(true);
            assertTrue(connectionManager.isConnected());
        }

        @Test
        public void isConnected_returnsFalseWhenDisconnected() {
            when(mockSocket.connected()).thenReturn(false);
            assertFalse(connectionManager.isConnected());
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            simulateSocketEvent(Socket.EVENT_CONNECT);
            simulateSocketEvent(Socket.EVENT_DISCONNECT);
            when(mockSocket.connected()).thenReturn(false);
            assertFalse(connectionManager.isConnected());
        }

        @Test
        public void requestStream_success() throws JSONException {
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            simulateSocketEvent(Socket.EVENT_CONNECT);
            when(mockSocket.connected()).thenReturn(true);
            connectionManager.requestStream(TEST_FEEDER_ID, mockStreamCallback);
            verify(mockSocket).emit(eq("stream start"), emitArgsCaptor.capture(), ackCaptor.capture());
            Object[] emittedArgs = emitArgsCaptor.getValue();
            assertTrue(emittedArgs[0] instanceof JSONObject);
            assertEquals(TEST_FEEDER_ID, ((JSONObject) emittedArgs[0]).getString("feeder_id"));
            io.socket.client.Ack ack = ackCaptor.getValue();
            JSONObject successResponse = new JSONObject()
                    .put("success", true)
                    .put("path", TEST_STREAM_PATH);
            ack.call(successResponse);
            verify(mockStreamCallback).onSuccess(TEST_STREAM_PATH);
        }

        @Test
        public void requestStream_error() throws JSONException {
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            simulateSocketEvent(Socket.EVENT_CONNECT);
            when(mockSocket.connected()).thenReturn(true);
            connectionManager.requestStream(TEST_FEEDER_ID, mockStreamCallback);
            verify(mockSocket).emit(eq("stream start"), any(Object[].class), ackCaptor.capture());
            io.socket.client.Ack ack = ackCaptor.getValue();
            JSONObject errorResponse = new JSONObject()
                    .put("success", false)
                    .put("error", "Feeder busy");
            ack.call(errorResponse);
            verify(mockStreamCallback).onError("Feeder busy");
        }
    */
    @Test
    public void requestStream_notConnected() {
//        when(mockSocket.connected()).thenReturn(false);
        connectionManager.requestStream(TEST_FEEDER_ID, mockStreamCallback);
        verify(mockSocket, never()).emit(anyString(), any(Object[].class), any(io.socket.client.Ack.class));
        verify(mockStreamCallback).onError("Not connected to server");
    }

    /*
        @Test
        public void stopStream_success() throws JSONException {
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            simulateSocketEvent(Socket.EVENT_CONNECT);
            when(mockSocket.connected()).thenReturn(true);
            connectionManager.stopStream(TEST_FEEDER_ID, mockSimpleCallback);
            verify(mockSocket).emit(eq("stream stop"), emitArgsCaptor.capture(), ackCaptor.capture());
            Object[] emittedArgs = emitArgsCaptor.getValue();
            assertTrue(emittedArgs[0] instanceof JSONObject);
            assertEquals(TEST_FEEDER_ID, ((JSONObject) emittedArgs[0]).getString("feeder_id"));
            io.socket.client.Ack ack = ackCaptor.getValue();
            JSONObject successResponse = new JSONObject().put("success", true);
            ack.call(successResponse);
            verify(mockSimpleCallback).onSuccess();
        }

        @Test
        public void stopStream_error() throws JSONException {
            connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
            simulateSocketEvent(Socket.EVENT_CONNECT);
            when(mockSocket.connected()).thenReturn(true);
            connectionManager.stopStream(TEST_FEEDER_ID, mockSimpleCallback);
            verify(mockSocket).emit(eq("stream stop"), any(Object[].class), ackCaptor.capture());
            io.socket.client.Ack ack = ackCaptor.getValue();
            JSONObject errorResponse = new JSONObject()
                    .put("success", false)
                    .put("error", "Stop failed");
            ack.call(errorResponse);
            verify(mockSimpleCallback).onError("Stop failed");
        }
    */
    @Test
    public void stopStream_notConnected() {
//        when(mockSocket.connected()).thenReturn(false);
        connectionManager.stopStream(TEST_FEEDER_ID, mockSimpleCallback);
        verify(mockSocket, never()).emit(anyString(), any(Object[].class), any(io.socket.client.Ack.class));
        verify(mockSimpleCallback).onError(contains("Not connected"));
    }

/*
    @Test
    public void handleServerStreamStopEvent() throws JSONException {
        connectionManager.connectWithId(TEST_SERVER_ADDRESS, TEST_CLIENT_ID, mockConnectionCallback);
        mockedToast.verify(() -> Toast.makeText(any(Context.class), any(CharSequence.class), anyInt()), times(1));
        simulateSocketEvent(Socket.EVENT_CONNECT);
        when(mockSocket.connected()).thenReturn(true);
        reset(mockForceStopObserver);
        JSONObject stopData = new JSONObject().put("feeder_id", TEST_FEEDER_ID);
        simulateSocketEvent("stream stopped", stopData);
        verify(mockForceStopObserver).onChanged(TEST_FEEDER_ID);
        connectionManager.clearForceStopEvent();
        verify(mockForceStopObserver, times(2)).onChanged(stringCaptor.capture());
        assertEquals(TEST_FEEDER_ID, stringCaptor.getAllValues().get(0));
        assertNull(stringCaptor.getAllValues().get(1));
    }

 */
}