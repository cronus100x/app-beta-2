package com.wiseplay.player;

import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

@UnstableApi
public class PlayerActivity extends AppCompatActivity {
    private ExoPlayer player;
    private PlayerView playerView;
    private DefaultTrackSelector trackSelector;
    private Channel channel;
    private TokenManager tokenManager;
    private boolean fullscreen;
    private boolean retryingUnauthorized;
    private long lastUnauthorizedRetry;
    private TextView errorText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.playerView);
        errorText = findViewById(R.id.errorText);
        tokenManager = TokenManager.get(this);

        String url = getIntent().getStringExtra("url_video");
        String name = getIntent().getStringExtra("channel_name");
        String group = getIntent().getStringExtra("channel_group");
        String image = getIntent().getStringExtra("channel_image");
        String userAgent = getIntent().getStringExtra("channel_user_agent");
        String referer = getIntent().getStringExtra("channel_referer");
        boolean isHost = getIntent().getBooleanExtra("channel_host", false);
        channel = new Channel(name, url, group, image, userAgent, referer, isHost, readHeaders());
        setTitle(channel.getName());
        ((TextView) findViewById(R.id.titleText)).setText(channel.getName());

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.fullscreenButton).setOnClickListener(v -> toggleFullscreen());
        findViewById(R.id.audioButton).setOnClickListener(v -> showTrackDialog(C.TRACK_TYPE_AUDIO, "Pista de audio"));
        findViewById(R.id.subtitleButton).setOnClickListener(v -> showTrackDialog(C.TRACK_TYPE_TEXT, "Subtítulos"));
        findViewById(R.id.aspectButton).setOnClickListener(v -> cycleAspectRatio());
        findViewById(R.id.retryButton).setOnClickListener(v -> preparePlayer(true));
        preparePlayer(false);
    }

    private Map<String, String> readHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        String raw = getIntent().getStringExtra("channel_headers");
        if (raw == null) return headers;
        for (String pair : raw.split("\\n")) {
            int separator = pair.indexOf(':');
            if (separator > 0) headers.put(pair.substring(0, separator).trim(), pair.substring(separator + 1).trim());
        }
        return headers;
    }

    private void preparePlayer(boolean fromRetry) {
        if (channel.getUrl().isEmpty()) {
            showError("El canal no tiene una URL válida");
            return;
        }
        if (player != null) player.release();
        errorText.setVisibility(View.GONE);
        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters().setMaxVideoSizeSd());

        String token = tokenManager.getValidToken();
        String playbackUrl = TokenManager.appendToken(channel.getUrl(), token);
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent(channel.getUserAgent().isEmpty() ? "WiseplayPlayer/2.0" : channel.getUserAgent());
        Map<String, String> requestHeaders = new LinkedHashMap<>(channel.getHeaders());
        if (!channel.getReferer().isEmpty()) requestHeaders.put("Referer", channel.getReferer());
        if (!token.isEmpty()) requestHeaders.put("Authorization", "Bearer " + token);
        httpFactory.setDefaultRequestProperties(requestHeaders);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
                .build();
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) errorText.setVisibility(View.GONE);
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (isUnauthorized(error) && !retryingUnauthorized
                        && System.currentTimeMillis() - lastUnauthorizedRetry > 3_000) {
                    retryingUnauthorized = true;
                    lastUnauthorizedRetry = System.currentTimeMillis();
                    tokenManager.renew();
                    Toast.makeText(PlayerActivity.this, "Sesión renovada, reintentando…", Toast.LENGTH_SHORT).show();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        preparePlayer(true);
                        retryingUnauthorized = false;
                    }, 250);
                } else {
                    showError("No se puede reproducir este canal: " + error.getErrorCodeName());
                }
            }
        });
        player.setMediaItem(MediaItem.fromUri(playbackUrl));
        player.prepare();
        player.play();
    }

    private boolean isUnauthorized(PlaybackException error) {
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                int code = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return code == 401 || code == 403;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void showTrackDialog(int trackType, String title) {
        if (player == null || player.getCurrentTracks().isEmpty()) {
            Toast.makeText(this, "No hay pistas disponibles", Toast.LENGTH_SHORT).show();
            return;
        }
        new TrackSelectionDialogBuilder(this, title, player, trackType)
                .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
                .build().show();
    }

    private void cycleAspectRatio() {
        int current = playerView.getResizeMode();
        int next = current == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : current == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
        playerView.setResizeMode(next);
        String label = next == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT ? "Ajustar"
                : next == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM ? "Zoom" : "Estirar";
        Toast.makeText(this, "Relación: " + label, Toast.LENGTH_SHORT).show();
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        Window window = getWindow();
        if (fullscreen) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) controller.hide(WindowInsets.Type.systemBars());
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) controller.show(WindowInsets.Type.systemBars());
            } else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    @Override protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override protected void onDestroy() {
        if (player != null) player.release();
        super.onDestroy();
    }
}
