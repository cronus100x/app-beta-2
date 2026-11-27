package com.wiseplay.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final List<Channel> channels = new ArrayList<>();
    private final List<Channel> visibleChannels = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private TextView statusText;
    private ProgressBar progressBar;
    private ArrayAdapter<String> channelAdapter;
    private ActivityResultLauncher<String[]> filePicker;
    private String lastRemoteUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onFileSelected);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 32, 24, 16);

        TextView title = new TextView(this);
        title.setText("Wiseplay Player");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 12);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Carga una playlist M3U, M3U8 o JSON y toca un canal para reproducirlo.");
        help.setPadding(0, 0, 0, 12);
        root.addView(help);

        urlInput = new EditText(this);
        urlInput.setHint("URL de la playlist (.m3u, .m3u8 o .json)");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlInput);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button urlButton = new Button(this);
        urlButton.setText("Cargar URL");
        urlButton.setOnClickListener(view -> loadFromUrl());
        actions.addView(urlButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button refreshButton = new Button(this);
        refreshButton.setText("Actualizar");
        refreshButton.setOnClickListener(view -> {
            if (lastRemoteUrl.isEmpty()) {
                Toast.makeText(this, "Carga primero una playlist remota", Toast.LENGTH_SHORT).show();
            } else {
                urlInput.setText(lastRemoteUrl);
                loadFromUrl();
            }
        });
        actions.addView(refreshButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button fileButton = new Button(this);
        fileButton.setText("Abrir archivo");
        fileButton.setOnClickListener(view -> filePicker.launch(new String[]{
                "text/*", "application/json", "application/octet-stream", "*/*"}));
        actions.addView(fileButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(actions);

        EditText searchInput = new EditText(this);
        searchInput.setHint("Buscar canal o grupo");
        searchInput.setSingleLine(true);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterChannels(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        root.addView(searchInput);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        statusText = new TextView(this);
        statusText.setText("Aún no hay ninguna playlist cargada.");
        statusText.setPadding(0, 8, 0, 8);
        root.addView(statusText);

        ListView channelList = new ListView(this);
        channelAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        channelList.setAdapter(channelAdapter);
        channelList.setOnItemClickListener((parent, view, position, id) -> openChannel(visibleChannels.get(position)));
        root.addView(channelList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void loadFromUrl() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            urlInput.setError("Introduce una URL http:// o https://");
            return;
        }
        setLoading(true);
        lastRemoteUrl = url;
        executor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "WiseplayPlayer/1.0");
                try (InputStream input = connection.getInputStream()) {
                    String content = readText(input);
                    List<Channel> parsed = PlaylistParser.parse(content);
                    runOnUiThread(() -> showChannels(parsed, "URL"));
                } finally {
                    connection.disconnect();
                }
            } catch (Exception exception) {
                showError(exception);
            }
        });
    }

    private void onFileSelected(Uri uri) {
        if (uri == null) {
            return;
        }
        setLoading(true);
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalArgumentException("No se pudo abrir el archivo");
                }
                List<Channel> parsed = PlaylistParser.parse(readText(input));
                runOnUiThread(() -> showChannels(parsed, "archivo"));
            } catch (Exception exception) {
                showError(exception);
            }
        });
    }

    private String readText(InputStream input) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    private void showChannels(List<Channel> parsed, String source) {
        setLoading(false);
        channels.clear();
        channels.addAll(parsed);
        visibleChannels.clear();
        visibleChannels.addAll(parsed);
        channelAdapter.clear();
        for (Channel channel : channels) {
            channelAdapter.add(channel.getDisplayName());
        }
        channelAdapter.notifyDataSetChanged();
        statusText.setText("Playlist cargada desde " + source + ": " + channels.size() + " canales");
    }

    private void filterChannels(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        visibleChannels.clear();
        for (Channel channel : channels) {
            if (normalized.isEmpty()
                    || channel.getName().toLowerCase().contains(normalized)
                    || channel.getGroup().toLowerCase().contains(normalized)) {
                visibleChannels.add(channel);
            }
        }
        channelAdapter.clear();
        for (Channel channel : visibleChannels) channelAdapter.add(channel.getDisplayName());
        channelAdapter.notifyDataSetChanged();
    }

    private void showError(Exception exception) {
        runOnUiThread(() -> {
            setLoading(false);
            String message = exception.getMessage() == null ? "No se pudo leer la playlist" : exception.getMessage();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            statusText.setText("Error: " + message);
        });
    }

    private void setLoading(boolean loading) {
        runOnUiThread(() -> progressBar.setVisibility(loading ? View.VISIBLE : View.GONE));
    }

    private void openChannel(Channel channel) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("url_video", channel.getUrl());
        intent.putExtra("channel_name", channel.getName());
        intent.putExtra("channel_group", channel.getGroup());
        intent.putExtra("channel_image", channel.getImage());
        intent.putExtra("channel_user_agent", channel.getUserAgent());
        intent.putExtra("channel_referer", channel.getReferer());
        intent.putExtra("channel_host", channel.isHost());
        intent.putExtra("channel_license_type", channel.getLicenseType());
        intent.putExtra("channel_license_key", channel.getLicenseKey());
        StringBuilder headers = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : channel.getHeaders().entrySet()) {
            headers.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        intent.putExtra("channel_headers", headers.toString());
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
