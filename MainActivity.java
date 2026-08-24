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
import android.widget.Button;
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
    private LinearLayout movieList;
    private TextView pageTitle;
    private TextView pageSubtitle;
    private TextView statMovies;
    private TextView statContinue;
    private TextView statFavorites;
    private EditText search;
    private String filter = "all";
    private Movie pendingCoverMovie;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<TextView> navButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
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
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(14), statusBarHeight() + dp(10), dp(14), Math.max(dp(10), navigationBarHeight()));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(15));
        header.setBackground(Ui.gradient(this, 28));
        header.setElevation(dp(5));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView menu = iconButton("☰");
        menu.setOnClickListener(v -> showSideMenu());
        top.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.icone);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams lpLogo = new LinearLayout.LayoutParams(dp(42), dp(42));
        lpLogo.setMargins(dp(10), 0, dp(10), 0);
        top.addView(logo, lpLogo);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        pageTitle = text("Cine Offline", 22, true, Color.WHITE);
        pageSubtitle = text("Sua biblioteca local", 12, false, Color.argb(220, 255, 255, 255));
        titles.addView(pageTitle);
        titles.addView(pageSubtitle);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView offline = text("● OFFLINE", 10, true, Color.WHITE);
        offline.setGravity(Gravity.CENTER);
        offline.setBackground(Ui.rounded(Color.argb(42,255,255,255), 13, this));
        top.addView(offline, new LinearLayout.LayoutParams(dp(78), dp(32)));

        TextView add = iconButton("＋");
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        addLp.setMargins(dp(8), 0, 0, 0);
        add.setOnClickListener(v -> showImportChoice());
        top.addView(add, addLp);
        header.addView(top);

        TextView hero = text("Seus filmes, mesmo sem internet.", 15, true, Color.WHITE);
        hero.setPadding(dp(4), dp(12), 0, 0);
        header.addView(hero);
        root.addView(header);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(14), 0, dp(12), 0);
        Ui.card(searchBox, this, 18);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        sbp.setMargins(0, dp(12), 0, 0);

        TextView searchIcon = text("⌕", 25, false, Ui.MUTED);
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT));
        search = new EditText(this);
        search.setHint("Buscar na biblioteca");
        search.setHintTextColor(Color.rgb(155, 160, 180));
        search.setTextColor(Ui.TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(8), 0, 0, 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchBox.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(searchBox, sbp);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, dp(10), 0, dp(8));
        statMovies = statCard(stats, "0", "Filmes", "▣");
        statContinue = statCard(stats, "0", "Continuar", "▶");
        statFavorites = statCard(stats, "0", "Favoritos", "★");
        root.addView(stats);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        movieList = new LinearLayout(this);
        movieList.setOrientation(LinearLayout.VERTICAL);
        movieList.setPadding(0, dp(4), 0, dp(12));
        scroll.addView(movieList, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        Ui.card(nav, this, 22);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        navLp.setMargins(0, dp(4), 0, 0);
        root.addView(nav, navLp);
        addNav(nav, "⌂\nBiblioteca", "all");
        addNav(nav, "▶\nContinuar", "continue");
        addNav(nav, "★\nFavoritos", "favorites");
        addNav(nav, "◷\nHistórico", "history");

        setContentView(root);
        updateNav();
    }

    private TextView statCard(LinearLayout parent, String value, String label, String icon) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6), dp(7), dp(6), dp(7));
        Ui.card(box, this, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(72), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(box, lp);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        TextView ic = text(icon, 14, true, Ui.PURPLE);
        ic.setGravity(Gravity.CENTER);
        row.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(24)));
        TextView number = text(value, 19, true, Ui.TEXT);
        row.addView(number);
        box.addView(row);
        TextView l = text(label, 10, false, Ui.MUTED);
        l.setGravity(Gravity.CENTER);
        box.addView(l);
        return number;
    }

    private void addNav(LinearLayout nav, String label, String value) {
        TextView b = text(label, 10, true, Ui.MUTED);
        b.setGravity(Gravity.CENTER);
        b.setTag(value);
        b.setLines(2);
        b.setOnClickListener(v -> setFilter(value));
        nav.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        navButtons.add(b);
    }

    private void updateNav() {
        for (TextView b : navButtons) {
            boolean active = filter.equals(String.valueOf(b.getTag()));
            b.setTextColor(active ? Color.WHITE : Ui.MUTED);
            b.setBackground(active ? Ui.gradient(this, 16) : new ColorDrawable(Color.TRANSPARENT));
        }
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
        brand.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, 0, 0);
        words.addView(text("Cine Offline", 20, true, Ui.TEXT));
        words.addView(text("100% local • sem internet", 11, false, Ui.MUTED));
        brand.addView(words);
        panel.addView(brand);

        View line = new View(this);
        line.setBackgroundColor(Ui.BORDER);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.setMargins(0, dp(18), 0, dp(12));
        panel.addView(line, lineLp);

        menuRow(panel, "⌂", "Biblioteca", "Todos os filmes importados", () -> { dialog.dismiss(); setFilter("all"); });
        menuRow(panel, "▶", "Continuar assistindo", "Retome de onde parou", () -> { dialog.dismiss(); setFilter("continue"); });
        menuRow(panel, "★", "Favoritos", "Sua seleção preferida", () -> { dialog.dismiss(); setFilter("favorites"); });
        menuRow(panel, "◷", "Histórico", "Últimos filmes reproduzidos", () -> { dialog.dismiss(); setFilter("history"); });
        menuRow(panel, "＋", "Importar filme", "ZIP ou pasta com index.m3u8", () -> { dialog.dismiss(); showImportChoice(); });
        menuRow(panel, "ⓘ", "Sobre", "Informações do Cine Offline", () -> { dialog.dismiss(); showAbout(); });

        TextView note = text("O Cine Offline não precisa de internet para reproduzir os filmes já importados.", 11, false, Ui.MUTED);
        note.setPadding(dp(6), dp(18), dp(6), 0);
        panel.addView(note);

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
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(Ui.rounded(Color.TRANSPARENT, 16, this));
        row.setOnClickListener(v -> action.run());

        TextView ic = text(icon, 21, true, Ui.PURPLE);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.softGradient(this, 14));
        row.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, 0, 0);
        words.addView(text(title, 14, true, Ui.TEXT));
        words.addView(text(sub, 10, false, Ui.MUTED));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
    }

    private TextView iconButton(String value) {
        TextView b = text(value, 24, true, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(Color.argb(42, 255, 255, 255), 16, this));
        return b;
    }

    private void setFilter(String f) {
        filter = f;
        if (f.equals("continue")) {
            pageTitle.setText("Continuar");
            pageSubtitle.setText("Retome de onde parou");
        } else if (f.equals("favorites")) {
            pageTitle.setText("Favoritos");
            pageSubtitle.setText("Seus filmes preferidos");
        } else if (f.equals("history")) {
            pageTitle.setText("Histórico");
            pageSubtitle.setText("O que você assistiu recentemente");
        } else {
            pageTitle.setText("Cine Offline");
            pageSubtitle.setText("Sua biblioteca local");
        }
        updateNav();
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
        statMovies.setText(String.valueOf(all.size()));
        statContinue.setText(String.valueOf(cont));
        statFavorites.setText(String.valueOf(fav));

        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Movie> visible = new ArrayList<>();
        for (Movie m : all) {
            if (filter.equals("favorites") && !m.favorite) continue;
            if (filter.equals("continue") && !isContinue(m)) continue;
            if (filter.equals("history") && m.lastPlayedAt <= 0) continue;
            if (!q.isEmpty() && !m.title.toLowerCase(Locale.ROOT).contains(q)) continue;
            visible.add(m);
        }
        if (filter.equals("history")) {
            Collections.sort(visible, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
        }

        movieList.removeAllViews();
        movieList.addView(sectionHeader(sectionTitle(), visible.size()));
        if (visible.isEmpty()) {
            movieList.addView(emptyState(q));
            return;
        }
        for (Movie m : visible) movieList.addView(movieCard(m));
    }

    private String sectionTitle() {
        if (filter.equals("continue")) return "Continuar assistindo";
        if (filter.equals("favorites")) return "Seus favoritos";
        if (filter.equals("history")) return "Assistidos recentemente";
        return "Minha biblioteca";
    }

    private View sectionHeader(String title, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(10));
        row.addView(text(title, 17, true, Ui.TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView c = text(count + (count == 1 ? " item" : " itens"), 11, true, Ui.PURPLE);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.softGradient(this, 12));
        row.addView(c, new LinearLayout.LayoutParams(dp(72), dp(28)));
        return row;
    }

    private View emptyState(String query) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(28), dp(22), dp(26));
        Ui.card(card, this, 24);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.icone);
        card.addView(icon, new LinearLayout.LayoutParams(dp(78), dp(78)));

        String title;
        String sub;
        if (!query.isEmpty()) {
            title = "Nada encontrado";
            sub = "Não encontrei nenhum filme com “" + query + "”.";
        } else if (filter.equals("continue")) {
            title = "Nada para continuar";
            sub = "Quando você parar um filme no meio, ele aparece aqui automaticamente.";
        } else if (filter.equals("favorites")) {
            title = "Nenhum favorito ainda";
            sub = "Toque na estrela de um filme para deixar ele fácil de encontrar.";
        } else if (filter.equals("history")) {
            title = "Histórico vazio";
            sub = "Os filmes que você abrir vão aparecer aqui.";
        } else {
            title = "Sua biblioteca está vazia";
            sub = "Importe um ZIP ou uma pasta com index.m3u8 + arquivos .dat/.ts. Depois, o filme fica disponível offline.";
        }

        TextView t = text(title, 19, true, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, dp(5));
        card.addView(t);
        TextView s = text(sub, 13, false, Ui.MUTED);
        s.setGravity(Gravity.CENTER);
        card.addView(s);

        if (filter.equals("all") && query.isEmpty()) {
            TextView add = text("＋  Importar filme", 14, true, Color.WHITE);
            Ui.button(add, this, true);
            add.setOnClickListener(v -> showImportChoice());
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            bp.setMargins(0, dp(18), 0, dp(14));
            card.addView(add, bp);

            LinearLayout features = new LinearLayout(this);
            features.setGravity(Gravity.CENTER);
            features.addView(featureChip("100% offline"));
            features.addView(featureChip("Capa automática"));
            features.addView(featureChip("Progresso salvo"));
            card.addView(features);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(cp);
        return card;
    }

    private TextView featureChip(String label) {
        TextView c = text(label, 9, true, Ui.PURPLE);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.softGradient(this, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private View movieCard(Movie m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(12));
        Ui.card(card, this, 24);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(cp);

        FrameLayout poster = new FrameLayout(this);
        poster.setClipToOutline(true);
        poster.setBackground(Ui.softGradient(this, 20));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(182));
        card.addView(poster, pp);

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bm = null;
        if (m.coverPath != null && !m.coverPath.isEmpty()) bm = BitmapFactory.decodeFile(m.coverPath);
        if (bm != null) {
            cover.setImageBitmap(bm);
        } else {
            cover.setImageResource(R.drawable.icone);
            cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            cover.setPadding(dp(52), dp(52), dp(52), dp(52));
        }
        poster.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        poster.setOnClickListener(v -> openPlayer(m));

        TextView offline = text("● OFFLINE", 9, true, Color.WHITE);
        offline.setGravity(Gravity.CENTER);
        offline.setBackground(Ui.rounded(Color.argb(205, 28, 35, 58), 11, this));
        FrameLayout.LayoutParams offLp = new FrameLayout.LayoutParams(dp(76), dp(28), Gravity.TOP | Gravity.START);
        offLp.setMargins(dp(10), dp(10), 0, 0);
        poster.addView(offline, offLp);

        TextView fav = text(m.favorite ? "★" : "☆", 23, true, Color.WHITE);
        fav.setGravity(Gravity.CENTER);
        fav.setBackground(Ui.rounded(Color.argb(190, 28, 35, 58), 16, this));
        fav.setOnClickListener(v -> { m.favorite = !m.favorite; repo.save(m); refresh(); });
        FrameLayout.LayoutParams favLp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.END);
        favLp.setMargins(0, dp(9), dp(9), 0);
        poster.addView(fav, favLp);

        if (isContinue(m)) {
            TextView resume = text("Continuar em " + time(m.progressMs), 10, true, Color.WHITE);
            resume.setGravity(Gravity.CENTER);
            resume.setBackground(Ui.rounded(Color.argb(215, 108, 99, 231), 11, this));
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(126), dp(29), Gravity.BOTTOM | Gravity.START);
            rlp.setMargins(dp(10), 0, 0, dp(10));
            poster.addView(resume, rlp);
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(4), dp(11), dp(4), 0);
        TextView title = text(m.title, 18, true, Ui.TEXT);
        title.setMaxLines(2);
        info.addView(title);

        String meta = progressText(m);
        if (filter.equals("history") && m.lastPlayedAt > 0) meta = "Última reprodução: " + relativeDate(m.lastPlayedAt) + "  •  " + meta;
        TextView details = text(meta, 11, false, Ui.MUTED);
        details.setPadding(0, dp(4), 0, dp(7));
        info.addView(details);

        if (m.durationMs > 0 && m.progressMs > 0) {
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgress((int) Math.min(1000, (m.progressMs * 1000L) / Math.max(1, m.durationMs)));
            progress.setProgressTintList(ColorStateList.valueOf(Ui.PURPLE));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(230, 232, 242)));
            info.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));
        }
        card.addView(info);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(4), dp(11), dp(4), 0);
        TextView watch = text(isContinue(m) ? "▶  Continuar" : "▶  Assistir", 14, true, Color.WHITE);
        Ui.button(watch, this, true);
        watch.setOnClickListener(v -> openPlayer(m));
        actions.addView(watch, new LinearLayout.LayoutParams(0, dp(48), 1));

        TextView more = text("⋮", 25, true, Ui.TEXT);
        Ui.button(more, this, false);
        more.setOnClickListener(v -> showMovieMenu(m));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(52), dp(48));
        mlp.setMargins(dp(8), 0, 0, 0);
        actions.addView(more, mlp);
        card.addView(actions);
        return card;
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
                    if (which == 0) { m.favorite = !m.favorite; repo.save(m); refresh(); }
                    else if (which == 1) renameMovie(m);
                    else if (which == 2) pickCover(m);
                    else if (which == 3) { m.progressMs = 0; repo.save(m); refresh(); }
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
                .setMessage("A cópia importada pelo Cine Offline será apagada do armazenamento interno do app.")
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
                    setFilter("all");
                    Toast.makeText(this, "Filme importado. A capa foi criada e ele já pode ser assistido offline.", Toast.LENGTH_LONG).show();
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
        return time(m.progressMs) + " / " + time(m.durationMs) + "  •  " + pct + "%";
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
