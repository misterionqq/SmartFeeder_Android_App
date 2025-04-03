package com.example.smartfeederapp;

/**
 * Represents a video item obtained from the server, containing its filename and URL.
 */
public class VideoItem {
    private String filename;
    private String url;

    public VideoItem(String filename, String url) {
        this.filename = filename;
        this.url = url;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
} 