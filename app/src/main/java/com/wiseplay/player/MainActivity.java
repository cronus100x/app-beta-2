package com.wiseplay.player;

import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.Spinner;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final List<Channel> channels = new ArrayList<>();
    private final List<Channel> visibleChannels = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private EditText searchInput;
    private TextView statusText;
    private ProgressBar progressBar;
    private ArrayAdapter<String> channelAdapter;
    private ActivityResultLauncher<String[]> filePicker;
    private String lastRemoteUrl = "";
    private SharedPreferences preferences;
    private LinearLayout settingsPanel;
    private Spinner savedUrlSpinner;
    private ArrayAdapter<String> savedUrlAdapter;
    private final Set<String> favorites = new HashSet<>();
    private boolean favoriteOnly;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("wiseplay_preferences", MODE_PRIVATE);
        favorites.addAll(preferences.getStringSet("favorites", Collections.emptySet()));
        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onFileSelected);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 32, 24, 16);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Wiseplay Player");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 12);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settingsButton = new Button(this);
        settingsButton.setText("⚙");
        settingsButton.setTextSize(22);
        settingsButton.setContentDescription("Ajustes");
        header.addView(settingsButton, new LinearLayout.LayoutParams(64, 54));
        root.addView(header);

        TextView help = new TextView(this);
        help.setText("Carga una playlist M3U, M3U8 o JSON y toca un canal para reproducirlo.");
        help.setPadding(0, 0, 0, 12);
        root.addView(help);

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setVisibility(View.GONE);
        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("Ajustes · Playlists");
        settingsTitle.setTextSize(17);
        settingsPanel.addView(settingsTitle);
        urlInput = new EditText(this);
        urlInput.setHint("URL de la playlist (.m3u, .m3u8 o .json)");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        settingsPanel.addView(urlInput);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button urlButton = new Button(this);
        urlButton.setText("Cargar URL");
        urlButton.setOnClickListener(view -> loadFromUrl());
        actions.addView(urlButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button saveButton = new Button(this);
        saveButton.setText("Guardar URL");
        saveButton.setOnClickListener(view -> saveCurrentUrl());
        actions.addView(saveButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

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
        settingsPanel.addView(actions);
        LinearLayout savedRow = new LinearLayout(this);
        savedUrlSpinner = new Spinner(this);
        savedUrlAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        savedUrlSpinner.setAdapter(savedUrlAdapter);
        savedUrlSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < savedUrlAdapter.getCount()) urlInput.setText(savedUrlAdapter.getItem(position));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        Button deleteUrl = new Button(this);
        deleteUrl.setText("Eliminar");
        deleteUrl.setOnClickListener(view -> deleteSelectedUrl());
        savedRow.addView(savedUrlSpinner, new LinearLayout.LayoutParams(0, -2, 1));
        savedRow.addView(deleteUrl, new LinearLayout.LayoutParams(120, -2));
        settingsPanel.addView(savedRow);
        root.addView(settingsPanel);
        settingsButton.setOnClickListener(view -> settingsPanel.setVisibility(
                settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        Button favoritesButton = new Button(this);
        favoritesButton.setText("☆ Favoritos");
        favoritesButton.setOnClickListener(view -> {
            favoriteOnly = !favoriteOnly;
            favoritesButton.setText(favoriteOnly ? "★ Todos los favoritos" : "☆ Favoritos");
            filterChannels(searchInput == null ? "" : searchInput.getText().toString());
        });
        root.addView(favoritesButton);

        searchInput = new EditText(this);
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
        channelList.setOnItemClickListener((parent, view, position, id) -> openChannel(position));
        channelList.setOnItemLongClickListener((parent, view, position, id) -> {
            toggleFavorite(visibleChannels.get(position));
            return true;
        });
        root.addView(channelList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        refreshSavedUrls();
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
        filterChannels(searchInput == null ? "" : searchInput.getText().toString());
        statusText.setText("Playlist cargada desde " + source + ": " + channels.size() + " canales");
    }

    private void filterChannels(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        visibleChannels.clear();
        for (Channel channel : channels) {
            if ((!favoriteOnly || favorites.contains(channel.getUrl()))
                    && (normalized.isEmpty()
                    || channel.getName().toLowerCase().contains(normalized)
                    || channel.getGroup().toLowerCase().contains(normalized))) {
                visibleChannels.add(channel);
            }
        }
        channelAdapter.clear();
        for (Channel channel : visibleChannels) channelAdapter.add(
                favorites.contains(channel.getUrl()) ? "★ " + channel.getDisplayName() : channel.getDisplayName());
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

    private void openChannel(int position) {
        if (position < 0 || position >= visibleChannels.size()) return;
        Channel channel = visibleChannels.get(position);
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("channel", channel);
        intent.putExtra("channel_list", new ArrayList<>(visibleChannels));
        intent.putExtra("channel_index", position);
        startActivity(intent);
    }

    private void toggleFavorite(Channel channel) {
        if (!favorites.add(channel.getUrl())) favorites.remove(channel.getUrl());
        preferences.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        filterChannels(searchInput == null ? "" : searchInput.getText().toString());
        Toast.makeText(this, favorites.contains(channel.getUrl()) ? "Añadido a favoritos" : "Quitado de favoritos", Toast.LENGTH_SHORT).show();
    }

    private void saveCurrentUrl() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) { urlInput.setError("Introduce una URL válida"); return; }
        Set<String> urls = new LinkedHashSet<>(preferences.getStringSet("playlist_urls", Collections.emptySet()));
        urls.add(url); preferences.edit().putStringSet("playlist_urls", urls).apply();
        refreshSavedUrls(); Toast.makeText(this, "URL guardada", Toast.LENGTH_SHORT).show();
    }

    private void deleteSelectedUrl() {
        if (savedUrlAdapter == null || savedUrlAdapter.getCount() == 0) return;
        String selected = savedUrlAdapter.getItem(savedUrlSpinner.getSelectedItemPosition());
        Set<String> urls = new LinkedHashSet<>(preferences.getStringSet("playlist_urls", Collections.emptySet()));
        urls.remove(selected); preferences.edit().putStringSet("playlist_urls", urls).apply();
        refreshSavedUrls(); Toast.makeText(this, "URL eliminada", Toast.LENGTH_SHORT).show();
    }

    private void refreshSavedUrls() {
        if (savedUrlAdapter == null) return;
        savedUrlAdapter.clear();
        savedUrlAdapter.addAll(new ArrayList<>(preferences.getStringSet("playlist_urls", Collections.emptySet())));
        savedUrlAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
