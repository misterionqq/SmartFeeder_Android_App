package com.example.smartfeederzatichkav20;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Адаптер для отображения списка видео в RecyclerView
 */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    private List<VideoItem> videoList = new ArrayList<>();
    private OnVideoClickListener listener;

    /**
     * Интерфейс для обработки нажатий на элементы списка
     */
    public interface OnVideoClickListener {
        void onVideoClick(VideoItem videoItem);
    }

    /**
     * Устанавливает слушатель нажатий
     * @param listener Слушатель нажатий
     */
    public void setOnVideoClickListener(OnVideoClickListener listener) {
        this.listener = listener;
    }

    /**
     * Обновляет список видео
     * @param videoList Новый список видео
     */
    public void setVideoList(List<VideoItem> videoList) {
        this.videoList = videoList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem videoItem = videoList.get(position);
        holder.bind(videoItem);
    }

    @Override
    public int getItemCount() {
        return videoList.size();
    }

    class VideoViewHolder extends RecyclerView.ViewHolder {
        private Button videoButton;

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoButton = itemView.findViewById(R.id.btnVideo);
        }

        void bind(final VideoItem videoItem) {
            videoButton.setText(videoItem.getFilename());
            videoButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onVideoClick(videoItem);
                }
            });
        }
    }
} 