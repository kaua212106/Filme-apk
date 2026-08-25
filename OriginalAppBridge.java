package com.offlineplayer.cineoffline;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.accessibility.AccessibilityManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
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
    private static final String KEY_PENDING_LOCALS = "pending_local_items";
    private static final String KEY_PENDING_CAPTURED = "pending_captured_items";
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

    private static File savedCoverFile(Context context, String resource) {
        String key = normalizeResource(resource);
        if (key.isEmpty()) return null;
        File dir = new File(context.getFilesDir(), "original_covers");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key + ".jpg");
    }

    private static boolean saveCoverForResource(Context context, String resource, String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) return false;
        File src = new File(sourcePath);
        File dst = savedCoverFile(context, resource);
        if (!src.exists() || src.length() <= 0 || dst == null) return false;
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dst.length() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean applySavedCover(Context context, Movie movie, String resource) {
        if (movie == null) return false;
        File src = savedCoverFile(context, resource);
        if (src == null || !src.exists() || src.length() <= 0) return false;
        File dst = new File(movie.folderPath, "cover.jpg");
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            movie.coverPath = dst.getAbsolutePath();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Capas capturadas do app original, usadas pelo backup da organização. */
    public static JSONObject exportSavedCovers(Context context) {
        JSONObject out = new JSONObject();
        File dir = new File(context.getFilesDir(), "original_covers");
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f == null || !f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".jpg")) continue;
            String resource = normalizeResource(f.getName().substring(0, f.getName().length() - 4));
            if (resource.isEmpty() || f.length() <= 0 || f.length() > 600_000) continue;
            try (InputStream in = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && bos.size() <= 650_000) bos.write(buf, 0, n);
                out.put(resource, Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static int importSavedCovers(Context context, JSONObject object) {
        if (object == null) return 0;
        int restored = 0;
        JSONArray names = object.names();
        if (names == null) return 0;
        for (int i = 0; i < names.length(); i++) {
            String rawKey = names.optString(i, "");
            String resource = normalizeResource(rawKey);
            String b64 = object.optString(rawKey, "");
            if (resource.isEmpty() || b64.isEmpty()) continue;
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                if (bytes.length == 0 || bytes.length > 700_000) continue;
                File dst = savedCoverFile(context, resource);
                if (dst == null) continue;
                try (FileOutputStream out = new FileOutputStream(dst)) { out.write(bytes); }
                restored++;
            } catch (Exception ignored) {}
        }
        return restored;
    }

    public static IdentificationResult applySavedMappings(Context context, MovieRepository repo) {
        Map<String, String> byResource = getSavedMappings(context);
        if (byResource.isEmpty()) return IdentificationResult.fail("Nenhuma associação de nome foi salva ainda.");
        IdentificationResult result = applyMappingsToLibrary(context, repo, byResource, 0, 0,
                "Os nomes vieram da configuração salva no próprio Cine Offline.");
        autoOrganizeRecognizedSeries(context, repo);
        return result;
    }

    public static IdentificationResult identifyAndRename(Context context, MovieRepository repo,
                                                         ProgressListener listener) {
        List<OriginalTitleAccessibilityService.CapturedItem> captured =
                OriginalTitleAccessibilityService.getCapturedItems(context.getApplicationContext());
        if (captured.isEmpty()) {
            return IdentificationResult.fail("Nenhum filme ou episódio individual foi capturado ainda. Faça uma nova captura começando em Downloads > Baixado.");
        }

        List<Movie> movies = repo.getAll();
        if (movies.isEmpty()) return IdentificationResult.fail("A biblioteca do Cine Offline está vazia.");

        ArrayList<LocalMovieInfo> locals = new ArrayList<>();
        for (Movie movie : movies) {
            String resource = extractResourceId(movie);
            if (resource.isEmpty()) continue;
            long size = readOriginalDownloadSize(context, movie, resource);
            locals.add(new LocalMovieInfo(movie, resource, size));
        }
        if (locals.isEmpty()) {
            return IdentificationResult.fail("Os filmes atuais não guardam o código da pasta. Importe novamente a pasta Filmes no modo rápido e tente de novo.");
        }

        Map<String, String> byResource = new HashMap<>();
        Set<String> usedResources = new HashSet<>();
        Set<Integer> usedCaptured = new HashSet<>();
        int matched = 0;

        // 1) Primeiro tenta usar a própria lista interna de downloads do app original.
        // Ela fornece o resource exato e, em várias versões, download_size/complete_name.
        // Isso elimina a principal causa dos itens pendentes: o tamanho mostrado na tela
        // nem sempre é igual à soma dos campos sz= do index.m3u8.
        List<DownloadEntry> remote = Collections.emptyList();
        try {
            listener.onProgress("🔗 Lendo a lista interna de downloads do app original…");
            remote = fetchDownloadEntries(context, listener);
        } catch (Exception ignored) {
            remote = Collections.emptyList();
        }

        if (!remote.isEmpty()) {
            // Se a versão do app original já devolver o nome junto do resource, usa direto.
            for (DownloadEntry e : remote) {
                String resource = normalizeResource(e.resource);
                String title = cleanCapturedTitle(e.title);
                if (resource.isEmpty() || title.isEmpty()) continue;
                byResource.put(resource, title);
                usedResources.add(resource);
            }

            listener.onProgress("🎯 Ligando nomes aos códigos exatos dos downloads…");
            matched += matchCapturedToDownloadEntries(context, captured, remote, byResource,
                    usedResources, usedCaptured);
        }

        // 2) Fallback: associa pelo tamanho calculado a partir do conteúdo local.
        // Usa casamento global (menor erro primeiro), em vez de consumir os itens na ordem
        // da tela. Isso evita que um episódio de tamanho parecido roube o recurso de outro.
        listener.onProgress("🔎 Conferindo os itens restantes pelo tamanho local…");
        matched += matchCapturedToLocalMovies(context, captured, locals, byResource,
                usedResources, usedCaptured);

        // 3) Última tentativa para os ainda pendentes: lê o tamanho REAL dos .dat/.ts
        // pelo Storage Access Framework. É mais lento, por isso só roda nos pendentes.
        ArrayList<LocalMovieInfo> unresolvedLocals = new ArrayList<>();
        for (LocalMovieInfo local : locals) {
            if (!usedResources.contains(local.resource)) unresolvedLocals.add(local);
        }
        boolean hasCapturedPendingWithSize = false;
        for (int i = 0; i < captured.size(); i++) {
            OriginalTitleAccessibilityService.CapturedItem item = captured.get(i);
            if (!usedCaptured.contains(i) && item != null && item.sizeBytes > 0) {
                hasCapturedPendingWithSize = true;
                break;
            }
        }
        if (!unresolvedLocals.isEmpty() && hasCapturedPendingWithSize) {
            listener.onProgress("📦 Conferindo o tamanho real dos arquivos pendentes…");
            for (LocalMovieInfo local : unresolvedLocals) {
                long exact = readActualStoredDownloadSize(context, local.movie, local.resource);
                if (exact > 0) local.exactSizeBytes = exact;
            }
            matched += matchCapturedToLocalMovies(context, captured, unresolvedLocals, byResource,
                    usedResources, usedCaptured);
        }

        // 4) Quando sobram poucos itens, resolve o conjunto inteiro de uma vez.
        // O algoritmo anterior analisava cada nome isoladamente e rejeitava casos em que
        // dois vídeos tinham tamanhos muito próximos. Aqui comparamos TODAS as combinações
        // restantes e escolhemos a combinação global de menor erro, mas só aceitamos se
        // houver uma vantagem clara sobre a segunda melhor combinação.
        int finalResolved = matchSmallRemainingSetGlobally(context, captured, locals, byResource,
                usedResources, usedCaptured);
        matched += finalResolved;

        // Se ainda houver itens ambíguos, salva a lista para permitir resolver manualmente
        // sem repetir toda a captura. Isso é especialmente útil quando só restam 1 ou 2.
        savePendingResolution(context, captured, locals, usedResources, usedCaptured);

        if (byResource.isEmpty()) {
            return IdentificationResult.fail(
                    "Consegui ler " + captured.size() + " item(ns) na tela, mas não consegui ligar nenhum deles às pastas locais com segurança. "
                            + "Faça a captura novamente sem apagar os downloads do app original.");
        }

        saveMappings(context, byResource);
        listener.onProgress("💾 Salvando filmes e episódios reconhecidos…");
        String warning = finalResolved > 0
                ? ("Resolvi " + finalResolved + " item(ns) restante(s) comparando o conjunto inteiro pelos tamanhos reais. "
                + "Se ainda restar algum item, use “Resolver pendentes” em vez de repetir a captura.")
                : "A associação usa o código exato do download quando o app original o disponibiliza e confere os tamanhos reais dos arquivos nos casos pendentes. As associações ficam no backup.";
        IdentificationResult result = applyMappingsToLibrary(context, repo, byResource,
                captured.size(), byResource.size(), warning);
        autoOrganizeRecognizedSeries(context, repo);
        return result;
    }

    private static int matchCapturedToDownloadEntries(Context context,
                                                       List<OriginalTitleAccessibilityService.CapturedItem> captured,
                                                       List<DownloadEntry> entries,
                                                       Map<String, String> byResource,
                                                       Set<String> usedResources,
                                                       Set<Integer> usedCaptured) {
        ArrayList<SizeCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < captured.size(); i++) {
            if (usedCaptured.contains(i)) continue;
            OriginalTitleAccessibilityService.CapturedItem item = captured.get(i);
            if (item == null || item.sizeBytes <= 0 || cleanCapturedTitle(item.title).isEmpty()) continue;
            long binary = parseDisplayedSizeBinary(item.sizeText);
            for (DownloadEntry e : entries) {
                String resource = normalizeResource(e.resource);
                if (resource.isEmpty() || usedResources.contains(resource) || e.sizeBytes <= 0) continue;
                long diff = sizeDifference(e.sizeBytes, item.sizeBytes, binary);
                double ratio = diff / (double) Math.max(1L, Math.max(e.sizeBytes, Math.max(item.sizeBytes, binary)));
                // Com download_size vindo do app original, 3,5% já cobre arredondamento e pequenas diferenças.
                if (ratio <= 0.035d || diff <= 5_000_000L) {
                    candidates.add(new SizeCandidate(i, resource, e.sizeBytes, diff, ratio));
                }
            }
        }
        return applySizeCandidates(context, captured, candidates, byResource, usedResources, usedCaptured, true);
    }

    private static int matchCapturedToLocalMovies(Context context,
                                                   List<OriginalTitleAccessibilityService.CapturedItem> captured,
                                                   List<LocalMovieInfo> locals,
                                                   Map<String, String> byResource,
                                                   Set<String> usedResources,
                                                   Set<Integer> usedCaptured) {
        ArrayList<SizeCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < captured.size(); i++) {
            if (usedCaptured.contains(i)) continue;
            OriginalTitleAccessibilityService.CapturedItem item = captured.get(i);
            if (item == null || item.sizeBytes <= 0 || cleanCapturedTitle(item.title).isEmpty()) continue;
            long binary = parseDisplayedSizeBinary(item.sizeText);
            for (LocalMovieInfo local : locals) {
                if (usedResources.contains(local.resource)) continue;
                long localSize = local.exactSizeBytes > 0 ? local.exactSizeBytes : local.sizeBytes;
                if (localSize <= 0) continue;
                long diff = sizeDifference(localSize, item.sizeBytes, binary);
                long reference = Math.max(localSize, Math.max(item.sizeBytes, binary));
                double ratio = diff / (double) Math.max(1L, reference);
                // O index.m3u8 pode ter um total um pouco diferente do tamanho exibido.
                // No tamanho real dos arquivos a diferença normalmente cai bastante.
                if (ratio <= 0.065d || diff <= 7_000_000L) {
                    candidates.add(new SizeCandidate(i, local.resource, localSize, diff, ratio));
                }
            }
        }
        return applySizeCandidates(context, captured, candidates, byResource, usedResources, usedCaptured, false);
    }

    private static int applySizeCandidates(Context context,
                                            List<OriginalTitleAccessibilityService.CapturedItem> captured,
                                            List<SizeCandidate> candidates,
                                            Map<String, String> byResource,
                                            Set<String> usedResources,
                                            Set<Integer> usedCaptured,
                                            boolean exactSource) {
        Collections.sort(candidates, (a, b) -> {
            int r = Double.compare(a.ratio, b.ratio);
            if (r != 0) return r;
            return Long.compare(a.diff, b.diff);
        });

        int matched = 0;
        for (SizeCandidate c : candidates) {
            if (usedCaptured.contains(c.capturedIndex) || usedResources.contains(c.resource)) continue;

            // Confere se existe outro recurso quase empatado para o mesmo nome.
            // Com o download_size do app original podemos ser mais permissivos; no fallback
            // local exigimos uma separação maior para não colocar nome no vídeo errado.
            SizeCandidate second = null;
            for (SizeCandidate other : candidates) {
                if (other == c || other.capturedIndex != c.capturedIndex) continue;
                if (usedResources.contains(other.resource)) continue;
                if (second == null || other.ratio < second.ratio
                        || (other.ratio == second.ratio && other.diff < second.diff)) second = other;
            }
            if (second != null) {
                double gap = second.ratio - c.ratio;
                long diffGap = second.diff - c.diff;
                double requiredGap = exactSource ? 0.0015d : 0.0035d;
                long requiredBytes = exactSource ? 350_000L : 900_000L;
                if (gap < requiredGap && diffGap < requiredBytes) continue;
            }

            OriginalTitleAccessibilityService.CapturedItem item = captured.get(c.capturedIndex);
            String title = cleanCapturedTitle(item.title);
            if (title.isEmpty()) continue;
            byResource.put(c.resource, title);
            if (item.coverPath != null && !item.coverPath.trim().isEmpty()) {
                saveCoverForResource(context, c.resource, item.coverPath);
            }
            usedCaptured.add(c.capturedIndex);
            usedResources.add(c.resource);
            matched++;
        }
        return matched;
    }


    /**
     * Resolve conjuntos pequenos (até 4 itens) usando o erro TOTAL da combinação.
     * Isso elimina o caso em que dois arquivos quase do mesmo tamanho ficam
     * eternamente pendentes porque cada um parece ambíguo quando analisado sozinho.
     */
    private static int matchSmallRemainingSetGlobally(Context context,
                                                       List<OriginalTitleAccessibilityService.CapturedItem> captured,
                                                       List<LocalMovieInfo> locals,
                                                       Map<String, String> byResource,
                                                       Set<String> usedResources,
                                                       Set<Integer> usedCaptured) {
        ArrayList<LocalMovieInfo> leftLocals = new ArrayList<>();
        ArrayList<Integer> leftCaptured = new ArrayList<>();

        for (LocalMovieInfo local : locals) {
            if (!usedResources.contains(local.resource)) {
                if (local.exactSizeBytes <= 0) {
                    long exact = readActualStoredDownloadSize(context, local.movie, local.resource);
                    if (exact > 0) local.exactSizeBytes = exact;
                }
                leftLocals.add(local);
            }
        }
        for (int i = 0; i < captured.size(); i++) {
            if (usedCaptured.contains(i)) continue;
            OriginalTitleAccessibilityService.CapturedItem item = captured.get(i);
            if (item == null || cleanCapturedTitle(item.title).isEmpty()) continue;
            if (item.sizeBytes <= 0 && parseDisplayedSizeBinary(item.sizeText) <= 0) continue;
            leftCaptured.add(i);
        }

        int n = leftLocals.size();
        if (n <= 0 || n > 4 || leftCaptured.size() != n) return 0;

        AssignmentSearch search = new AssignmentSearch(n);
        boolean[] used = new boolean[n];
        int[] current = new int[n];
        searchAssignments(0, current, used, leftLocals, leftCaptured, captured, search);

        if (search.bestPermutation == null || Double.isInfinite(search.bestCost)) return 0;

        double avg = search.bestCost / n;
        boolean clearWinner = search.secondBestCost == Double.POSITIVE_INFINITY
                || (search.secondBestCost - search.bestCost) >= 0.008d
                || search.bestCost <= search.secondBestCost * 0.82d;
        if (search.bestMaxRatio > 0.30d || avg > 0.18d || !clearWinner) return 0;

        int resolved = 0;
        for (int localIndex = 0; localIndex < n; localIndex++) {
            int capturedSlot = search.bestPermutation[localIndex];
            int capturedIndex = leftCaptured.get(capturedSlot);
            OriginalTitleAccessibilityService.CapturedItem item = captured.get(capturedIndex);
            LocalMovieInfo local = leftLocals.get(localIndex);
            String title = cleanCapturedTitle(item.title);
            if (title.isEmpty()) continue;
            byResource.put(local.resource, title);
            if (item.coverPath != null && !item.coverPath.trim().isEmpty()) {
                saveCoverForResource(context, local.resource, item.coverPath);
            }
            usedResources.add(local.resource);
            usedCaptured.add(capturedIndex);
            resolved++;
        }
        return resolved;
    }

    private static void searchAssignments(int depth, int[] current, boolean[] used,
                                          List<LocalMovieInfo> locals,
                                          List<Integer> capturedIndexes,
                                          List<OriginalTitleAccessibilityService.CapturedItem> captured,
                                          AssignmentSearch search) {
        int n = locals.size();
        if (depth >= n) {
            double cost = 0d;
            double maxRatio = 0d;
            for (int li = 0; li < n; li++) {
                LocalMovieInfo local = locals.get(li);
                OriginalTitleAccessibilityService.CapturedItem item = captured.get(capturedIndexes.get(current[li]));
                long localSize = local.exactSizeBytes > 0 ? local.exactSizeBytes : local.sizeBytes;
                long binary = parseDisplayedSizeBinary(item.sizeText);
                long diff = sizeDifference(localSize, item.sizeBytes, binary);
                if (localSize <= 0 || diff == Long.MAX_VALUE) return;
                long reference = Math.max(localSize, Math.max(item.sizeBytes, binary));
                double ratio = diff / (double) Math.max(1L, reference);
                cost += ratio;
                maxRatio = Math.max(maxRatio, ratio);
            }
            if (cost < search.bestCost) {
                search.secondBestCost = search.bestCost;
                search.bestCost = cost;
                search.bestMaxRatio = maxRatio;
                search.bestPermutation = current.clone();
            } else if (cost < search.secondBestCost) {
                search.secondBestCost = cost;
            }
            return;
        }

        for (int i = 0; i < n; i++) {
            if (used[i]) continue;
            used[i] = true;
            current[depth] = i;
            searchAssignments(depth + 1, current, used, locals, capturedIndexes, captured, search);
            used[i] = false;
        }
    }

    private static final class AssignmentSearch {
        final int n;
        double bestCost = Double.POSITIVE_INFINITY;
        double secondBestCost = Double.POSITIVE_INFINITY;
        double bestMaxRatio = Double.POSITIVE_INFINITY;
        int[] bestPermutation;
        AssignmentSearch(int n) { this.n = n; }
    }

    private static void savePendingResolution(Context context,
                                              List<OriginalTitleAccessibilityService.CapturedItem> captured,
                                              List<LocalMovieInfo> locals,
                                              Set<String> usedResources,
                                              Set<Integer> usedCaptured) {
        JSONArray localArr = new JSONArray();
        JSONArray capturedArr = new JSONArray();

        try {
            for (LocalMovieInfo local : locals) {
                if (usedResources.contains(local.resource)) continue;
                JSONObject o = new JSONObject();
                o.put("resource", local.resource);
                o.put("currentTitle", local.movie == null ? "" : local.movie.title);
                o.put("durationMs", local.movie == null ? 0L : local.movie.durationMs);
                long localSize = local.exactSizeBytes > 0 ? local.exactSizeBytes : local.sizeBytes;
                o.put("sizeBytes", localSize);
                localArr.put(o);
            }

            for (int i = 0; i < captured.size(); i++) {
                if (usedCaptured.contains(i)) continue;
                OriginalTitleAccessibilityService.CapturedItem item = captured.get(i);
                if (item == null || cleanCapturedTitle(item.title).isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("title", cleanCapturedTitle(item.title));
                o.put("sizeBytes", item.sizeBytes);
                o.put("sizeText", item.sizeText == null ? "" : item.sizeText);
                o.put("coverPath", item.coverPath == null ? "" : item.coverPath);
                capturedArr.put(o);
            }
        } catch (Exception ignored) {}

        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_LOCALS, localArr.toString())
                .putString(KEY_PENDING_CAPTURED, capturedArr.toString())
                .apply();
    }

    public static List<PendingLocalItem> getPendingLocalItems(Context context) {
        ArrayList<PendingLocalItem> out = new ArrayList<>();
        String raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_LOCALS, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String resource = normalizeResource(o.optString("resource", ""));
                if (resource.isEmpty()) continue;
                out.add(new PendingLocalItem(
                        resource,
                        o.optString("currentTitle", ""),
                        o.optLong("durationMs", 0L),
                        o.optLong("sizeBytes", 0L)
                ));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static List<PendingCapturedItem> getPendingCapturedItems(Context context) {
        ArrayList<PendingCapturedItem> out = new ArrayList<>();
        String raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_CAPTURED, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = cleanCapturedTitle(o.optString("title", ""));
                if (title.isEmpty()) continue;
                out.add(new PendingCapturedItem(
                        title,
                        o.optLong("sizeBytes", 0L),
                        o.optString("sizeText", ""),
                        o.optString("coverPath", "")
                ));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static boolean saveManualPendingMapping(Context context, MovieRepository repo,
                                                   String resource, PendingCapturedItem item) {
        String key = normalizeResource(resource);
        if (key.isEmpty() || item == null) return false;
        String title = cleanCapturedTitle(item.title);
        if (title.isEmpty()) return false;

        Map<String, String> one = new HashMap<>();
        one.put(key, title);
        saveMappings(context, one);
        if (item.coverPath != null && !item.coverPath.trim().isEmpty()) {
            saveCoverForResource(context, key, item.coverPath);
        }
        applySavedMappings(context, repo);
        return true;
    }

    public static void clearPendingResolution(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .remove(KEY_PENDING_LOCALS)
                .remove(KEY_PENDING_CAPTURED)
                .apply();
    }

    public static final class PendingLocalItem {
        public final String resource;
        public final String currentTitle;
        public final long durationMs;
        public final long sizeBytes;
        PendingLocalItem(String resource, String currentTitle, long durationMs, long sizeBytes) {
            this.resource = resource == null ? "" : resource;
            this.currentTitle = currentTitle == null ? "" : currentTitle;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
        }
    }

    public static final class PendingCapturedItem {
        public final String title;
        public final long sizeBytes;
        public final String sizeText;
        public final String coverPath;
        PendingCapturedItem(String title, long sizeBytes, String sizeText, String coverPath) {
            this.title = title == null ? "" : title;
            this.sizeBytes = sizeBytes;
            this.sizeText = sizeText == null ? "" : sizeText;
            this.coverPath = coverPath == null ? "" : coverPath;
        }
    }

    private static long sizeDifference(long target, long decimalShown, long binaryShown) {
        if (target <= 0) return Long.MAX_VALUE;
        long best = Long.MAX_VALUE;
        if (decimalShown > 0) best = Math.min(best, Math.abs(target - decimalShown));
        if (binaryShown > 0) best = Math.min(best, Math.abs(target - binaryShown));
        return best;
    }

    private static long readActualStoredDownloadSize(Context context, Movie movie, String resource) {
        if (context == null || movie == null || resource == null || resource.isEmpty()) return 0L;
        String source = movie.sourceUri == null ? "" : movie.sourceUri.trim();
        if (source.isEmpty()) return 0L;
        android.database.Cursor cursor = null;
        try {
            Uri tree = Uri.parse(source);
            String treeDocId = DocumentsContract.getTreeDocumentId(tree);
            if (treeDocId == null || treeDocId.isEmpty()) return 0L;
            String folderDocId = treeDocId;
            String decoded = Uri.decode(treeDocId);
            if (!decoded.toUpperCase(Locale.ROOT).endsWith(resource.toUpperCase(Locale.ROOT))) {
                folderDocId = treeDocId + "/" + resource;
            }
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderDocId);
            String[] projection = new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE
            };
            cursor = context.getContentResolver().query(children, projection, null, null, null);
            if (cursor == null) return 0L;
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            long total = 0L;
            while (cursor.moveToNext()) {
                String name = nameCol >= 0 ? cursor.getString(nameCol) : "";
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".dat") && !lower.endsWith(".ts")) continue;
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) total += Math.max(0L, cursor.getLong(sizeCol));
            }
            return total;
        } catch (Exception ignored) {
            return 0L;
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Exception ignored) {}
        }
    }

    private static final class SizeCandidate {
        final int capturedIndex;
        final String resource;
        final long targetSize;
        final long diff;
        final double ratio;
        SizeCandidate(int capturedIndex, String resource, long targetSize, long diff, double ratio) {
            this.capturedIndex = capturedIndex;
            this.resource = resource;
            this.targetSize = targetSize;
            this.diff = diff;
            this.ratio = ratio;
        }
    }

    private static IdentificationResult applyMappingsToLibrary(Context context, MovieRepository repo, Map<String, String> byResource,
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
            boolean coverApplied = applySavedCover(context, movie, resource);
            if (!title.equals(movie.title)) {
                movie.title = title;
                repo.save(movie);
                renamed++;
            } else {
                if (coverApplied) repo.save(movie);
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

    private static long readOriginalDownloadSize(Context context, Movie movie, String resource) {
        if (context == null || movie == null || resource == null || resource.isEmpty()) return 0L;
        String source = movie.sourceUri == null ? "" : movie.sourceUri.trim();
        if (source.isEmpty()) return 0L;

        try {
            Uri tree = Uri.parse(source);
            String treeDocId = DocumentsContract.getTreeDocumentId(tree);
            if (treeDocId == null || treeDocId.isEmpty()) return 0L;

            String folderDocId = treeDocId;
            String decoded = Uri.decode(treeDocId);
            if (!decoded.toUpperCase(Locale.ROOT).endsWith(resource.toUpperCase(Locale.ROOT))) {
                folderDocId = treeDocId + "/" + resource;
            }

            Uri playlistUri = DocumentsContract.buildDocumentUriUsingTree(tree, folderDocId + "/index.m3u8");
            try (InputStream in = context.getContentResolver().openInputStream(playlistUri)) {
                if (in == null) return 0L;
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                long total = 0L;
                Pattern p = Pattern.compile("(?:[?&])sz=(\\d+)", Pattern.CASE_INSENSITIVE);
                while ((line = br.readLine()) != null) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        try { total += Long.parseLong(m.group(1)); } catch (Exception ignored) {}
                    }
                }
                return total;
            }
        } catch (Exception ignored) {
            return 0L;
        }
    }


    private static long parseDisplayedSizeBinary(String text) {
        if (text == null) return 0L;
        Matcher m = Pattern.compile("(?i)(\\d{1,4}(?:[.,]\\d{1,3})?)\\s*(KB|MB|GB|TB)").matcher(text);
        if (!m.find()) return 0L;
        try {
            double n = Double.parseDouble(m.group(1).replace(',', '.'));
            String u = m.group(2).toUpperCase(Locale.ROOT);
            double mul = 1d;
            if ("KB".equals(u)) mul = 1024d;
            else if ("MB".equals(u)) mul = 1024d * 1024d;
            else if ("GB".equals(u)) mul = 1024d * 1024d * 1024d;
            else if ("TB".equals(u)) mul = 1024d * 1024d * 1024d * 1024d;
            return Math.round(n * mul);
        } catch (Exception e) {
            return 0L;
        }
    }
    private static void autoOrganizeRecognizedSeries(Context context, MovieRepository repo) {
        if (context == null || repo == null) return;
        Pattern p = Pattern.compile("(?iu)^(.+?)\\s*[–—-]\\s*Temporada\\s*(\\d{1,3})\\s+(?:Epis[oó]dio\\s*)?(\\d{1,4})\\s*$");
        SeriesRepository seriesRepo = new SeriesRepository(context);
        Map<String, SeriesRepository.SeriesInfo> existing = new HashMap<>();
        for (SeriesRepository.SeriesInfo s : seriesRepo.getAllSeries()) {
            existing.put(s.name.trim().toLowerCase(Locale.ROOT), s);
        }

        for (Movie movie : repo.getAll()) {
            String title = movie.title == null ? "" : movie.title.trim();
            Matcher m = p.matcher(title);
            if (!m.matches()) continue;
            String seriesName = m.group(1).trim();
            int season;
            int episode;
            try {
                season = Integer.parseInt(m.group(2));
                episode = Integer.parseInt(m.group(3));
            } catch (Exception e) {
                continue;
            }
            String key = seriesName.toLowerCase(Locale.ROOT);
            SeriesRepository.SeriesInfo info = existing.get(key);
            if (info == null) {
                info = seriesRepo.createSeries(seriesName);
                existing.put(key, info);
            }
            seriesRepo.assign(movie.id, info.id, season, episode);
        }
    }

    private static final class LocalMovieInfo {
        final Movie movie;
        final String resource;
        final long sizeBytes;
        long exactSizeBytes;
        LocalMovieInfo(Movie movie, String resource, long sizeBytes) {
            this.movie = movie;
            this.resource = resource;
            this.sizeBytes = sizeBytes;
            this.exactSizeBytes = 0L;
        }
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
        return s.contains("resource") && (s.contains("download_time") || s.contains("download_percent"));
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
                String resource = o.optString("resource", o.optString("streamid", o.optString("streamId", "")));
                resource = normalizeResource(resource);
                if (resource.isEmpty()) continue;
                DownloadEntry e = new DownloadEntry();
                e.resource = resource;
                e.downloadTime = o.optString("download_time", o.optString("downloadTime", ""));
                if (o.has("download_percent")) {
                    e.percentKnown = true;
                    e.percent = o.optInt("download_percent", 0);
                } else if (o.has("downloadPercent")) {
                    e.percentKnown = true;
                    e.percent = o.optInt("downloadPercent", 0);
                }

                Object sizeValue = o.has("download_size") ? o.opt("download_size")
                        : (o.has("downloadSize") ? o.opt("downloadSize")
                        : (o.has("total_size") ? o.opt("total_size")
                        : (o.has("totalSize") ? o.opt("totalSize") : o.opt("size"))));
                e.sizeBytes = parseServerSize(sizeValue);

                String title = firstNonEmpty(
                        o.optString("complete_name", ""),
                        o.optString("completeName", ""),
                        o.optString("name", ""),
                        o.optString("title", "")
                );
                e.title = cleanCapturedTitle(title);
                out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private static long parseServerSize(Object value) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) {
            long n = ((Number) value).longValue();
            return Math.max(0L, n);
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return 0L;
        Matcher withUnit = Pattern.compile("(?i)(\\d{1,12}(?:[.,]\\d{1,4})?)\\s*(B|KB|MB|GB|TB)").matcher(s);
        if (withUnit.find()) {
            try {
                double n = Double.parseDouble(withUnit.group(1).replace(',', '.'));
                String u = withUnit.group(2).toUpperCase(Locale.ROOT);
                double mul = 1d;
                if ("KB".equals(u)) mul = 1_000d;
                else if ("MB".equals(u)) mul = 1_000_000d;
                else if ("GB".equals(u)) mul = 1_000_000_000d;
                else if ("TB".equals(u)) mul = 1_000_000_000_000d;
                return Math.max(0L, Math.round(n * mul));
            } catch (Exception ignored) {}
        }
        String digits = s.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            try { return Math.max(0L, Long.parseLong(digits)); } catch (Exception ignored) {}
        }
        return 0L;
    }

    private static JSONArray findArray(Object root) {
        if (root instanceof JSONArray) return (JSONArray) root;
        if (!(root instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) root;
        String[] keys = {"data", "list", "result", "rows", "downloads"};
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
        String downloadTime = "";
        String title = "";
        long sizeBytes = 0L;
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
