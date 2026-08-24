package com.offlineplayer.cineoffline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identificação online dos downloads produzidos pelo app original.
 *
 * A ideia é deliberadamente conservadora: o Cine Offline NÃO adivinha o nome pelo
 * número/ordem do arquivo. Ele só troca um título quando consegue provar a associação
 * por um identificador hexadecimal de 32 caracteres ou pelo MD5 de uma URL devolvida
 * pelo catálogo do servidor original.
 *
 * Os nomes confirmados são salvos no MovieRepository e em um cache local. Depois disso
 * eles continuam disponíveis totalmente offline.
 */
public final class InternetTitleIdentifier {
    private static final String PREF = "cine_online_title_lookup";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_BASE = "base_url";
    private static final String KEY_CACHE = "title_cache";

    private static final String DEFAULT_BASE = "https://filmbr.i2s1n.com";
    private static final String APP_ID = "filmbr";
    private static final String ORIGINAL_PACKAGE = "com.starshort.minishort";
    private static final String ORIGINAL_VERSION = "40000";
    private static final String SIGN_SECRET = "47Q8tBqO4YqrMHf4";

    private static final Pattern HASH32 = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])");
    private static final Pattern RESOURCE_IN_URL = Pattern.compile("(?i)(?:resource|streamid|stream_id|hash|md5)=([0-9a-f]{32})");
    private static final Pattern HTTP_URL = Pattern.compile("https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);

    private static final int CONNECT_TIMEOUT = 12000;
    private static final int READ_TIMEOUT = 18000;
    private static final int MAX_TOTAL_PAGES = 180;
    private static final int MAX_PAGES_PER_TYPE = 45;

    private InternetTitleIdentifier() {}

    public interface ProgressListener {
        void onProgress(String text);
    }

    public static final class Result {
        public boolean ok;
        public String error = "";
        public String warning = "";
        public String diagnostic = "";
        public int libraryItems;
        public int idsFound;
        public int renamed;
        public int matched;
        public int notMatched;
        public int cachedMatches;
        public int networkMatches;
        public int requests;
        public int pages;
        public int catalogObjects;
    }

    private static final class Match {
        final String title;
        final String source;
        Match(String title, String source) {
            this.title = title;
            this.source = source;
        }
    }

    private static final class Session {
        final Context context;
        final SharedPreferences prefs;
        String base;
        String deviceId;
        String token;
        int requests;
        String lastServerMessage = "";

        Session(Context context) {
            this.context = context.getApplicationContext();
            this.prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            this.base = cleanBase(prefs.getString(KEY_BASE, DEFAULT_BASE));
            this.deviceId = prefs.getString(KEY_DEVICE, "");
            this.token = prefs.getString(KEY_TOKEN, "");
            if (deviceId == null || deviceId.length() < 16) {
                deviceId = randomDeviceId();
                prefs.edit().putString(KEY_DEVICE, deviceId).apply();
            }
        }

        boolean init() {
            // O app original faz /api/public/init antes das demais chamadas e recebe
            // um token anônimo. Tentamos primeiro como instalação nova e depois como
            // instalação já existente, pois o servidor pode variar entre versões.
            JSONObject root = null;
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("invited_by", "");
                form.put("is_install", "1");
                root = post("/api/public/init", form, false);
                if (!isOk(root)) {
                    form.put("is_install", "0");
                    root = post("/api/public/init", form, false);
                }
            } catch (Exception ignored) {}

            if (root == null) return false;
            lastServerMessage = root.optString("message", "");
            JSONObject result = root.optJSONObject("result");
            if (result == null) return isOk(root);

            JSONObject user = result.optJSONObject("user_info");
            if (user != null) {
                String newToken = user.optString("token", "").trim();
                if (!newToken.isEmpty()) {
                    token = newToken;
                    prefs.edit().putString(KEY_TOKEN, token).apply();
                }
            }

            JSONObject conf = result.optJSONObject("sys_conf");
            if (conf != null) {
                String api2 = conf.optString("api_url2", "").trim();
                if (api2.startsWith("http://") || api2.startsWith("https://")) {
                    base = cleanBase(api2);
                    prefs.edit().putString(KEY_BASE, base).apply();
                }
            }
            return isOk(root);
        }

        JSONObject post(String path, Map<String, String> form, boolean requireToken) throws Exception {
            String currentBase = cleanBase(base);
            URL u = new URL(currentBase + path);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setRequestMethod("POST");
            c.setDoInput(true);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL + ")");

            String curTime = String.valueOf(System.currentTimeMillis());
            String sign = md5Upper(SIGN_SECRET + deviceId + curTime);
            String androidId = "";
            try {
                androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            } catch (Exception ignored) {}
            if (androidId == null) androidId = "";

            // Cabeçalhos reconstruídos do app que criou os downloads.
            c.setRequestProperty("app_id", APP_ID);
            c.setRequestProperty("package_name", ORIGINAL_PACKAGE);
            c.setRequestProperty("version", ORIGINAL_VERSION);
            c.setRequestProperty("sys_platform", "2");
            c.setRequestProperty("mob_mfr", safe(Build.MANUFACTURER).toLowerCase(Locale.ROOT));
            c.setRequestProperty("mobmodel", safe(Build.MODEL));
            c.setRequestProperty("sysrelease", safe(Build.VERSION.RELEASE));
            c.setRequestProperty("device_id", deviceId);
            c.setRequestProperty("gaid", "");
            c.setRequestProperty("channel_code", "");
            c.setRequestProperty("androidid", androidId);
            c.setRequestProperty("cur_time", curTime);
            c.setRequestProperty("token", token == null ? "" : token);
            c.setRequestProperty("sign", sign);
            c.setRequestProperty("is_vvv", "0");
            c.setRequestProperty("is_language", "pt");
            c.setRequestProperty("is_display", "1");
            c.setRequestProperty("app_language", "pt");
            c.setRequestProperty("en_al", "0");

            String body = encodeForm(form);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
                out.write(bytes);
                out.flush();
            }

            requests++;
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = readText(in);
            c.disconnect();
            if (text == null || text.trim().isEmpty()) {
                throw new Exception("Servidor respondeu sem conteúdo (HTTP " + code + ").");
            }
            JSONObject root = new JSONObject(text);
            lastServerMessage = root.optString("message", "");

            // Se o token anônimo expirou, renova uma única vez e repete.
            if (requireToken && (code == 401 || root.optInt("code", 0) == 401)) {
                token = "";
                prefs.edit().remove(KEY_TOKEN).apply();
                if (init()) return post(path, form, false);
            }
            return root;
        }
    }

    public static Result identify(Context context, MovieRepository repo, ProgressListener listener) {
        Result out = new Result();
        List<Movie> movies = repo.getAll();
        out.libraryItems = movies.size();
        if (movies.isEmpty()) {
            out.error = "A biblioteca está vazia.";
            return out;
        }

        progress(listener, "Lendo os códigos dos arquivos…");
        Map<String, Movie> byHash = new LinkedHashMap<>();
        for (Movie movie : movies) {
            String hash = findResourceId(movie);
            if (hash != null) byHash.put(hash, movie);
        }
        out.idsFound = byHash.size();
        if (byHash.isEmpty()) {
            out.error = "Não encontrei os códigos de 32 caracteres nas playlists importadas. Importe novamente a pasta Filmes usando o modo rápido e tente de novo.";
            return out;
        }

        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Map<String, String> cache = loadCache(prefs);
        Map<String, Match> matches = new LinkedHashMap<>();
        for (Map.Entry<String, Movie> e : byHash.entrySet()) {
            String cached = cleanTitle(cache.get(e.getKey()));
            if (!cached.isEmpty()) matches.put(e.getKey(), new Match(cached, "cache"));
        }
        out.cachedMatches = matches.size();
        if (matches.size() == byHash.size()) {
            applyMatches(repo, byHash, matches, out);
            out.ok = true;
            out.matched = matches.size();
            out.notMatched = 0;
            out.diagnostic = "Todos os títulos já estavam no cache offline.";
            return out;
        }

        progress(listener, "Conectando ao catálogo original…");
        Session session = new Session(context);
        boolean initOk = session.init();

        Set<Integer> typeIds = new LinkedHashSet<>();
        typeIds.add(0); // algumas versões aceitam 0 como “todos”
        try {
            JSONObject types = session.post("/api/type/get_list", Collections.emptyMap(), true);
            collectTypeIds(types == null ? null : types.opt("result"), typeIds, 0);
        } catch (Exception ignored) {}
        // Fallback conservador caso a lista de tipos não venha na resposta.
        if (typeIds.size() <= 1) {
            for (int i = 1; i <= 16; i++) typeIds.add(i);
        }
        while (typeIds.size() > 36) {
            Integer last = null;
            for (Integer x : typeIds) last = x;
            if (last == null) break;
            typeIds.remove(last);
        }

        int totalPages = 0;
        int scannedObjects = 0;
        Set<String> pageFingerprints = new HashSet<>();
        int typeIndex = 0;

        outer:
        for (Integer typeId : typeIds) {
            typeIndex++;
            int emptyInRow = 0;
            for (int page = 1; page <= MAX_PAGES_PER_TYPE && totalPages < MAX_TOTAL_PAGES; page++) {
                if (matches.size() >= byHash.size()) break outer;
                progress(listener, "🔎 Catálogo " + typeIndex + "/" + typeIds.size() + " • página " + page + " • " + matches.size() + "/" + byHash.size() + " encontrados");

                Map<String, String> form = new LinkedHashMap<>();
                form.put("type_id", String.valueOf(typeId));
                form.put("type", "0");
                form.put("area", "");
                form.put("audio_lang", "");
                form.put("year", "");
                form.put("sort", "");
                form.put("pn", String.valueOf(page));

                JSONObject root;
                try {
                    root = session.post("/api/search/screen", form, true);
                } catch (Exception e) {
                    // type_id=0 pode não ser aceito. Nos demais tipos, um erro de uma
                    // categoria não deve interromper a identificação inteira.
                    if (typeId == 0 && page == 1) break;
                    emptyInRow++;
                    if (emptyInRow >= 2) break;
                    continue;
                }
                totalPages++;
                Object result = root == null ? null : root.opt("result");
                int count = countCatalogObjects(result);
                scannedObjects += count;
                if (count <= 0) {
                    emptyInRow++;
                    if (emptyInRow >= 2) break;
                    continue;
                }
                emptyInRow = 0;

                // Evita loop caso o backend ignore pn e devolva a mesma página sempre.
                String fp = typeId + ":" + md5Upper(String.valueOf(result));
                if (!pageFingerprints.add(fp)) break;

                scanNode(result, "", byHash.keySet(), matches, 0);
                if (matches.size() >= byHash.size()) break outer;
            }
        }

        out.requests = session.requests;
        out.pages = totalPages;
        out.catalogObjects = scannedObjects;
        out.networkMatches = Math.max(0, matches.size() - out.cachedMatches);
        out.matched = matches.size();
        out.notMatched = Math.max(0, byHash.size() - matches.size());

        if (!matches.isEmpty()) {
            for (Map.Entry<String, Match> e : matches.entrySet()) {
                cache.put(e.getKey(), e.getValue().title);
            }
            saveCache(prefs, cache);
            applyMatches(repo, byHash, matches, out);
        }

        out.ok = true;
        StringBuilder diag = new StringBuilder();
        diag.append("Servidor inicializado: ").append(initOk ? "sim" : "parcial");
        diag.append("\nRequisições: ").append(out.requests);
        diag.append("\nPáginas analisadas: ").append(out.pages);
        diag.append("\nObjetos do catálogo analisados: ").append(out.catalogObjects);
        diag.append("\nCódigos locais: ").append(out.idsFound);
        diag.append("\nCorrespondências confirmadas: ").append(out.matched);
        out.diagnostic = diag.toString();

        if (out.matched == 0) {
            out.warning = "A conexão foi tentada, mas o servidor não devolveu uma associação confirmável entre os códigos das pastas e os títulos. Nenhum nome foi inventado nem trocado por ordem.";
            if (session.lastServerMessage != null && !session.lastServerMessage.trim().isEmpty()) {
                out.warning += "\n\nResposta do servidor: " + session.lastServerMessage.trim();
            }
        } else if (out.notMatched > 0) {
            out.warning = out.notMatched + " item(ns) ficaram sem nome porque não houve correspondência exata. Os demais foram salvos e continuarão com o nome mesmo offline.";
        }
        return out;
    }

    private static void applyMatches(MovieRepository repo, Map<String, Movie> byHash,
                                     Map<String, Match> matches, Result out) {
        List<Movie> changed = new ArrayList<>();
        for (Map.Entry<String, Match> e : matches.entrySet()) {
            Movie movie = byHash.get(e.getKey());
            if (movie == null) continue;
            String title = cleanTitle(e.getValue().title);
            if (title.isEmpty()) continue;
            if (!title.equals(movie.title)) {
                movie.title = title;
                changed.add(movie);
            }
        }
        if (!changed.isEmpty()) repo.saveAll(changed);
        out.renamed = changed.size();
    }

    /** Lê o código da própria playlist já importada, sem voltar a listar milhares de .dat. */
    private static String findResourceId(Movie movie) {
        if (movie == null) return null;
        // 1) playlist privada: no modo linked os Content URIs mantêm o nome da pasta/hash.
        String h = findHashInFile(movie.playlistPath);
        if (h != null) return h;
        // 2) fallbacks úteis para versões anteriores.
        h = firstHash(movie.sourceUri);
        if (h != null) return h;
        h = firstHash(movie.folderPath);
        if (h != null) return h;
        h = firstHash(movie.title);
        return h;
    }

    private static String findHashInFile(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        File f = new File(path);
        if (!f.exists() || !f.isFile()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines++ < 80) {
                String h = firstHash(line);
                if (h != null) return h;
                try {
                    String decoded = URLDecoder.decode(line, "UTF-8");
                    h = firstHash(decoded);
                    if (h != null) return h;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String firstHash(String s) {
        if (s == null) return null;
        Matcher m = HASH32.matcher(s);
        return m.find() ? m.group().toUpperCase(Locale.ROOT) : null;
    }

    private static void scanNode(Object node, String inheritedTitle, Set<String> targets,
                                 Map<String, Match> matches, int depth) {
        if (node == null || depth > 9 || matches.size() >= targets.size()) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                scanNode(a.opt(i), inheritedTitle, targets, matches, depth + 1);
                if (matches.size() >= targets.size()) return;
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            if (node instanceof String) matchString((String) node, inheritedTitle, targets, matches);
            return;
        }

        JSONObject o = (JSONObject) node;
        String own = preferredTitle(o);
        String title = combineTitle(inheritedTitle, own, o);
        if (title.isEmpty()) title = inheritedTitle;

        // Primeiro tenta identificadores explicitamente devolvidos pelo servidor.
        String[] idKeys = {"resource", "streamid", "stream_id", "resource_id", "hash", "md5"};
        for (String k : idKeys) {
            String h = firstHash(o.optString(k, ""));
            if (h != null && targets.contains(h) && !matches.containsKey(h) && !title.isEmpty()) {
                matches.put(h, new Match(title, "id:" + k));
            }
        }

        // Em seguida tenta URLs/campos de mídia. O servidor local do app original gera um
        // resource de 32 hex para cada URL; nas versões observadas ele é compatível com MD5.
        JSONArray names = o.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                Object value = o.opt(key);
                if (value instanceof String) {
                    matchString((String) value, title, targets, matches);
                }
            }

            // Recorre depois para manter o título pai em episódios/coleções.
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                Object value = o.opt(key);
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    scanNode(value, title, targets, matches, depth + 1);
                }
            }
        }
    }

    private static void matchString(String value, String title, Set<String> targets,
                                    Map<String, Match> matches) {
        if (value == null || value.trim().isEmpty() || title == null || title.trim().isEmpty()) return;
        String v = value.trim();

        Matcher direct = RESOURCE_IN_URL.matcher(v);
        while (direct.find()) {
            String h = direct.group(1).toUpperCase(Locale.ROOT);
            if (targets.contains(h) && !matches.containsKey(h)) {
                matches.put(h, new Match(title, "resource-url"));
            }
        }

        // O próprio campo pode ser um hash puro.
        if (v.matches("(?i)[0-9a-f]{32}")) {
            String h = v.toUpperCase(Locale.ROOT);
            if (targets.contains(h) && !matches.containsKey(h)) {
                matches.put(h, new Match(title, "hash"));
            }
        }

        List<String> candidates = new ArrayList<>();
        if (looksLikeUrl(v)) candidates.add(v);
        Matcher urlMatcher = HTTP_URL.matcher(v);
        while (urlMatcher.find()) candidates.add(urlMatcher.group());
        if (v.contains("$")) {
            String[] p = v.split("\\$");
            for (String s : p) if (looksLikeUrl(s)) candidates.add(s.trim());
        }
        if (v.contains("#")) {
            String[] p = v.split("#");
            for (String s : p) if (looksLikeUrl(s)) candidates.add(s.trim());
        }

        for (String url : candidates) {
            for (String variant : urlVariants(url)) {
                String h = md5Upper(variant);
                if (targets.contains(h) && !matches.containsKey(h)) {
                    matches.put(h, new Match(title, "md5-url"));
                }
            }
        }
    }

    private static List<String> urlVariants(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null) return new ArrayList<>();
        String v = raw.trim().replace("\\/", "/");
        if (v.isEmpty()) return new ArrayList<>();
        out.add(v);
        try { out.add(URLDecoder.decode(v, "UTF-8")); } catch (Exception ignored) {}

        // O app original acrescenta &type=2 ao solicitar a inclusão do download.
        if (!v.contains("type=2")) {
            out.add(v + (v.contains("?") ? "&" : "?") + "type=2");
        }
        String noType = v.replaceAll("(?i)([?&])type=2(?:&)?", "$1")
                .replace("?&", "?").replace("&&", "&");
        if (noType.endsWith("?") || noType.endsWith("&")) noType = noType.substring(0, noType.length() - 1);
        out.add(noType);
        return new ArrayList<>(out);
    }

    private static boolean looksLikeUrl(String s) {
        if (s == null) return false;
        String x = s.trim().toLowerCase(Locale.ROOT);
        return x.startsWith("http://") || x.startsWith("https://");
    }

    private static String preferredTitle(JSONObject o) {
        if (o == null) return "";
        String[] keys = {"complete_name", "vod_name", "episode_name", "collection_new_title", "title", "name", "last_name", "remarks"};
        for (String k : keys) {
            String v = cleanTitle(o.optString(k, ""));
            if (!v.isEmpty() && !isTechnical(v)) return v;
        }
        return "";
    }

    private static String combineTitle(String parent, String own, JSONObject o) {
        parent = cleanTitle(parent);
        own = cleanTitle(own);
        if (parent.isEmpty()) return own;
        if (own.isEmpty() || own.equalsIgnoreCase(parent)) {
            String ep = episodeLabel(o);
            return ep.isEmpty() ? parent : parent + " • " + ep;
        }
        if (own.toLowerCase(Locale.ROOT).contains(parent.toLowerCase(Locale.ROOT))) return own;
        if (parent.toLowerCase(Locale.ROOT).contains(own.toLowerCase(Locale.ROOT))) return parent;
        // Só combina quando o objeto parece ser episódio/coleção. Caso contrário o próprio
        // objeto pode ser outro filme aninhado em uma lista de recomendações.
        if (looksLikeEpisodeObject(o)) return parent + " • " + own;
        return own;
    }

    private static boolean looksLikeEpisodeObject(JSONObject o) {
        if (o == null) return false;
        String[] keys = {"collection", "collection_id", "episode", "episode_id", "episode_no", "serial", "season", "orginal_url", "is_p2p"};
        for (String k : keys) if (o.has(k)) return true;
        return false;
    }

    private static String episodeLabel(JSONObject o) {
        if (o == null) return "";
        String[] keys = {"episode_name", "last_name", "title"};
        for (String k : keys) {
            String v = cleanTitle(o.optString(k, ""));
            if (!v.isEmpty()) return v;
        }
        int season = optPositiveInt(o, "season", "season_number", "temporada");
        int episode = optPositiveInt(o, "episode", "episode_no", "episode_number", "collection", "collection_id");
        if (season > 0 && episode > 0) return String.format(Locale.ROOT, "T%02dE%02d", season, episode);
        if (episode > 0) return "Episódio " + episode;
        return "";
    }

    private static int optPositiveInt(JSONObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k)) continue;
            try {
                int n = Integer.parseInt(String.valueOf(o.opt(k)).replaceAll("[^0-9]", ""));
                if (n > 0) return n;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static boolean isTechnical(String s) {
        if (s == null) return true;
        String x = s.trim();
        if (x.isEmpty()) return true;
        if (x.matches("(?i)[0-9a-f]{24,}")) return true;
        return x.equalsIgnoreCase("index") || x.equalsIgnoreCase("null");
    }

    private static int countCatalogObjects(Object node) {
        if (node instanceof JSONArray) return ((JSONArray) node).length();
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            // Algumas APIs embrulham a lista em data/list/rows/items.
            String[] keys = {"data", "list", "rows", "items", "result"};
            for (String k : keys) {
                Object v = o.opt(k);
                if (v instanceof JSONArray) return ((JSONArray) v).length();
            }
            return o.length() > 0 ? 1 : 0;
        }
        return 0;
    }

    private static void collectTypeIds(Object node, Set<Integer> out, int depth) {
        if (node == null || depth > 6 || out.size() >= 36) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectTypeIds(a.opt(i), out, depth + 1);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String[] keys = {"type_id", "id"};
            for (String k : keys) {
                if (o.has(k)) {
                    int n = o.optInt(k, -1);
                    if (n >= 0 && n <= 10000) out.add(n);
                }
            }
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) collectTypeIds(o.opt(names.optString(i)), out, depth + 1);
        }
    }

    private static boolean isOk(JSONObject root) {
        if (root == null) return false;
        int code = root.optInt("code", Integer.MIN_VALUE);
        return code == 10000 || code == 0 || code == 1 || code == 200 || root.optBoolean("success", false);
    }

    private static Map<String, String> loadCache(SharedPreferences prefs) {
        Map<String, String> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_CACHE, "{}"));
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i, "").toUpperCase(Locale.ROOT);
                String v = cleanTitle(o.optString(k, ""));
                if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void saveCache(SharedPreferences prefs, Map<String, String> cache) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : cache.entrySet()) o.put(e.getKey(), e.getValue());
        } catch (Exception ignored) {}
        prefs.edit().putString(KEY_CACHE, o.toString()).apply();
    }

    private static String encodeForm(Map<String, String> form) throws Exception {
        if (form == null || form.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static String readText(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String md5Upper(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : dig) sb.append(String.format(Locale.ROOT, "%02X", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String randomDeviceId() {
        final String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random r = new Random(System.nanoTime());
        StringBuilder sb = new StringBuilder(22);
        for (int i = 0; i < 22; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static String cleanBase(String s) {
        String x = s == null ? DEFAULT_BASE : s.trim();
        if (!x.startsWith("http://") && !x.startsWith("https://")) x = DEFAULT_BASE;
        while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
        return x;
    }

    private static String cleanTitle(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static void progress(ProgressListener l, String s) { if (l != null) l.onProgress(s); }
}
