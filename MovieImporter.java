package com.offlineplayer.cineoffline;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MovieImporter {
    public interface ProgressListener { void onProgress(String text); }

    /**
     * Importa ZIP para o armazenamento interno. Como ZIP não oferece acesso aleatório simples
     * pelo seletor do Android, os segmentos ainda precisam ser extraídos, mas a cópia foi
     * otimizada e agora mostra porcentagem aproximada quando o tamanho do arquivo é conhecido.
     */
    public static ImportResult importZip(Context context, Uri uri, String title, ProgressListener listener) {
        String id = UUID.randomUUID().toString();
        File dir = createMovieDir(context, id);
        if (dir == null) return ImportResult.fail("Não foi possível criar a pasta interna do filme.");

        String playlist = null;
        Map<Integer, File> segments = new HashMap<>();
        int copied = 0;
        long totalBytes = querySize(context, uri);
        int[] lastPercent = {-1};

        try (InputStream source = context.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new Exception("Não foi possível abrir o ZIP.");
            CountingInputStream counting = new CountingInputStream(new BufferedInputStream(source, 256 * 1024));
            try (ZipInputStream zis = new ZipInputStream(counting)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String base = baseName(entry.getName());
                    String lower = base.toLowerCase(Locale.ROOT);

                    if (lower.equals("index.m3u8") || (playlist == null && lower.endsWith(".m3u8"))) {
                        playlist = readEntryText(zis);
                        reportZipProgress(listener, counting.getCount(), totalBytes, copied, lastPercent);
                    } else if (lower.endsWith(".dat") || lower.endsWith(".ts")) {
                        Integer n = numberFromName(base);
                        if (n != null) {
                            File out = new File(dir, String.format(Locale.US, "%06d.ts", n));
                            final int partsBefore = copied;
                            copyEntry(zis, out, () -> reportZipProgress(listener, counting.getCount(), totalBytes, partsBefore, lastPercent));
                            segments.put(n, out);
                            copied++;
                            reportZipProgress(listener, counting.getCount(), totalBytes, copied, lastPercent);
                        }
                    } else if (isImage(lower) && !new File(dir, "cover.jpg").exists()) {
                        copyEntry(zis, new File(dir, "cover.jpg"), null);
                    }
                    zis.closeEntry();
                }
            }
        } catch (Exception e) {
            deleteRecursive(dir);
            return ImportResult.fail("Falha ao ler o ZIP: " + safeMessage(e));
        }

        return finishCopied(dir, id, title, playlist, segments, "zip", "", listener);
    }

    /** Compatibilidade: mantém o nome antigo usando o modo de cópia. */
    public static ImportResult importFolder(Context context, Uri treeUri, String title, ProgressListener listener) {
        return importFolderCopied(context, treeUri, title, listener);
    }

    /**
     * Modo seguro: copia os segmentos da pasta para o armazenamento do Cine Offline.
     */
    public static ImportResult importFolderCopied(Context context, Uri treeUri, String title, ProgressListener listener) {
        String id = UUID.randomUUID().toString();
        File dir = createMovieDir(context, id);
        if (dir == null) return ImportResult.fail("Não foi possível criar a pasta interna do filme.");

        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists()) {
            deleteRecursive(dir);
            return ImportResult.fail("A pasta selecionada não pôde ser aberta.");
        }

        CopiedHolder h = new CopiedHolder();
        try {
            scanFolderCopied(context, root, dir, h, listener);
        } catch (Exception e) {
            deleteRecursive(dir);
            return ImportResult.fail("Falha ao copiar a pasta: " + safeMessage(e));
        }
        return finishCopied(dir, id, title, h.playlist, h.segments, "copied", "", listener);
    }

    /**
     * Modo rápido: NÃO copia os segmentos. O app guarda apenas uma playlist pequena apontando
     * para os arquivos originais via Content URI. A pasta precisa permanecer no mesmo lugar.
     */
    public static ImportResult importFolderLinked(Context context, Uri treeUri, String title, ProgressListener listener) {
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists()) {
            return ImportResult.fail("A pasta selecionada não pôde ser aberta.");
        }
        return importFolderLinkedDocument(context, root, title, treeUri.toString(), listener);
    }

    /**
     * Importa de uma vez uma pasta como a pasta "Filmes" mostrada pelo usuário. Cada
     * subpasta que contém uma playlist .m3u8 + partes .dat/.ts vira um filme separado.
     * Nada é duplicado: todos os filmes continuam usando os arquivos originais.
     */
    public static LibraryImportResult importLibraryFolderLinked(Context context, Uri treeUri, ProgressListener listener) {
        DocumentFile selectedRoot = DocumentFile.fromTreeUri(context, treeUri);
        if (selectedRoot == null || !selectedRoot.exists()) {
            return LibraryImportResult.fail("A pasta selecionada não pôde ser aberta.");
        }

        listener.onProgress("🎬 Procurando filmes dentro da pasta…");
        List<DocumentFile> movieFolders = discoverMovieFolders(selectedRoot);
        if (movieFolders.isEmpty()) {
            return LibraryImportResult.fail("Não encontrei subpastas com index.m3u8 e segmentos .dat/.ts.");
        }

        List<Movie> movies = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int total = movieFolders.size();

        for (int i = 0; i < total; i++) {
            DocumentFile folder = movieFolders.get(i);
            final int current = i + 1;
            String title = guessMovieTitle(context, folder, current);
            listener.onProgress("🎬 Adicionando " + current + " de " + total + ": " + title);

            ImportResult result = importFolderLinkedDocument(
                    context, folder, title, treeUri.toString(),
                    text -> listener.onProgress("🎬 " + current + "/" + total + " • " + text)
            );
            if (result.ok) {
                movies.add(result.movie);
            } else {
                errors.add(title + ": " + result.error);
            }
        }

        return new LibraryImportResult(movies, errors, total, null);
    }

    private static ImportResult importFolderLinkedDocument(Context context, DocumentFile root, String title,
                                                            String permissionSourceUri, ProgressListener listener) {
        String id = UUID.randomUUID().toString();
        File dir = createMovieDir(context, id);
        if (dir == null) return ImportResult.fail("Não foi possível criar a pasta interna do filme.");

        LinkedHolder h = new LinkedHolder();
        try {
            listener.onProgress("⚡ Indexando arquivos, sem copiar…");
            scanFolderLinked(context, root, h, listener);
        } catch (Exception e) {
            deleteRecursive(dir);
            return ImportResult.fail("Falha ao indexar a pasta: " + safeMessage(e));
        }

        if (h.coverUri != null) {
            File cover = new File(dir, "cover.jpg");
            try (InputStream in = context.getContentResolver().openInputStream(h.coverUri);
                 FileOutputStream out = new FileOutputStream(cover)) {
                if (in != null) copy(in, out, null);
            } catch (Exception ignored) {
                // Capa não é obrigatória. Ela pode ser criada depois em segundo plano.
            }
        }

        return finishLinked(dir, id, title, h.playlist, h.segments, permissionSourceUri, listener);
    }

    private static List<DocumentFile> discoverMovieFolders(DocumentFile selectedRoot) {
        List<DocumentFile> direct = new ArrayList<>();
        try {
            for (DocumentFile child : selectedRoot.listFiles()) {
                if (child.isDirectory() && containsMoviePackage(child)) {
                    direct.add(child);
                }
            }
        } catch (Exception ignored) {}

        // No formato da pasta "Filmes", cada pasta hexadecimal é um filme.
        if (!direct.isEmpty()) return direct;

        // Também aceita selecionar diretamente a pasta de um único filme.
        List<DocumentFile> result = new ArrayList<>();
        if (containsMoviePackage(selectedRoot)) result.add(selectedRoot);
        return result;
    }

    private static boolean containsMoviePackage(DocumentFile folder) {
        MovieProbe probe = new MovieProbe();
        probeMoviePackage(folder, probe);
        return probe.playlist && probe.segment;
    }

    private static void probeMoviePackage(DocumentFile folder, MovieProbe probe) {
        if (probe.playlist && probe.segment) return;
        DocumentFile[] files;
        try { files = folder.listFiles(); } catch (Exception e) { return; }
        for (DocumentFile item : files) {
            if (probe.playlist && probe.segment) return;
            if (item.isDirectory()) {
                probeMoviePackage(item, probe);
                continue;
            }
            String name = item.getName();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".m3u8")) probe.playlist = true;
            if (lower.endsWith(".dat") || lower.endsWith(".ts")) probe.segment = true;
        }
    }

    private static String guessMovieTitle(Context context, DocumentFile folder, int index) {
        String metadataTitle = findMetadataTitle(context, folder, 0);
        if (metadataTitle != null && !metadataTitle.trim().isEmpty()) {
            return cleanTitle(metadataTitle);
        }

        String folderName = folder.getName();
        if (folderName != null) {
            String trimmed = folderName.trim();
            // Pastas como 0C7DC2A47234F8E971C5E9E345F725FF não são úteis como título.
            if (!trimmed.matches("(?i)[0-9a-f]{16,}")) return cleanTitle(trimmed);
        }
        return String.format(Locale.getDefault(), "Filme %02d", index);
    }

    private static String findMetadataTitle(Context context, DocumentFile folder, int depth) {
        if (depth > 3) return null;
        DocumentFile[] files;
        try { files = folder.listFiles(); } catch (Exception e) { return null; }

        for (DocumentFile item : files) {
            if (item.isDirectory()) continue;
            String name = item.getName();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);

            try {
                if (lower.equals("title.txt") || lower.equals("name.txt") || lower.equals("movie.txt")) {
                    String raw = readLimitedText(context, item.getUri(), 4096);
                    if (raw != null && !raw.trim().isEmpty()) return raw.trim();
                }

                boolean likelyMetadata = lower.endsWith(".json") &&
                        (lower.contains("meta") || lower.contains("info") || lower.contains("movie") ||
                                lower.contains("video") || lower.contains("detail"));
                if (likelyMetadata) {
                    String raw = readLimitedText(context, item.getUri(), 128 * 1024);
                    String title = titleFromJson(raw);
                    if (title != null && !title.trim().isEmpty()) return title.trim();
                }
            } catch (Exception ignored) {}
        }

        for (DocumentFile item : files) {
            if (item.isDirectory()) {
                String found = findMetadataTitle(context, item, depth + 1);
                if (found != null && !found.trim().isEmpty()) return found;
            }
        }
        return null;
    }

    private static String titleFromJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            String[] keys = {"title", "movieTitle", "videoTitle", "original_title", "originalTitle", "name"};
            for (String key : keys) {
                String value = o.optString(key, "").trim();
                if (!value.isEmpty()) return value;
            }
            String[] nestedKeys = {"movie", "video", "data", "info", "metadata"};
            for (String nested : nestedKeys) {
                JSONObject n = o.optJSONObject(nested);
                if (n == null) continue;
                for (String key : keys) {
                    String value = n.optString(key, "").trim();
                    if (!value.isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String readLimitedText(Context context, Uri uri, int maxBytes) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) > 0) {
                bos.write(buffer, 0, n);
                total += n;
                if (total >= maxBytes) break;
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String cleanTitle(String value) {
        String s = value == null ? "" : value.trim();
        s = s.replace('_', ' ').replaceAll("\\s+", " ");
        if (s.length() > 100) s = s.substring(0, 100).trim();
        return s.isEmpty() ? "Filme offline" : s;
    }

    private static File createMovieDir(Context context, String id) {
        File dir = new File(context.getFilesDir(), "movies/" + id);
        if (!dir.mkdirs() && !dir.exists()) return null;
        return dir;
    }

    private static ImportResult finishCopied(File dir, String id, String title, String playlist,
                                             Map<Integer, File> segments, String storageMode,
                                             String sourceUri, ProgressListener listener) {
        String validation = validatePlaylist(playlist, segments.size());
        if (validation != null) {
            deleteRecursive(dir);
            return ImportResult.fail(validation);
        }

        listener.onProgress("Finalizando biblioteca…");
        Map<Integer, String> targets = new HashMap<>();
        for (Integer n : segments.keySet()) {
            targets.put(n, String.format(Locale.US, "%06d.ts", n));
        }
        RewriteResult rr = rewritePlaylist(playlist, targets);
        if (rr.missing > 0) {
            deleteRecursive(dir);
            return ImportResult.fail("Faltam " + rr.missing + " partes exigidas pelo index.m3u8.");
        }
        return saveMovieMetadata(dir, id, title, rr, storageMode, sourceUri);
    }

    private static ImportResult finishLinked(File dir, String id, String title, String playlist,
                                             Map<Integer, Uri> segments, String sourceUri,
                                             ProgressListener listener) {
        String validation = validatePlaylist(playlist, segments.size());
        if (validation != null) {
            deleteRecursive(dir);
            return ImportResult.fail(validation);
        }

        listener.onProgress("⚡ Finalizando índice…");
        Map<Integer, String> targets = new HashMap<>();
        for (Map.Entry<Integer, Uri> e : segments.entrySet()) {
            targets.put(e.getKey(), e.getValue().toString());
        }
        RewriteResult rr = rewritePlaylist(playlist, targets);
        if (rr.missing > 0) {
            deleteRecursive(dir);
            return ImportResult.fail("Faltam " + rr.missing + " partes exigidas pelo index.m3u8.");
        }
        return saveMovieMetadata(dir, id, title, rr, "linked", sourceUri);
    }

    private static String validatePlaylist(String playlist, int segmentCount) {
        if (playlist == null || playlist.trim().isEmpty()) {
            return "Não encontrei index.m3u8 no conteúdo selecionado.";
        }
        if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
            return "Esta playlist usa criptografia HLS. O Cine Offline não remove DRM nem chaves de proteção.";
        }
        if (segmentCount <= 0) return "Não encontrei segmentos .dat ou .ts.";
        return null;
    }

    private static ImportResult saveMovieMetadata(File dir, String id, String title,
                                                  RewriteResult rr, String storageMode,
                                                  String sourceUri) {
        File offline = new File(dir, "offline.m3u8");
        try (FileOutputStream fos = new FileOutputStream(offline)) {
            fos.write(rr.text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            deleteRecursive(dir);
            return ImportResult.fail("Não consegui criar a playlist offline: " + safeMessage(e));
        }

        File cover = new File(dir, "cover.jpg");
        Movie movie = new Movie();
        movie.id = id;
        movie.title = title == null || title.trim().isEmpty() ? "Filme offline" : title.trim();
        movie.folderPath = dir.getAbsolutePath();
        movie.playlistPath = offline.getAbsolutePath();
        // O caminho já fica salvo mesmo antes da capa existir. Assim ela pode ser criada depois,
        // sem regravar o cadastro e sem atrasar a importação.
        movie.coverPath = cover.getAbsolutePath();
        movie.durationMs = rr.durationMs;
        movie.progressMs = 0;
        movie.addedAt = System.currentTimeMillis();
        movie.lastPlayedAt = 0;
        movie.playCount = 0;
        movie.storageMode = storageMode;
        movie.sourceUri = sourceUri == null ? "" : sourceUri;
        return ImportResult.ok(movie);
    }

    /**
     * Cria a capa depois que o filme já apareceu na biblioteca. Retorna true se uma imagem foi criada.
     */
    public static boolean createAutomaticCover(Context context, Movie movie) {
        if (movie == null || movie.playlistPath == null || movie.playlistPath.isEmpty()) return false;
        File cover = new File(movie.folderPath, "cover.jpg");
        if (cover.exists() && cover.length() > 0) return false;

        File playlist = new File(movie.playlistPath);
        if (!playlist.exists()) return false;

        List<String> refs = new ArrayList<>();
        try (InputStream in = new java.io.FileInputStream(playlist);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null && refs.size() < 4) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) refs.add(line);
            }
        } catch (Exception ignored) {
            return false;
        }

        Bitmap frame = null;
        for (String ref : refs) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                if (ref.startsWith("content://")) {
                    retriever.setDataSource(context, Uri.parse(ref));
                } else if (ref.startsWith("file://")) {
                    retriever.setDataSource(Uri.parse(ref).getPath());
                } else {
                    File segment = new File(playlist.getParentFile(), ref);
                    if (!segment.exists()) continue;
                    retriever.setDataSource(segment.getAbsolutePath());
                }
                frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) frame = retriever.getFrameAtTime();
                if (frame != null) break;
            } catch (Exception ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }

        if (frame == null) return false;
        try (FileOutputStream fos = new FileOutputStream(cover)) {
            frame.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            frame.recycle();
        }
    }

    private static void scanFolderCopied(Context context, DocumentFile folder, File dir,
                                         CopiedHolder h, ProgressListener listener) throws Exception {
        for (DocumentFile item : folder.listFiles()) {
            if (item.isDirectory()) {
                scanFolderCopied(context, item, dir, h, listener);
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
                        copy(in, fos, null);
                    }
                    h.segments.put(n, out);
                    if (h.segments.size() % 6 == 0) {
                        listener.onProgress("Copiando para o Cine Offline… " + h.segments.size() + " partes");
                    }
                }
            } else if (isImage(lower) && !new File(dir, "cover.jpg").exists()) {
                try (InputStream in = context.getContentResolver().openInputStream(item.getUri());
                     FileOutputStream fos = new FileOutputStream(new File(dir, "cover.jpg"))) {
                    if (in != null) copy(in, fos, null);
                }
            }
        }
    }

    private static void scanFolderLinked(Context context, DocumentFile folder,
                                         LinkedHolder h, ProgressListener listener) throws Exception {
        for (DocumentFile item : folder.listFiles()) {
            if (item.isDirectory()) {
                scanFolderLinked(context, item, h, listener);
                continue;
            }
            String name = item.getName();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);

            if (lower.endsWith(".m3u8") && (h.playlist == null || lower.equals("index.m3u8"))) {
                try (InputStream in = context.getContentResolver().openInputStream(item.getUri())) {
                    h.playlist = readAllText(in);
                }
                listener.onProgress("⚡ Playlist encontrada. Indexando partes…");
            } else if (lower.endsWith(".dat") || lower.endsWith(".ts")) {
                Integer n = numberFromName(name);
                if (n != null) {
                    h.segments.put(n, item.getUri());
                    if (h.segments.size() % 20 == 0) {
                        listener.onProgress("⚡ Indexando… " + h.segments.size() + " partes");
                    }
                }
            } else if (isImage(lower) && h.coverUri == null) {
                h.coverUri = item.getUri();
            }
        }
    }

    private static RewriteResult rewritePlaylist(String playlist, Map<Integer, String> targets) {
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
                String target = n == null ? null : targets.get(n);
                if (target == null) {
                    missing++;
                    out.append(original).append('\n');
                } else {
                    out.append(target).append('\n');
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
        byte[] buffer = new byte[32 * 1024];
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

    private interface CopyProgress { void update(); }

    private static void copyEntry(ZipInputStream zis, File out, CopyProgress progress) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            copy(zis, fos, progress);
        }
    }

    private static void copy(InputStream in, FileOutputStream out, CopyProgress progress) throws Exception {
        byte[] buffer = new byte[256 * 1024];
        int n;
        int ticks = 0;
        while ((n = in.read(buffer)) >= 0) {
            if (n > 0) {
                out.write(buffer, 0, n);
                if (progress != null && (++ticks % 4 == 0)) progress.update();
            }
        }
        if (progress != null) progress.update();
    }

    private static void reportZipProgress(ProgressListener listener, long read, long total,
                                          int parts, int[] lastPercent) {
        if (listener == null) return;
        if (total > 0) {
            int percent = (int) Math.min(99, Math.max(0, (read * 100L) / total));
            if (percent != lastPercent[0] && (percent >= lastPercent[0] + 2 || percent >= 95)) {
                lastPercent[0] = percent;
                listener.onProgress("Extraindo ZIP… " + percent + "%  •  " + parts + " partes");
            }
        } else if (parts > 0 && parts % 6 == 0) {
            listener.onProgress("Extraindo ZIP… " + parts + " partes");
        }
    }

    private static long querySize(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return -1;
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

    public static class LibraryImportResult {
        public final List<Movie> movies;
        public final List<String> errors;
        public final int discovered;
        public final String fatalError;

        LibraryImportResult(List<Movie> movies, List<String> errors, int discovered, String fatalError) {
            this.movies = movies;
            this.errors = errors;
            this.discovered = discovered;
            this.fatalError = fatalError;
        }

        public static LibraryImportResult fail(String error) {
            return new LibraryImportResult(new ArrayList<>(), new ArrayList<>(), 0, error);
        }
    }

    private static class MovieProbe {
        boolean playlist;
        boolean segment;
    }

    private static class CopiedHolder {
        String playlist;
        Map<Integer, File> segments = new HashMap<>();
    }

    private static class LinkedHolder {
        String playlist;
        Map<Integer, Uri> segments = new HashMap<>();
        Uri coverUri;
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

    private static class CountingInputStream extends FilterInputStream {
        private long count;
        CountingInputStream(InputStream in) { super(in); }
        long getCount() { return count; }
        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
        @Override public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) count += skipped;
            return skipped;
        }
    }
}
