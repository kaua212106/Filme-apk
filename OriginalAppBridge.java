package com.offlineplayer.cineoffline;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ponte sem root entre o Cine Offline e o app que criou a pasta Filmes. */
public final class OriginalAppBridge {
    public static final String ORIGINAL_PACKAGE = "com.starshort.minishort";
    private static final String PREF = "cine_original_bridge";
    private static final String KEY_PORT = "last_port";
    private static final String KEY_SAVED_MAP = "saved_resource_title_map";
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("(?i)(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])");

    private OriginalAppBridge() {}

    public interface ProgressListener { void onProgress(String text); }

    public static boolean isOriginalAppInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(ORIGINAL_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isAccessibilityEnabled(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) return false;
        String wanted = new ComponentName(context, OriginalTitleAccessibilityService.class).flattenToString();
        for (AccessibilityServiceInfo info : enabled) {
            if (info == null || info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            ComponentName c = new ComponentName(info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name);
            if (wanted.equals(c.flattenToString())) return true;
        }
        return false;
    }

    public static void openAccessibilitySettings(Context context) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    public static boolean openOriginalApp(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent i = pm.getLaunchIntentForPackage(ORIGINAL_PACKAGE);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void beginCapture(Context context) {
        OriginalTitleAccessibilityService.beginCapture(context.getApplicationContext());
    }

    public static void clearCapture(Context context) {
        OriginalTitleAccessibilityService.clearCapture(context.getApplicationContext());
    }

    public static int getCapturedTitleCount(Context context) {
        return OriginalTitleAccessibilityService.capturedCount(context.getApplicationContext());
    }

    public static boolean isCaptureFinished(Context context) {
        return OriginalTitleAccessibilityService.isCaptureFinished(context.getApplicationContext());
    }

    public static List<String> getCapturedTitles(Context context) {
        return OriginalTitleAccessibilityService.getCapturedTitles(context.getApplicationContext());
    }

    /** Relações código da pasta -> título já confirmadas. Ficam salvas mesmo após apagar o download do app original. */
    public static Map<String, String> getSavedMappings(Context context) {
        Map<String, String> out = new HashMap<>();
        String raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SAVED_MAP, "{}");
        try {
            JSONObject o = new JSONObject(raw == null ? "{}" : raw);
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = normalizeResource(names.optString(i, ""));
                    String title = cleanCapturedTitle(o.optString(names.optString(i, ""), ""));
                    if (!key.isEmpty() && !title.isEmpty()) out.put(key, title);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static int getSavedMappingCount(Context context) {
        return getSavedMappings(context).size();
    }

    private static void saveMappings(Context context, Map<String, String> incoming) {
        Map<String, String> merged = getSavedMappings(context);
        if (incoming != null) {
            for (Map.Entry<String, String> e : incoming.entrySet()) {
                String key = normalizeResource(e.getKey());
                String title = cleanCapturedTitle(e.getValue());
                if (!key.isEmpty() && !title.isEmpty()) merged.put(key, title);
            }
        }
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, String> e : merged.entrySet()) o.put(e.getKey(), e.getValue()); }
        catch (Exception ignored) {}
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_SAVED_MAP, o.toString()).apply();
    }

    public static JSONObject exportSavedMappings(Context context) {
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, String> e : getSavedMappings(context).entrySet()) o.put(e.getKey(), e.getValue()); }
        catch (Exception ignored) {}
        return o;
    }

    public static int importSavedMappings(Context context, JSONObject object) {
        if (object == null) return 0;
        Map<String, String> incoming = new HashMap<>();
        JSONArray names = object.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String rawKey = names.optString(i, "");
                String key = normalizeResource(rawKey);
                String title = cleanCapturedTitle(object.optString(rawKey, ""));
                if (!key.isEmpty() && !title.isEmpty()) incoming.put(key, title);
            }
        }
        saveMappings(context, incoming);
        return incoming.size();
    }

    public static IdentificationResult applySavedMappings(Context context, MovieRepository repo) {
        Map<String, String> byResource = getSavedMappings(context);
        if (byResource.isEmpty()) return IdentificationResult.fail("Nenhuma associação de nome foi salva ainda.");
        return applyMappingsToLibrary(repo, byResource, 0, 0,
                "Os nomes vieram da configuração salva no próprio Cine Offline.");
    }

    public static IdentificationResult identifyAndRename(Context context, MovieRepository repo,
                                                         ProgressListener listener) {
        // A captura de acessibilidade continua existindo como fallback para filmes antigos.
        // Para séries, porém, a tela principal mostra apenas a temporada agrupada. O servidor
        // local do app original expõe cada download individual com streamid/complete_name,
        // então usamos esses dados primeiro para não perder os episódios que ficam "dentro"
        // da temporada.
        List<String> capturedTitles = getCapturedTitles(context);

        listener.onProgress("🔎 Lendo filmes e episódios do app original…");
        List<DownloadEntry> entries;
        try {
            entries = fetchDownloadEntries(context, listener);
        } catch (Exception e) {
            return IdentificationResult.fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (entries.isEmpty()) return IdentificationResult.fail("O app original não retornou downloads.");

        List<DownloadEntry> completed = new ArrayList<>();
        boolean hasPercent = false;
        for (DownloadEntry e : entries) if (e.percentKnown) hasPercent = true;
        for (DownloadEntry e : entries) {
            if (!hasPercent || e.percent >= 100) completed.add(e);
        }
        Collections.sort(completed, Comparator.comparing(a -> a.downloadTime == null ? "" : a.downloadTime));
        if (completed.isEmpty()) return IdentificationResult.fail("Não encontrei downloads concluídos no app original.");

        Map<String, String> byResource = new HashMap<>();
        int directNames = 0;

        // Primeiro método: associação exata do próprio app original. Cada episódio possui seu
        // próprio streamid, mesmo quando a interface mostra a temporada como um único cartão.
        for (DownloadEntry e : completed) {
            String resource = normalizeResource(e.resource);
            String title = cleanCapturedTitle(e.completeName);
            if (resource.isEmpty() || title.isEmpty()) continue;
            byResource.put(resource, title);
            directNames++;
        }

        // Fallback antigo: só usa a ordem da tela quando a quantidade bate EXATAMENTE com os
        // itens ainda sem nome. Isso evita atribuir o nome de uma temporada a um episódio errado.
        if (byResource.size() < completed.size() && !capturedTitles.isEmpty()) {
            List<DownloadEntry> missing = new ArrayList<>();
            for (DownloadEntry e : completed) {
                String resource = normalizeResource(e.resource);
                if (!resource.isEmpty() && !byResource.containsKey(resource)) missing.add(e);
            }

            if (capturedTitles.size() == missing.size()) {
                for (int i = 0; i < missing.size(); i++) {
                    String resource = normalizeResource(missing.get(i).resource);
                    String title = cleanCapturedTitle(capturedTitles.get(i));
                    if (!resource.isEmpty() && !title.isEmpty()) byResource.put(resource, title);
                }
            } else if (directNames == 0 && capturedTitles.size() == completed.size()) {
                for (int i = 0; i < completed.size(); i++) {
                    String resource = normalizeResource(completed.get(i).resource);
                    String title = cleanCapturedTitle(capturedTitles.get(i));
                    if (!resource.isEmpty() && !title.isEmpty()) byResource.put(resource, title);
                }
            }
        }

        if (byResource.isEmpty()) {
            return IdentificationResult.fail(
                    "O app original foi encontrado, mas não devolveu nomes individuais para os downloads. "
                            + "Deixe-o aberto em Downloads > Baixado e tente novamente. Nenhum nome foi alterado.");
        }

        saveMappings(context, byResource);
        listener.onProgress("💾 Salvando filmes e episódios no Cine Offline…");

        String warning = directNames > 0
                ? "Os episódios agrupados por temporada foram lidos individualmente pelo registro de downloads do app original. "
                    + "As associações código → nome ficaram salvas no celular e também entram no backup da organização."
                : "Os nomes vieram da captura visual. As associações código → nome ficaram salvas no celular e entram no backup da organização.";

        return applyMappingsToLibrary(repo, byResource, capturedTitles.size(), completed.size(), warning);
    }

    private static IdentificationResult applyMappingsToLibrary(MovieRepository repo, Map<String, String> byResource,
                                                                 int capturedTitles, int completedDownloads,
                                                                 String warning) {
        List<Movie> movies = repo.getAll();
        int renamed = 0;
        int foundIds = 0;
        int unchanged = 0;
        int notMatched = 0;

        for (Movie movie : movies) {
            String resource = extractResourceId(movie);
            if (resource.isEmpty()) {
                notMatched++;
                continue;
            }
            foundIds++;
            String title = byResource.get(resource);
            if (title == null || title.isEmpty()) {
                notMatched++;
                continue;
            }
            if (!title.equals(movie.title)) {
                movie.title = title;
                repo.save(movie);
                renamed++;
            } else {
                unchanged++;
            }
        }

        if (foundIds == 0 && !movies.isEmpty()) {
            return IdentificationResult.fail("Os filmes atuais não guardam o código da pasta no índice offline. "
                    + "Remova-os e importe a pasta Filmes novamente no modo rápido, depois tente de novo.");
        }

        return new IdentificationResult(true, renamed, unchanged, movies.size(), foundIds,
                capturedTitles, completedDownloads, notMatched, warning, null);
    }

    private static String cleanCapturedTitle(String s) {
        if (s == null) return "";
        String out = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (out.length() > 180) out = out.substring(0, 180).trim();
        return out;
    }

    private static String normalizeResource(String s) {
        if (s == null) return "";
        Matcher m = RESOURCE_PATTERN.matcher(s);
        if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
        String v = s.trim();
        return v.matches("(?i)[0-9a-f]{16,64}") ? v.toUpperCase(Locale.ROOT) : "";
    }

    private static String extractResourceId(Movie movie) {
        if (movie == null) return "";
        // No modo rápido, o offline.m3u8 contém Content URIs com o nome da pasta hexadecimal.
        String fromPlaylist = findHexInFile(movie.playlistPath);
        if (!fromPlaylist.isEmpty()) return fromPlaylist;
        String[] fallbacks = {movie.sourceUri, movie.folderPath, movie.playlistPath};
        for (String s : fallbacks) {
            if (s == null) continue;
            Matcher m = RESOURCE_PATTERN.matcher(Uri.decode(s));
            if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
        }
        return "";
    }

    private static String findHexInFile(String path) {
        if (path == null || path.isEmpty()) return "";
        File f = new File(path);
        if (!f.exists()) return "";
        try (InputStream in = new FileInputStream(f);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines++ < 80) {
                Matcher m = RESOURCE_PATTERN.matcher(Uri.decode(line));
                if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static List<DownloadEntry> fetchDownloadEntries(Context context, ProgressListener listener) throws Exception {
        int saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_PORT, -1);
        if (saved > 0) {
            String body = queryDownloadInfo(saved);
            List<DownloadEntry> parsed = parseEntries(body);
            if (!parsed.isEmpty()) return parsed;
        }

        Set<Integer> candidates = listeningPorts();
        for (int port : candidates) {
            String body = queryDownloadInfo(port);
            List<DownloadEntry> parsed = parseEntries(body);
            if (!parsed.isEmpty()) {
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_PORT, port).apply();
                return parsed;
            }
        }

        listener.onProgress("🔎 Procurando o servidor local do app original…");
        int foundPort = scanLocalhostForServer();
        if (foundPort <= 0) {
            throw new Exception("Não encontrei o servidor local do app original. Abra o app original, deixe-o aberto na tela de Downloads e volte ao Cine Offline sem fechá-lo.");
        }
        String body = queryDownloadInfo(foundPort);
        List<DownloadEntry> parsed = parseEntries(body);
        if (parsed.isEmpty()) throw new Exception("Encontrei o servidor local, mas ele não retornou a lista de downloads.");
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_PORT, foundPort).apply();
        return parsed;
    }

    private static Set<Integer> listeningPorts() {
        HashSet<Integer> out = new HashSet<>();
        readProcPorts("/proc/net/tcp", out);
        readProcPorts("/proc/net/tcp6", out);
        return out;
    }

    private static void readProcPorts(String path, Set<Integer> out) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.US_ASCII))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] p = line.trim().split("\\s+");
                if (p.length < 4 || !"0A".equals(p[3])) continue;
                String local = p[1];
                int colon = local.lastIndexOf(':');
                if (colon < 0) continue;
                int port = Integer.parseInt(local.substring(colon + 1), 16);
                if (port > 0 && port <= 65535) out.add(port);
            }
        } catch (Exception ignored) {}
    }

    private static int scanLocalhostForServer() {
        final int workers = 64;
        final int minPort = 1024;
        final int maxPort = 65535;
        AtomicInteger found = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(workers);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(workers);
        for (int w = 0; w < workers; w++) {
            final int offset = w;
            pool.execute(() -> {
                try {
                    for (int port = minPort + offset; port <= maxPort && found.get() < 0; port += workers) {
                        if (!isPortOpen(port)) continue;
                        String body = queryDownloadInfo(port);
                        if (looksLikeDownloadInfo(body) && found.compareAndSet(-1, port)) break;
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        try { done.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        pool.shutdownNow();
        return found.get();
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 22);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String queryDownloadInfo(int port) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://127.0.0.1:" + port + "/control?msg=download_info");
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(180);
            c.setReadTimeout(450);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return "";
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 2_000_000) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean looksLikeDownloadInfo(String body) {
        if (body == null) return false;
        String s = body.toLowerCase(Locale.ROOT);
        boolean hasId = s.contains("resource") || s.contains("streamid") || s.contains("stream_id");
        boolean hasDownloadData = s.contains("download_time") || s.contains("download_percent")
                || s.contains("complete_name") || s.contains("download_size");
        return hasId && hasDownloadData;
    }

    private static List<DownloadEntry> parseEntries(String body) {
        ArrayList<DownloadEntry> out = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return out;
        try {
            Object root;
            String s = body.trim();
            if (s.startsWith("[")) root = new JSONArray(s);
            else root = new JSONObject(s);
            JSONArray arr = findArray(root);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;

                DownloadEntry e = new DownloadEntry();
                e.resource = firstNonBlank(
                        o.optString("resource", ""),
                        o.optString("streamid", ""),
                        o.optString("streamId", ""),
                        o.optString("stream_id", "")
                );
                if (normalizeResource(e.resource).isEmpty()) continue;

                e.completeName = firstNonBlank(
                        o.optString("complete_name", ""),
                        o.optString("completeName", ""),
                        o.optString("name", ""),
                        o.optString("title", "")
                );
                e.downloadTime = firstNonBlank(
                        o.optString("download_time", ""),
                        o.optString("downloadTime", ""),
                        o.optString("updateTime", "")
                );
                e.videoType = firstNonBlank(
                        o.optString("videoType", ""),
                        o.optString("video_type", "")
                );
                e.episodeNum = firstNonBlank(
                        o.optString("episodeNum", ""),
                        o.optString("episode_num", ""),
                        o.optString("episode", "")
                );

                if (o.has("download_percent")) {
                    e.percentKnown = true;
                    e.percent = o.optInt("download_percent", 0);
                } else if (o.has("downloadPercent")) {
                    e.percentKnown = true;
                    e.percent = o.optInt("downloadPercent", 0);
                }

                e.downloadSize = firstPositiveLong(
                        o.optLong("download_size", 0),
                        o.optLong("downloadSize", 0),
                        o.optLong("file_size", 0),
                        o.optLong("fileSize", 0),
                        o.optLong("total_size", 0),
                        o.optLong("totalSize", 0),
                        o.optLong("size", 0)
                );
                out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.trim().isEmpty() && !"null".equalsIgnoreCase(v.trim())) return v.trim();
        }
        return "";
    }

    private static long firstPositiveLong(long... values) {
        if (values == null) return 0L;
        for (long v : values) if (v > 0) return v;
        return 0L;
    }

    private static JSONArray findArray(Object root) {
        if (root instanceof JSONArray) return (JSONArray) root;
        if (!(root instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) root;
        String[] keys = {"data", "list", "result", "rows", "downloads", "download_info", "downloadInfo"};
        for (String key : keys) {
            Object v = o.opt(key);
            if (v instanceof JSONArray) return (JSONArray) v;
            if (v instanceof JSONObject) {
                JSONArray nested = findArray(v);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static class DownloadEntry {
        String resource = "";
        String completeName = "";
        String downloadTime = "";
        String videoType = "";
        String episodeNum = "";
        long downloadSize = 0L;
        int percent = 0;
        boolean percentKnown = false;
    }

    public static class IdentificationResult {
        public final boolean ok;
        public final int renamed;
        public final int unchanged;
        public final int libraryMovies;
        public final int movieIdsFound;
        public final int capturedTitles;
        public final int completedDownloads;
        public final int notMatched;
        public final String warning;
        public final String error;

        IdentificationResult(boolean ok, int renamed, int unchanged, int libraryMovies, int movieIdsFound,
                             int capturedTitles, int completedDownloads, int notMatched,
                             String warning, String error) {
            this.ok = ok;
            this.renamed = renamed;
            this.unchanged = unchanged;
            this.libraryMovies = libraryMovies;
            this.movieIdsFound = movieIdsFound;
            this.capturedTitles = capturedTitles;
            this.completedDownloads = completedDownloads;
            this.notMatched = notMatched;
            this.warning = warning == null ? "" : warning;
            this.error = error;
        }

        static IdentificationResult fail(String error) {
            return new IdentificationResult(false, 0, 0, 0, 0, 0, 0, 0, "", error);
        }
    }
}
