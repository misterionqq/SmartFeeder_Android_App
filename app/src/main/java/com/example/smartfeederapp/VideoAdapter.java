package com.example.smartfeederapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView Adapter for displaying a list of video items.
 * Handles binding video data to the view holder and provides callbacks
 * for user actions (play, download) via the OnVideoActionListener interface.
 */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    private List<VideoItem> videoList = new ArrayList<>();
    private OnVideoActionListener actionListener;

    /**
     * Interface definition for callbacks to be invoked when actions are performed on a video item.
     */
    public interface OnVideoActionListener {
        /**
         * Called when the play action is triggered for a video item.
         * @param videoItem The video item associated with the action.
         */
        void onVideoPlayClick(VideoItem videoItem);

        /**
         * Called when the download action is triggered for a video item.
         * @param videoItem The video item associated with the action.
         */
        void onVideoDownloadClick(VideoItem videoItem);
    }

    /**
     * Sets the listener that will receive action callbacks.
     * @param listener The listener implementing OnVideoActionListener.
     */
    public void setOnVideoActionListener(OnVideoActionListener listener) {
        this.actionListener = listener;
    }

    /**
     * Updates the list of videos displayed by the adapter.
     * @param videoList The new list of VideoItem objects.
     */
    public void setVideoList(List<VideoItem> videoList) {
        this.videoList = videoList != null ? videoList : new ArrayList<>();
        notifyDataSetChanged();
    }

    /**
     * Called when RecyclerView needs a new ViewHolder of the given type to represent an item.
     * @param parent The ViewGroup into which the new View will be added after it is bound to an adapter position.
     * @param viewType The view type of the new View.
     * @return A new VideoViewHolder that holds a View of the given view type.
     */
    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(itemView);
    }

    /**
     * Called by RecyclerView to display the data at the specified position.
     * Binds the video data to the ViewHolder.
     * @param holder The ViewHolder which should be updated to represent the contents of the item at the given position.
     * @param position The position of the item within the adapter's data set.
     */
    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem videoItem = videoList.get(position);
        holder.bind(videoItem, actionListener);
    }

    /**
     * Returns the total number of items in the data set held by the adapter.
     * @return The total number of items in this adapter.
     */
    @Override
    public int getItemCount() {
        return videoList.size();
    }

    /**
     * ViewHolder class for video items in the RecyclerView.
     * Holds references to the UI elements within each item's layout.
     */
    static class VideoViewHolder extends RecyclerView.ViewHolder {
        private final TextView videoNameTextView;
        private final ImageButton downloadButton;

        /**
         * Constructor for the ViewHolder.
         * @param itemView The view of the inflated layout for a single item.
         */
        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoNameTextView = itemView.findViewById(R.id.tvVideoName);
            downloadButton = itemView.findViewById(R.id.btnDownloadVideo);
        }

        /**
         * Binds a VideoItem's data to the ViewHolder's views and sets click listeners.
         * @param videoItem The VideoItem data to bind.
         * @param listener The listener to notify of actions.
         */
        void bind(final VideoItem videoItem, final OnVideoActionListener listener) {
            videoNameTextView.setText(videoItem.getFilename());

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onVideoPlayClick(videoItem);
                }
            });

            downloadButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onVideoDownloadClick(videoItem);
                }
            });
        }
    }
}