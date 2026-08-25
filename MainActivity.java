package com.offlineplayer.cineoffline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_ZIP = 1001;
    private static final int REQ_FOLDER = 1002;
    private static final int REQ_COVER = 1003;
    private static final int REQ_EXPORT_BACKUP = 1004;
    private static final int REQ_IMPORT_BACKUP = 1005;

    private static final int IMPORT_ZIP = 0;
    private static final int IMPORT_FOLDER_LINKED = 1;
    private static final int IMPORT_FOLDER_COPIED = 2;
    private static final int IMPORT_LIBRARY_LINKED = 3;

    private MovieRepository repo;
    private SeriesRepository seriesRepo;
    private LinearLayout pageContent;
    private LinearLayout bottomNav;
    private final List<TextView> navButtons = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statMovies;
    private TextView statSeries;
    private TextView statContinue;
    private TextView statFavorites;
    private EditText searchInput;
    private LinearLayout searchResults;
    private Movie pendingCoverMovie;
    private int pendingFolderMode = IMPORT_FOLDER_LINKED;
    private String page = "home";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BLUE);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        repo = new MovieRepository(this);
        seriesRepo = new SeriesRepository(this);
        buildUi();
        renderPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repo != null && pageContent != null) renderPage();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.screenGradient(this));
        root.setPadding(0, statusBarHeight(), 0, Math.max(dp(4), navigationBarHeight()));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(12), dp(14), dp(12));
        topBar.setBackground(Ui.topBarGradient(this));
        topBar.setElevation(dp(4));

        TextView menu = squareTopButton("☰");
        menu.setOnClickListener(v -> showSideMenu());
        topBar.addView(menu, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER);
        TextView title = text("Cine Offline", 20, true, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        TextView sub = text("Filmes locais, sem internet", 11, false, Color.argb(220, 255, 255, 255));
        sub.setGravity(Gravity.CENTER);
        brand.addView(title);
        brand.addView(sub);
        topBar.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView add = squareTopButton("+");
        add.setTextSize(27);
        add.setOnClickListener(v -> showImportChoice());
        topBar.addView(add, new LinearLayout.LayoutParams(dp(54), dp(54)));
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));

        FrameLayout stage = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setPadding(dp(14), dp(18), dp(14), dp(100));
        scroll.addView(pageContent, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stage.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(6), dp(6), dp(6), dp(6));
        Ui.card(bottomNav, this, 23);
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76), Gravity.BOTTOM);
        navLp.setMargins(dp(12), 0, dp(12), dp(10));
        stage.addView(bottomNav, navLp);

        addBottomNav("⌂", "Início", "home");
        addBottomNav("⌕", "Buscar", "search");
        addBottomNav("▦", "Biblioteca", "library");
        addBottomNav("◷", "Histórico", "history");
        addBottomNav("⚙", "Ajustes", "settings");

        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        updateBottomNav();
    }

    private TextView squareTopButton(String value) {
        TextView b = text(value, 24, true, Ui.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(Color.argb(245, 255, 255, 255), 15, this));
        b.setElevation(dp(2));
        return b;
    }

    private void addBottomNav(String icon, String label, String target) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setTag(target);
        item.setPadding(dp(2), dp(5), dp(2), dp(4));
        item.setOnClickListener(v -> setPage(target));

        TextView ic = text(icon, 19, true, Ui.MUTED);
        ic.setGravity(Gravity.CENTER);
        TextView tx = text(label, 9, true, Ui.MUTED);
        tx.setGravity(Gravity.CENTER);
        item.addView(ic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        item.addView(tx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        TextView holder = new TextView(this);
        holder.setTag(item);
        navButtons.add(holder);
        bottomNav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void updateBottomNav() {
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            View child = bottomNav.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout item = (LinearLayout) child;
            String target = String.valueOf(item.getTag());
            boolean active = page.equals(target);
            if (page.equals("favorites") && target.equals("home")) active = true;
            if (page.startsWith("series:") && target.equals("library")) active = true;
            item.setBackground(active ? Ui.softGradient(this, 18) : new ColorDrawable(Color.TRANSPARENT));
            for (int j = 0; j < item.getChildCount(); j++) {
                View v = item.getChildAt(j);
                if (v instanceof TextView) ((TextView) v).setTextColor(active ? Ui.PURPLE : Ui.MUTED);
            }
        }
    }

    private void setPage(String target) {
        page = target;
        updateBottomNav();
        renderPage();
    }

    private void renderPage() {
        if (pageContent == null) return;
        pageContent.removeAllViews();
        searchInput = null;
        searchResults = null;

        if (page.equals("home")) renderHome();
        else if (page.equals("search")) renderSearch();
        else if (page.equals("library")) renderLibrary(false);
        else if (page.equals("favorites")) renderLibrary(true);
        else if (page.equals("history")) renderHistory();
        else if (page.startsWith("series:")) renderSeriesDetail(page.substring("series:".length()));
        else renderSettings();
    }

    private void renderHome() {
        List<Movie> all = repo.getAll();
        List<Movie> continuing = new ArrayList<>();
        List<Movie> favorites = new ArrayList<>();
        List<Movie> recent = new ArrayList<>(all);
        int movieCount = 0;
        for (Movie m : all) {
            if (isContinue(m)) continuing.add(m);
            if (m.favorite) favorites.add(m);
            // Episódios organizados em uma série deixam de contar como filme avulso.
            if (seriesRepo.getAssignment(m.id) == null) movieCount++;
        }
        int seriesCount = seriesRepo.getAllSeries().size();
        Collections.sort(continuing, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
        Collections.sort(favorites, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
        Collections.sort(recent, (a, b) -> Long.compare(b.addedAt, a.addedAt));

        TextView hero = text("Cine Offline", 30, true, Color.WHITE);
        pageContent.addView(hero);
        TextView heroSub = text("Sua biblioteca de filmes que funciona mesmo sem internet.", 14, false, Color.argb(225, 255, 255, 255));
        heroSub.setPadding(0, dp(4), 0, dp(16));
        pageContent.addView(heroSub);

        // Contadores compactos: uma única barra, sem ocupar metade da tela.
        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(dp(6), dp(4), dp(6), dp(4));
        Ui.card(stats, this, 18);
        statMovies = homeStat(stats, String.valueOf(movieCount), "FILMES");
        statSeries = homeStat(stats, String.valueOf(seriesCount), "SÉRIES");
        statContinue = homeStat(stats, String.valueOf(continuing.size()), "CONTINUAR");
        statFavorites = homeStat(stats, String.valueOf(favorites.size()), "FAVORITOS");
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        statsLp.setMargins(0, 0, 0, dp(14));
        pageContent.addView(stats, statsLp);

        pageContent.addView(homeSection("▶", "Continuar", "Ver lista", continuing,
                "Nada em andamento", "Quando você parar um filme no meio, ele aparece aqui.", "continue"));

        pageContent.addView(homeSection("☆", "Favoritos", "Ver todos", favorites,
                "Sem favoritos", "Use a estrela em qualquer filme para deixar ele aqui.", "favorites"));

        pageContent.addView(homeSection("◷", "Adicionados recentemente", "Local", recent,
                "Sua biblioteca está vazia", "Use o botão + para importar seu primeiro filme offline.", "recent"));

        TextView random = text("🎲  Escolher algo para eu assistir", 13, true, Color.WHITE);
        random.setGravity(Gravity.CENTER);
        random.setBackground(Ui.rounded(Color.argb(38, 255, 255, 255), 16, this));
        random.setOnClickListener(v -> {
            List<Movie> movies = repo.getAll();
            if (movies.isEmpty()) Toast.makeText(this, "Importe um filme primeiro.", Toast.LENGTH_SHORT).show();
            else openPlayer(movies.get((int) (Math.random() * movies.size())));
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        rp.setMargins(0, dp(2), 0, dp(8));
        pageContent.addView(random, rp);
    }

    private TextView homeStat(LinearLayout parent, String number, String label) {
        if (parent.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(Ui.BORDER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(38));
            dlp.gravity = Gravity.CENTER_VERTICAL;
            parent.addView(divider, dlp);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(4), dp(3), dp(4), dp(3));

        TextView n = text(number, 20, true, Ui.BLUE);
        n.setGravity(Gravity.CENTER);
        TextView l = text(label, 8, true, Ui.MUTED);
        l.setGravity(Gravity.CENTER);
        box.addView(n);
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpLabel.setMargins(0, dp(2), 0, 0);
        box.addView(l, lpLabel);

        parent.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return n;
    }

    private View homeSection(String icon, String title, String badge, List<Movie> list,
                             String emptyTitle, String emptySub, String target) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        Ui.card(card, this, 25);
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outer.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(outer);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ttl = text(icon + " " + title, 20, true, Ui.TEXT);
        head.addView(ttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chip = text(badge, 10, true, Ui.MUTED);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.rounded(Color.rgb(247, 247, 251), 15, this));
        if (target.equals("continue")) chip.setOnClickListener(v -> setPage("library"));
        else if (target.equals("favorites")) chip.setOnClickListener(v -> setPage("favorites"));
        else chip.setOnClickListener(v -> setPage("library"));
        head.addView(chip, new LinearLayout.LayoutParams(dp(78), dp(34)));
        card.addView(head);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setPadding(dp(13), dp(13), dp(13), dp(13));
        inner.setBackground(Ui.rounded(Color.rgb(251, 251, 253), 20, this));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.setMargins(0, dp(12), 0, 0);
        card.addView(inner, ip);

        if (list.isEmpty()) {
            TextView et = text(emptyTitle, 16, true, Ui.TEXT);
            et.setGravity(Gravity.CENTER);
            inner.addView(et);
            TextView es = text(emptySub, 12, false, Ui.MUTED);
            es.setGravity(Gravity.CENTER);
            es.setPadding(0, dp(6), 0, dp(2));
            inner.addView(es);
            if (target.equals("recent")) {
                TextView importButton = text("+  Importar filme", 12, true, Ui.PURPLE);
                importButton.setGravity(Gravity.CENTER);
                importButton.setBackground(Ui.softGradient(this, 14));
                importButton.setOnClickListener(v -> showImportChoice());
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(160), dp(42));
                bp.setMargins(0, dp(12), 0, 0);
                inner.addView(importButton, bp);
            }
        } else {
            inner.setGravity(Gravity.NO_GRAVITY);
            int max = target.equals("recent") ? Math.min(2, list.size()) : 1;
            for (int i = 0; i < max; i++) {
                inner.addView(homeMovieRow(list.get(i)));
                if (i < max - 1) {
                    View line = new View(this);
                    line.setBackgroundColor(Ui.BORDER);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                    lp.setMargins(dp(4), dp(9), dp(4), dp(9));
                    inner.addView(line, lp);
                }
            }
        }
        return card;
    }

    private View homeMovieRow(Movie m) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(2), dp(2), dp(2));
        row.setOnClickListener(v -> openPlayer(m));

        ImageView cover = coverView(m, dp(68), dp(86), 14);
        row.addView(cover, new LinearLayout.LayoutParams(dp(68), dp(86)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(6), 0);
        TextView title = text(m.title, 15, true, Ui.TEXT);
        title.setMaxLines(2);
        info.addView(title);
        String homeMeta = progressText(m);
        String homeSeries = seriesMeta(m);
        if (!homeSeries.isEmpty()) homeMeta = homeSeries + " • " + homeMeta;
        TextView meta = text(homeMeta, 10, false, Ui.MUTED);
        meta.setPadding(0, dp(5), 0, 0);
        info.addView(meta);
        if (isContinue(m) && m.durationMs > 0) {
            ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            p.setMax(1000);
            p.setProgress((int) Math.min(1000, (m.progressMs * 1000L) / Math.max(1, m.durationMs)));
            p.setProgressTintList(ColorStateList.valueOf(Ui.PURPLE));
            p.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(231, 232, 241)));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
            plp.setMargins(0, dp(8), 0, 0);
            info.addView(p, plp);
        }
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView play = text("▶", 17, true, Color.WHITE);
        play.setGravity(Gravity.CENTER);
        play.setBackground(Ui.gradient(this, 16));
        row.addView(play, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return row;
    }

    private void renderSearch() {
        addPageHeading("Descobrir", "Encontre rapidamente qualquer filme da sua biblioteca.");

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(14), 0, dp(12), 0);
        Ui.card(searchBox, this, 18);
        TextView ic = text("⌕", 24, false, Ui.MUTED);
        ic.setGravity(Gravity.CENTER);
        searchBox.addView(ic, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT));
        searchInput = new EditText(this);
        searchInput.setHint("Buscar na biblioteca");
        searchInput.setHintTextColor(Color.rgb(155, 160, 180));
        searchInput.setTextColor(Ui.TEXT);
        searchInput.setTextSize(15);
        searchInput.setSingleLine(true);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setPadding(dp(6), 0, 0, 0);
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        sbp.setMargins(0, 0, 0, dp(14));
        pageContent.addView(searchBox, sbp);

        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        pageContent.addView(searchResults);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderSearchResults(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        renderSearchResults("");
    }

    private void renderSearchResults(String query) {
        if (searchResults == null) return;
        searchResults.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Movie> movies = repo.getAll();
        List<Movie> result = new ArrayList<>();
        for (Movie m : movies) {
            if (q.isEmpty() || m.title.toLowerCase(Locale.ROOT).contains(q)) result.add(m);
        }
        searchResults.addView(listSectionTitle(q.isEmpty() ? "Todos os filmes" : "Resultados", result.size()));
        if (result.isEmpty()) {
            searchResults.addView(simpleEmpty("Nada encontrado", "Tente outro nome ou importe um novo filme."));
        } else {
            for (Movie m : result) searchResults.addView(movieListCard(m));
        }
    }

    private void renderLibrary(boolean favoritesOnly) {
        addPageHeading(favoritesOnly ? "Favoritos" : "Biblioteca",
                favoritesOnly ? "Seus filmes marcados com estrela." : "Filmes avulsos e séries organizadas por você.");

        List<Movie> movies = repo.getAll();
        if (favoritesOnly) {
            List<Movie> filtered = new ArrayList<>();
            for (Movie m : movies) if (m.favorite) filtered.add(m);
            movies = filtered;
            Collections.sort(movies, (a, b) -> Long.compare(b.addedAt, a.addedAt));
            pageContent.addView(listSectionTitle("Seus favoritos", movies.size()));
            if (movies.isEmpty()) {
                pageContent.addView(simpleEmpty("Sem favoritos", "Toque na estrela de qualquer filme para adicionar aqui."));
            } else {
                for (Movie m : movies) pageContent.addView(movieListCard(m));
            }
            return;
        }

        List<SeriesRepository.SeriesInfo> series = seriesRepo.getAllSeries();
        if (!series.isEmpty()) {
            pageContent.addView(listSectionTitle("Séries", series.size()));
            for (SeriesRepository.SeriesInfo item : series) pageContent.addView(seriesCard(item));
        }

        List<Movie> loose = new ArrayList<>();
        for (Movie m : movies) if (seriesRepo.getAssignment(m.id) == null) loose.add(m);
        Collections.sort(loose, (a, b) -> Long.compare(b.addedAt, a.addedAt));

        String sectionTitle = series.isEmpty() ? "Minha biblioteca" : "Filmes avulsos";
        pageContent.addView(listSectionTitle(sectionTitle, loose.size()));
        if (loose.isEmpty()) {
            if (movies.isEmpty()) {
                pageContent.addView(simpleEmpty("Sua biblioteca está vazia", "Use o botão + no topo para importar um ZIP ou uma pasta."));
            } else {
                pageContent.addView(simpleEmpty("Tudo organizado", "Todos os itens da biblioteca já estão dentro de séries."));
            }
        } else {
            for (Movie m : loose) pageContent.addView(movieListCard(m));
        }
    }

    private void renderHistory() {
        addPageHeading("Histórico", "O que você assistiu recentemente.");
        List<Movie> movies = new ArrayList<>();
        for (Movie m : repo.getAll()) if (m.lastPlayedAt > 0) movies.add(m);
        Collections.sort(movies, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));

        if (!movies.isEmpty()) {
            TextView clear = text("🗑  Limpar histórico de assistidos", 12, true, Ui.PURPLE);
            clear.setGravity(Gravity.CENTER);
            clear.setBackground(Ui.rounded(Color.argb(245, 255, 255, 255), 16, this));
            clear.setOnClickListener(v -> confirmClearHistory());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            clp.setMargins(0, 0, 0, dp(14));
            pageContent.addView(clear, clp);
        }

        pageContent.addView(listSectionTitle("Reproduzidos recentemente", movies.size()));
        if (movies.isEmpty()) {
            pageContent.addView(simpleEmpty("Histórico vazio", "Quando você abrir um filme, ele aparece aqui automaticamente."));
        } else {
            for (Movie m : movies) pageContent.addView(movieListCard(m));
        }
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Limpar histórico?")
                .setMessage("Isso remove a lista de assistidos/reproduzidos recentemente. O progresso salvo de cada filme ou episódio não será apagado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar", (d, w) -> {
                    List<Movie> all = repo.getAll();
                    for (Movie m : all) {
                        m.lastPlayedAt = 0;
                        m.playCount = 0;
                    }
                    repo.saveAll(all);
                    renderPage();
                    Toast.makeText(this, "Histórico apagado.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void renderSettings() {
        addPageHeading("Ajustes", "Gerencie sua biblioteca e o Cine Offline.");

        LinearLayout quick = settingsCard("📦", "Importar filme", "Adicionar ZIP ou pasta com index.m3u8 e segmentos .dat/.ts");
        quick.setOnClickListener(v -> showImportChoice());
        pageContent.addView(quick);

        LinearLayout createSeries = settingsCard("▤", "Criar série", "Agrupe episódios e organize por temporada e número do episódio");
        createSeries.setOnClickListener(v -> showCreateSeriesDialog());
        pageContent.addView(createSeries);

        int capturedNames = OriginalAppBridge.getCapturedTitleCount(this);
        int savedNames = OriginalAppBridge.getSavedMappingCount(this);
        String captureSub = savedNames > 0
                ? savedNames + " nome(s) já salvos • pode apagar do app original depois de associar"
                : (capturedNames > 0 ? capturedNames + " nome(s) capturado(s) • falta associar e salvar"
                : "Abre o app original em Downloads e captura os nomes sem root");
        LinearLayout capture = settingsCard("↗", "Pegar nomes do app original", captureSub);
        capture.setOnClickListener(v -> showOriginalTitleTools());
        pageContent.addView(capture);

        LinearLayout backup = settingsCard("⇩", "Exportar backup da organização", "Salva nomes, séries, episódios, favoritos e progresso em um JSON pequeno");
        backup.setOnClickListener(v -> createBackupDocument());
        pageContent.addView(backup);

        LinearLayout restore = settingsCard("⇧", "Importar backup da organização", "Restaura os nomes e séries depois de reinstalar e importar a pasta novamente");
        restore.setOnClickListener(v -> pickBackupDocument());
        pageContent.addView(restore);

        LinearLayout fav = settingsCard("★", "Favoritos", "Abrir todos os filmes que você marcou com estrela");
        fav.setOnClickListener(v -> setPage("favorites"));
        pageContent.addView(fav);

        LinearLayout about = settingsCard("ⓘ", "Sobre o Cine Offline", "Recursos, funcionamento offline e informações do app");
        about.setOnClickListener(v -> showAbout());
        pageContent.addView(about);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(18), dp(17), dp(18), dp(17));
        Ui.card(info, this, 22);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        info.setLayoutParams(lp);
        info.addView(text("100% offline", 17, true, Ui.TEXT));
        TextView sub = text("O Cine Offline pode copiar os nomes que aparecem nos Downloads do app original. Depois que a associação código → nome for salva, você pode apagar os downloads do app original sem perder os nomes. O backup da organização também leva essas associações.", 12, false, Ui.MUTED);
        sub.setPadding(0, dp(7), 0, 0);
        info.addView(sub);
        pageContent.addView(info);
    }

    private void showOriginalTitleTools() {
        boolean installed = OriginalAppBridge.isOriginalAppInstalled(this);
        boolean accessibility = OriginalAppBridge.isAccessibilityEnabled(this);
        int captured = OriginalAppBridge.getCapturedTitleCount(this);
        int saved = OriginalAppBridge.getSavedMappingCount(this);
        boolean finished = OriginalAppBridge.isCaptureFinished(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(6), dp(20), dp(8));

        StringBuilder status = new StringBuilder();
        status.append("Esse método usa somente a tela de Downloads do app original e não precisa de root.\n\n");
        status.append("App original: ").append(installed ? "✅ instalado" : "❌ não encontrado").append('\n');
        status.append("Acessibilidade: ").append(accessibility ? "✅ ativada" : "❌ desativada").append('\n');
        status.append("Captura atual: ").append(captured).append(" nome(s)");
        if (finished && captured > 0) status.append(" ✅");
        status.append('\n').append("Associações salvas: ").append(saved).append(" nome(s)");
        status.append("\n\nDepois de associar e salvar, você pode remover os downloads do app original. Os nomes continuam guardados no Cine Offline e também entram no backup da organização.");

        TextView statusView = text(status.toString(), 14, false, Ui.TEXT);
        statusView.setLineSpacing(0f, 1.08f);
        content.addView(statusView);

        TextView hint = text("Ações", 12, true, Ui.MUTED);
        hint.setPadding(0, dp(18), 0, dp(8));
        content.addView(hint);

        TextView accessibilityBtn = null;
        if (!accessibility) {
            accessibilityBtn = modalActionButton("⚙ Ativar acessibilidade", false);
            content.addView(accessibilityBtn);
        }

        TextView openBtn = null;
        if (installed) {
            openBtn = modalActionButton(accessibility
                    ? "▶ Capturar nomes e abrir app original"
                    : "↗ Abrir app original", true);
            content.addView(openBtn);
        }

        TextView saveBtn = null;
        if (captured > 0) {
            saveBtn = modalActionButton("💾 Associar captura aos arquivos e salvar", false);
            content.addView(saveBtn);
        }

        TextView applyBtn = null;
        if (saved > 0) {
            applyBtn = modalActionButton("↻ Aplicar nomes já salvos à biblioteca", false);
            content.addView(applyBtn);
        }

        TextView clearBtn = null;
        if (captured > 0) {
            clearBtn = modalActionButton("🗑 Limpar somente a captura atual", false);
            content.addView(clearBtn);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pegar nomes do app original")
                .setView(scroll)
                .setNegativeButton("Fechar", null)
                .create();

        if (accessibilityBtn != null) {
            accessibilityBtn.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(this, "Ative o serviço Cine Offline e depois volte ao app.", Toast.LENGTH_LONG).show();
                OriginalAppBridge.openAccessibilitySettings(this);
            });
        }

        if (openBtn != null) {
            openBtn.setOnClickListener(v -> {
                dialog.dismiss();
                if (OriginalAppBridge.isAccessibilityEnabled(this)) {
                    OriginalAppBridge.beginCapture(this);
                    if (!OriginalAppBridge.openOriginalApp(this)) {
                        Toast.makeText(this, "Não consegui abrir o app original.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, "Vá em Downloads > Concluídos e deixe a lista no topo. A captura começou.", Toast.LENGTH_LONG).show();
                } else {
                    if (!OriginalAppBridge.openOriginalApp(this)) {
                        Toast.makeText(this, "Não consegui abrir o app original.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, "O app original foi aberto. Para capturar os nomes, ative a Acessibilidade do Cine Offline.", Toast.LENGTH_LONG).show();
                }
            });
        }

        if (saveBtn != null) {
            saveBtn.setOnClickListener(v -> {
                dialog.dismiss();
                applyOriginalCapturedNames();
            });
        }

        if (applyBtn != null) {
            applyBtn.setOnClickListener(v -> {
                dialog.dismiss();
                applySavedOriginalNames();
            });
        }

        if (clearBtn != null) {
            clearBtn.setOnClickListener(v -> {
                OriginalAppBridge.clearCapture(this);
                dialog.dismiss();
                Toast.makeText(this, "Captura atual apagada. Os nomes já salvos foram mantidos.", Toast.LENGTH_LONG).show();
                renderPage();
            });
        }

        dialog.show();
    }

    private void explainAndStartOriginalCapture() {
        new AlertDialog.Builder(this)
                .setTitle("Capturar nomes")
                .setMessage("Vou abrir o app onde você baixou os filmes.\n\n1. Entre em Downloads.\n2. Abra a lista de downloads concluídos.\n3. Deixe a lista no topo.\n4. O Cine Offline vai ler os nomes e rolar a lista automaticamente.\n\nIMPORTANTE: ainda não apague esses downloads. Primeiro volte ao Cine Offline e use ‘Associar captura aos arquivos e salvar’. Depois disso pode apagar do app original.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Abrir app original", (d, w) -> {
                    OriginalAppBridge.beginCapture(this);
                    if (!OriginalAppBridge.openOriginalApp(this)) {
                        Toast.makeText(this, "Não consegui abrir o app original.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, "Abra Downloads > Concluídos e deixe a lista no topo.", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void applyOriginalCapturedNames() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.PURPLE));
        content.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView message = text("Associando nomes aos códigos dos downloads…", 13, false, Ui.MUTED);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(14), 0, 0);
        content.addView(message);

        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Salvando identificação")
                .setView(content)
                .setCancelable(false)
                .create();
        progress.show();

        executor.execute(() -> {
            OriginalAppBridge.IdentificationResult result = OriginalAppBridge.identifyAndRename(
                    getApplicationContext(), repo,
                    txt -> runOnUiThread(() -> { if (!isFinishing()) message.setText(txt); })
            );
            runOnUiThread(() -> {
                if (!isFinishing()) progress.dismiss();
                if (!result.ok) {
                    new AlertDialog.Builder(this)
                            .setTitle("Não foi possível associar")
                            .setMessage(result.error + "\n\nDeixe o app original aberto na tela de Downloads e tente novamente antes de apagar os downloads.")
                            .setNegativeButton("Fechar", null)
                            .setPositiveButton("Abrir app original", (d, w) -> OriginalAppBridge.openOriginalApp(this))
                            .show();
                    return;
                }

                renderPage();
                int saved = OriginalAppBridge.getSavedMappingCount(this);
                StringBuilder msg = new StringBuilder();
                msg.append("✅ ").append(result.renamed).append(" título(s) atualizado(s).\n")
                        .append("• ").append(result.capturedTitles).append(" nomes capturados\n")
                        .append("• ").append(result.completedDownloads).append(" downloads associados\n")
                        .append("• ").append(saved).append(" associação(ões) guardada(s) no celular");
                if (result.notMatched > 0) {
                    msg.append("\n• ").append(result.notMatched).append(" item(ns) da biblioteca ainda sem associação");
                    msg.append("\n\n⚠️ Ainda NÃO apague os downloads do app original. Faça outra tentativa enquanto eles ainda estão lá, para o Cine Offline conseguir salvar os itens pendentes.");
                } else {
                    msg.append("\n\n✅ Todos os itens da biblioteca foram associados. Agora você pode apagar esses downloads do app original. Para se proteger caso desinstale o Cine Offline, use também ‘Exportar backup da organização’. ");
                }

                if (result.warning != null && !result.warning.trim().isEmpty()) {
                    msg.append("\n\n").append(result.warning.trim());
                }

                AlertDialog.Builder done = new AlertDialog.Builder(this)
                        .setTitle("Nomes salvos")
                        .setMessage(msg.toString())
                        .setPositiveButton("OK", null);

                if (result.notMatched > 0
                        && !OriginalAppBridge.getPendingLocalItems(this).isEmpty()
                        && !OriginalAppBridge.getPendingCapturedItems(this).isEmpty()) {
                    done.setNegativeButton("Resolver pendentes", (d, w) -> showPendingOriginalResolver());
                }
                done.show();
            });
        });
    }


    private void showPendingOriginalResolver() {
        List<OriginalAppBridge.PendingLocalItem> locals =
                new ArrayList<>(OriginalAppBridge.getPendingLocalItems(this));
        List<OriginalAppBridge.PendingCapturedItem> captured =
                new ArrayList<>(OriginalAppBridge.getPendingCapturedItems(this));

        if (locals.isEmpty()) {
            Toast.makeText(this, "Não há itens pendentes.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (captured.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Sem nomes pendentes")
                    .setMessage("Existem arquivos sem associação, mas a captura não deixou nomes individuais restantes para escolher.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        resolvePendingOriginalAt(locals, captured, 0);
    }

    private void resolvePendingOriginalAt(List<OriginalAppBridge.PendingLocalItem> locals,
                                          List<OriginalAppBridge.PendingCapturedItem> captured,
                                          int index) {
        if (index >= locals.size() || captured.isEmpty()) {
            OriginalAppBridge.clearPendingResolution(this);
            renderPage();

            OriginalAppBridge.IdentificationResult check =
                    OriginalAppBridge.applySavedMappings(this, repo);
            int remain = check.ok ? check.notMatched : 0;

            new AlertDialog.Builder(this)
                    .setTitle(remain == 0 ? "Tudo associado" : "Pendentes atualizados")
                    .setMessage(remain == 0
                            ? "✅ Todos os vídeos agora têm uma associação salva. Você pode exportar o backup e depois apagar os downloads do app original."
                            : "Ainda restam " + remain + " item(ns) sem associação.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        OriginalAppBridge.PendingLocalItem local = locals.get(index);
        String[] labels = new String[captured.size()];
        for (int i = 0; i < captured.size(); i++) {
            OriginalAppBridge.PendingCapturedItem item = captured.get(i);
            String size = item.sizeText == null || item.sizeText.trim().isEmpty()
                    ? pendingSize(item.sizeBytes)
                    : item.sizeText;
            labels[i] = item.title + (size.isEmpty() ? "" : "  •  " + size);
        }

        int suggested = closestPendingCaptured(local, captured);
        int[] selected = new int[]{suggested};

        String current = local.currentTitle == null || local.currentTitle.trim().isEmpty()
                ? "Item sem nome"
                : local.currentTitle.trim();
        StringBuilder detail = new StringBuilder();
        detail.append(current);
        if (local.durationMs > 0) detail.append("  •  ").append(durationShort(local.durationMs));
        if (local.sizeBytes > 0) detail.append("\\nTamanho local: ").append(pendingSize(local.sizeBytes));
        if (local.resource != null && local.resource.length() >= 8) {
            detail.append("\\nCódigo: …").append(local.resource.substring(local.resource.length() - 8));
        }
        detail.append("\\n\\nEscolha qual dos nomes capturados pertence a este arquivo. "
                + "O mais próximo pelo tamanho já vem marcado como sugestão.");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Resolver pendente " + (index + 1) + " de " + locals.size())
                .setMessage(detail.toString())
                .setSingleChoiceItems(labels, suggested, (d, which) -> selected[0] = which)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar e continuar", null)
                .create();

        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int which = selected[0];
                if (which < 0 || which >= captured.size()) {
                    Toast.makeText(this, "Escolha um nome.", Toast.LENGTH_SHORT).show();
                    return;
                }

                OriginalAppBridge.PendingCapturedItem picked = captured.get(which);
                boolean ok = OriginalAppBridge.saveManualPendingMapping(
                        this, repo, local.resource, picked);
                if (!ok) {
                    Toast.makeText(this, "Não consegui salvar essa associação.", Toast.LENGTH_LONG).show();
                    return;
                }

                dialog.dismiss();
                captured.remove(which);
                renderPage();
                resolvePendingOriginalAt(locals, captured, index + 1);
            });
        });
        dialog.show();
    }

    private int closestPendingCaptured(OriginalAppBridge.PendingLocalItem local,
                                       List<OriginalAppBridge.PendingCapturedItem> captured) {
        if (captured == null || captured.isEmpty()) return -1;
        if (local == null || local.sizeBytes <= 0) return 0;
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < captured.size(); i++) {
            long size = captured.get(i).sizeBytes;
            if (size <= 0) continue;
            long diff = Math.abs(local.sizeBytes - size);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private String pendingSize(long bytes) {
        if (bytes <= 0) return "";
        double mb = bytes / 1_000_000d;
        if (mb >= 1000d) return String.format(Locale.getDefault(), "%.2f GB", mb / 1000d);
        return String.format(Locale.getDefault(), "%.2f MB", mb);
    }

    private void applySavedOriginalNames() {
        OriginalAppBridge.IdentificationResult result = OriginalAppBridge.applySavedMappings(this, repo);
        if (!result.ok) {
            Toast.makeText(this, result.error, Toast.LENGTH_LONG).show();
            return;
        }
        renderPage();
        new AlertDialog.Builder(this)
                .setTitle("Nomes salvos aplicados")
                .setMessage(result.renamed + " título(s) atualizado(s).\n" +
                        result.unchanged + " já estavam corretos.\n" +
                        result.notMatched + " item(ns) não tinham uma associação salva.")
                .setPositiveButton("OK", null)
                .show();
    }

    private LinearLayout settingsCard(String icon, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(14), dp(15), dp(14));
        Ui.card(row, this, 22);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(lp);

        TextView ic = text(icon, 22, true, Ui.PURPLE);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.softGradient(this, 16));
        row.addView(ic, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(13), 0, dp(8), 0);
        words.addView(text(title, 15, true, Ui.TEXT));
        TextView s = text(subtitle, 10, false, Ui.MUTED);
        s.setPadding(0, dp(4), 0, 0);
        words.addView(s);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text("›", 27, false, Ui.MUTED);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(48)));
        return row;
    }

    private void addPageHeading(String title, String subtitle) {
        TextView t = text(title, 29, true, Color.WHITE);
        pageContent.addView(t);
        TextView s = text(subtitle, 14, false, Color.argb(225, 255, 255, 255));
        s.setPadding(0, dp(4), 0, dp(16));
        pageContent.addView(s);
    }

    private View listSectionTitle(String title, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(3), 0, dp(3), dp(10));
        TextView t = text(title, 18, true, Color.WHITE);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView c = text(count + (count == 1 ? " item" : " itens"), 10, true, Ui.PURPLE);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.rounded(Color.argb(245, 255, 255, 255), 14, this));
        row.addView(c, new LinearLayout.LayoutParams(dp(76), dp(30)));
        return row;
    }

    private View simpleEmpty(String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(22), dp(28), dp(22), dp(28));
        Ui.card(card, this, 24);
        TextView icon = text("▣", 38, true, Ui.PURPLE);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);
        TextView t = text(title, 18, true, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(6));
        card.addView(t);
        TextView s = text(subtitle, 12, false, Ui.MUTED);
        s.setGravity(Gravity.CENTER);
        card.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);
        return card;
    }

    private View movieListCard(Movie m) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        Ui.card(card, this, 22);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, dp(11));
        card.setLayoutParams(cp);

        ImageView cover = coverView(m, dp(88), dp(116), 16);
        cover.setOnClickListener(v -> openPlayer(m));
        card.addView(cover, new LinearLayout.LayoutParams(dp(88), dp(116)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), dp(2), dp(6), dp(2));
        TextView title = text(m.title, 16, true, Ui.TEXT);
        title.setMaxLines(2);
        title.setOnClickListener(v -> openPlayer(m));
        info.addView(title);

        String meta = progressText(m);
        String seriesMeta = seriesMeta(m);
        if (!seriesMeta.isEmpty()) meta = seriesMeta + " • " + meta;
        if (page.equals("history") && m.lastPlayedAt > 0) meta = "Visto " + relativeDate(m.lastPlayedAt) + " • " + meta;
        TextView details = text(meta, 10, false, Ui.MUTED);
        details.setPadding(0, dp(5), 0, dp(8));
        info.addView(details);

        if (m.durationMs > 0 && m.progressMs > 0) {
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgress((int) Math.min(1000, (m.progressMs * 1000L) / Math.max(1, m.durationMs)));
            progress.setProgressTintList(ColorStateList.valueOf(Ui.PURPLE));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(230, 232, 242)));
            info.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(9), 0, 0);
        TextView watch = text(isContinue(m) ? "▶ Continuar" : "▶ Assistir", 11, true, Color.WHITE);
        watch.setGravity(Gravity.CENTER);
        watch.setBackground(Ui.gradient(this, 14));
        watch.setOnClickListener(v -> openPlayer(m));
        actions.addView(watch, new LinearLayout.LayoutParams(dp(112), dp(40)));

        TextView fav = text(m.favorite ? "★" : "☆", 19, true, Ui.PURPLE);
        fav.setGravity(Gravity.CENTER);
        fav.setBackground(Ui.softGradient(this, 14));
        fav.setOnClickListener(v -> { m.favorite = !m.favorite; repo.save(m); renderPage(); });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(42), dp(40));
        fp.setMargins(dp(7), 0, 0, 0);
        actions.addView(fav, fp);
        info.addView(actions);
        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView more = text("⋮", 23, true, Ui.MUTED);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> showMovieMenu(m));
        card.addView(more, new LinearLayout.LayoutParams(dp(34), dp(60)));
        return card;
    }

    private ImageView coverView(Movie m, int width, int height, float radius) {
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Ui.softGradient(this, radius));
        cover.setClipToOutline(true);
        Bitmap bm = null;
        if (m.coverPath != null && !m.coverPath.isEmpty()) bm = BitmapFactory.decodeFile(m.coverPath);
        if (bm != null) {
            cover.setImageBitmap(bm);
        } else {
            cover.setImageResource(R.drawable.icone);
            cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            cover.setPadding(dp(14), dp(14), dp(14), dp(14));
        }
        return cover;
    }

    private View seriesCard(SeriesRepository.SeriesInfo series) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        Ui.card(card, this, 22);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, dp(11));
        card.setLayoutParams(cp);
        card.setOnClickListener(v -> setPage("series:" + series.id));

        TextView icon = text("▤", 25, true, Ui.PURPLE);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.softGradient(this, 16));
        card.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(13), 0, dp(8), 0);
        TextView name = text(series.name, 16, true, Ui.TEXT);
        name.setMaxLines(2);
        words.addView(name);
        int episodes = seriesRepo.countMovies(series.id);
        int seasons = seriesRepo.countSeasons(series.id);
        String details = episodes + (episodes == 1 ? " episódio" : " episódios");
        if (seasons > 0) details += " • " + seasons + (seasons == 1 ? " temporada" : " temporadas");
        TextView sub = text(details, 10, false, Ui.MUTED);
        sub.setPadding(0, dp(4), 0, 0);
        words.addView(sub);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = text("›", 28, false, Ui.MUTED);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(30), dp(54)));
        return card;
    }

    private void renderSeriesDetail(String seriesId) {
        SeriesRepository.SeriesInfo series = seriesRepo.getSeries(seriesId);
        if (series == null) {
            page = "library";
            updateBottomNav();
            renderLibrary(false);
            return;
        }

        addPageHeading(series.name, "Episódios organizados por temporada.");

        LinearLayout add = settingsCard("＋", "Adicionar episódios", "Escolha itens da biblioteca para colocar nesta série");
        add.setOnClickListener(v -> showAddEpisodesToSeries(series));
        pageContent.addView(add);

        LinearLayout rename = settingsCard("✎", "Renomear série", "Alterar somente o nome do grupo");
        rename.setOnClickListener(v -> showRenameSeries(series));
        pageContent.addView(rename);

        List<SeriesRepository.Assignment> assignments = seriesRepo.getAssignmentsForSeries(series.id);
        if (assignments.isEmpty()) {
            pageContent.addView(simpleEmpty("Série vazia", "Toque em “Adicionar episódios” para começar a organizar."));
        } else {
            int currentSeason = -1;
            for (SeriesRepository.Assignment a : assignments) {
                Movie movie = repo.getById(a.movieId);
                if (movie == null) continue;
                if (a.season != currentSeason) {
                    currentSeason = a.season;
                    TextView seasonTitle = text("Temporada " + currentSeason, 18, true, Color.WHITE);
                    seasonTitle.setPadding(dp(3), dp(8), 0, dp(10));
                    pageContent.addView(seasonTitle);
                }
                pageContent.addView(movieListCard(movie));
            }
        }

        LinearLayout delete = settingsCard("×", "Excluir série", "Remove apenas a organização; os vídeos continuam na biblioteca");
        delete.setOnClickListener(v -> confirmDeleteSeries(series));
        pageContent.addView(delete);
    }

    private String seriesMeta(Movie m) {
        if (seriesRepo == null || m == null) return "";
        SeriesRepository.Assignment a = seriesRepo.getAssignment(m.id);
        if (a == null) return "";
        SeriesRepository.SeriesInfo s = seriesRepo.getSeries(a.seriesId);
        StringBuilder out = new StringBuilder();
        if (s != null && s.name != null && !s.name.trim().isEmpty()) out.append(s.name.trim());
        if (a.season > 0) {
            if (out.length() > 0) out.append(" • ");
            out.append("T").append(a.season);
        }
        if (a.episode > 0) out.append(" E").append(a.episode);
        return out.toString();
    }

    private void showCreateSeriesDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nome da série");
        int pad = dp(18);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Criar série")
                .setMessage("Depois você pode adicionar os episódios e definir temporada e número de cada um.")
                .setView(wrap)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Criar", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Digite um nome para a série.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    SeriesRepository.SeriesInfo series = seriesRepo.createSeries(name);
                    page = "series:" + series.id;
                    updateBottomNav();
                    renderPage();
                    showAddEpisodesToSeries(series);
                }).show();
    }

    private void showRenameSeries(SeriesRepository.SeriesInfo series) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(series.name);
        input.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(18), 0, dp(18), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Renomear série")
                .setView(wrap)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        seriesRepo.renameSeries(series.id, name);
                        renderPage();
                    }
                }).show();
    }

    private void showAddEpisodesToSeries(SeriesRepository.SeriesInfo series) {
        List<Movie> available = new ArrayList<>();
        for (Movie m : repo.getAll()) {
            SeriesRepository.Assignment a = seriesRepo.getAssignment(m.id);
            if (a == null) available.add(m);
        }
        Collections.sort(available, (a, b) -> Long.compare(a.addedAt, b.addedAt));
        if (available.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Nenhum item disponível")
                    .setMessage("Todos os vídeos já estão organizados em alguma série. Para mover um episódio, abra o menu ⋮ dele e escolha “Editar série / episódio”.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String[] labels = new String[available.size()];
        boolean[] checked = new boolean[available.size()];
        for (int i = 0; i < available.size(); i++) {
            Movie m = available.get(i);
            labels[i] = m.title + "  •  " + durationShort(m.durationMs);
        }

        new AlertDialog.Builder(this)
                .setTitle("Adicionar episódios")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (d, w) -> {
                    List<Movie> selected = new ArrayList<>();
                    for (int i = 0; i < available.size(); i++) if (checked[i]) selected.add(available.get(i));
                    if (selected.isEmpty()) {
                        Toast.makeText(this, "Nenhum episódio selecionado.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    askBatchSeason(series, selected);
                }).show();
    }

    private void askBatchSeason(SeriesRepository.SeriesInfo series, List<Movie> selected) {
        EditText seasonInput = new EditText(this);
        seasonInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        seasonInput.setSingleLine(true);
        seasonInput.setHint("Temporada");
        seasonInput.setText("1");
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(18), 0, dp(18), 0);
        wrap.addView(seasonInput);
        new AlertDialog.Builder(this)
                .setTitle("Qual temporada?")
                .setMessage("Os itens selecionados serão numerados em sequência a partir do próximo episódio disponível. Depois você pode ajustar qualquer episódio pelo menu ⋮.")
                .setView(wrap)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Adicionar", (d, w) -> {
                    int season = positiveInt(seasonInput.getText().toString(), 1);
                    int episode = seriesRepo.nextEpisode(series.id, season);
                    for (Movie m : selected) seriesRepo.assign(m.id, series.id, season, episode++);
                    renderPage();
                    Toast.makeText(this, selected.size() + " episódio(s) adicionado(s).", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showSeriesAssignmentDialog(Movie movie) {
        List<SeriesRepository.SeriesInfo> all = seriesRepo.getAllSeries();
        if (all.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Nenhuma série criada")
                    .setMessage("Crie uma série primeiro e depois organize este item nela.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Criar série", (d, w) -> showCreateSeriesDialog())
                    .show();
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Escolher série")
                .setItems(names, (d, which) -> showSeasonEpisodeDialog(movie, all.get(which)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showSeasonEpisodeDialog(Movie movie, SeriesRepository.SeriesInfo series) {
        SeriesRepository.Assignment current = seriesRepo.getAssignment(movie.id);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), 0, dp(18), 0);

        EditText seasonInput = new EditText(this);
        seasonInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        seasonInput.setHint("Temporada");
        int seasonDefault = current != null && series.id.equals(current.seriesId) ? current.season : 1;
        seasonInput.setText(String.valueOf(seasonDefault));
        form.addView(seasonInput);

        EditText episodeInput = new EditText(this);
        episodeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        episodeInput.setHint("Episódio");
        int episodeDefault = current != null && series.id.equals(current.seriesId) && current.episode > 0
                ? current.episode : seriesRepo.nextEpisode(series.id, seasonDefault);
        episodeInput.setText(String.valueOf(episodeDefault));
        form.addView(episodeInput);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(series.name)
                .setMessage("Defina onde este vídeo fica dentro da série.")
                .setView(form)
                .setPositiveButton("Salvar", (d, w) -> {
                    int season = positiveInt(seasonInput.getText().toString(), 1);
                    int episode = positiveInt(episodeInput.getText().toString(), 1);
                    seriesRepo.assign(movie.id, series.id, season, episode);
                    renderPage();
                });
        if (current != null) {
            builder.setNegativeButton("Remover da série", (d, w) -> {
                seriesRepo.unassign(movie.id);
                renderPage();
            });
            builder.setNeutralButton("Cancelar", null);
        } else {
            builder.setNegativeButton("Cancelar", null);
        }
        builder.show();
    }

    private void confirmDeleteSeries(SeriesRepository.SeriesInfo series) {
        new AlertDialog.Builder(this)
                .setTitle("Excluir série?")
                .setMessage("Somente o grupo “" + series.name + "” será apagado. Nenhum filme ou episódio será excluído do celular.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir série", (d, w) -> {
                    seriesRepo.deleteSeries(series.id);
                    page = "library";
                    updateBottomNav();
                    renderPage();
                }).show();
    }

    private int positiveInt(String raw, int fallback) {
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            return value > 0 ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String durationShort(long ms) {
        if (ms <= 0) return "duração desconhecida";
        long total = ms / 1000L;
        long h = total / 3600L;
        long min = (total % 3600L) / 60L;
        if (h > 0) return h + "h " + min + "min";
        return min + "min";
    }

    private void showSideMenu() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), statusBarHeight() + dp(14), dp(18), dp(18));
        panel.setBackgroundColor(Color.WHITE);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.icone);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, 0, 0);
        words.addView(text("Cine Offline", 20, true, Ui.TEXT));
        words.addView(text("Sua biblioteca local", 11, false, Ui.MUTED));
        brand.addView(words);
        panel.addView(brand);

        View line = new View(this);
        line.setBackgroundColor(Ui.BORDER);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.setMargins(0, dp(18), 0, dp(12));
        panel.addView(line, lineLp);

        menuRow(panel, "⌂", "Início", "Resumo da sua biblioteca", () -> { dialog.dismiss(); setPage("home"); });
        menuRow(panel, "⌕", "Buscar", "Encontre qualquer filme", () -> { dialog.dismiss(); setPage("search"); });
        menuRow(panel, "▦", "Biblioteca", "Todos os filmes importados", () -> { dialog.dismiss(); setPage("library"); });
        menuRow(panel, "★", "Favoritos", "Filmes marcados com estrela", () -> { dialog.dismiss(); setPage("favorites"); });
        menuRow(panel, "◷", "Histórico", "Últimas reproduções", () -> { dialog.dismiss(); setPage("history"); });
        menuRow(panel, "+", "Importar filme", "ZIP ou pasta", () -> { dialog.dismiss(); showImportChoice(); });
        menuRow(panel, "⚙", "Ajustes", "Informações e opções", () -> { dialog.dismiss(); setPage("settings"); });

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.45f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.START);
        }
        dialog.show();
        if (w != null) w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86f), ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void menuRow(LinearLayout panel, String icon, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setOnClickListener(v -> action.run());
        TextView ic = text(icon, 20, true, Ui.PURPLE);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.softGradient(this, 14));
        row.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, 0, 0);
        words.addView(text(title, 14, true, Ui.TEXT));
        words.addView(text(sub, 10, false, Ui.MUTED));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
    }

    private void showMovieMenu(Movie m) {
        SeriesRepository.Assignment assignment = seriesRepo.getAssignment(m.id);
        String[] options = {
                m.favorite ? "Remover dos favoritos" : "Adicionar aos favoritos",
                "Renomear",
                "Alterar capa",
                assignment == null ? "Adicionar a uma série" : "Editar série / episódio",
                "Zerar progresso",
                "Excluir filme"
        };
        new AlertDialog.Builder(this)
                .setTitle(m.title)
                .setItems(options, (d, which) -> {
                    if (which == 0) { m.favorite = !m.favorite; repo.save(m); renderPage(); }
                    else if (which == 1) renameMovie(m);
                    else if (which == 2) pickCover(m);
                    else if (which == 3) showSeriesAssignmentDialog(m);
                    else if (which == 4) { m.progressMs = 0; repo.save(m); renderPage(); }
                    else confirmDelete(m);
                }).show();
    }

    private void renameMovie(Movie m) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(m.title);
        input.setSelectAllOnFocus(true);
        int pad = dp(18);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Renomear filme")
                .setView(wrap)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (!s.isEmpty()) { m.title = s; repo.save(m); renderPage(); }
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
                .setMessage(m.isLinked()
                        ? "O filme será removido da biblioteca. Os arquivos originais da pasta NÃO serão apagados."
                        : "A cópia importada pelo Cine Offline será apagada do armazenamento interno do app.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> { seriesRepo.unassign(m.id); repo.delete(m); renderPage(); })
                .show();
    }

    private void openPlayer(Movie m) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("movieId", m.id);
        startActivity(i);
    }

    private void showImportChoice() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        card.setBackground(Ui.rounded(Color.WHITE, 24, this));

        TextView title = text("Adicionar filme", 20, true, Ui.TEXT);
        card.addView(title);

        TextView subtitle = text("Escolha como adicionar. O modo rápido não copia o vídeo e costuma terminar em poucos segundos.", 12, false, Ui.MUTED);
        subtitle.setPadding(0, dp(5), 0, dp(16));
        card.addView(subtitle);

        View series = importChoiceRow("▤", "Criar série", "Crie uma série e depois adicione episódios por temporada", () -> {
            dialog.dismiss();
            showCreateSeriesDialog();
        });
        card.addView(series, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        View library = importChoiceRow("🎬", "Adicionar pasta com vários filmes", "Selecione a pasta Filmes • cada subpasta vira um filme • sem copiar os vídeos", () -> {
            dialog.dismiss();
            pickFolder(IMPORT_LIBRARY_LINKED);
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        llp.setMargins(0, dp(9), 0, 0);
        card.addView(library, llp);

        View fast = importChoiceRow("⚡", "Usar pasta original", "Para adicionar apenas um filme • não duplica o vídeo", () -> {
            dialog.dismiss();
            pickFolder(IMPORT_FOLDER_LINKED);
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        flp.setMargins(0, dp(9), 0, 0);
        card.addView(fast, flp);

        View safe = importChoiceRow("📥", "Copiar pasta para o app", "Mais seguro • pode apagar/mover a pasta original depois • demora mais", () -> {
            dialog.dismiss();
            pickFolder(IMPORT_FOLDER_COPIED);
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        slp.setMargins(0, dp(9), 0, 0);
        card.addView(safe, slp);

        View zip = importChoiceRow("📦", "Importar ZIP", "Extrai o ZIP para o Cine Offline • mostra o progresso da importação", () -> {
            dialog.dismiss();
            pickZip();
        });
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        zlp.setMargins(0, dp(9), 0, 0);
        card.addView(zip, zlp);

        TextView note = text("Dica: para filmes grandes, prefira ⚡ Usar pasta original.", 10, false, Ui.MUTED);
        note.setPadding(dp(4), dp(10), dp(4), 0);
        card.addView(note);

        TextView cancel = text("Cancelar", 12, true, Ui.MUTED);
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        clp.setMargins(0, dp(6), 0, 0);
        card.addView(cancel, clp);

        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getResources().getDisplayMetrics().widthPixels - dp(36);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
    }

    private View importChoiceRow(String icon, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setBackground(Ui.roundedStroke(Color.rgb(249, 249, 253), Ui.BORDER, 17, 1, this));
        row.setOnClickListener(v -> action.run());

        TextView ico = text(icon, 24, false, Ui.TEXT);
        ico.setGravity(Gravity.CENTER);
        row.addView(ico, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 15, true, Ui.TEXT);
        TextView s = text(subtitle, 10, false, Ui.MUTED);
        s.setMaxLines(2);
        s.setPadding(0, dp(3), 0, 0);
        texts.addView(t);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = text("›", 25, false, Ui.PURPLE);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "application/x-zip-compressed"});
        startActivityForResult(i, REQ_ZIP);
    }

    private void pickFolder(int mode) {
        pendingFolderMode = mode;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    private void createBackupDocument() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "cine_offline_backup.json");
        startActivityForResult(i, REQ_EXPORT_BACKUP);
    }

    private void pickBackupDocument() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "text/plain", "application/octet-stream"});
        startActivityForResult(i, REQ_IMPORT_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQ_EXPORT_BACKUP) {
            writeBackup(uri);
            return;
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            readAndRestoreBackup(uri);
            return;
        }

        if (requestCode == REQ_ZIP) {
            askTitleAndImport(uri, IMPORT_ZIP, defaultName(uri));
        } else if (requestCode == REQ_FOLDER) {
            // Só o modo rápido precisa manter acesso permanente à pasta original.
            if (pendingFolderMode == IMPORT_FOLDER_LINKED || pendingFolderMode == IMPORT_LIBRARY_LINKED) {
                try {
                    int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                        getContentResolver().takePersistableUriPermission(uri, flags);
                    }
                } catch (Exception ignored) {}
            }
            if (pendingFolderMode == IMPORT_LIBRARY_LINKED) {
                startLibraryImport(uri);
            } else {
                askTitleAndImport(uri, pendingFolderMode, "Filme offline");
            }
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

    private void askTitleAndImport(Uri uri, int mode, String defaultTitle) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(defaultTitle);
        input.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(18), 0, dp(18), 0);
        wrap.addView(input);

        String modeText;
        if (mode == IMPORT_FOLDER_LINKED) modeText = "⚡ Modo rápido: o filme será usado direto da pasta original, sem criar outra cópia.";
        else if (mode == IMPORT_FOLDER_COPIED) modeText = "📥 Modo seguro: os arquivos serão copiados para o Cine Offline.";
        else modeText = "📦 O ZIP será extraído para o armazenamento do Cine Offline.";

        new AlertDialog.Builder(this)
                .setTitle("Nome do filme")
                .setMessage(modeText + "\n\nVocê pode mudar o nome depois.")
                .setView(wrap)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Adicionar", (d, w) -> startImport(uri, mode, input.getText().toString()))
                .show();
    }

    private void startLibraryImport(Uri uri) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.PURPLE));
        content.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView message = text("🎬 Procurando filmes na pasta…", 13, false, Ui.MUTED);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(14), 0, 0);
        content.addView(message);

        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Adicionando biblioteca")
                .setView(content)
                .setCancelable(false)
                .create();
        progress.show();

        executor.execute(() -> {
            MovieImporter.LibraryImportResult result = MovieImporter.importLibraryFolderLinked(
                    getApplicationContext(), uri,
                    txt -> runOnUiThread(() -> message.setText(txt))
            );

            runOnUiThread(() -> {
                if (!isFinishing()) progress.dismiss();

                if (result.fatalError != null) {
                    new AlertDialog.Builder(this)
                            .setTitle("Não foi possível adicionar a pasta")
                            .setMessage(result.fatalError)
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                // Grava a biblioteca inteira em uma única operação. Antes cada filme
                // reabria e regravava o JSON completo, o que ficava lento com dezenas de itens.
                repo.saveAll(result.movies);

                if (!result.movies.isEmpty()) {
                    page = "library";
                    updateBottomNav();
                    renderPage();
                    // Não gera 47 capas logo após a importação. Abrir dezenas de segmentos
                    // de vídeo em sequência deixava o celular ocupado mesmo depois da lista aparecer.
                }

                String summary = result.movies.size() + (result.movies.size() == 1 ? " filme adicionado" : " filmes adicionados")
                        + " de " + result.discovered + " encontrado(s).";

                if (result.errors.isEmpty()) {
                    Toast.makeText(this, "🎬 " + summary, Toast.LENGTH_LONG).show();
                } else {
                    StringBuilder details = new StringBuilder(summary);
                    details.append("\n\nNão foi possível adicionar ").append(result.errors.size()).append(" pasta(s):");
                    int limit = Math.min(6, result.errors.size());
                    for (int i = 0; i < limit; i++) details.append("\n• ").append(result.errors.get(i));
                    if (result.errors.size() > limit) details.append("\n• … e mais ").append(result.errors.size() - limit);

                    new AlertDialog.Builder(this)
                            .setTitle("Importação concluída")
                            .setMessage(details.toString())
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        });
    }

    private void startImport(Uri uri, int mode, String title) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.PURPLE));
        content.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView message = text(mode == IMPORT_FOLDER_LINKED ? "⚡ Indexando sem copiar…" : "Preparando…", 13, false, Ui.MUTED);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(14), 0, 0);
        content.addView(message);

        String dialogTitle = mode == IMPORT_FOLDER_LINKED ? "Adicionando em modo rápido" : "Importando filme";
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setView(content)
                .setCancelable(false)
                .create();
        progress.show();

        executor.execute(() -> {
            MovieImporter.ProgressListener listener = txt -> runOnUiThread(() -> message.setText(txt));
            ImportResult result;
            if (mode == IMPORT_FOLDER_LINKED) {
                result = MovieImporter.importFolderLinked(getApplicationContext(), uri, title, listener);
            } else if (mode == IMPORT_FOLDER_COPIED) {
                result = MovieImporter.importFolderCopied(getApplicationContext(), uri, title, listener);
            } else {
                result = MovieImporter.importZip(getApplicationContext(), uri, title, listener);
            }

            runOnUiThread(() -> {
                if (!isFinishing()) progress.dismiss();
                if (result.ok) {
                    repo.save(result.movie);
                    page = "library";
                    updateBottomNav();
                    renderPage();

                    if (result.movie.isLinked()) {
                        Toast.makeText(this, "⚡ Filme adicionado sem copiar. Mantenha a pasta original no mesmo lugar.", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Filme importado e pronto para assistir offline.", Toast.LENGTH_LONG).show();
                    }

                    // A capa automática é criada DEPOIS que o filme já entrou na biblioteca.
                    // Assim ela não deixa a tela de importação presa por vários segundos.
                    createCoverInBackground(result.movie);
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Não foi possível importar")
                            .setMessage(result.error)
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        });
    }

    private void writeBackup(Uri uri) {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "cine-offline-organization-v1");
            root.put("createdAt", System.currentTimeMillis());

            List<Movie> all = repo.getAll();
            JSONArray movies = new JSONArray();
            Map<String, Movie> byId = new HashMap<>();
            for (Movie m : all) {
                byId.put(m.id, m);
                JSONObject o = new JSONObject();
                o.put("key", stableMovieKey(m));
                o.put("title", m.title);
                o.put("durationMs", m.durationMs);
                o.put("favorite", m.favorite);
                o.put("progressMs", m.progressMs);
                o.put("lastPlayedAt", m.lastPlayedAt);
                o.put("playCount", m.playCount);
                movies.put(o);
            }
            root.put("movies", movies);

            JSONArray series = new JSONArray();
            for (SeriesRepository.SeriesInfo item : seriesRepo.getAllSeries()) {
                JSONObject o = new JSONObject();
                o.put("id", item.id);
                o.put("name", item.name);
                series.put(o);
            }
            root.put("series", series);

            JSONArray assignments = new JSONArray();
            for (SeriesRepository.SeriesInfo item : seriesRepo.getAllSeries()) {
                for (SeriesRepository.Assignment a : seriesRepo.getAssignmentsForSeries(item.id)) {
                    Movie m = byId.get(a.movieId);
                    if (m == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("movieKey", stableMovieKey(m));
                    o.put("seriesId", a.seriesId);
                    o.put("season", a.season);
                    o.put("episode", a.episode);
                    assignments.put(o);
                }
            }
            root.put("assignments", assignments);

            // Também guarda a relação estável código da pasta -> nome capturado no app original.
            root.put("originalMappings", OriginalAppBridge.exportSavedMappings(this));
            root.put("originalCovers", OriginalAppBridge.exportSavedCovers(this));

            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("Não foi possível abrir o arquivo de destino.");
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Backup salvo. Guarde esse JSON fora do app.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao criar backup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readAndRestoreBackup(Uri uri) {
        try {
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                if (in == null) throw new Exception("Não foi possível abrir o backup.");
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) >= 0) if (n > 0) bos.write(buffer, 0, n);
                bytes = bos.toByteArray();
            }
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"cine-offline-organization-v1".equals(root.optString("format"))) {
                throw new Exception("Esse arquivo não é um backup compatível do Cine Offline.");
            }
            restoreBackup(root);
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao restaurar backup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restoreBackup(JSONObject root) throws Exception {
        int restoredOriginalMappings = OriginalAppBridge.importSavedMappings(this, root.optJSONObject("originalMappings"));
        int restoredOriginalCovers = OriginalAppBridge.importSavedCovers(this, root.optJSONObject("originalCovers"));
        List<Movie> current = repo.getAll();
        Map<String, Movie> currentByKey = new HashMap<>();
        Map<Long, List<Movie>> currentByDuration = new HashMap<>();
        for (Movie m : current) {
            currentByKey.put(stableMovieKey(m), m);
            List<Movie> sameDuration = currentByDuration.get(m.durationMs);
            if (sameDuration == null) {
                sameDuration = new ArrayList<>();
                currentByDuration.put(m.durationMs, sameDuration);
            }
            sameDuration.add(m);
        }

        JSONArray savedMovies = root.optJSONArray("movies");
        Map<String, Movie> restoredBySavedKey = new HashMap<>();
        int restoredNames = 0;
        if (savedMovies != null) {
            for (int i = 0; i < savedMovies.length(); i++) {
                JSONObject o = savedMovies.optJSONObject(i);
                if (o == null) continue;
                String savedKey = o.optString("key", "");
                Movie target = currentByKey.get(savedKey);

                // Fallback para itens que não possuem o hash original: só usa duração quando ela é única.
                if (target == null) {
                    long duration = o.optLong("durationMs", -1);
                    List<Movie> sameDuration = currentByDuration.get(duration);
                    if (sameDuration != null && sameDuration.size() == 1) target = sameDuration.get(0);
                }
                if (target == null) continue;

                String title = o.optString("title", "").trim();
                if (!title.isEmpty()) target.title = title;
                target.favorite = o.optBoolean("favorite", target.favorite);
                target.progressMs = Math.max(0, o.optLong("progressMs", target.progressMs));
                target.lastPlayedAt = Math.max(0, o.optLong("lastPlayedAt", target.lastPlayedAt));
                target.playCount = Math.max(0, o.optInt("playCount", target.playCount));
                restoredBySavedKey.put(savedKey, target);
                restoredNames++;
            }
        }
        repo.saveAll(current);
        // Reaplica também as capas salvas por código da pasta.
        try { OriginalAppBridge.applySavedMappings(this, repo); } catch (Exception ignored) {}

        // Recria as séries do backup e depois liga cada episódio ao filme atual correspondente.
        for (SeriesRepository.SeriesInfo old : new ArrayList<>(seriesRepo.getAllSeries())) {
            seriesRepo.deleteSeries(old.id);
        }
        Map<String, String> newSeriesIds = new HashMap<>();
        JSONArray savedSeries = root.optJSONArray("series");
        if (savedSeries != null) {
            for (int i = 0; i < savedSeries.length(); i++) {
                JSONObject o = savedSeries.optJSONObject(i);
                if (o == null) continue;
                String oldId = o.optString("id", "");
                String name = o.optString("name", "Série");
                SeriesRepository.SeriesInfo created = seriesRepo.createSeries(name);
                newSeriesIds.put(oldId, created.id);
            }
        }

        int restoredEpisodes = 0;
        JSONArray savedAssignments = root.optJSONArray("assignments");
        if (savedAssignments != null) {
            for (int i = 0; i < savedAssignments.length(); i++) {
                JSONObject o = savedAssignments.optJSONObject(i);
                if (o == null) continue;
                String key = o.optString("movieKey", "");
                Movie movie = currentByKey.get(key);
                if (movie == null) movie = restoredBySavedKey.get(key);
                String seriesId = newSeriesIds.get(o.optString("seriesId", ""));
                if (movie == null || seriesId == null) continue;
                seriesRepo.assign(movie.id, seriesId,
                        Math.max(1, o.optInt("season", 1)),
                        Math.max(1, o.optInt("episode", 1)));
                restoredEpisodes++;
            }
        }

        renderPage();
        new AlertDialog.Builder(this)
                .setTitle("Backup restaurado")
                .setMessage(restoredNames + " item(ns) tiveram nomes/dados restaurados.\n" +
                        newSeriesIds.size() + " série(s) recriada(s).\n" +
                        restoredEpisodes + " episódio(s) reorganizado(s).\n" +
                        restoredOriginalMappings + " associação(ões) do app original restaurada(s).\n" +
                        restoredOriginalCovers + " capa(s) restaurada(s).")
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Gera uma chave estável para os downloads do app original. A playlist reescrita ainda
     * contém o identificador hexadecimal da pasta dentro dos Content URIs, então essa chave
     * continua igual mesmo depois de desinstalar, reinstalar e importar a pasta novamente.
     */
    private String stableMovieKey(Movie movie) {
        String hash = findOriginalResourceHash(movie);
        if (!hash.isEmpty()) return "resource:" + hash.toUpperCase(Locale.ROOT);
        // Fallback apenas para outros formatos. O restore ainda exige correspondência segura.
        return "local:" + movie.id;
    }

    private String findOriginalResourceHash(Movie movie) {
        if (movie == null || movie.playlistPath == null || movie.playlistPath.isEmpty()) return "";
        File f = new File(movie.playlistPath);
        if (!f.exists() || !f.isFile()) return "";
        try (InputStream in = new java.io.FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buffer)) >= 0 && total < 512 * 1024) {
                if (n <= 0) continue;
                int take = Math.min(n, 512 * 1024 - total);
                bos.write(buffer, 0, take);
                total += take;
            }
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            for (int i = 0; i + 32 <= text.length(); i++) {
                String candidate = text.substring(i, i + 32);
                if (isHex32(candidate)) return candidate;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private boolean isHex32(String value) {
        if (value == null || value.length() != 32) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private void createCoverInBackground(Movie movie) {
        File cover = new File(movie.folderPath, "cover.jpg");
        if (cover.exists() && cover.length() > 0) return;
        executor.execute(() -> {
            boolean created = MovieImporter.createAutomaticCover(getApplicationContext(), movie);
            if (created) {
                runOnUiThread(() -> {
                    if (!isFinishing() && pageContent != null) renderPage();
                });
            }
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
            renderPage();
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao trocar capa: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        pendingCoverMovie = null;
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Cine Offline 3.3")
                .setMessage("Player local para filmes HLS salvos como index.m3u8 + segmentos .dat/.ts.\n\nRecursos: biblioteca, busca, favoritos, histórico, continuar de onde parou, capa automática, capa personalizada, velocidade, avanço/retorno de 10 s e tela cheia.\n\nA reprodução dos filmes importados não usa internet.")
                .setPositiveButton("OK", null).show();
    }

    private boolean isContinue(Movie m) {
        return m.progressMs > 15_000 && (m.durationMs <= 0 || m.progressMs < m.durationMs - 30_000);
    }

    private String progressText(Movie m) {
        if (m.durationMs <= 0) return m.progressMs > 0 ? "Progresso salvo em " + time(m.progressMs) : "Pronto para assistir offline";
        int pct = (int) Math.min(100, Math.round((m.progressMs * 100.0) / m.durationMs));
        if (m.progressMs < 15_000) return "Duração: " + time(m.durationMs);
        return time(m.progressMs) + " / " + time(m.durationMs) + " • " + pct + "%";
    }

    private String relativeDate(long ts) {
        long diff = Math.max(0, System.currentTimeMillis() - ts);
        long min = diff / 60_000;
        if (min < 1) return "agora";
        if (min < 60) return "há " + min + " min";
        long h = min / 60;
        if (h < 24) return "há " + h + " h";
        long d = h / 24;
        return "há " + d + (d == 1 ? " dia" : " dias");
    }

    private String time(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600;
        long min = (total % 3600) / 60;
        long sec = total % 60;
        return h > 0 ? String.format(Locale.getDefault(), "%d:%02d:%02d", h, min, sec)
                : String.format(Locale.getDefault(), "%d:%02d", min, sec);
    }

    private TextView modalActionButton(String label, boolean primary) {
        TextView button = text(label, 14, true, primary ? Color.WHITE : Ui.TEXT);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(13), dp(14), dp(13));
        button.setClickable(true);
        button.setFocusable(true);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (primary) {
            bg.setColor(Ui.PURPLE);
        } else {
            bg.setColor(Color.rgb(246, 246, 252));
            bg.setStroke(dp(1), Color.rgb(226, 226, 238));
        }
        button.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(7), 0, 0);
        button.setLayoutParams(lp);
        return button;
    }

    private TextView text(String value, float size, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
