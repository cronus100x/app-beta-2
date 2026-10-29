package com.wiseplay.player;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.MediaItem;
import android.widget.Toast;

public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView playerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.playerView);
        String urlVideo = getIntent().getStringExtra("url_video");
        String channelName = getIntent().getStringExtra("channel_name");
        if (channelName != null && !channelName.isEmpty()) {
            setTitle(channelName);
        }

        if (urlVideo != null && !urlVideo.isEmpty()) {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Toast.makeText(PlayerActivity.this,
                            "No se puede reproducir este canal: " + error.getErrorCodeName(),
                            Toast.LENGTH_LONG).show();
                }
            });
            
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
