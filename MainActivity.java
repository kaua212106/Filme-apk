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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_ZIP = 1001;
    private static final int REQ_FOLDER = 1002;
    private static final int REQ_COVER = 1003;

    private MovieRepository repo;
    private LinearLayout pageContent;
    private LinearLayout bottomNav;
    private final List<TextView> navButtons = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statMovies;
    private TextView statContinue;
    private TextView statFavorites;
    private EditText searchInput;
    private LinearLayout searchResults;
    private Movie pendingCoverMovie;
    private String page = "home";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BLUE);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        repo = new MovieRepository(this);
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
        else renderSettings();
    }

    private void renderHome() {
        List<Movie> all = repo.getAll();
        List<Movie> continuing = new ArrayList<>();
        List<Movie> favorites = new ArrayList<>();
        List<Movie> recent = new ArrayList<>(all);
        for (Movie m : all) {
            if (isContinue(m)) continuing.add(m);
            if (m.favorite) favorites.add(m);
        }
        Collections.sort(continuing, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
        Collections.sort(favorites, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
        Collections.sort(recent, (a, b) -> Long.compare(b.addedAt, a.addedAt));

        TextView hero = text("Cine Offline", 30, true, Color.WHITE);
        pageContent.addView(hero);
        TextView heroSub = text("Sua biblioteca de filmes que funciona mesmo sem internet.", 14, false, Color.argb(225, 255, 255, 255));
        heroSub.setPadding(0, dp(4), 0, dp(16));
        pageContent.addView(heroSub);

        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER);
        statMovies = homeStat(stats, String.valueOf(all.size()), "FILMES");
        statContinue = homeStat(stats, String.valueOf(continuing.size()), "CONTINUAR");
        statFavorites = homeStat(stats, String.valueOf(favorites.size()), "FAVORITOS");
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112));
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        Ui.card(box, this, 20);
        TextView n = text(number, 27, false, Ui.BLUE);
        n.setGravity(Gravity.CENTER);
        TextView l = text(label, 9, true, Ui.MUTED);
        l.setGravity(Gravity.CENTER);
        box.addView(n);
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpLabel.setMargins(0, dp(8), 0, 0);
        box.addView(l, lpLabel);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(box, lp);
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
        TextView meta = text(progressText(m), 10, false, Ui.MUTED);
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
                favoritesOnly ? "Seus filmes marcados com estrela." : "Todos os filmes salvos neste aparelho.");
        List<Movie> movies = repo.getAll();
        if (favoritesOnly) {
            List<Movie> filtered = new ArrayList<>();
            for (Movie m : movies) if (m.favorite) filtered.add(m);
            movies = filtered;
        }
        Collections.sort(movies, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        pageContent.addView(listSectionTitle(favoritesOnly ? "Seus favoritos" : "Minha biblioteca", movies.size()));
        if (movies.isEmpty()) {
            pageContent.addView(simpleEmpty(favoritesOnly ? "Sem favoritos" : "Sua biblioteca está vazia",
                    favoritesOnly ? "Toque na estrela de qualquer filme para adicionar aqui." : "Use o botão + no topo para importar um ZIP ou uma pasta."));
        } else {
            for (Movie m : movies) pageContent.addView(movieListCard(m));
        }
    }

    private void renderHistory() {
        addPageHeading("Histórico", "O que você assistiu recentemente.");
        List<Movie> movies = new ArrayList<>();
        for (Movie m : repo.getAll()) if (m.lastPlayedAt > 0) movies.add(m);
        Collections.sort(movies, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
        pageContent.addView(listSectionTitle("Reproduzidos recentemente", movies.size()));
        if (movies.isEmpty()) {
            pageContent.addView(simpleEmpty("Histórico vazio", "Quando você abrir um filme, ele aparece aqui automaticamente."));
        } else {
            for (Movie m : movies) pageContent.addView(movieListCard(m));
        }
    }

    private void renderSettings() {
        addPageHeading("Ajustes", "Gerencie sua biblioteca e o Cine Offline.");

        LinearLayout quick = settingsCard("📦", "Importar filme", "Adicionar ZIP ou pasta com index.m3u8 e segmentos .dat/.ts");
        quick.setOnClickListener(v -> showImportChoice());
        pageContent.addView(quick);

        LinearLayout fav = settingsCard("★", "Favoritos", "Abrir todos os filmes que você marcou com estrela");
        fav.setOnClickListener(v -> setPage("favorites"));
        pageContent.addView(fav);

        LinearLayout about = settingsCard("ⓘ", "Sobre o Cine Offline", "Recursos, funcionamento offline e informações da versão 3.0");
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
        TextView sub = text("O Cine Offline não usa internet para reproduzir filmes já importados. O progresso, favoritos e histórico ficam salvos no aparelho.", 12, false, Ui.MUTED);
        sub.setPadding(0, dp(7), 0, 0);
        info.addView(sub);
        pageContent.addView(info);
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
        String[] options = {
                m.favorite ? "Remover dos favoritos" : "Adicionar aos favoritos",
                "Renomear",
                "Alterar capa",
                "Zerar progresso",
                "Excluir filme"
        };
        new AlertDialog.Builder(this)
                .setTitle(m.title)
                .setItems(options, (d, which) -> {
                    if (which == 0) { m.favorite = !m.favorite; repo.save(m); renderPage(); }
                    else if (which == 1) renameMovie(m);
                    else if (which == 2) pickCover(m);
                    else if (which == 3) { m.progressMs = 0; repo.save(m); renderPage(); }
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
                .setMessage("A cópia importada pelo Cine Offline será apagada do armazenamento interno do app.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> { repo.delete(m); renderPage(); })
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
                .setMessage("Escolha como o filme está salvo no celular.")
                .setItems(new String[]{"📦 Importar ZIP", "📁 Importar pasta"}, (d, which) -> {
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
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
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
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(18), 0, dp(18), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Nome do filme")
                .setMessage("Você pode mudar esse nome depois.")
                .setView(wrap)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Importar", (d, w) -> startImport(uri, folder, input.getText().toString()))
                .show();
    }

    private void startImport(Uri uri, boolean folder, String title) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.PURPLE));
        content.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView message = text("Preparando…", 13, false, Ui.MUTED);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(14), 0, 0);
        content.addView(message);

        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Importando filme")
                .setView(content)
                .setCancelable(false)
                .create();
        progress.show();

        executor.execute(() -> {
            MovieImporter.ProgressListener listener = txt -> runOnUiThread(() -> message.setText(txt));
            ImportResult result = folder
                    ? MovieImporter.importFolder(getApplicationContext(), uri, title, listener)
                    : MovieImporter.importZip(getApplicationContext(), uri, title, listener);
            runOnUiThread(() -> {
                if (!isFinishing()) progress.dismiss();
                if (result.ok) {
                    repo.save(result.movie);
                    page = "library";
                    updateBottomNav();
                    renderPage();
                    Toast.makeText(this, "Filme importado e pronto para assistir offline.", Toast.LENGTH_LONG).show();
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
            renderPage();
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao trocar capa: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        pendingCoverMovie = null;
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Cine Offline 3.0")
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
