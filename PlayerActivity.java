package com.offlineplayer.cineoffline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.List;

public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private MovieRepository repo;
    private Movie movie;
    private PlayerView playerView;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView speedButton;
    private boolean started;
    private boolean fullscreen;
    private final Handler nextHandler = new Handler(Looper.getMainLooper());
    private boolean nextEpisodeScheduled;

    private static final String PREF_SETTINGS = "cine_offline_settings";
    private static final String KEY_AUTO_NEXT = "auto_next_episode";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        repo = new MovieRepository(this);
        String id = getIntent().getStringExtra("movieId");
        movie = repo.getById(id == null ? "" : id);
        if (movie == null) {
            Toast.makeText(this, "Filme não encontrado.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        movie.lastPlayedAt = System.currentTimeMillis();
        movie.playCount = movie.playCount + 1;
        repo.save(movie);

        buildUi();
        initPlayer();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(0, statusBarHeight(), 0, 0);

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        topBar.setBackgroundColor(Ui.NAVY);

        TextView back = control("‹", 28);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8), 0, dp(8), 0);
        TextView title = text(movie.title, 17, true, Color.WHITE);
        title.setSingleLine(true);
        titles.addView(title);
        TextView local = text(movie.isLinked() ? "⚡ Pasta original • sem cópia" : "● Cópia local", 10, false, Color.rgb(168, 231, 208));
        titles.addView(local);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        speedButton = control("1×", 14);
        speedButton.setOnClickListener(v -> chooseSpeed());
        topBar.addView(speedButton, new LinearLayout.LayoutParams(dp(56), dp(44)));

        TextView rotate = control("↻", 23);
        rotate.setOnClickListener(v -> rotateScreen());
        topBar.addView(rotate, new LinearLayout.LayoutParams(dp(50), dp(44)));
        root.addView(topBar);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerShowTimeoutMs(3500);
        playerView.setControllerHideOnTouch(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setBackgroundColor(Color.BLACK);
        root.addView(playerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(8), dp(8), Math.max(dp(10), navigationBarHeight()));
        bottomBar.setBackgroundColor(Ui.NAVY);

        TextView rewind = pill("↶ 10 s");
        rewind.setOnClickListener(v -> seekBy(-10_000));
        bottomBar.addView(rewind, weighted());

        TextView restart = pill("↺ Início");
        restart.setOnClickListener(v -> { if (player != null) player.seekTo(0); });
        bottomBar.addView(restart, weighted());

        TextView full = pill("⛶ Tela cheia");
        full.setOnClickListener(v -> toggleFullscreen());
        bottomBar.addView(full, weighted());

        TextView forward = pill("10 s ↷");
        forward.setOnClickListener(v -> seekBy(10_000));
        bottomBar.addView(forward, weighted());
        root.addView(bottomBar);

        setContentView(root);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private TextView control(String label, float size) {
        TextView v = text(label, size, true, Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setBackground(Ui.rounded(Color.argb(40, 255, 255, 255), 14, this));
        return v;
    }

    private TextView pill(String label) {
        TextView v = text(label, 11, true, Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setBackground(Ui.rounded(Color.argb(34, 255, 255, 255), 13, this));
        return v;
    }

    private void initPlayer() {
        File playlist = new File(movie.playlistPath);
        if (!playlist.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("Arquivo ausente")
                    .setMessage("A playlist offline deste filme não foi encontrada.")
                    .setPositiveButton("OK", (d, w) -> finish())
                    .show();
            return;
        }

        player = new ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        playerView.setPlayer(player);
        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.fromFile(playlist))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();
        player.setMediaItem(item);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && !started) {
                    started = true;
                    if (movie.progressMs > 0) player.seekTo(movie.progressMs);
                    player.play();
                }
                if (playbackState == Player.STATE_ENDED) {
                    movie.progressMs = 0;
                    movie.watched = true;
                    long d = player.getDuration();
                    if (d > 0) movie.durationMs = d;
                    repo.save(movie);
                    scheduleNextEpisodeIfEnabled();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                String message;
                if (movie.isLinked()) {
                    message = "Não consegui acessar os arquivos originais. Verifique se a pasta ainda existe no mesmo lugar e se o Android manteve a permissão de acesso.";
                } else {
                    message = "Erro na reprodução: " + error.getErrorCodeName();
                }
                new AlertDialog.Builder(PlayerActivity.this)
                        .setTitle("Não foi possível reproduzir")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
        player.prepare();
    }


    private void scheduleNextEpisodeIfEnabled() {
        if (nextEpisodeScheduled || movie == null) return;
        boolean enabled = getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE)
                .getBoolean(KEY_AUTO_NEXT, false);
        if (!enabled) return;

        Movie next = findNextEpisode();
        if (next == null) return;
        nextEpisodeScheduled = true;
        Toast.makeText(this, "⏭ Próximo episódio: " + next.title, Toast.LENGTH_SHORT).show();
        nextHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            Intent i = new Intent(PlayerActivity.this, PlayerActivity.class);
            i.putExtra("movieId", next.id);
            startActivity(i);
            finish();
        }, 1500);
    }

    private Movie findNextEpisode() {
        try {
            SeriesRepository seriesRepo = new SeriesRepository(this);
            SeriesRepository.Assignment current = seriesRepo.getAssignment(movie.id);
            if (current == null) return null;
            List<SeriesRepository.Assignment> list = seriesRepo.getAssignmentsForSeries(current.seriesId);
            for (int i = 0; i < list.size(); i++) {
                SeriesRepository.Assignment a = list.get(i);
                if (!movie.id.equals(a.movieId)) continue;
                if (i + 1 >= list.size()) return null;
                return repo.getById(list.get(i + 1).movieId);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void seekBy(long amount) {
        if (player == null) return;
        long duration = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + amount);
        if (duration > 0) target = Math.min(duration, target);
        player.seekTo(target);
    }

    private void chooseSpeed() {
        if (player == null) return;
        String[] labels = {"0,5×", "0,75×", "1×", "1,25×", "1,5×", "1,75×", "2×"};
        float[] values = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        new AlertDialog.Builder(this)
                .setTitle("Velocidade de reprodução")
                .setItems(labels, (d, which) -> {
                    player.setPlaybackParameters(new PlaybackParameters(values[which]));
                    speedButton.setText(labels[which]);
                }).show();
    }

    private void rotateScreen() {
        int o = getResources().getConfiguration().orientation;
        if (o == Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        View decor = getWindow().getDecorView();
        if (fullscreen) {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void saveProgress() {
        if (player == null || movie == null) return;
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = player.getDuration();
        if (dur > 0) movie.durationMs = dur;
        if (dur > 0 && pos >= dur - 20_000) {
            movie.progressMs = 0;
            movie.watched = true;
        } else {
            movie.progressMs = pos;
        }
        movie.lastPlayedAt = System.currentTimeMillis();
        repo.save(movie);
    }

    @Override
    protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        nextHandler.removeCallbacksAndMessages(null);
        saveProgress();
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            toggleFullscreen();
        } else {
            super.onBackPressed();
        }
    }

    private TextView text(String value, float size, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
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
