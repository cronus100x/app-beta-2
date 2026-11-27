package com.wiseplay.player;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

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
    private static final int BG = Color.rgb(11, 16, 32);
    private static final int SURFACE = Color.rgb(21, 29, 51);
    private static final int SURFACE_ALT = Color.rgb(28, 39, 66);
    private static final int ACCENT = Color.rgb(91, 140, 255);
    private static final int MUTED = Color.rgb(155, 168, 199);
    private static final int PRIMARY = Color.rgb(245, 247, 255);

    private final List<Channel> channels = new ArrayList<>();
    private final List<Channel> visibleChannels = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> favorites = new HashSet<>();
    private SharedPreferences preferences;
    private ActivityResultLauncher<String[]> filePicker;
    private EditText urlInput, searchInput;
    private TextView statusText;
    private ProgressBar progressBar;
    private LinearLayout channelGrid, categoryBar, settingsPanel;
    private Spinner savedUrlSpinner;
    private ArrayAdapter<String> savedUrlAdapter;
    private String lastRemoteUrl = "";
    private String selectedCategory = "Todos";
    private boolean favoriteOnly;
    private boolean recentOnly;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("wiseplay_preferences", MODE_PRIVATE);
        favorites.addAll(preferences.getStringSet("favorites", Collections.emptySet()));
        filePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onFileSelected);
        buildUi();
        refreshSavedUrls();
        restoreCachedChannels();
        autoLoadSavedUrl();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(8), dp(8), dp(8), dp(8)); root.setBackgroundColor(BG);
        LinearLayout main = new LinearLayout(this); main.setOrientation(LinearLayout.HORIZONTAL); root.addView(main, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout sidebar = panel(); sidebar.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView catTitle = label("CATEGORÍAS", 11, MUTED); catTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); sidebar.addView(catTitle, wrap());
        ScrollView sideScroll = new ScrollView(this);
        categoryBar = new LinearLayout(this); categoryBar.setOrientation(LinearLayout.VERTICAL); sideScroll.addView(categoryBar); sidebar.addView(sideScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        main.addView(sidebar, new LinearLayout.LayoutParams(dp(132), -1));

        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(8), 0, 0, 0); main.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); content.addView(header, wrap());
        LinearLayout titleBox = new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL); header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = label("Wiseplay Player", 22, PRIMARY); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); titleBox.addView(title);
        statusText = label("Carga una playlist para comenzar", 12, MUTED); titleBox.addView(statusText);
        progressBar = new ProgressBar(this); progressBar.setVisibility(View.GONE); header.addView(progressBar, new LinearLayout.LayoutParams(dp(28), dp(28)));

        searchInput = edit("Buscar canal o categoría"); content.addView(searchInput, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0, dp(8), 0, dp(4)); content.addView(actions, wrap());
        Button refresh = action("Actualizar"); refresh.setOnClickListener(v -> { if (lastRemoteUrl.isEmpty()) toast("Carga primero una playlist remota"); else { urlInput.setText(lastRemoteUrl); loadFromUrl(); }}); actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button settings = action("⚙ Ajustes"); settings.setOnClickListener(v -> settingsPanel.setVisibility(settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)); actions.addView(settings, new LinearLayout.LayoutParams(0, dp(44), 1));

        settingsPanel = panel(); settingsPanel.setPadding(dp(10), dp(8), dp(10), dp(8)); settingsPanel.setVisibility(View.GONE); content.addView(settingsPanel, wrap());
        buildSettings();
        ScrollView scroll = new ScrollView(this); channelGrid = new LinearLayout(this); channelGrid.setOrientation(LinearLayout.VERTICAL); channelGrid.setPadding(dp(2), dp(2), dp(2), dp(20)); scroll.addView(channelGrid); content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        searchInput.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){ filterChannels(s.toString()); } public void afterTextChanged(Editable e){} });
    }

    private void buildSettings() {
        TextView t = label("Ajustes · Playlists", 16, PRIMARY); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); settingsPanel.addView(t, wrap());
        urlInput = edit("URL de la playlist (.m3u, .m3u8 o .json)"); settingsPanel.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout row = new LinearLayout(this); settingsPanel.addView(row, wrap());
        Button load = action("Cargar URL"); load.setOnClickListener(v -> loadFromUrl()); row.addView(load, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button save = action("Guardar"); save.setOnClickListener(v -> saveCurrentUrl()); row.addView(save, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button file = action("Abrir archivo"); file.setOnClickListener(v -> filePicker.launch(new String[]{"text/*","application/json","*/*"})); row.addView(file, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout saved = new LinearLayout(this); settingsPanel.addView(saved, wrap());
        savedUrlSpinner = new Spinner(this); savedUrlAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>()); savedUrlSpinner.setAdapter(savedUrlAdapter); savedUrlSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){ public void onItemSelected(android.widget.AdapterView<?> p, View v,int pos,long id){ if(pos>=0&&pos<savedUrlAdapter.getCount()) urlInput.setText(savedUrlAdapter.getItem(pos)); } public void onNothingSelected(android.widget.AdapterView<?> p){} }); saved.addView(savedUrlSpinner, new LinearLayout.LayoutParams(0, -2, 1));
        Button del = action("Eliminar"); del.setOnClickListener(v -> deleteSelectedUrl()); saved.addView(del, new LinearLayout.LayoutParams(dp(110), dp(44)));
    }

    private void rebuildCategories() {
        categoryBar.removeAllViews();
        addCategory("★ Favoritos  (" + favorites.size() + ")", favorites.size() > 0, true);
        addCategory("▶ Recientes  (" + recentUrls().size() + ")", !recentUrls().isEmpty(), false);
        addCategory("▦ Ver todos  (" + channels.size() + ")", true, false);
        Set<String> groups = new LinkedHashSet<>(); for (Channel c : channels) if (!c.getGroup().isEmpty()) groups.add(c.getGroup());
        for (String g : groups) { int count = 0; for (Channel c : channels) if (g.equals(c.getGroup())) count++; addCategory("• " + g + "  (" + count + ")", true, false); }
    }
    private void addCategory(String text, boolean enabled, boolean fav) { Button b = action(text); b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); b.setTextSize(13); b.setEnabled(enabled); b.setOnClickListener(v -> { favoriteOnly=fav; recentOnly=text.startsWith("▶"); selectedCategory=fav?"Favoritos":text; filterChannels(searchInput.getText().toString()); }); categoryBar.addView(b, new LinearLayout.LayoutParams(-1, dp(44))); }

    private void filterChannels(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(); visibleChannels.clear();
        List<String> recents = recentUrls();
        for (Channel c : channels) { boolean cat; if (favoriteOnly) cat = favorites.contains(c.getUrl()); else if (recentOnly) cat = recents.contains(c.getUrl()); else { String selectedGroup = selectedCategory.startsWith("• ") ? selectedCategory.substring(2, selectedCategory.indexOf("  (") >= 0 ? selectedCategory.indexOf("  (") : selectedCategory.length()) : ""; cat = selectedCategory.equals("Todos") || selectedCategory.startsWith("▦") || selectedCategory.equals("Ver todos") || (selectedCategory.startsWith("• ") && selectedGroup.equals(c.getGroup())); } boolean text = q.isEmpty() || c.getName().toLowerCase().contains(q) || c.getGroup().toLowerCase().contains(q); if(cat&&text) visibleChannels.add(c); }
        renderChannels();
    }
    private void renderChannels() {
        channelGrid.removeAllViews(); LinearLayout row = null;
        for (int i=0;i<visibleChannels.size();i++) { if(i%2==0){ row=new LinearLayout(this); row.setGravity(Gravity.TOP); channelGrid.addView(row, wrap()); } addCard(row, visibleChannels.get(i)); }
        statusText.setText(visibleChannels.size()+" canales disponibles"); channelGrid.requestLayout();
    }
    private void addCard(LinearLayout row, Channel c) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL); card.setPadding(dp(10), dp(4), dp(10), dp(10)); card.setBackground(cardBackground()); card.setOnClickListener(v -> openChannel(c)); card.setOnLongClickListener(v -> { toggleFavorite(c); return true; });
        TextView star=label(favorites.contains(c.getUrl())?"★":"☆",18,Color.rgb(255,209,102)); star.setGravity(Gravity.END); card.addView(star,new LinearLayout.LayoutParams(-1,dp(24)));
        ImageView logo = new ImageView(this); logo.setScaleType(ImageView.ScaleType.FIT_CENTER); logo.setPadding(dp(12),dp(8),dp(12),dp(8)); logo.setBackground(new ColorDrawable(ACCENT)); logo.setContentDescription("Imagen de " + c.getName()); card.addView(logo,new LinearLayout.LayoutParams(-1,dp(76))); loadLogo(c, logo);
        TextView name=label(c.getName(),13,PRIMARY); name.setGravity(Gravity.CENTER); name.setMaxLines(2); card.addView(name,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(166),1); cp.setMargins(dp(4),dp(4),dp(4),dp(4)); row.addView(card,cp);
    }

    private void openChannel(Channel c){ rememberRecent(c); int index=visibleChannels.indexOf(c); Intent i=new Intent(this,PlayerActivity.class); i.putExtra("channel",c); i.putExtra("channel_list",new ArrayList<>(visibleChannels)); i.putExtra("channel_index",index); startActivity(i); }
    private void loadLogo(Channel c, ImageView target) {
        String image = c.getImage().trim();
        if (image.startsWith("//")) image = "https:" + image;
        if (!image.startsWith("http://") && !image.startsWith("https://")) return;
        final String imageUrl = image;
        executor.execute(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(imageUrl).openConnection();
                con.setConnectTimeout(10000); con.setReadTimeout(15000); con.setInstanceFollowRedirects(true);
                if (!c.getUserAgent().isEmpty()) con.setRequestProperty("User-Agent", c.getUserAgent());
                if (!c.getReferer().isEmpty()) con.setRequestProperty("Referer", c.getReferer());
                try (InputStream input = con.getInputStream()) {
                    android.graphics.Bitmap bitmap = BitmapFactory.decodeStream(input);
                    if (bitmap != null) runOnUiThread(() -> target.setImageBitmap(bitmap));
                } finally { con.disconnect(); }
            } catch (Exception ignored) { }
        });
    }
    private void loadFromUrl(){ String u=urlInput.getText().toString().trim(); if(!u.startsWith("http://")&&!u.startsWith("https://")){urlInput.setError("Introduce una URL válida");return;} lastRemoteUrl=u; setLoading(true); executor.execute(()->{try{HttpURLConnection con=(HttpURLConnection)new URL(u).openConnection();con.setConnectTimeout(15000);con.setReadTimeout(30000);con.setRequestProperty("User-Agent","WiseplayPlayer/1.0");try(InputStream in=con.getInputStream()){List<Channel> p=PlaylistParser.parse(readText(in));runOnUiThread(()->showChannels(p,"URL"));}finally{con.disconnect();}}catch(Exception e){showError(e);}}); }
    private void onFileSelected(Uri uri){if(uri==null)return;setLoading(true);executor.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IllegalArgumentException("No se pudo abrir el archivo");List<Channel> p=PlaylistParser.parse(readText(in));runOnUiThread(()->showChannels(p,"archivo"));}catch(Exception e){showError(e);}});}
    private String readText(InputStream in)throws Exception{StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String l;while((l=r.readLine())!=null)s.append(l).append('\n');}return s.toString();}
    private void showChannels(List<Channel> p,String source){setLoading(false);channels.clear();channels.addAll(p);saveCachedChannels();lastRemoteUrl=urlInput.getText().toString().trim();selectedCategory="Todos";favoriteOnly=false;recentOnly=false;rebuildCategories();filterChannels("");statusText.setText("Playlist cargada desde "+source+": "+channels.size()+" canales");}
    private void toggleFavorite(Channel c){if(!favorites.add(c.getUrl()))favorites.remove(c.getUrl());preferences.edit().putStringSet("favorites",new HashSet<>(favorites)).apply();rebuildCategories();filterChannels(searchInput.getText().toString());toast(favorites.contains(c.getUrl())?"Añadido a favoritos":"Quitado de favoritos");}
    private void saveCachedChannels(){ preferences.edit().putString("cached_channels",new Gson().toJson(channels)).putLong("cached_at",System.currentTimeMillis()).apply(); }
    private void restoreCachedChannels(){
        String raw=preferences.getString("cached_channels",""); if(raw.isEmpty()) return;
        try { List<Channel> cached=new Gson().fromJson(raw,new TypeToken<List<Channel>>(){}.getType()); if(cached==null||cached.isEmpty()) return; channels.clear(); channels.addAll(cached); rebuildCategories(); filterChannels(""); long at=preferences.getLong("cached_at",0); statusText.setText("Canales guardados"+(at>0?" · lista local disponible":"")); } catch(Exception ignored) { }
    }
    private List<String> recentUrls(){ String raw=preferences.getString("recent_channels","[]"); try { List<String> list=new Gson().fromJson(raw,new TypeToken<List<String>>(){}.getType()); return list==null?new ArrayList<>():list; } catch(Exception e){ return new ArrayList<>(); } }
    private void rememberRecent(Channel c){ List<String> list=recentUrls(); list.remove(c.getUrl()); list.add(0,c.getUrl()); if(list.size()>10) list=new ArrayList<>(list.subList(0,10)); preferences.edit().putString("recent_channels",new Gson().toJson(list)).apply(); rebuildCategories(); }
    private void saveCurrentUrl(){String u=urlInput.getText().toString().trim();if(!u.startsWith("http://")&&!u.startsWith("https://")){urlInput.setError("Introduce una URL válida");return;}Set<String>s=new LinkedHashSet<>(preferences.getStringSet("playlist_urls",Collections.emptySet()));s.add(u);preferences.edit().putStringSet("playlist_urls",s).apply();refreshSavedUrls();toast("URL guardada");}
    private void deleteSelectedUrl(){if(savedUrlAdapter==null||savedUrlAdapter.getCount()==0)return;String u=savedUrlAdapter.getItem(savedUrlSpinner.getSelectedItemPosition());Set<String>s=new LinkedHashSet<>(preferences.getStringSet("playlist_urls",Collections.emptySet()));s.remove(u);preferences.edit().putStringSet("playlist_urls",s).apply();refreshSavedUrls();toast("URL eliminada");}
    private void refreshSavedUrls(){if(savedUrlAdapter==null)return;savedUrlAdapter.clear();savedUrlAdapter.addAll(new ArrayList<>(preferences.getStringSet("playlist_urls",Collections.emptySet())));savedUrlAdapter.notifyDataSetChanged();}
    private void autoLoadSavedUrl(){
        Set<String> urls = preferences.getStringSet("playlist_urls", Collections.emptySet());
        if (urls.isEmpty()) return;
        String saved = urls.iterator().next();
        if (saved != null && !saved.trim().isEmpty()) { urlInput.setText(saved); loadFromUrl(); }
    }
    private void showError(Exception e){runOnUiThread(()->{setLoading(false);String m=e.getMessage()==null?"No se pudo leer la playlist":e.getMessage();statusText.setText("Error: "+m);toast(m);});}
    private void setLoading(boolean b){runOnUiThread(()->progressBar.setVisibility(b?View.VISIBLE:View.GONE));}
    private String initials(String s){String[] p=s.trim().split("\\s+");return p.length>1?(p[0].substring(0,1)+p[1].substring(0,1)).toUpperCase():s.substring(0,Math.min(2,s.length())).toUpperCase();}
    private TextView label(String s,float size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private EditText edit(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(PRIMARY);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_TEXT);e.setPadding(dp(14),0,dp(14),0);e.setBackground(round(SURFACE,12,0,0));return e;}
    private Button action(String text){Button b=new Button(this);b.setText(text);b.setTextColor(PRIMARY);b.setTextSize(12);b.setAllCaps(false);b.setMinWidth(0);b.setPadding(dp(8),0,dp(8),0);b.setBackground(round(SURFACE_ALT,12,ACCENT,1));return b;}
    private LinearLayout panel(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setBackground(round(SURFACE,12,Color.rgb(38,52,85),1));return l;}
    private GradientDrawable cardBackground(){return round(SURFACE,12,Color.rgb(38,52,85),1);}
    private GradientDrawable round(int color,int radius,int stroke,int width){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(width>0)d.setStroke(dp(width),stroke);return d;}
    private LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
