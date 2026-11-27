package com.wiseplay.player;

import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
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
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;

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
    private boolean dataSaver;
    private ArrayList<Channel> channelList = new ArrayList<>();
    private int channelIndex;
    private SharedPreferences preferences;
    private TextView errorText;
    private TextView qualityText;
    private View controlsOverlay;
    private final Handler controlsHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = () -> {
        if (controlsOverlay != null) controlsOverlay.setVisibility(View.GONE);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.playerView);
        errorText = findViewById(R.id.errorText);
        qualityText = findViewById(R.id.qualityText);
        controlsOverlay = findViewById(R.id.controlsOverlay);
        tokenManager = TokenManager.get(this);
        preferences = getSharedPreferences("wiseplay_preferences", MODE_PRIVATE);
        dataSaver = preferences.getBoolean("data_saver", false);

        channel = (Channel) getIntent().getSerializableExtra("channel");
        channelList = (ArrayList<Channel>) getIntent().getSerializableExtra("channel_list");
        if (channelList == null) channelList = new ArrayList<>();
        channelIndex = getIntent().getIntExtra("channel_index", 0);
        if (channel == null) { finish(); return; }
        setTitle(channel.getName());
        ((TextView) findViewById(R.id.titleText)).setText(channel.getName());

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        playerView.setOnClickListener(v -> toggleControls());
        findViewById(R.id.previousButton).setOnClickListener(v -> moveChannel(-1));
        findViewById(R.id.nextButton).setOnClickListener(v -> moveChannel(1));
        findViewById(R.id.saverButton).setOnClickListener(v -> {
            dataSaver = !dataSaver;
            preferences.edit().putBoolean("data_saver", dataSaver).apply();
            updateSaverButton();
            preparePlayer(true);
            showControls();
        });
        findViewById(R.id.retryButton).setOnClickListener(v -> { preparePlayer(true); showControls(); });
        updateSaverButton();
        updateQualityLabel();
        setFullscreen(true);
        preparePlayer(false);
    }

    private void updateSaverButton() {
        ((Button) findViewById(R.id.saverButton)).setText(dataSaver ? "Ahorro: sí" : "Ahorro: no");
    }

    private void updateQualityLabel() {
        if (qualityText == null) return;
        Format format = player == null ? null : player.getVideoFormat();
        int height = format == null ? Format.NO_VALUE : format.height;
        if (height > 0) {
            qualityText.setText(dataSaver
                    ? "Ahorro activo · calidad " + height + "p"
                    : "Calidad " + height + "p");
        } else {
            qualityText.setText(dataSaver
                    ? "Ahorro activo · calidad baja"
                    : "Calidad automática");
        }
    }

    private void showControls() {
        if (controlsOverlay == null) return;
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsHandler.removeCallbacks(hideControls);
        controlsHandler.postDelayed(hideControls, 3500L);
    }

    private void toggleControls() {
        if (controlsOverlay != null && controlsOverlay.getVisibility() == View.VISIBLE) {
            controlsHandler.removeCallbacks(hideControls);
            controlsOverlay.setVisibility(View.GONE);
        } else showControls();
    }

    private void moveChannel(int direction) {
        int target = channelIndex + direction;
        if (target < 0 || target >= channelList.size()) {
            Toast.makeText(this, direction < 0 ? "Primer canal" : "Último canal", Toast.LENGTH_SHORT).show();
            return;
        }
        channelIndex = target;
        channel = channelList.get(channelIndex);
        setTitle(channel.getName());
        ((TextView) findViewById(R.id.titleText)).setText(channel.getName());
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
        DefaultTrackSelector.Parameters.Builder trackParameters = trackSelector.buildUponParameters()
                .setPreferredAudioLanguages("es", "spa");
        if (dataSaver) trackParameters.setMaxVideoSizeSd().setMaxVideoBitrate(800_000);
        trackSelector.setParameters(trackParameters);

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
        androidx.media3.exoplayer.source.DefaultMediaSourceFactory mediaSourceFactory =
                new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory);
        if ("clearkey".equalsIgnoreCase(channel.getLicenseType())
                && !channel.getLicenseKey().isEmpty()) {
            try {
                DrmSessionManager drmSessionManager = new DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(new LocalMediaDrmCallback(
                                channel.getLicenseKey().getBytes(StandardCharsets.UTF_8)));
                mediaSourceFactory.setDrmSessionManagerProvider(mediaItem -> drmSessionManager);
            } catch (RuntimeException drmError) {
                showError("No se pudo inicializar ClearKey: " + drmError.getMessage());
            }
        }
        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) errorText.setVisibility(View.GONE);
                updateQualityLabel();
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                updateQualityLabel();
            }
            @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                updateQualityLabel();
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
        MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(playbackUrl);
        if ("clearkey".equalsIgnoreCase(channel.getLicenseType())
                && !channel.getLicenseKey().isEmpty()) {
            mediaItemBuilder.setDrmConfiguration(
                    new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build());
        }
        player.setMediaItem(mediaItemBuilder.build());
        player.prepare();
        player.play();
        updateQualityLabel();
        showControls();
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

    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
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
        controlsHandler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        super.onDestroy();
    }
}
