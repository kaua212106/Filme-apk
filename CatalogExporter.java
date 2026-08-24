package com.offlineplayer.cineoffline;

import android.content.Context;
import android.net.Uri;
import android.util.JsonWriter;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gera um catálogo pequeno da pasta de filmes sem copiar os vídeos.
 * O arquivo exportado pode ser enviado para análise para descobrir títulos,
 * séries, temporadas e episódios.
 */
public final class CatalogExporter {
    private CatalogExporter() {}

    public interface ProgressListener {
        void onProgress(String message);
    }

    private static final Set<String> HEAVY_MEDIA = new HashSet<>(Arrays.asList(
            "dat", "ts", "m2ts", "mts", "mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp"
    ));

    private static final Set<String> TEXT_METADATA = new HashSet<>(Arrays.asList(
            "m3u8", "json", "txt", "nfo", "xml", "srt", "vtt", "info", "ini", "conf", "cue", "url", "html", "htm"
    ));

    private static final Set<String> IMAGE_FILES = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp"
    ));

    private static final int MAX_TEXT_PER_FILE = 128 * 1024;
    private static final int MAX_TEXT_TOTAL = 8 * 1024 * 1024;
    private static final int MAX_MEDIA_SAMPLES = 14;
    private static final int MAX_OTHER_FILES_PER_FOLDER = 80;

    private static final Pattern[] TITLE_PATTERNS = new Pattern[] {
            Pattern.compile("\\\"(?:title|name|movie_title|original_title|showtitle|seriesName|series_name|episodeName|episode_name)\\\"\\s*:\\s*\\\"([^\\\"]{2,180})\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<(?:title|showtitle|originaltitle|episodetitle|name)>\\s*([^<]{2,180})\\s*</", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:title|name|movie|series|show|episode)\\s*[:=]\\s*(.{2,180})$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            Pattern.compile("#EXTINF:[^,]*,\\s*(.{2,180})$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
    };

    public static final class Result {
        public final File file;
        public final int folders;
        public final int files;
        public final int mediaFiles;
        public final long mediaBytes;
        public final int metadataFiles;

        Result(File file, Counters c) {
            this.file = file;
            this.folders = c.folders;
            this.files = c.files;
            this.mediaFiles = c.mediaFiles;
            this.mediaBytes = c.mediaBytes;
            this.metadataFiles = c.metadataFiles;
        }
    }

    private static final class Counters {
        int folders;
        int files;
        int mediaFiles;
        long mediaBytes;
        int metadataFiles;
        int imageFiles;
        int otherFiles;
        int textBytes;
    }

    public static Result scanToCache(Context context, Uri treeUri, ProgressListener listener) throws Exception {
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists() || !root.isDirectory()) {
            throw new IllegalArgumentException("A pasta selecionada não pôde ser aberta.");
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File out = new File(context.getCacheDir(), "cine_catalogo_" + stamp + ".json");
        Counters counters = new Counters();

        if (listener != null) listener.onProgress("Preparando leitura da pasta…");

        try (FileOutputStream fos = new FileOutputStream(out);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             JsonWriter w = new JsonWriter(osw)) {

            w.setIndent("  ");
            w.beginObject();
            w.name("format").value("cine-offline-catalog-v1");
            w.name("createdAt").value(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
            w.name("sourceFolderName").value(safe(root.getName(), "Filmes"));
            w.name("importantNote").value("Os arquivos de vídeo pesados não foram copiados. O catálogo contém nomes, tamanhos, estrutura e pequenos trechos de metadados para identificação.");
            w.name("root");
            writeDirectory(context, root, "", w, counters, listener, 0);
            w.name("summary");
            w.beginObject();
            w.name("folders").value(counters.folders);
            w.name("files").value(counters.files);
            w.name("heavyMediaFiles").value(counters.mediaFiles);
            w.name("heavyMediaBytes").value(counters.mediaBytes);
            w.name("metadataFiles").value(counters.metadataFiles);
            w.name("imageFiles").value(counters.imageFiles);
            w.name("otherFiles").value(counters.otherFiles);
            w.name("metadataTextBytesIncluded").value(counters.textBytes);
            w.endObject();
            w.endObject();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }

        if (listener != null) listener.onProgress("Catálogo pronto para salvar.");
        return new Result(out, counters);
    }

    private static void writeDirectory(Context context, DocumentFile dir, String parentPath,
                                       JsonWriter w, Counters c, ProgressListener listener,
                                       int depth) throws Exception {
        checkInterrupted();
        c.folders++;

        String name = safe(dir.getName(), depth == 0 ? "Filmes" : "Pasta");
        String path = parentPath.isEmpty() ? name : parentPath + "/" + name;
        if (listener != null) listener.onProgress("Lendo: " + shorten(path, 58));

        DocumentFile[] items;
        try {
            items = dir.listFiles();
        } catch (Exception e) {
            items = new DocumentFile[0];
        }

        List<DocumentFile> children = new ArrayList<>();
        List<DocumentFile> metadata = new ArrayList<>();
        List<DocumentFile> others = new ArrayList<>();
        List<String> mediaSamples = new ArrayList<>();
        LinkedHashSet<String> titleHints = new LinkedHashSet<>();
        int localMediaCount = 0;
        long localMediaBytes = 0;
        int localImageCount = 0;
        int localOtherCount = 0;

        for (DocumentFile item : items) {
            checkInterrupted();
            if (item.isDirectory()) {
                children.add(item);
                continue;
            }
            c.files++;
            String fileName = safe(item.getName(), "arquivo");
            String ext = extension(fileName);
            long size = Math.max(0L, item.length());

            if (HEAVY_MEDIA.contains(ext)) {
                c.mediaFiles++;
                c.mediaBytes += size;
                localMediaCount++;
                localMediaBytes += size;
                if (mediaSamples.size() < MAX_MEDIA_SAMPLES) mediaSamples.add(fileName);
                addFilenameHint(fileName, titleHints);
            } else if (TEXT_METADATA.contains(ext)) {
                c.metadataFiles++;
                metadata.add(item);
                addFilenameHint(fileName, titleHints);
            } else if (IMAGE_FILES.contains(ext)) {
                c.imageFiles++;
                localImageCount++;
                addFilenameHint(fileName, titleHints);
                if (others.size() < MAX_OTHER_FILES_PER_FOLDER) others.add(item);
            } else {
                c.otherFiles++;
                localOtherCount++;
                addFilenameHint(fileName, titleHints);
                if (others.size() < MAX_OTHER_FILES_PER_FOLDER) others.add(item);
            }
        }

        w.beginObject();
        w.name("name").value(name);
        w.name("path").value(path);
        w.name("lastModified").value(dir.lastModified());

        // Pasta com nome normal também vira pista. Hashes longos são ignorados.
        if (!looksLikeHash(name)) addCleanHint(name, titleHints);

        w.name("heavyMedia");
        w.beginObject();
        w.name("count").value(localMediaCount);
        w.name("totalBytes").value(localMediaBytes);
        w.name("sampleNames");
        w.beginArray();
        for (String s : mediaSamples) w.value(s);
        w.endArray();
        w.endObject();

        w.name("images");
        w.beginObject();
        w.name("count").value(localImageCount);
        w.endObject();

        w.name("metadataFiles");
        w.beginArray();
        for (DocumentFile f : metadata) {
            checkInterrupted();
            writeMetadataFile(context, f, w, c, titleHints);
        }
        w.endArray();

        w.name("otherFiles");
        w.beginArray();
        int written = 0;
        for (DocumentFile f : others) {
            if (written++ >= MAX_OTHER_FILES_PER_FOLDER) break;
            w.beginObject();
            w.name("name").value(safe(f.getName(), "arquivo"));
            w.name("size").value(Math.max(0L, f.length()));
            w.name("lastModified").value(f.lastModified());
            w.endObject();
        }
        w.endArray();
        w.name("otherFilesNotListed").value(Math.max(0, localOtherCount + localImageCount - others.size()));

        // Os hints encontrados nos metadados acima entram aqui.
        w.name("titleHints");
        w.beginArray();
        int hintCount = 0;
        for (String hint : titleHints) {
            if (hintCount++ >= 20) break;
            w.value(hint);
        }
        w.endArray();

        w.name("children");
        w.beginArray();
        for (DocumentFile child : children) {
            writeDirectory(context, child, path, w, c, listener, depth + 1);
        }
        w.endArray();
        w.endObject();
    }

    private static void writeMetadataFile(Context context, DocumentFile f, JsonWriter w,
                                          Counters c, LinkedHashSet<String> titleHints) throws Exception {
        String name = safe(f.getName(), "metadata");
        String text = "";
        boolean truncated = false;
        String error = null;

        int remainingGlobal = Math.max(0, MAX_TEXT_TOTAL - c.textBytes);
        int limit = Math.min(MAX_TEXT_PER_FILE, remainingGlobal);
        if (limit > 0) {
            try {
                ReadTextResult r = readText(context, f.getUri(), limit);
                text = r.text;
                truncated = r.truncated;
                c.textBytes += text.getBytes(StandardCharsets.UTF_8).length;
                extractTitleHints(text, titleHints);
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + safe(e.getMessage(), "não foi possível ler");
            }
        } else {
            truncated = true;
        }

        w.beginObject();
        w.name("name").value(name);
        w.name("size").value(Math.max(0L, f.length()));
        w.name("lastModified").value(f.lastModified());
        w.name("textIncluded").value(!text.isEmpty());
        w.name("truncated").value(truncated);
        if (!text.isEmpty()) w.name("text").value(text);
        if (error != null) w.name("readError").value(error);
        w.endObject();
    }

    private static final class ReadTextResult {
        String text;
        boolean truncated;
    }

    private static ReadTextResult readText(Context context, Uri uri, int maxBytes) throws Exception {
        ReadTextResult out = new ReadTextResult();
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        boolean truncated = false;

        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("arquivo não pôde ser aberto");
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                checkInterrupted();
                if (n == 0) continue;
                String piece = new String(buf, 0, n);
                int pieceBytes = piece.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + pieceBytes > maxBytes) {
                    int charsToKeep = Math.max(0, Math.min(piece.length(), (maxBytes - bytes) / 2));
                    if (charsToKeep > 0) sb.append(piece, 0, charsToKeep);
                    truncated = true;
                    break;
                }
                sb.append(piece);
                bytes += pieceBytes;
                if (bytes >= maxBytes) {
                    truncated = true;
                    break;
                }
            }
            if (!truncated && br.read() != -1) truncated = true;
        }

        out.text = sb.toString();
        out.truncated = truncated;
        return out;
    }

    private static void extractTitleHints(String text, LinkedHashSet<String> hints) {
        if (text == null || text.isEmpty()) return;
        for (Pattern p : TITLE_PATTERNS) {
            Matcher m = p.matcher(text);
            int found = 0;
            while (m.find() && found++ < 8 && hints.size() < 20) {
                addCleanHint(m.group(1), hints);
            }
        }

        // Pistas comuns de temporada/episódio, ex.: S02E05, 2x05.
        Matcher episode = Pattern.compile("(?i)\\b(?:S\\s*\\d{1,2}\\s*E\\s*\\d{1,3}|\\d{1,2}x\\d{1,3})\\b").matcher(text);
        int ep = 0;
        while (episode.find() && ep++ < 6 && hints.size() < 20) addCleanHint(episode.group(), hints);
    }

    private static void addFilenameHint(String filename, LinkedHashSet<String> hints) {
        if (filename == null) return;
        String base = filename.replaceFirst("(?i)\\.[a-z0-9]{1,6}$", "");
        if (!looksLikeHash(base)) addCleanHint(base, hints);
    }

    private static void addCleanHint(String raw, LinkedHashSet<String> hints) {
        if (raw == null) return;
        String s = raw.replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (s.length() < 2 || s.length() > 180 || looksLikeHash(s)) return;
        hints.add(s);
    }

    private static boolean looksLikeHash(String s) {
        if (s == null) return true;
        String x = s.trim().replaceAll("[-_ ]", "");
        if (x.length() >= 16 && x.matches("(?i)[0-9a-f]+")) return true;
        if (x.length() >= 20 && x.matches("(?i)[a-z0-9]+") && !x.matches(".*[aeiou].*[aeiou].*")) return true;
        if (x.matches("(?i)(?:segment|part|chunk|video|file)?\\d{3,}")) return true;
        return false;
    }

    private static String extension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String shorten(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return "…" + s.substring(s.length() - max + 1);
    }

    private static String safe(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s.trim();
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Leitura cancelada.");
    }
}
