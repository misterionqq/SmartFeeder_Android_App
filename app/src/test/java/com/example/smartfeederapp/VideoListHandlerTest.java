package com.example.smartfeederapp;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.util.Log;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Unit tests for the {@link VideoListHandler} class.
 */
@RunWith(MockitoJUnitRunner.class)
public class VideoListHandlerTest {
    @Mock private Context mockContext;
    @Mock private ApiClient mockApiClient;
    @Mock private ApiService mockApiService;
    @Mock private RecyclerView mockRecyclerView;
    @Mock private VideoAdapter mockVideoAdapter;
    @Mock private ProgressBar mockProgressBar;
    @Mock private Call<List<VideoItem>> mockCall;
    @Mock private Toast mockToastInstance;
    @Mock private VideoAdapter.OnVideoActionListener mockVideoActionListener;

    @Captor private ArgumentCaptor<Callback<List<VideoItem>>> callbackCaptor;
    @Captor private ArgumentCaptor<List<VideoItem>> videoListCaptor;
    @Captor private ArgumentCaptor<VideoAdapter.OnVideoActionListener> actionListenerCaptor;
    private MockedStatic<Log> mockedLog;
    private MockedStatic<Toast> mockedToast;

    private VideoListHandler videoListHandler;

    private List<VideoItem> testVideoList;

    @Before
    public void setUp() {
        testVideoList = Arrays.asList(
                new VideoItem("vid1.mp4", "url1"),
                new VideoItem("vid2.mp4", "url2")
        );

        mockedLog = Mockito.mockStatic(Log.class);
        mockedLog.when(() -> Log.d(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.w(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.e(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.e(anyString(), anyString(), any(Throwable.class))).thenReturn(0);

        mockedToast = Mockito.mockStatic(Toast.class);
        mockedToast.when(() -> Toast.makeText(any(Context.class), any(CharSequence.class), anyInt()))
                .thenReturn(mockToastInstance);
        mockedToast.when(() -> Toast.makeText(any(Context.class), anyInt(), anyInt()))
                .thenReturn(mockToastInstance);
        doNothing().when(mockToastInstance).show();

        when(mockApiClient.getApiService()).thenReturn(mockApiService);

        when(mockApiService.getVideos()).thenReturn(mockCall);

        videoListHandler = new VideoListHandler(mockContext, mockApiClient, mockRecyclerView, mockVideoAdapter, mockProgressBar);
    }

    @After
    public void tearDown() {
        if (mockedLog != null) mockedLog.close();
        if (mockedToast != null) mockedToast.close();
    }

    @Test
    public void loadVideos_whenApiAvailable_showsProgressAndEnqueuesCall() {
        videoListHandler.loadVideos();

        InOrder inOrder = inOrder(mockApiClient, mockProgressBar, mockApiService, mockCall);

        inOrder.verify(mockApiClient).getApiService();
        inOrder.verify(mockProgressBar).setVisibility(View.VISIBLE);
        inOrder.verify(mockApiService).getVideos();
        inOrder.verify(mockCall).enqueue(callbackCaptor.capture());

        assertNotNull(callbackCaptor.getValue());

        mockedLog.verify(() -> Log.d(eq("VideoListHandler"), eq("Requesting video list...")));
    }

    @Test
    public void loadVideos_whenApiNotAvailable_showsToastAndReturns() {
        when(mockApiClient.getApiService()).thenReturn(null);

        videoListHandler.loadVideos();

        verify(mockProgressBar, never()).setVisibility(eq(View.VISIBLE));
        verify(mockApiService, never()).getVideos();
        verify(mockCall, never()).enqueue(any());
        mockedToast.verify(() -> Toast.makeText(eq(mockContext), contains("API not available"), eq(Toast.LENGTH_SHORT)));
        verify(mockToastInstance).show();
        mockedLog.verify(() -> Log.w(eq("VideoListHandler"), contains("ApiService not available")));
    }

    @Test
    public void loadVideos_onSuccessfulResponse_hidesProgressUpdatesAdapterAndShowsToast() {
        videoListHandler.loadVideos();
        verify(mockCall).enqueue(callbackCaptor.capture());
        Callback<List<VideoItem>> callback = callbackCaptor.getValue();

        Response<List<VideoItem>> successResponse = Response.success(testVideoList);

        callback.onResponse(mockCall, successResponse);

        InOrder inOrder = inOrder(mockProgressBar, mockVideoAdapter, mockToastInstance);

        inOrder.verify(mockProgressBar).setVisibility(View.GONE);
        inOrder.verify(mockVideoAdapter).setVideoList(videoListCaptor.capture());
        assertEquals(testVideoList, videoListCaptor.getValue());
        inOrder.verify(mockToastInstance).show();
        mockedToast.verify(() -> Toast.makeText(eq(mockContext), contains("Loaded " + testVideoList.size() + " videos"), eq(Toast.LENGTH_SHORT)));
    }

    @Test
    public void loadVideos_onSuccessfulEmptyResponse_hidesProgressUpdatesAdapterAndShowsEmptyToast() {
        videoListHandler.loadVideos();
        verify(mockCall).enqueue(callbackCaptor.capture());
        Callback<List<VideoItem>> callback = callbackCaptor.getValue();
        List<VideoItem> emptyList = Collections.emptyList();
        Response<List<VideoItem>> successEmptyResponse = Response.success(emptyList);

        callback.onResponse(mockCall, successEmptyResponse);

        InOrder inOrder = inOrder(mockProgressBar, mockVideoAdapter, mockToastInstance);
        inOrder.verify(mockProgressBar).setVisibility(View.GONE);
        inOrder.verify(mockVideoAdapter).setVideoList(videoListCaptor.capture());
        assertTrue(videoListCaptor.getValue().isEmpty());
        inOrder.verify(mockToastInstance).show();
        mockedToast.verify(() -> Toast.makeText(eq(mockContext), eq("Video list is empty"), eq(Toast.LENGTH_SHORT)));
    }

    @Test
    public void loadVideos_onApiErrorResponse_hidesProgressLogsErrorAndShowsErrorToast() {
        videoListHandler.loadVideos();
        verify(mockCall).enqueue(callbackCaptor.capture());
        Callback<List<VideoItem>> callback = callbackCaptor.getValue();

        ResponseBody errorBody = ResponseBody.create(MediaType.parse("application/json"), "{\"error\":\"Not Found\"}");
        Response<List<VideoItem>> errorResponse = Response.error(404, errorBody);

        callback.onResponse(mockCall, errorResponse);

        InOrder inOrder = inOrder(mockProgressBar, mockToastInstance);
        inOrder.verify(mockProgressBar).setVisibility(View.GONE);

        verify(mockVideoAdapter, never()).setVideoList(any());

        mockedLog.verify(() -> Log.e(eq("VideoListHandler"), contains("Error loading videos: 404")));

        inOrder.verify(mockToastInstance).show();
        mockedToast.verify(() -> Toast.makeText(eq(mockContext), contains("Error loading videos: 404"), eq(Toast.LENGTH_SHORT)));
    }

    @Test
    public void loadVideos_onNetworkFailure_hidesProgressLogsErrorAndShowsErrorToast() {
        videoListHandler.loadVideos();
        verify(mockCall).enqueue(callbackCaptor.capture());
        Callback<List<VideoItem>> callback = callbackCaptor.getValue();
        IOException networkException = new IOException("Network timeout");

        callback.onFailure(mockCall, networkException);

        InOrder inOrder = inOrder(mockProgressBar, mockToastInstance);
        inOrder.verify(mockProgressBar).setVisibility(View.GONE);
        verify(mockVideoAdapter, never()).setVideoList(any());
        mockedLog.verify(() -> Log.e(eq("VideoListHandler"), eq("Network error loading videos"), eq(networkException)));
        inOrder.verify(mockToastInstance).show();
        mockedToast.verify(() -> Toast.makeText(eq(mockContext), contains("Network error: " + networkException.getMessage()), eq(Toast.LENGTH_SHORT)));
    }

    @Test
    public void setVideoActionListener_callsAdapterMethod() {
        videoListHandler.setVideoActionListener(mockVideoActionListener);

        verify(mockVideoAdapter).setOnVideoActionListener(actionListenerCaptor.capture());
        assertSame(mockVideoActionListener, actionListenerCaptor.getValue());
    }
}