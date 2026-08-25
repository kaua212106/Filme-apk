package com.offlineplayer.cineoffline;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ferramentas de verificação e tamanho da biblioteca. */
public final class LibraryHealth {
    private static final String PREF = "cine_offline_library_health";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_TOTAL_BYTES = "total_bytes";
    private static final String KEY_MISSING = "missing_parts";
    private static final String KEY_BAD_MOVIES = "bad_movies";
    private static final String SIZE_PREFIX = "size:";

    private LibraryHealth() {}

    public interface ProgressListener {
        void onProgress(String text);
    }

    public static final class CheckResult {
        public int movieCount;
        public int healthyMovies;
        public int badMovies;
        public int segmentCount;
        public int missingSegments;
        public long totalBytes;
        public long checkedAt;
        public final List<String> problems = new ArrayList<>();
    }

    public static CheckResult verify(Context context, List<Movie> movies, ProgressListener listener) {
        CheckResult result = new CheckResult();
        if (movies == null) movies = new ArrayList<>();
        result.movieCount = movies.size();
        SharedPreferences.Editor cache = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        long totalBytes = 0L;

        for (int i = 0; i < movies.size(); i++) {
            Movie movie = movies.get(i);
            if (listener != null) {
                listener.onProgress("🔎 Verificando " + (i + 1) + " de " + movies.size() + " • " + safeTitle(movie));
            }

            MovieCheck one = checkMovie(context, movie);
            result.segmentCount += one.segmentCount;
            result.missingSegments += one.missing;
            totalBytes += one.bytes;
            cache.putLong(SIZE_PREFIX + movie.id, Math.max(0L, one.bytes));

            if (one.missing == 0 && one.segmentCount > 0 && one.playlistOk) {
                result.healthyMovies++;
            } else {
                result.badMovies++;
                if (result.problems.size() < 12) {
                    StringBuilder p = new StringBuilder(safeTitle(movie)).append(": ");
                    if (!one.playlistOk) p.append("playlist ausente/inválida");
                    else if (one.segmentCount == 0) p.append("nenhum segmento encontrado");
                    else p.append(one.missing).append(one.missing == 1 ? " segmento ausente" : " segmentos ausentes");
                    result.problems.add(p.toString());
                }
            }
        }

        result.totalBytes = totalBytes;
        result.checkedAt = System.currentTimeMillis();
        cache.putLong(KEY_LAST_CHECK, result.checkedAt)
                .putLong(KEY_TOTAL_BYTES, result.totalBytes)
                .putInt(KEY_MISSING, result.missingSegments)
                .putInt(KEY_BAD_MOVIES, result.badMovies)
                .apply();
        return result;
    }

    private static MovieCheck checkMovie(Context context, Movie movie) {
        MovieCheck out = new MovieCheck();
        if (movie == null || movie.playlistPath == null || movie.playlistPath.trim().isEmpty()) return out;
        File playlist = new File(movie.playlistPath);
        if (!playlist.exists() || !playlist.isFile() || playlist.length() <= 0) return out;
        out.playlistOk = true;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(playlist), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String ref = line.trim();
                if (ref.isEmpty() || ref.startsWith("#")) continue;
                out.segmentCount++;
                SegmentInfo info = inspectSegment(context, playlist.getParentFile(), ref);
                if (!info.exists) out.missing++;
                else out.bytes += Math.max(0L, info.bytes);
            }
        } catch (Exception e) {
            out.playlistOk = false;
        }
        return out;
    }

    private static SegmentInfo inspectSegment(Context context, File parent, String ref) {
        SegmentInfo info = new SegmentInfo();
        try {
            if (ref.startsWith("content://")) {
                Uri uri = Uri.parse(ref);
                try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (afd == null) return info;
                    info.exists = true;
                    long len = afd.getLength();
                    if (len >= 0) info.bytes = len;
                }
                if (info.bytes <= 0) info.bytes = querySize(context, uri);
                return info;
            }

            String path = ref;
            if (ref.startsWith("file://")) path = Uri.parse(ref).getPath();
            File file = path != null && new File(path).isAbsolute()
                    ? new File(path)
                    : new File(parent, path == null ? "" : path);
            info.exists = file.exists() && file.isFile() && file.length() > 0;
            if (info.exists) info.bytes = file.length();
            return info;
        } catch (Exception ignored) {
            return info;
        }
    }

    private static long querySize(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return Math.max(0L, c.getLong(idx));
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    public static long getMovieBytes(Context context, Movie movie) {
        if (movie == null || movie.id == null) return 0L;
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getLong(SIZE_PREFIX + movie.id, 0L);
    }

    public static long getTotalBytes(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_TOTAL_BYTES, 0L);
    }

    public static long getLastCheck(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L);
    }

    public static int getLastMissing(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_MISSING, 0);
    }

    public static int getLastBadMovies(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_BAD_MOVIES, 0);
    }

    public static void forgetMovie(Context context, Movie movie) {
        if (movie == null || movie.id == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long old = prefs.getLong(SIZE_PREFIX + movie.id, 0L);
        long total = Math.max(0L, prefs.getLong(KEY_TOTAL_BYTES, 0L) - Math.max(0L, old));
        prefs.edit().remove(SIZE_PREFIX + movie.id).putLong(KEY_TOTAL_BYTES, total).apply();
    }

    public static void invalidateTotals(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, 0L)
                .putLong(KEY_TOTAL_BYTES, 0L)
                .putInt(KEY_MISSING, 0)
                .putInt(KEY_BAD_MOVIES, 0)
                .apply();
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (unit == 0) return ((long) value) + " " + units[unit];
        return String.format(Locale.getDefault(), value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private static String safeTitle(Movie movie) {
        if (movie == null || movie.title == null || movie.title.trim().isEmpty()) return "Item sem nome";
        return movie.title.trim();
    }

    private static final class MovieCheck {
        boolean playlistOk;
        int segmentCount;
        int missing;
        long bytes;
    }

    private static final class SegmentInfo {
        boolean exists;
        long bytes;
    }
}
