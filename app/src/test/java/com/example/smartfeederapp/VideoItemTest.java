package com.example.smartfeederapp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for the {@link VideoItem} class.
 * Verifies the constructor, getters, and setters.
 */
public class VideoItemTest {

    private static final String INITIAL_FILENAME = "initial_video.mp4";
    private static final String INITIAL_URL = "http://example.com/initial";
    private static final String UPDATED_FILENAME = "updated_video.avi";
    private static final String UPDATED_URL = "http://example.com/updated";
    private static final String NULL_FILENAME = null;
    private static final String NULL_URL = null;

    @Test
    public void constructor_setsInitialValuesCorrectly() {
        VideoItem item = new VideoItem(INITIAL_FILENAME, INITIAL_URL);

        assertEquals("Constructor should set filename", INITIAL_FILENAME, item.getFilename());
        assertEquals("Constructor should set URL", INITIAL_URL, item.getUrl());
    }

    @Test
    public void constructor_handlesNullValues() {
        VideoItem item = new VideoItem(NULL_FILENAME, NULL_URL);

        assertNull("Constructor should handle null filename", item.getFilename());
        assertNull("Constructor should handle null URL", item.getUrl());
    }

    @Test
    public void setFilename_updatesFilenameCorrectly() {
        VideoItem item = new VideoItem(INITIAL_FILENAME, INITIAL_URL);

        item.setFilename(UPDATED_FILENAME);

        assertEquals("setFilename should update the filename", UPDATED_FILENAME, item.getFilename());
        assertEquals("URL should remain unchanged after setting filename", INITIAL_URL, item.getUrl());
    }

    @Test
    public void setFilename_handlesNullValue() {
        VideoItem item = new VideoItem(INITIAL_FILENAME, INITIAL_URL);

        item.setFilename(NULL_FILENAME);

        assertNull("setFilename should handle null value", item.getFilename());
    }

    @Test
    public void setUrl_updatesUrlCorrectly() {
        VideoItem item = new VideoItem(INITIAL_FILENAME, INITIAL_URL);

        item.setUrl(UPDATED_URL);

        assertEquals("setUrl should update the URL", UPDATED_URL, item.getUrl());
        assertEquals("Filename should remain unchanged after setting URL", INITIAL_FILENAME, item.getFilename());
    }

    @Test
    public void setUrl_handlesNullValue() {
        VideoItem item = new VideoItem(INITIAL_FILENAME, INITIAL_URL);

        item.setUrl(NULL_URL);

        assertNull("setUrl should handle null value", item.getUrl());
    }

    @Test
    public void getFilename_returnsCorrectValueAfterSet() {
        VideoItem item = new VideoItem(null, null);

        item.setFilename(UPDATED_FILENAME);
        String retrievedFilename = item.getFilename();

        assertEquals("getFilename should return the value set by setFilename", UPDATED_FILENAME, retrievedFilename);
    }

    @Test
    public void getUrl_returnsCorrectValueAfterSet() {
        VideoItem item = new VideoItem(null, null);

        item.setUrl(UPDATED_URL);
        String retrievedUrl = item.getUrl();

        assertEquals("getUrl should return the value set by setUrl", UPDATED_URL, retrievedUrl);
    }
}