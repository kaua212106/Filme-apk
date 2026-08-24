package com.offlineplayer.cineoffline;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Locale;

public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private MovieRepository repo;
    private Movie movie;
    private PlayerView playerView;
    private TextView title;
    private boolean started;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        repo = new MovieRepository(this);
        String id = getIntent().getStringExtra("movieId");
        movie = repo.getById(id == null ? "" : id);
        if (movie == null) {
            Toast.makeText(this, "Filme não encontrado.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        buildUi();
        initPlayer();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(8), dp(6));

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTextColor(Color.WHITE);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(54), dp(50)));

        title = new TextView(this);
        title.setText(movie.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setMaxLines(1);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button speed = new Button(this);
        speed.setText("1×");
        speed.setTextColor(Color.WHITE);
        speed.setBackgroundColor(Color.TRANSPARENT);
        speed.setOnClickListener(v -> chooseSpeed(speed));
        top.addView(speed, new LinearLayout.LayoutParams(dp(70), dp(50)));
        root.addView(top);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setKeepContentOnPlayerReset(true);
        root.addView(playerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(6), dp(8), dp(8));

        Button rewind = new Button(this);
        rewind.setText("↶ 10s");
        rewind.setAllCaps(false);
        rewind.setOnClickListener(v -> seekBy(-10_000));
        bottom.addView(rewind, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button fullscreen = new Button(this);
        fullscreen.setText("Tela cheia");
        fullscreen.setAllCaps(false);
        fullscreen.setOnClickListener(v -> toggleFullscreen());
        bottom.addView(fullscreen, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button forward = new Button(this);
        forward.setText("10s ↷");
        forward.setAllCaps(false);
        forward.setOnClickListener(v -> seekBy(10_000));
        bottom.addView(forward, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(bottom);

        setContentView(root);
    }

    private void initPlayer() {
        File playlist = new File(movie.playlistPath);
        if (!playlist.exists()) {
            new AlertDialog.Builder(this).setTitle("Arquivo ausente").setMessage("A playlist offline deste filme não foi encontrada.").setPositiveButton("OK", (d, w) -> finish()).show();
            return;
        }

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(playlist)));
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
                    long d = player.getDuration();
                    if (d > 0) movie.durationMs = d;
                    repo.save(movie);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlayerActivity.this, "Erro na reprodução: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
            }
        });
        player.prepare();
    }

    private void seekBy(long amount) {
        if (player == null) return;
        long duration = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + amount);
        if (duration > 0) target = Math.min(duration, target);
        player.seekTo(target);
    }

    private void chooseSpeed(Button button) {
        if (player == null) return;
        String[] labels = {"0,5×", "0,75×", "1×", "1,25×", "1,5×", "2×"};
        float[] values = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        new AlertDialog.Builder(this)
                .setTitle("Velocidade")
                .setItems(labels, (d, which) -> {
                    player.setPlaybackParameters(new PlaybackParameters(values[which]));
                    button.setText(labels[which]);
                }).show();
    }

    private void toggleFullscreen() {
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        boolean full = (flags & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
        if (full) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void saveProgress() {
        if (player == null || movie == null) return;
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = player.getDuration();
        if (dur > 0) movie.durationMs = dur;
        if (dur > 0 && pos >= dur - 20_000) movie.progressMs = 0;
        else movie.progressMs = pos;
        repo.save(movie);
    }

    @Override
    protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveProgress();
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
