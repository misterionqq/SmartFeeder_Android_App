package com.example.smartfeederapp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

@RunWith(MockitoJUnitRunner.class)
public class SettingsManagerTest {

    private static final String TEST_PREFS_NAME = "AppPrefs";
    private static final String KEY_SERVER_ADDRESS = "server_address";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String TEST_SERVER_ADDRESS = "192.168.1.100:5000";
    private static final String TEST_CLIENT_ID = "test-client-abc-123";

    @Mock
    private Context mockContext;
    @Mock
    private SharedPreferences mockSharedPreferences;
    @Mock
    private SharedPreferences.Editor mockEditor;
    private SettingsManager settingsManager;

    @Before
    public void setUp() throws Exception {
        resetSingletonInstance();
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        when(mockContext.getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE))
                .thenReturn(mockSharedPreferences);
        when(mockSharedPreferences.edit()).thenReturn(mockEditor);
        when(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor);
        when(mockEditor.remove(anyString())).thenReturn(mockEditor);
        settingsManager = SettingsManager.getInstance(mockContext);
    }

    private void resetSingletonInstance() throws Exception {
        Field instanceField = SettingsManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        instanceField.setAccessible(false);
    }

    @After
    public void tearDown() throws Exception {
        resetSingletonInstance();
    }

    @Test
    public void saveServerAddress_savesCorrectly() {
        settingsManager.saveServerAddress(TEST_SERVER_ADDRESS);
        verify(mockEditor).putString(KEY_SERVER_ADDRESS, TEST_SERVER_ADDRESS);
        verify(mockEditor).apply();
    }

    @Test
    public void getServerAddress_returnsSavedValue() {
        when(mockSharedPreferences.getString(KEY_SERVER_ADDRESS, null)).thenReturn(TEST_SERVER_ADDRESS);
        String retrievedAddress = settingsManager.getServerAddress();
        assertEquals(TEST_SERVER_ADDRESS, retrievedAddress);
    }

    @Test
    public void getServerAddress_returnsNullWhenNotSet() {
        when(mockSharedPreferences.getString(KEY_SERVER_ADDRESS, null)).thenReturn(null);
        String retrievedAddress = settingsManager.getServerAddress();
        assertNull(retrievedAddress);
    }

    @Test
    public void saveClientId_savesCorrectly() {
        settingsManager.saveClientId(TEST_CLIENT_ID);
        verify(mockEditor).putString(KEY_CLIENT_ID, TEST_CLIENT_ID);
        verify(mockEditor).apply();
    }

    @Test
    public void getClientId_returnsSavedValue() {
        when(mockSharedPreferences.getString(KEY_CLIENT_ID, null)).thenReturn(TEST_CLIENT_ID);
        String retrievedId = settingsManager.getClientId();
        assertEquals(TEST_CLIENT_ID, retrievedId);
    }

    @Test
    public void getClientId_returnsNullWhenNotSet() {
        when(mockSharedPreferences.getString(KEY_CLIENT_ID, null)).thenReturn(null);
        String retrievedId = settingsManager.getClientId();
        assertNull(retrievedId);
    }

    @Test
    public void clearSettings_removesBothKeys() {
        settingsManager.clearSettings();
        verify(mockEditor).remove(KEY_SERVER_ADDRESS);
        verify(mockEditor).remove(KEY_CLIENT_ID);
        verify(mockEditor).apply();
    }

    @Test
    public void areSettingsAvailable_returnsTrueWhenBothSet() {
        when(mockSharedPreferences.getString(KEY_SERVER_ADDRESS, null)).thenReturn(TEST_SERVER_ADDRESS);
        when(mockSharedPreferences.getString(KEY_CLIENT_ID, null)).thenReturn(TEST_CLIENT_ID);
        boolean result = settingsManager.areSettingsAvailable();
        assertTrue(result);
    }

    @Test
    public void areSettingsAvailable_returnsFalseWhenOnlyAddressSet() {
        when(mockSharedPreferences.getString(KEY_SERVER_ADDRESS, null)).thenReturn(TEST_SERVER_ADDRESS);
        when(mockSharedPreferences.getString(KEY_CLIENT_ID, null)).thenReturn(null);
        boolean result = settingsManager.areSettingsAvailable();
        assertFalse(result);
    }

    @Test
    public void areSettingsAvailable_returnsFalseWhenOnlyIdSet() {
        when(mockSharedPreferences.getString(KEY_SERVER_ADDRESS, null)).thenReturn(null);

        boolean result = settingsManager.areSettingsAvailable();

        assertFalse(result);
    }

    @Test
    public void areSettingsAvailable_returnsFalseWhenNeitherSet() {
        when(mockSharedPreferences.getString(KEY_SERVER_ADDRESS, null)).thenReturn(null);

        boolean result = settingsManager.areSettingsAvailable();

        assertFalse(result);
    }

    @Test
    public void getInstance_returnsSameInstance() {
        SettingsManager secondInstance = SettingsManager.getInstance(mockContext);
        assertSame("getInstance should return the same instance", settingsManager, secondInstance);
    }
}