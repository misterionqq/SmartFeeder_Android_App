package com.example.smartfeederapp;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for the {@link VideoAdapter.VideoViewHolder} class.
 * Focuses on verifying the behavior of the {@code bind} method.
 */
@RunWith(MockitoJUnitRunner.class)
public class VideoViewHolderTest {
    @Mock
    private View mockItemView;
    @Mock
    private TextView mockTextView;
    @Mock
    private ImageButton mockDownloadButton;
    @Mock
    private VideoAdapter.OnVideoActionListener mockListener;

    @Captor
    private ArgumentCaptor<View.OnClickListener> itemViewClickListenerCaptor;
    @Captor
    private ArgumentCaptor<View.OnClickListener> downloadClickListenerCaptor;

    private VideoItem testVideoItem;

    private VideoAdapter.VideoViewHolder viewHolder;

    @Before
    public void setUp() {
        testVideoItem = new VideoItem("test_video_01.mp4", "http://example.com/video1.mp4");

        when(mockItemView.findViewById(R.id.tvVideoName)).thenReturn(mockTextView);
        when(mockItemView.findViewById(R.id.btnDownloadVideo)).thenReturn(mockDownloadButton);

        viewHolder = new VideoAdapter.VideoViewHolder(mockItemView);
    }

    @Test
    public void bind_setsVideoFileNameCorrectly() {
        viewHolder.bind(testVideoItem, mockListener);
        verify(mockTextView).setText(testVideoItem.getFilename());
    }

    @Test
    public void bind_setsItemViewClickListener_andCallsListenerOnClick() {
        viewHolder.bind(testVideoItem, mockListener);

        verify(mockItemView).setOnClickListener(itemViewClickListenerCaptor.capture());
        assertNotNull("ClickListener should be set on itemView", itemViewClickListenerCaptor.getValue());

        itemViewClickListenerCaptor.getValue().onClick(mockItemView);

        verify(mockListener).onVideoPlayClick(testVideoItem);
        verify(mockListener, never()).onVideoDownloadClick(any(VideoItem.class));
    }

    @Test
    public void bind_setsDownloadButtonClickListener_andCallsListenerOnClick() {
        viewHolder.bind(testVideoItem, mockListener);

        verify(mockDownloadButton).setOnClickListener(downloadClickListenerCaptor.capture());
        assertNotNull("ClickListener should be set on downloadButton", downloadClickListenerCaptor.getValue());

        downloadClickListenerCaptor.getValue().onClick(mockDownloadButton);

        verify(mockListener).onVideoDownloadClick(testVideoItem);
        verify(mockListener, never()).onVideoPlayClick(any(VideoItem.class));
    }

    @Test
    public void bind_withNullListener_doesNotCrashOnClick() {
        viewHolder.bind(testVideoItem, null);

        verify(mockItemView).setOnClickListener(itemViewClickListenerCaptor.capture());
        verify(mockDownloadButton).setOnClickListener(downloadClickListenerCaptor.capture());
        assertNotNull(itemViewClickListenerCaptor.getValue());
        assertNotNull(downloadClickListenerCaptor.getValue());

        try {
            itemViewClickListenerCaptor.getValue().onClick(mockItemView);
            downloadClickListenerCaptor.getValue().onClick(mockDownloadButton);
        } catch (NullPointerException e) {
            fail("NullPointerException occurred when clicking with a null listener.");
        }

        verifyNoInteractions(mockListener);
    }
}