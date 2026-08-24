package com.offlineplayer.cineoffline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identificação conservadora pelo próprio conteúdo do vídeo.
 *
 * O fluxo é:
 * 1) lê algumas partes do offline.m3u8;
 * 2) extrai frames em memória (não salva imagem);
 * 3) usa OCR local do ML Kit;
 * 4) procura apenas os melhores trechos de texto no Wikidata;
 * 5) só troca o nome se houver correspondência forte com um filme ou episódio.
 *
 * Para séries, um resultado que identifica somente o nome geral da série NÃO é aplicado
 * automaticamente a um episódio, pois isso faria vários episódios ficarem com o mesmo nome.
 */
public class ContentTitleIdentifier {
    public interface ProgressListener { void onProgress(String text); }

    public static class Result {
        public boolean ok;
        public String error = "";
        public String warning = "";
        public String diagnostic = "";
        public int libraryItems;
        public int analyzed;
        public int renamed;
        public int confirmedMovies;
        public int confirmedEpisodes;
        public int seriesOnly;
        public int noFrames;
        public int noUsefulText;
        public int notConfirmed;
        public int networkQueries;
        public int ocrFrames;
    }

    private static class CandidateStat {
        String raw;
        String normalized;
        double score;
        int frames;
        Set<Integer> seenFrames = new HashSet<>();
    }

    private static class Match {
        String label;
        String description;
        String kind; // film | episode | series | other
        double confidence;
    }

    private static final double[] SAMPLE_POSITIONS = {
            0.002, 0.006, 0.012, 0.022, 0.04, 0.07, 0.11, 0.17,
            0.28, 0.46, 0.63,
            0.84, 0.93, 0.972, 0.992
    };
    private static final int MAX_CANDIDATES_TO_LOOKUP = 5;
    private static final int MAX_WEB_PHRASES = 2;
    private static final int CONNECT_TIMEOUT = 7000;
    private static final int READ_TIMEOUT = 9000;

    private static final Set<String> CREDIT_WORDS = new HashSet<>();
    static {
        Collections.addAll(CREDIT_WORDS,
                "directed by", "director", "direcao", "direção", "diretor", "diretora",
                "produced by", "producer", "production", "producao", "produção", "produtor", "produtora",
                "written by", "writer", "screenplay", "roteiro", "roteirista",
                "starring", "casting", "cast", "elenco", "music by", "musica", "música",
                "executive producer", "executive producers", "based on", "baseado em", "adapted by",
                "presents", "presenta", "apresenta", "a film by", "um filme de",
                "netflix", "prime video", "amazon original", "disney+", "disney plus", "hbo", "max original",
                "paramount+", "paramount plus", "apple tv+", "apple tv plus", "copyright", "todos os direitos",
                "legendado", "dublado", "www.", "http", "instagram", "facebook", "youtube"
        );
    }

    public static Result identify(Context context, MovieRepository repo, ProgressListener listener) {
        Result out = new Result();
        List<Movie> all = repo.getAll();
        out.libraryItems = all.size();
        if (all.isEmpty()) {
            out.error = "A biblioteca está vazia.";
            return out;
        }

        List<Movie> pending = new ArrayList<>();
        for (Movie m : all) {
            if (needsIdentification(m.title)) pending.add(m);
        }
        if (pending.isEmpty()) {
            out.ok = true;
            out.warning = "Nenhum item com nome provisório foi encontrado.";
            out.diagnostic = "A análise só é feita em nomes como Filme 01, Filme offline etc.";
            return out;
        }

        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        List<Movie> changed = new ArrayList<>();

        try {
            for (int i = 0; i < pending.size(); i++) {
                Movie movie = pending.get(i);
                out.analyzed++;
                progress(listener, "🎞️ Analisando " + (i + 1) + "/" + pending.size() + " • " + safeCurrentName(movie.title));

                List<String> refs = readSegmentRefs(movie.playlistPath);
                if (refs.isEmpty()) {
                    out.noFrames++;
                    continue;
                }

                List<Integer> sampleIndexes = chooseSampleIndexes(refs.size());
                Map<String, CandidateStat> stats = new LinkedHashMap<>();
                int frameOrdinal = 0;
                int framesForMovie = 0;

                for (Integer idx : sampleIndexes) {
                    if (idx == null || idx < 0 || idx >= refs.size()) continue;
                    frameOrdinal++;
                    Bitmap frame = extractFrame(context, refs.get(idx));
                    if (frame == null) continue;
                    framesForMovie++;
                    out.ocrFrames++;
                    Bitmap scaled = scaleForOcr(frame);
                    if (scaled != frame) frame.recycle();
                    try {
                        Text text = Tasks.await(
                                recognizer.process(InputImage.fromBitmap(scaled, 0)),
                                9, TimeUnit.SECONDS
                        );
                        collectCandidates(text, scaled.getWidth(), scaled.getHeight(), frameOrdinal, stats);
                    } catch (Exception ignored) {
                    } finally {
                        scaled.recycle();
                    }

                    // Se um título grande apareceu em 2 frames, já temos uma pista muito forte.
                    if (hasRepeatedStrongCandidate(stats)) break;
                }

                if (framesForMovie == 0) {
                    out.noFrames++;
                    continue;
                }

                List<CandidateStat> candidates = rankCandidates(stats);
                if (candidates.isEmpty()) {
                    out.noUsefulText++;
                    continue;
                }

                Match best = null;
                boolean sawSeriesOnly = false;
                int lookups = 0;
                for (CandidateStat c : candidates) {
                    if (lookups >= MAX_CANDIDATES_TO_LOOKUP) break;
                    lookups++;
                    progress(listener, "🔎 " + (i + 1) + "/" + pending.size() + " • verificando “" + shortText(c.raw, 34) + "”");
                    Match m = lookupWikidata(c.raw);
                    out.networkQueries++;
                    if (m == null) continue;

                    // Combina a confiança do catálogo com a força visual do OCR.
                    double visual = Math.min(1.0, (c.score / 9.0) + Math.min(0.22, c.frames * 0.07));
                    m.confidence = Math.min(1.0, m.confidence * 0.78 + visual * 0.22);

                    if ("series".equals(m.kind)) {
                        sawSeriesOnly = true;
                        continue;
                    }
                    if (!("film".equals(m.kind) || "episode".equals(m.kind))) continue;
                    if (m.confidence < 0.86) continue;
                    if (best == null || m.confidence > best.confidence) best = m;
                }

                if (best == null) {
                    Match web = lookupByWebSearch(
                            candidates,
                            movie.playlistPath,
                            out,
                            listener,
                            i + 1,
                            pending.size()
                    );
                    if (web != null) best = web;
                }

                if (best != null) {
                    String newTitle = cleanDisplayTitle(best.label);
                    if (!newTitle.isEmpty() && !newTitle.equalsIgnoreCase(movie.title)) {
                        movie.title = newTitle;
                        changed.add(movie);
                        out.renamed++;
                        if ("episode".equals(best.kind)) out.confirmedEpisodes++;
                        else out.confirmedMovies++;
                    }
                } else if (sawSeriesOnly) {
                    // Não troca “Filme 12” apenas por “Breaking Bad”, por exemplo. Isso perderia
                    // a distinção entre episódios. Conta para o diagnóstico e mantém o nome atual.
                    out.seriesOnly++;
                } else {
                    out.notConfirmed++;
                }
            }

            if (!changed.isEmpty()) repo.saveAll(changed);
            out.ok = true;

            StringBuilder d = new StringBuilder();
            d.append("Itens provisórios analisados: ").append(out.analyzed)
                    .append("\nFrames lidos por OCR: ").append(out.ocrFrames)
                    .append("\nConsultas de confirmação: ").append(out.networkQueries)
                    .append("\nFilmes confirmados: ").append(out.confirmedMovies)
                    .append("\nEpisódios confirmados: ").append(out.confirmedEpisodes)
                    .append("\nSérie reconhecida sem episódio exato: ").append(out.seriesOnly)
                    .append("\nSem frame legível: ").append(out.noFrames)
                    .append("\nSem texto útil: ").append(out.noUsefulText)
                    .append("\nSem confirmação suficiente: ").append(out.notConfirmed);
            out.diagnostic = d.toString();

            if (out.renamed == 0) {
                out.warning = "Nenhum título atingiu confiança suficiente para ser alterado automaticamente. O app prefere deixar o nome provisório a colocar um filme errado.";
            } else if (out.seriesOnly > 0) {
                out.warning = out.seriesOnly + " item(ns) parecem ser episódios de série, mas o conteúdo só revelou o nome da série. Eles foram mantidos sem renomear para não misturar episódios.";
            }
        } catch (Exception e) {
            out.error = "Falha durante a identificação: " + safeMessage(e);
        } finally {
            try { recognizer.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean needsIdentification(String title) {
        String s = normalize(title);
        if (s.isEmpty()) return true;
        if (s.equals("filme offline") || s.equals("offline") || s.equals("video offline")) return true;
        return s.matches("filme\\s*\\d+") || s.matches("movie\\s*\\d+") || s.matches("video\\s*\\d+");
    }

    private static String safeCurrentName(String title) {
        String s = title == null ? "" : title.trim();
        return s.isEmpty() ? "item sem nome" : s;
    }

    private static List<String> readSegmentRefs(String playlistPath) {
        List<String> refs = new ArrayList<>();
        if (playlistPath == null || playlistPath.trim().isEmpty()) return refs;
        java.io.File f = new java.io.File(playlistPath);
        if (!f.exists()) return refs;
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) refs.add(line);
            }
        } catch (Exception ignored) {}
        return refs;
    }

    private static List<Integer> chooseSampleIndexes(int count) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        if (count <= 0) return new ArrayList<>();
        for (double p : SAMPLE_POSITIONS) {
            int idx = (int) Math.round((count - 1) * p);
            idx = Math.max(0, Math.min(count - 1, idx));
            set.add(idx);
        }
        if (count < 8) {
            for (int i = 0; i < count; i++) set.add(i);
        }
        return new ArrayList<>(set);
    }

    private static Bitmap extractFrame(Context context, String ref) {
        if (ref == null || ref.trim().isEmpty()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            String s = ref.trim();
            if (s.startsWith("content://")) r.setDataSource(context, Uri.parse(s));
            else if (s.startsWith("file://")) r.setDataSource(Uri.parse(s).getPath());
            else r.setDataSource(s);

            Bitmap b = r.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) b = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) b = r.getFrameAtTime();
            return b;
        } catch (Exception ignored) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private static Bitmap scaleForOcr(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= 1400) return src;
        double ratio = 1400.0 / max;
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static void collectCandidates(Text text, int width, int height, int frameIndex,
                                          Map<String, CandidateStat> stats) {
        if (text == null || width <= 0 || height <= 0) return;
        for (Text.TextBlock block : text.getTextBlocks()) {
            List<Text.Line> lines = block.getLines();
            List<String> goodBlockLines = new ArrayList<>();
            double blockScore = 0;

            for (Text.Line line : lines) {
                String raw = sanitizeOcr(line.getText());
                Rect box = line.getBoundingBox();
                double score = scoreLine(raw, box, width, height);
                if (score <= 0) continue;
                addCandidate(stats, raw, score, frameIndex);
                if (score >= 2.2) {
                    goodBlockLines.add(raw);
                    blockScore += score;
                }
            }

            // Títulos frequentemente são divididos em 2 ou 3 linhas grandes.
            if (goodBlockLines.size() >= 2 && goodBlockLines.size() <= 3) {
                String joined = sanitizeOcr(String.join(" ", goodBlockLines));
                if (isPlausibleText(joined)) {
                    addCandidate(stats, joined, blockScore * 0.72 + 1.0, frameIndex);
                }
            }
        }
    }

    private static void addCandidate(Map<String, CandidateStat> stats, String raw, double score, int frameIndex) {
        String norm = normalize(raw);
        if (norm.length() < 2) return;
        CandidateStat c = stats.get(norm);
        if (c == null) {
            c = new CandidateStat();
            c.raw = titleCaseIfAllCaps(raw);
            c.normalized = norm;
            stats.put(norm, c);
        }
        c.score += score;
        if (c.seenFrames.add(frameIndex)) c.frames++;
    }

    private static double scoreLine(String raw, Rect box, int width, int height) {
        if (!isPlausibleText(raw)) return -1;
        String norm = normalize(raw);
        for (String banned : CREDIT_WORDS) {
            if (norm.contains(normalize(banned))) return -1;
        }

        int letters = 0, digits = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isLetter(ch)) letters++;
            else if (Character.isDigit(ch)) digits++;
        }
        if (letters < 2) return -1;
        if (digits > letters * 2) return -1;

        String[] words = raw.trim().split("\\s+");
        if (words.length > 9) return -1;

        double score = 1.0;
        if (box != null) {
            double hRatio = box.height() / (double) Math.max(1, height);
            score += Math.min(3.6, hRatio * 20.0);
            double centerX = box.centerX() / (double) Math.max(1, width);
            double centerY = box.centerY() / (double) Math.max(1, height);
            score += Math.max(0, 0.9 - Math.abs(centerX - 0.5) * 1.7);
            if (centerY > 0.12 && centerY < 0.88) score += 0.35;
            if (box.width() > width * 0.22) score += 0.35;
        }

        if (isMostlyUppercase(raw) && raw.length() >= 4) score += 0.55;
        if (raw.length() >= 4 && raw.length() <= 38) score += 0.35;
        if (words.length >= 2 && words.length <= 6) score += 0.25;
        return score;
    }

    private static boolean isPlausibleText(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.length() < 2 || s.length() > 90) return false;
        int letters = 0, visible = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isWhitespace(ch)) visible++;
            if (Character.isLetter(ch)) letters++;
        }
        if (visible == 0 || letters < 2) return false;
        return letters / (double) visible >= 0.45;
    }

    private static boolean hasRepeatedStrongCandidate(Map<String, CandidateStat> stats) {
        for (CandidateStat c : stats.values()) {
            if (c.frames >= 2 && c.score >= 7.5) return true;
        }
        return false;
    }

    private static List<CandidateStat> rankCandidates(Map<String, CandidateStat> stats) {
        List<CandidateStat> list = new ArrayList<>(stats.values());
        list.removeIf(c -> c.normalized.length() < 3 || c.raw.length() > 80);
        list.sort((a, b) -> Double.compare(
                b.score + b.frames * 1.6,
                a.score + a.frames * 1.6
        ));

        // Evita consultar versões quase iguais do mesmo texto.
        List<CandidateStat> out = new ArrayList<>();
        for (CandidateStat c : list) {
            boolean duplicate = false;
            for (CandidateStat e : out) {
                if (similarity(c.normalized, e.normalized) > 0.90) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.add(c);
            if (out.size() >= 8) break;
        }
        return out;
    }

    private static Match lookupWikidata(String candidate) {
        String query = sanitizeOcr(candidate);
        if (query.length() < 2) return null;
        try {
            String u = "https://www.wikidata.org/w/api.php?action=wbsearchentities"
                    + "&format=json&language=pt&uselang=pt&limit=8&type=item&search="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "CineOffline/3.5 (Android; title identification)");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = readAll(in);
            c.disconnect();
            if (body == null || body.trim().isEmpty()) return null;

            JSONArray arr = new JSONObject(body).optJSONArray("search");
            if (arr == null) return null;
            String candNorm = normalize(query);
            Match best = null;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String label = o.optString("label", "").trim();
                String desc = o.optString("description", "").trim();
                if (label.isEmpty()) continue;
                String kind = mediaKind(desc);
                if ("other".equals(kind)) continue;

                double sim = similarity(candNorm, normalize(label));
                if (sim < 0.72) continue;
                // Para correspondências não exatas, exige que um texto contenha o outro.
                String ln = normalize(label);
                if (sim < 0.84 && !(candNorm.contains(ln) || ln.contains(candNorm))) continue;

                double confidence = sim;
                if (candNorm.equals(ln)) confidence += 0.10;
                if ("episode".equals(kind)) confidence += 0.035;
                confidence = Math.min(1.0, confidence);

                if (best == null || confidence > best.confidence) {
                    best = new Match();
                    best.label = label;
                    best.description = desc;
                    best.kind = kind;
                    best.confidence = confidence;
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }


    private static Match lookupByWebSearch(List<CandidateStat> candidates,
                                           String playlistPath,
                                           Result out,
                                           ProgressListener listener,
                                           int itemNumber,
                                           int totalItems) {
        if (candidates == null || candidates.isEmpty()) return null;

        double durationMinutes = playlistDurationMinutes(playlistPath);
        String hint;
        if (durationMinutes > 0 && durationMinutes <= 68) hint = "episódio série";
        else if (durationMinutes >= 75) hint = "filme";
        else hint = "filme série episódio";

        Map<String, WebVote> votes = new LinkedHashMap<>();
        int used = 0;

        for (CandidateStat c : candidates) {
            if (used >= MAX_WEB_PHRASES) break;
            String phrase = sanitizeOcr(c.raw);
            if (!isUsefulWebPhrase(phrase)) continue;
            used++;

            progress(listener, "🌐 " + itemNumber + "/" + totalItems
                    + " • cruzando a frase “" + shortText(phrase, 30) + "”");

            List<String> titles = searchDuckDuckGoTitles(phrase, hint);
            out.networkQueries++;

            String picked = "";
            boolean pickedTrusted = false;
            for (String title : titles) {
                String cleaned = cleanSearchResultTitle(title);
                if (!isPlausibleResultTitle(cleaned)) continue;
                boolean trusted = trustedSearchHeading(title);
                if (picked.isEmpty() || trusted) {
                    picked = cleaned;
                    pickedTrusted = trusted;
                }
                if (trusted) break;
            }
            if (picked.isEmpty()) continue;

            String key = normalize(picked);
            WebVote v = votes.get(key);
            if (v == null) {
                v = new WebVote();
                v.rawLabel = picked;
                votes.put(key, v);
            }
            if (v.phrases.add(normalize(phrase))) v.support++;
            if (pickedTrusted) v.trustedHits++;
            v.bestPhraseLength = Math.max(v.bestPhraseLength, phrase.length());
        }

        WebVote winner = null;
        for (WebVote v : votes.values()) {
            if (winner == null
                    || v.support > winner.support
                    || (v.support == winner.support && v.trustedHits > winner.trustedHits)) {
                winner = v;
            }
        }
        if (winner == null) return null;

        // Duas frases diferentes apontando para o mesmo título é a melhor evidência.
        if (winner.support >= 2 && winner.trustedHits >= 1) {
            Match m = new Match();
            m.label = winner.rawLabel;
            m.description = "confirmado por duas frases OCR em resultados de busca";
            m.kind = durationMinutes > 0 && durationMinutes <= 68 ? "episode" : "film";
            m.confidence = 0.93;
            return m;
        }

        // Se só uma frase encontrou algo, fazemos UMA confirmação no Wikidata.
        // Assim a versão 3.6 não dispara centenas de requisições por filme.
        if (winner.support == 1 && winner.trustedHits >= 1 && winner.bestPhraseLength >= 18) {
            Match verified = lookupWikidata(winner.rawLabel);
            out.networkQueries++;
            if (verified != null
                    && ("film".equals(verified.kind) || "episode".equals(verified.kind))
                    && verified.confidence >= 0.88) {
                return verified;
            }
        }
        return null;
    }

    private static class WebVote {
        String rawLabel;
        int support;
        int trustedHits;
        int bestPhraseLength;
        Set<String> phrases = new HashSet<>();
    }

    private static boolean isUsefulWebPhrase(String value) {
        String s = sanitizeOcr(value);
        String n = normalize(s);
        if (s.length() < 7 || s.length() > 72) return false;
        if (n.matches(".*\\b\\d{4}\\b.*") && s.length() < 16) return false;
        if (containsAny(n,
                "netflix", "prime video", "amazon", "disney", "paramount",
                "hbo", "copyright", "legendado", "dublado", "www", "http",
                "produzido por", "direcao", "direção", "roteiro", "elenco")) return false;

        int letters = 0, words = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) letters++;
        String[] parts = n.split("\\s+");
        for (String part : parts) if (part.length() >= 2) words++;
        return letters >= 6 && words >= 2;
    }

    private static List<String> searchDuckDuckGoTitles(String phrase, String hint) {
        List<String> out = new ArrayList<>();
        HttpURLConnection c = null;
        try {
            String q = "\"" + phrase + "\" " + hint;
            String u = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(q, StandardCharsets.UTF_8.name());
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7");
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124 Mobile Safari/537.36");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return out;
            String body = readAll(c.getInputStream());
            if (body == null || body.isEmpty()) return out;

            Pattern p = Pattern.compile(
                    "<a[^>]*class=[\\\"'][^\\\"']*result__a[^\\\"']*[\\\"'][^>]*>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher m = p.matcher(body);
            while (m.find() && out.size() < 8) {
                String t = htmlToText(m.group(1));
                if (!t.isEmpty()) out.add(t);
            }

            if (out.isEmpty()) {
                Pattern p2 = Pattern.compile(
                        "<a[^>]*>([^<]{4,140})</a>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
                );
                Matcher m2 = p2.matcher(body);
                while (m2.find() && out.size() < 8) {
                    String t = htmlToText(m2.group(1));
                    if (trustedSearchHeading(t)) out.add(t);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
        return out;
    }

    private static String htmlToText(String value) {
        if (value == null) return "";
        String s = value.replaceAll("<[^>]+>", " ");
        s = s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&ndash;", "-")
                .replace("&mdash;", "-")
                .replace("&middot;", "·");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int cp = Integer.parseInt(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(sb, " ");
            }
        }
        m.appendTail(sb);
        return sanitizeOcr(sb.toString());
    }

    private static boolean trustedSearchHeading(String title) {
        String n = normalize(title);
        return containsAny(n,
                "imdb", "wikipedia", "wikipédia", "the movie database", "tmdb",
                "rottentomatoes", "fandom", "tvmaze",
                "episode", "episodio", "episódio", "subtitles", "legendas", "transcript");
    }

    private static String cleanSearchResultTitle(String value) {
        String s = sanitizeOcr(value);
        if (s.isEmpty()) return s;

        s = s.replaceAll("(?i)\\s*[|–—-]\\s*(IMDb|Wikipedia|Wikipédia|TMDB|The Movie Database|Rotten Tomatoes).*$", "");
        s = s.replaceAll("(?i)\\s*[|–—-]\\s*(subtitles?|legendas?|transcripts?|transcrição).*$", "");
        s = s.replaceAll("(?i)^watch\\s+", "");
        s = s.replaceAll("(?i)^assistir\\s+", "");
        s = s.replaceAll("(?i)\\s*\\((?:19|20)\\d{2}\\)\\s*$", "");
        s = s.replaceAll("\\s+", " ").trim();

        int pipe = s.indexOf(" | ");
        if (pipe > 2) s = s.substring(0, pipe).trim();

        return cleanDisplayTitle(s);
    }

    private static boolean isPlausibleResultTitle(String value) {
        String s = cleanDisplayTitle(value);
        if (s.length() < 2 || s.length() > 100) return false;
        String n = normalize(s);
        if (containsAny(n,
                "search results", "resultados da pesquisa", "duckduckgo",
                "download subtitles", "baixar legendas", "transcript search")) return false;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) letters++;
        return letters >= 2;
    }

    private static double playlistDurationMinutes(String playlistPath) {
        if (playlistPath == null || playlistPath.trim().isEmpty()) return -1;
        java.io.File f = new java.io.File(playlistPath);
        if (!f.exists()) return -1;
        double seconds = 0;
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("#EXTINF:")) continue;
                String x = line.substring(8);
                int comma = x.indexOf(',');
                if (comma >= 0) x = x.substring(0, comma);
                try { seconds += Double.parseDouble(x.trim()); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            return -1;
        }
        return seconds > 0 ? seconds / 60.0 : -1;
    }

    private static String mediaKind(String description) {
        String d = normalize(description);
        if (d.isEmpty()) return "other";
        if (containsAny(d,
                "episodio de serie", "episodio de uma serie", "episodio televisivo", "episodio de televisao",
                "television episode", "episode of a television series")) return "episode";
        if (containsAny(d,
                "serie de televisao", "serie televisiva", "programa de televisao", "television series", "tv series",
                "minisserie", "telenovela", "serie animada")) return "series";
        if (containsAny(d,
                "filme", "longa metragem", "curta metragem", "film", "motion picture", "animated film",
                "documentario", "documentary film")) return "film";
        return "other";
    }

    private static boolean containsAny(String text, String... values) {
        for (String v : values) if (text.contains(normalize(v))) return true;
        return false;
    }

    private static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        a = a.trim(); b = b.trim();
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;

        int max = Math.max(a.length(), b.length());
        int dist = levenshtein(a, b);
        double edit = 1.0 - dist / (double) Math.max(1, max);

        Set<String> ta = new LinkedHashSet<>();
        Set<String> tb = new LinkedHashSet<>();
        Collections.addAll(ta, a.split("\\s+"));
        Collections.addAll(tb, b.split("\\s+"));
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        double token = union.isEmpty() ? 0 : inter.size() / (double) union.size();

        double contain = (a.contains(b) || b.contains(a))
                ? Math.min(a.length(), b.length()) / (double) Math.max(a.length(), b.length())
                : 0;
        return Math.max(edit, Math.max(token * 0.96, contain * 0.94));
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private static boolean isMostlyUppercase(String s) {
        int letters = 0, upper = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                if (Character.isUpperCase(ch)) upper++;
            }
        }
        return letters >= 2 && upper / (double) letters >= 0.78;
    }

    private static String titleCaseIfAllCaps(String s) {
        if (!isMostlyUppercase(s) || s.length() <= 3) return s;
        String[] words = s.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            if (w.length() <= 2 && (w.equals("de") || w.equals("da") || w.equals("do") || w.equals("e") || w.equals("a") || w.equals("o"))) {
                out.append(w);
            } else {
                out.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) out.append(w.substring(1));
            }
        }
        return out.toString();
    }

    private static String sanitizeOcr(String value) {
        if (value == null) return "";
        String s = value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("[\\u0000-\\u001F]", " ")
                .replaceAll("\\s+", " ").trim();
        if (s.length() > 100) s = s.substring(0, 100).trim();
        return s;
    }

    private static String cleanDisplayTitle(String value) {
        String s = sanitizeOcr(value);
        s = s.replace('_', ' ').replaceAll("\\s+", " ").trim();
        return s.length() > 100 ? s.substring(0, 100).trim() : s;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String shortText(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "erro desconhecido";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static void progress(ProgressListener l, String s) {
        if (l != null) l.onProgress(s);
    }
}
