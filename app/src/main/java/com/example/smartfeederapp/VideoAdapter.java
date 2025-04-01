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

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    private List<VideoItem> videoList = new ArrayList<>();

    private OnVideoActionListener actionListener;

    /**
     * Интерфейс для обработки нажатий на элементы списка и кнопки
     */
    public interface OnVideoActionListener {
        void onVideoPlayClick(VideoItem videoItem);

        void onVideoDownloadClick(VideoItem videoItem);
    }

    /**
     * Устанавливает слушатель действий
     *
     * @param listener Слушатель действий
     */
    public void setOnVideoActionListener(OnVideoActionListener listener) {
        this.actionListener = listener;
    }


    /**
     * Обновляет список видео
     *
     * @param videoList Новый список видео
     */
    public void setVideoList(List<VideoItem> videoList) {
        this.videoList = videoList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem videoItem = videoList.get(position);
        holder.bind(videoItem, actionListener);
    }

    @Override
    public int getItemCount() {
        return videoList.size();
    }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        private final TextView videoNameTextView;
        private final ImageButton downloadButton;

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoNameTextView = itemView.findViewById(R.id.tvVideoName);
            downloadButton = itemView.findViewById(R.id.btnDownloadVideo);
        }

        void bind(final VideoItem videoItem, final OnVideoActionListener listener) {
            videoNameTextView.setText(videoItem.getFilename());

            /*
            videoNameTextView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onVideoPlayClick(videoItem);
                }
            });*/


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