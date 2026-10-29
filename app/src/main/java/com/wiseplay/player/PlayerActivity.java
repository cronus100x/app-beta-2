package com.wiseplay.player;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.MediaItem;

public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView playerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.playerView);
        String urlVideo = getIntent().getStringExtra("url_video");

        if (urlVideo != null && !urlVideo.isEmpty()) {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            
            MediaItem mediaItem = MediaItem.fromUri(urlVideo);
            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
        }
    }
}
