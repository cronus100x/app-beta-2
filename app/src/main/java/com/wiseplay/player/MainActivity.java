package com.wiseplay.player;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText urlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView title = new TextView(this);
        title.setText("Wiseplay Player");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 24);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        urlInput = new EditText(this);
        urlInput.setHint("https://ejemplo.com/video.m3u8");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button playButton = new Button(this);
        playButton.setText("Reproducir");
        playButton.setOnClickListener(view -> openPlayer());
        root.addView(playButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void openPlayer() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            urlInput.setError("Introduce una URL de vídeo");
            return;
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "La URL debe comenzar por http:// o https://", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("url_video", url);
        startActivity(intent);
    }
}
