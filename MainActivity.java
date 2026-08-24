package com.offlineplayer.cineoffline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_ZIP = 1001;
    private static final int REQ_FOLDER = 1002;
    private static final int REQ_COVER = 1003;

    private MovieRepository repo;
    private LinearLayout movieList;
    private TextView pageTitle;
    private TextView pageSubtitle;
    private TextView stats;
    private String filter = "all";
    private Movie pendingCoverMovie;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new MovieRepository(this);
        buildUi();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repo != null) refresh();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(246, 247, 251));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button menu = compactButton("☰");
        menu.setOnClickListener(this::showMenu);
        top.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, dp(10), 0);
        pageTitle = text("Cine Offline", 22, true, Color.rgb(32, 40, 58));
        pageSubtitle = text("Sua biblioteca local", 13, false, Color.DKGRAY);
        titles.addView(pageTitle);
        titles.addView(pageSubtitle);
        top.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button add = compactButton("+");
        add.setTextSize(27);
        add.setOnClickListener(v -> showImportChoice());
        top.addView(add, new LinearLayout.LayoutParams(dp(52), dp(48)));
        root.addView(top);

        stats = text("0 filmes  •  0 continuar  •  0 favoritos", 14, true, Color.rgb(74, 85, 104));
        stats.setPadding(dp(4), dp(18), dp(4), dp(12));
        root.addView(stats);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(filterButton("Biblioteca", "all"), new LinearLayout.LayoutParams(0, dp(44), 1));
        chips.addView(filterButton("Continuar", "continue"), new LinearLayout.LayoutParams(0, dp(44), 1));
        chips.addView(filterButton("Favoritos", "favorites"), new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(chips);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        movieList = new LinearLayout(this);
        movieList.setOrientation(LinearLayout.VERTICAL);
        movieList.setPadding(0, dp(12), 0, dp(28));
        scroll.addView(movieList, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Biblioteca");
        popup.getMenu().add("Continuar assistindo");
        popup.getMenu().add("Favoritos");
        popup.getMenu().add("Importar ZIP");
        popup.getMenu().add("Importar pasta");
        popup.getMenu().add("Sobre");
        popup.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.equals("Biblioteca")) setFilter("all");
            else if (t.equals("Continuar assistindo")) setFilter("continue");
            else if (t.equals("Favoritos")) setFilter("favorites");
            else if (t.equals("Importar ZIP")) pickZip();
            else if (t.equals("Importar pasta")) pickFolder();
            else showAbout();
            return true;
        });
        popup.show();
    }

    private Button filterButton(String title, String value) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(v -> setFilter(value));
        return b;
    }

    private Button compactButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void setFilter(String f) {
        filter = f;
        if (f.equals("continue")) {
            pageTitle.setText("Continuar assistindo");
            pageSubtitle.setText("Retome de onde parou");
        } else if (f.equals("favorites")) {
            pageTitle.setText("Favoritos");
            pageSubtitle.setText("Seus filmes preferidos");
        } else {
            pageTitle.setText("Cine Offline");
            pageSubtitle.setText("Sua biblioteca local");
        }
        refresh();
    }

    private void refresh() {
        if (movieList == null) return;
        List<Movie> all = repo.getAll();
        int cont = 0, fav = 0;
        for (Movie m : all) {
            if (isContinue(m)) cont++;
            if (m.favorite) fav++;
        }
        stats.setText(all.size() + " filmes  •  " + cont + " continuar  •  " + fav + " favoritos");

        List<Movie> visible = new ArrayList<>();
        for (Movie m : all) {
            if (filter.equals("favorites") && !m.favorite) continue;
            if (filter.equals("continue") && !isContinue(m)) continue;
            visible.add(m);
        }

        movieList.removeAllViews();
        if (visible.isEmpty()) {
            TextView empty = text(filter.equals("all") ? "Nenhum filme importado ainda.\nToque em + para adicionar um ZIP ou uma pasta." : "Nenhum filme nesta seção.", 16, false, Color.GRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(60), dp(20), dp(60));
            movieList.addView(empty);
            return;
        }
        for (Movie m : visible) movieList.addView(movieCard(m));
    }

    private View movieCard(Movie m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cp);

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.rgb(224, 228, 238));
        Bitmap bm = null;
        if (m.coverPath != null && !m.coverPath.isEmpty()) bm = BitmapFactory.decodeFile(m.coverPath);
        if (bm != null) cover.setImageBitmap(bm);
        else cover.setImageResource(android.R.drawable.ic_media_play);
        info.addView(cover, new LinearLayout.LayoutParams(dp(84), dp(110)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, 0, 0);
        TextView title = text(m.title, 18, true, Color.rgb(32, 40, 58));
        TextView meta = text(progressText(m), 13, false, Color.DKGRAY);
        words.addView(title);
        words.addView(meta);
        info.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(info);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(10), 0, 0);
        Button watch = new Button(this);
        watch.setAllCaps(false);
        watch.setText(isContinue(m) ? "▶ Continuar" : "▶ Assistir");
        watch.setOnClickListener(v -> openPlayer(m));
        actions.addView(watch, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button fav = compactButton(m.favorite ? "★" : "☆");
        fav.setTextSize(25);
        fav.setOnClickListener(v -> { m.favorite = !m.favorite; repo.save(m); refresh(); });
        actions.addView(fav, new LinearLayout.LayoutParams(dp(54), dp(48)));

        Button more = compactButton("⋮");
        more.setTextSize(24);
        more.setOnClickListener(v -> showMovieMenu(v, m));
        actions.addView(more, new LinearLayout.LayoutParams(dp(54), dp(48)));
        card.addView(actions);
        return card;
    }

    private void showMovieMenu(View anchor, Movie m) {
        PopupMenu p = new PopupMenu(this, anchor);
        p.getMenu().add("Renomear");
        p.getMenu().add("Alterar capa");
        p.getMenu().add("Zerar progresso");
        p.getMenu().add("Excluir filme");
        p.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.equals("Renomear")) renameMovie(m);
            else if (t.equals("Alterar capa")) pickCover(m);
            else if (t.equals("Zerar progresso")) { m.progressMs = 0; repo.save(m); refresh(); }
            else confirmDelete(m);
            return true;
        });
        p.show();
    }

    private void renameMovie(Movie m) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(m.title);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Renomear filme")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (!s.isEmpty()) { m.title = s; repo.save(m); refresh(); }
                }).show();
    }

    private void pickCover(Movie m) {
        pendingCoverMovie = m;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_COVER);
    }

    private void confirmDelete(Movie m) {
        new AlertDialog.Builder(this)
                .setTitle("Excluir filme?")
                .setMessage("A cópia offline importada pelo Cine Offline será apagada.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> { repo.delete(m); refresh(); })
                .show();
    }

    private void openPlayer(Movie m) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("movieId", m.id);
        startActivity(i);
    }

    private void showImportChoice() {
        new AlertDialog.Builder(this)
                .setTitle("Adicionar filme")
                .setItems(new String[]{"Importar ZIP", "Importar pasta"}, (d, which) -> {
                    if (which == 0) pickZip(); else pickFolder();
                }).show();
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "application/x-zip-compressed"});
        startActivityForResult(i, REQ_ZIP);
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQ_ZIP) {
            askTitleAndImport(uri, false, defaultName(uri));
        } else if (requestCode == REQ_FOLDER) {
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}
            askTitleAndImport(uri, true, "Filme offline");
        } else if (requestCode == REQ_COVER && pendingCoverMovie != null) {
            copyCover(uri, pendingCoverMovie);
        }
    }

    private String defaultName(Uri uri) {
        String name = "Filme offline";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return name;
    }

    private void askTitleAndImport(Uri uri, boolean folder, String defaultTitle) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(defaultTitle);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Nome do filme")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Importar", (d, w) -> startImport(uri, folder, input.getText().toString()))
                .show();
    }

    private void startImport(Uri uri, boolean folder, String title) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Importando filme");
        progress.setMessage("Preparando…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        executor.execute(() -> {
            MovieImporter.ProgressListener listener = text -> runOnUiThread(() -> progress.setMessage(text));
            ImportResult result = folder
                    ? MovieImporter.importFolder(getApplicationContext(), uri, title, listener)
                    : MovieImporter.importZip(getApplicationContext(), uri, title, listener);
            runOnUiThread(() -> {
                if (!isFinishing()) progress.dismiss();
                if (result.ok) {
                    repo.save(result.movie);
                    setFilter("all");
                    Toast.makeText(this, "Filme importado. Já pode assistir offline.", Toast.LENGTH_LONG).show();
                } else {
                    new AlertDialog.Builder(this).setTitle("Não foi possível importar").setMessage(result.error).setPositiveButton("OK", null).show();
                }
            });
        });
    }

    private void copyCover(Uri uri, Movie movie) {
        File out = new File(movie.folderPath, "cover.jpg");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new Exception("Arquivo não pôde ser aberto");
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) >= 0) if (n > 0) fos.write(b, 0, n);
            movie.coverPath = out.getAbsolutePath();
            repo.save(movie);
            refresh();
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao trocar capa: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        pendingCoverMovie = null;
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Cine Offline")
                .setMessage("Player local para filmes HLS salvos como index.m3u8 + segmentos .dat/.ts.\n\nNão usa internet e não contorna DRM ou criptografia.")
                .setPositiveButton("OK", null).show();
    }

    private boolean isContinue(Movie m) {
        return m.progressMs > 15_000 && (m.durationMs <= 0 || m.progressMs < m.durationMs - 30_000);
    }

    private String progressText(Movie m) {
        if (m.durationMs <= 0) return m.progressMs > 0 ? "Progresso salvo: " + time(m.progressMs) : "Pronto para assistir offline";
        int pct = (int) Math.min(100, Math.round((m.progressMs * 100.0) / m.durationMs));
        if (m.progressMs < 15_000) return "Duração: " + time(m.durationMs);
        return time(m.progressMs) + " / " + time(m.durationMs) + "  •  " + pct + "%";
    }

    private String time(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600;
        long min = (total % 3600) / 60;
        long sec = total % 60;
        return h > 0 ? String.format(Locale.getDefault(), "%d:%02d:%02d", h, min, sec)
                : String.format(Locale.getDefault(), "%d:%02d", min, sec);
    }

    private TextView text(String value, float size, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
