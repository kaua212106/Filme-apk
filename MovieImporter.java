package com.offlineplayer.cineoffline;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MovieImporter {
    public interface ProgressListener { void onProgress(String text); }

    public static ImportResult importZip(Context context, Uri uri, String title, ProgressListener listener) {
        String id = UUID.randomUUID().toString();
        File dir = new File(context.getFilesDir(), "movies/" + id);
        if (!dir.mkdirs() && !dir.exists()) return ImportResult.fail("Não foi possível criar a pasta interna do filme.");

        String playlist = null;
        Map<Integer, File> segments = new HashMap<>();
        int copied = 0;

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new Exception("Não foi possível abrir o ZIP.");
            try (ZipInputStream zis = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String base = baseName(entry.getName());
                    String lower = base.toLowerCase(Locale.ROOT);

                    if (lower.equals("index.m3u8") || (playlist == null && lower.endsWith(".m3u8"))) {
                        playlist = readEntryText(zis);
                        listener.onProgress("Playlist encontrada…");
                    } else if (lower.endsWith(".dat") || lower.endsWith(".ts")) {
                        Integer n = numberFromName(base);
                        if (n != null) {
                            File out = new File(dir, String.format(Locale.US, "%06d.ts", n));
                            copyEntry(zis, out);
                            segments.put(n, out);
                            copied++;
                            if (copied % 8 == 0) listener.onProgress("Copiando vídeo… " + copied + " partes");
                        }
                    } else if (isImage(lower) && !new File(dir, "cover.jpg").exists()) {
                        copyEntry(zis, new File(dir, "cover.jpg"));
                    }
                }
            }
        } catch (Exception e) {
            deleteRecursive(dir);
            return ImportResult.fail("Falha ao ler o ZIP: " + safeMessage(e));
        }
        return finish(dir, id, title, playlist, segments, listener);
    }

    public static ImportResult importFolder(Context context, Uri treeUri, String title, ProgressListener listener) {
        String id = UUID.randomUUID().toString();
        File dir = new File(context.getFilesDir(), "movies/" + id);
        if (!dir.mkdirs() && !dir.exists()) return ImportResult.fail("Não foi possível criar a pasta interna do filme.");

        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists()) {
            deleteRecursive(dir);
            return ImportResult.fail("A pasta selecionada não pôde ser aberta.");
        }

        Holder h = new Holder();
        try {
            scanFolder(context, root, dir, h, listener);
        } catch (Exception e) {
            deleteRecursive(dir);
            return ImportResult.fail("Falha ao copiar a pasta: " + safeMessage(e));
        }
        return finish(dir, id, title, h.playlist, h.segments, listener);
    }

    private static ImportResult finish(File dir, String id, String title, String playlist,
                                       Map<Integer, File> segments, ProgressListener listener) {
        if (playlist == null || playlist.trim().isEmpty()) {
            deleteRecursive(dir);
            return ImportResult.fail("Não encontrei index.m3u8 no conteúdo selecionado.");
        }
        if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
            deleteRecursive(dir);
            return ImportResult.fail("Esta playlist usa criptografia HLS. O Cine Offline não remove DRM nem chaves de proteção.");
        }
        if (segments.isEmpty()) {
            deleteRecursive(dir);
            return ImportResult.fail("Não encontrei segmentos .dat ou .ts.");
        }

        listener.onProgress("Preparando reprodução offline…");
        RewriteResult rr = rewritePlaylist(playlist, segments);
        if (rr.missing > 0) {
            deleteRecursive(dir);
            return ImportResult.fail("Faltam " + rr.missing + " partes exigidas pelo index.m3u8.");
        }

        File offline = new File(dir, "offline.m3u8");
        try (FileOutputStream fos = new FileOutputStream(offline)) {
            fos.write(rr.text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            deleteRecursive(dir);
            return ImportResult.fail("Não consegui criar a playlist offline: " + safeMessage(e));
        }

        File cover = new File(dir, "cover.jpg");
        if (!cover.exists()) {
            listener.onProgress("Criando capa automaticamente…");
            createAutomaticCover(segments, cover);
        }

        Movie movie = new Movie();
        movie.id = id;
        movie.title = title == null || title.trim().isEmpty() ? "Filme offline" : title.trim();
        movie.folderPath = dir.getAbsolutePath();
        movie.playlistPath = offline.getAbsolutePath();
        movie.coverPath = cover.exists() ? cover.getAbsolutePath() : "";
        movie.durationMs = rr.durationMs;
        movie.progressMs = 0;
        movie.addedAt = System.currentTimeMillis();
        movie.lastPlayedAt = 0;
        movie.playCount = 0;
        return ImportResult.ok(movie);
    }

    private static void createAutomaticCover(Map<Integer, File> segments, File cover) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            List<Integer> keys = new ArrayList<>(segments.keySet());
            Collections.sort(keys);
            int attempts = Math.min(keys.size(), 4);
            Bitmap frame = null;
            for (int i = 0; i < attempts && frame == null; i++) {
                File segment = segments.get(keys.get(i));
                if (segment == null || !segment.exists()) continue;
                try {
                    retriever.setDataSource(segment.getAbsolutePath());
                    frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) frame = retriever.getFrameAtTime();
                } catch (Exception ignored) {}
            }
            if (frame != null) {
                try (FileOutputStream fos = new FileOutputStream(cover)) {
                    frame.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                }
                frame.recycle();
            }
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static void scanFolder(Context context, DocumentFile folder, File dir, Holder h,
                                   ProgressListener listener) throws Exception {
        for (DocumentFile item : folder.listFiles()) {
            if (item.isDirectory()) {
                scanFolder(context, item, dir, h, listener);
                continue;
            }
            String name = item.getName();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);

            if (lower.endsWith(".m3u8") && (h.playlist == null || lower.equals("index.m3u8"))) {
                try (InputStream in = context.getContentResolver().openInputStream(item.getUri())) {
                    h.playlist = readAllText(in);
                }
                listener.onProgress("Playlist encontrada…");
            } else if (lower.endsWith(".dat") || lower.endsWith(".ts")) {
                Integer n = numberFromName(name);
                if (n != null) {
                    File out = new File(dir, String.format(Locale.US, "%06d.ts", n));
                    try (InputStream in = context.getContentResolver().openInputStream(item.getUri());
                         FileOutputStream fos = new FileOutputStream(out)) {
                        if (in == null) throw new Exception("Não foi possível abrir " + name);
                        copy(in, fos);
                    }
                    h.segments.put(n, out);
                    if (h.segments.size() % 8 == 0) listener.onProgress("Copiando vídeo… " + h.segments.size() + " partes");
                }
            } else if (isImage(lower) && !new File(dir, "cover.jpg").exists()) {
                try (InputStream in = context.getContentResolver().openInputStream(item.getUri());
                     FileOutputStream fos = new FileOutputStream(new File(dir, "cover.jpg"))) {
                    if (in != null) copy(in, fos);
                }
            }
        }
    }

    private static RewriteResult rewritePlaylist(String playlist, Map<Integer, File> segments) {
        StringBuilder out = new StringBuilder();
        int missing = 0;
        long duration = 0;
        String[] lines = playlist.replace("\r", "").split("\n");

        for (String original : lines) {
            String line = original.trim();
            if (line.startsWith("#EXTINF:")) {
                try {
                    String v = line.substring(8);
                    int comma = v.indexOf(',');
                    if (comma >= 0) v = v.substring(0, comma);
                    duration += Math.round(Double.parseDouble(v) * 1000.0);
                } catch (Exception ignored) {}
                out.append(original).append('\n');
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String clean = line;
                int q = clean.indexOf('?');
                if (q >= 0) clean = clean.substring(0, q);
                Integer n = numberFromName(baseName(clean));
                if (n == null || !segments.containsKey(n)) {
                    missing++;
                    out.append(original).append('\n');
                } else {
                    out.append(String.format(Locale.US, "%06d.ts", n)).append('\n');
                }
            } else {
                out.append(original).append('\n');
            }
        }
        return new RewriteResult(out.toString(), missing, duration);
    }

    private static Integer numberFromName(String name) {
        try {
            int dot = name.indexOf('.');
            String s = dot >= 0 ? name.substring(0, dot) : name;
            String digits = s.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) return null;
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String readEntryText(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zis.read(buffer)) > 0) bos.write(buffer, 0, n);
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private static String readAllText(InputStream in) throws Exception {
        if (in == null) return null;
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    private static void copyEntry(ZipInputStream zis, File out) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(out)) { copy(zis, fos); }
    }

    private static void copy(InputStream in, FileOutputStream out) throws Exception {
        byte[] buffer = new byte[1024 * 64];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n > 0) out.write(buffer, 0, n);
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static class Holder {
        String playlist;
        Map<Integer, File> segments = new HashMap<>();
    }

    private static class RewriteResult {
        final String text;
        final int missing;
        final long durationMs;
        RewriteResult(String text, int missing, long durationMs) {
            this.text = text;
            this.missing = missing;
            this.durationMs = durationMs;
        }
    }
}
