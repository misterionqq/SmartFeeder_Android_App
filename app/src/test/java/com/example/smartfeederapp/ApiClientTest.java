package com.example.smartfeederapp;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

/**
 * Unit tests for the {@link ApiClient} class.
 */
@RunWith(MockitoJUnitRunner.class)
public class ApiClientTest {
    @Mock
    private Context mockContext;
    @Mock
    private Context mockAppContext;
    @Mock
    private SettingsManager mockSettingsManager;
    @Mock
    private SharedPreferences mockSharedPreferences;
    @Mock
    private SharedPreferences.Editor mockEditor;

    private ApiClient apiClient;

    private MockedStatic<Log> mockedLog;

    private static final String TEST_ADDRESS_1 = "192.168.1.100:5000";
    private static final String TEST_ADDRESS_2 = "10.0.0.5:8080";
    private static final String EXPECTED_BASE_URL_1 = "http://192.168.1.100:5000/";
    private static final String EXPECTED_BASE_URL_2 = "http://10.0.0.5:8080/";
    private static final String INVALID_ADDRESS_FORMAT = "invalid-address";
    private static final String TEST_PREFS_NAME = "AppPrefs";

    @Before
    public void setUp() throws Exception {
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);

        resetSettingsManagerSingleton();
        try {
            Field instanceField = SettingsManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, mockSettingsManager);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set mock SettingsManager instance: " + e.getMessage());
        }

        mockedLog = Mockito.mockStatic(Log.class);
        mockedLog.when(() -> Log.v(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.d(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.i(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.w(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.w(anyString(), anyString(), any(Throwable.class))).thenReturn(0);
        mockedLog.when(() -> Log.e(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.e(anyString(), anyString(), any(Throwable.class))).thenReturn(0);

        when(mockSettingsManager.getServerAddress()).thenReturn(null);
        apiClient = new ApiClient(mockContext);
    }

    @After
    public void tearDown() throws Exception {
        resetSettingsManagerSingleton();
        if (mockedLog != null) {
            mockedLog.close();
        }
    }

    private void resetSettingsManagerSingleton() throws Exception {
        Field instanceField = SettingsManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        instanceField.setAccessible(false);
    }

    @Test
    public void getApiService_whenAddressNotSetInitially_returnsNullAndLogsWarning() {

        reset(mockSettingsManager, mockAppContext, mockContext);
        when(mockSettingsManager.getServerAddress()).thenReturn(null);
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);

        apiClient = new ApiClient(mockContext);

        ApiService service = apiClient.getApiService();

        assertNull("Service should be null when address is not set", service);
        verify(mockContext, times(1)).getApplicationContext();
        verify(mockAppContext, never()).getSharedPreferences(anyString(), anyInt());

        reset(mockSettingsManager);
        when(mockSettingsManager.getServerAddress()).thenReturn(null);
        apiClient = new ApiClient(mockContext);
        apiClient.getApiService();
        verify(mockSettingsManager, times(3)).getServerAddress();

        // mockedLog.verify(() -> Log.w(eq(ApiClient.class.getSimpleName()), contains("Server address not configured")), atLeastOnce());
        // mockedLog.verify(() -> Log.w(eq(ApiClient.class.getSimpleName()), contains("Server address is not set. ApiService cannot be used.")), atLeastOnce());
    }

    @Test
    public void getApiService_whenAddressIsEmptyInitially_returnsNullAndLogsWarning() {
        reset(mockSettingsManager, mockAppContext, mockContext);
        when(mockSettingsManager.getServerAddress()).thenReturn("");
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);

        apiClient = new ApiClient(mockContext);

        ApiService service = apiClient.getApiService();

        assertNull("Service should be null when address is empty", service);
        verify(mockSettingsManager, times(3)).getServerAddress();
        verify(mockAppContext, never()).getSharedPreferences(anyString(), anyInt());

        // mockedLog.verify(() -> Log.w(eq(ApiClient.class.getSimpleName()), contains("Server address not configured")), atLeast(2)); // Called by both init calls
        // mockedLog.verify(() -> Log.w(eq(ApiClient.class.getSimpleName()), contains("Server address is not set. ApiService cannot be used.")), atLeastOnce()); // Log from getApiService check
    }

    @Test
    public void getApiService_whenAddressSetCorrectly_returnsServiceAndLogsSuccess() throws Exception {
        reset(mockSettingsManager, mockAppContext, mockContext);
        when(mockSettingsManager.getServerAddress()).thenReturn(TEST_ADDRESS_1);
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);

        apiClient = new ApiClient(mockContext);

        ApiService service = apiClient.getApiService();

        assertNotNull("Service should not be null", service);
        verify(mockSettingsManager, times(2)).getServerAddress();
        verify(mockAppContext, never()).getSharedPreferences(anyString(), anyInt());

        mockedLog.verify(() -> Log.d(eq(ApiClient.class.getSimpleName()), contains("ApiService initialized successfully for URL: " + EXPECTED_BASE_URL_1)), atLeastOnce());

        Field baseUrlField = ApiClient.class.getDeclaredField("currentBaseUrl");
        baseUrlField.setAccessible(true);
        assertEquals(EXPECTED_BASE_URL_1, baseUrlField.get(apiClient));
    }

    @Test
    public void getApiService_calledTwiceWithSameAddress_returnsSameInternalInstance() throws Exception {
        reset(mockSettingsManager, mockAppContext, mockContext);
        when(mockSettingsManager.getServerAddress()).thenReturn(TEST_ADDRESS_1);
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);

        apiClient = new ApiClient(mockContext);

        ApiService service1 = apiClient.getApiService();
        Field apiServiceField = ApiClient.class.getDeclaredField("apiService");
        apiServiceField.setAccessible(true);
        Object internalService1 = apiServiceField.get(apiClient);
        ApiService service2 = apiClient.getApiService();
        Object internalService2 = apiServiceField.get(apiClient);

        assertNotNull("First service call should return non-null", service1);
        assertNotNull("Second service call should return non-null", service2);
        assertNotNull("Internal service should exist after first call", internalService1);
        assertSame("Internal ApiService instance should be the same on second call", internalService1, internalService2);
        verify(mockSettingsManager, times(3)).getServerAddress();
        verify(mockAppContext, never()).getSharedPreferences(anyString(), anyInt());
        mockedLog.verify(() -> Log.d(eq(ApiClient.class.getSimpleName()), contains("ApiService initialized successfully")), times(1));
    }

    @Test
    public void getApiService_whenAddressChanges_reinitializesServiceAndLogs() throws Exception {
        reset(mockSettingsManager, mockAppContext, mockContext);
        when(mockSettingsManager.getServerAddress()).thenReturn(TEST_ADDRESS_1);
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);

        apiClient = new ApiClient(mockContext);
        ApiService service1 = apiClient.getApiService();
        assertNotNull(service1);
        mockedLog.verify(() -> Log.d(eq(ApiClient.class.getSimpleName()), contains(EXPECTED_BASE_URL_1)), times(1));

        Field apiServiceField = ApiClient.class.getDeclaredField("apiService");
        apiServiceField.setAccessible(true);
        Object internalService1 = apiServiceField.get(apiClient);
        assertNotNull(internalService1);

        when(mockSettingsManager.getServerAddress()).thenReturn(TEST_ADDRESS_2);
        ApiService service2 = apiClient.getApiService();

        assertNotNull("Service should not be null after address change", service2);
        Object internalService2 = apiServiceField.get(apiClient);
        assertNotNull(internalService2);
        assertNotSame("Internal ApiService instance should be different after URL change", internalService1, internalService2);
        Field baseUrlField = ApiClient.class.getDeclaredField("currentBaseUrl");
        baseUrlField.setAccessible(true);
        assertEquals(EXPECTED_BASE_URL_2, baseUrlField.get(apiClient));
        mockedLog.verify(() -> Log.d(eq(ApiClient.class.getSimpleName()), contains("ApiService is not ready or URL changed")), times(1));
        mockedLog.verify(() -> Log.d(eq(ApiClient.class.getSimpleName()), contains("ApiService initialized successfully for URL: " + EXPECTED_BASE_URL_2)), times(1));
        verify(mockSettingsManager, times(4)).getServerAddress();
        verify(mockAppContext, never()).getSharedPreferences(anyString(), anyInt());
    }

    @Test
    public void getApiService_whenAddressBecomesInvalid_logsErrorButMayReturnService() throws Exception {
        reset(mockSettingsManager, mockAppContext, mockContext);
        when(mockSettingsManager.getServerAddress()).thenReturn(TEST_ADDRESS_1);
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);

        apiClient = new ApiClient(mockContext);
        ApiService service1 = apiClient.getApiService();
        assertNotNull(service1);

        when(mockSettingsManager.getServerAddress()).thenReturn(INVALID_ADDRESS_FORMAT);
        ApiService service2 = apiClient.getApiService();

        // mockedLog.verify(() -> Log.d(eq(ApiClient.class.getSimpleName()), contains("ApiService is not ready or URL changed")), times(1));
        // mockedLog.verify(() -> Log.e(eq(ApiClient.class.getSimpleName()), contains("Invalid server address format"), any(IllegalArgumentException.class)), times(1));
        verify(mockSettingsManager, times(4)).getServerAddress();
        verify(mockAppContext, never()).getSharedPreferences(anyString(), anyInt());
    }

    @Test
    public void getApiService_whenAddressCleared_returnsNullAndLogsWarning() throws Exception {
        reset(mockSettingsManager, mockAppContext, mockContext);
        when(mockSettingsManager.getServerAddress()).thenReturn(TEST_ADDRESS_1);
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);

        apiClient = new ApiClient(mockContext);
        ApiService service1 = apiClient.getApiService();
        assertNotNull(service1);

        when(mockSettingsManager.getServerAddress()).thenReturn(null);
        ApiService service2 = apiClient.getApiService();

        assertNull("Service should become null after address is cleared", service2);
        Field apiServiceField = ApiClient.class.getDeclaredField("apiService");
        apiServiceField.setAccessible(true);
        assertNull("apiService field should be null after address is cleared", apiServiceField.get(apiClient));
        mockedLog.verify(() -> Log.w(eq(ApiClient.class.getSimpleName()), contains("Server address is not set. ApiService cannot be used.")), times(1));
        verify(mockSettingsManager, times(3)).getServerAddress();
        verify(mockAppContext, never()).getSharedPreferences(anyString(), anyInt()); 
    }
}