package com.offlineplayer.cineoffline;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.view.Display;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captura filmes e também entra automaticamente nas temporadas agrupadas do app original
 * para ler os episódios individuais. O serviço só recebe eventos de com.starshort.minishort.
 */
public class OriginalTitleAccessibilityService extends AccessibilityService {
    static final String ORIGINAL_PACKAGE = "com.starshort.minishort";
    private static final String PREF = "cine_original_title_capture";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_FINISHED = "finished";
    private static final String KEY_TITLES = "titles";
    private static final String KEY_RECORDS = "records_v2";
    private static final String KEY_SIGNATURES = "signatures";
    private static final String KEY_LAST_ACTIVITY = "last_activity";
    private static final String KEY_SESSION = "session";

    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "(?i)(\\d{1,4}(?:[.,]\\d{1,3})?)\\s*(KB|MB|GB|TB)\\b");
    private static final Pattern SEASON_CONTAINER = Pattern.compile(
            "(?iu).*\\btemporada\\s*\\d{1,3}\\s*$");
    private static final Pattern EPISODE_STYLE = Pattern.compile(
            "(?iu).*\\btemporada\\s*\\d{1,3}\\s+(?:epis[oó]dio\\s*)?\\d{1,4}\\s*$");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<CapturedItem> records = new ArrayList<>();
    private final Set<String> signatures = new HashSet<>();
    private final Set<String> visitedGroups = new HashSet<>();

    private boolean scheduled = false;
    private int mainIdlePasses = 0;
    private int detailIdlePasses = 0;
    private int waitingPasses = 0;
    private String lastActivity = "";
    private String activeGroupTitle = null;
    private long groupClickedAt = 0L;
    private long loadedSession = -1L;

    static final class CapturedItem {
        final String title;
        final long sizeBytes;
        final String sizeText;
        final String groupTitle;
        String coverPath;

        CapturedItem(String title, long sizeBytes, String sizeText, String groupTitle, String coverPath) {
            this.title = title == null ? "" : title;
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.sizeText = sizeText == null ? "" : sizeText;
            this.groupTitle = groupTitle == null ? "" : groupTitle;
            this.coverPath = coverPath == null ? "" : coverPath;
        }
    }

    private static final class CoverTarget {
        final CapturedItem item;
        final Rect bounds;
        CoverTarget(CapturedItem item, Rect bounds) {
            this.item = item;
            this.bounds = bounds;
        }
    }

    static void beginCapture(android.content.Context context) {
        context.getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putBoolean(KEY_FINISHED, false)
                .putString(KEY_TITLES, "[]")
                .putString(KEY_RECORDS, "[]")
                .putString(KEY_SIGNATURES, "[]")
                .putString(KEY_LAST_ACTIVITY, "")
                .putLong(KEY_SESSION, System.currentTimeMillis())
                .apply();
    }

    static void clearCapture(android.content.Context context) {
        context.getSharedPreferences(PREF, MODE_PRIVATE).edit().clear().apply();
    }

    static boolean isCaptureActive(android.content.Context context) {
        return context.getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_ACTIVE, false);
    }

    static boolean isCaptureFinished(android.content.Context context) {
        return context.getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_FINISHED, false);
    }

    static int capturedCount(android.content.Context context) {
        List<CapturedItem> items = getCapturedItems(context);
        if (!items.isEmpty()) return items.size();
        return getCapturedTitles(context).size();
    }

    static List<String> getCapturedTitles(android.content.Context context) {
        ArrayList<String> out = new ArrayList<>();
        List<CapturedItem> items = getCapturedItems(context);
        if (!items.isEmpty()) {
            for (CapturedItem item : items) if (!item.title.isEmpty()) out.add(item.title);
            return out;
        }
        String raw = context.getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_TITLES, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static List<CapturedItem> getCapturedItems(android.content.Context context) {
        ArrayList<CapturedItem> out = new ArrayList<>();
        String raw = context.getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_RECORDS, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = o.optString("title", "").trim();
                if (title.isEmpty()) continue;
                out.add(new CapturedItem(
                        title,
                        o.optLong("sizeBytes", 0L),
                        o.optString("sizeText", ""),
                        o.optString("groupTitle", ""),
                        o.optString("coverPath", "")
                ));
            }
        } catch (Exception ignored) {}
        return out;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.packageNames = new String[]{ORIGINAL_PACKAGE};
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
        loadState();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!ORIGINAL_PACKAGE.contentEquals(event.getPackageName())) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getClassName() != null) {
            lastActivity = event.getClassName().toString();
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_LAST_ACTIVITY, lastActivity).apply();
        }

        if (!isCaptureActive(this)) return;
        scheduleScan(220);
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void scheduleScan(long delay) {
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(() -> {
            scheduled = false;
            scanState();
        }, delay);
    }

    private void scanState() {
        if (!isCaptureActive(this)) return;
        long session = getSharedPreferences(PREF, MODE_PRIVATE).getLong(KEY_SESSION, 0L);
        if (session != loadedSession) resetForNewSession(session);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleScan(600);
            return;
        }

        if (activeGroupTitle != null) {
            // Dá tempo para a tela da temporada realmente abrir antes de tratá-la como detalhe.
            long elapsed = SystemClock.uptimeMillis() - groupClickedAt;
            if (elapsed < 750) {
                scheduleScan(800 - elapsed);
                return;
            }
            scanSeriesDetail(root);
        } else {
            scanMainDownloads(root);
        }
    }

    private void scanMainDownloads(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo list = findBestList(root);
        if (list == null) {
            waitingPasses++;
            if (waitingPasses < 60) scheduleScan(500);
            return;
        }
        waitingPasses = 0;

        int before = records.size();
        AccessibilityNodeInfo groupToOpen = null;
        String groupTitle = null;
        ArrayList<CoverTarget> coverTargets = new ArrayList<>();

        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo row = list.getChild(i);
            if (row == null || !row.isVisibleToUser()) continue;
            RowInfo info = rowInfo(row);
            if (info == null) continue;

            if (isSeriesContainer(info.title)) {
                String key = normalizeGroupKey(info.title);
                if (!visitedGroups.contains(key) && groupToOpen == null) {
                    groupToOpen = row;
                    groupTitle = info.title;
                }
                // O cartão da temporada representa vários episódios; não o salvamos como filme.
                continue;
            }
            CapturedItem added = addRecord(info, "");
            if (added != null) coverTargets.add(new CoverTarget(added, coverBoundsForRow(row)));
        }

        if (records.size() > before) {
            mainIdlePasses = 0;
            persistState(false);
            if (!coverTargets.isEmpty()) {
                captureVisibleCovers(coverTargets);
                // Não rola/abre outra tela antes da captura do frame atual.
                scheduleScan(520);
                return;
            }
        } else {
            mainIdlePasses++;
        }

        // Antes de rolar, entra na primeira temporada visível ainda não analisada.
        if (groupToOpen != null && groupTitle != null) {
            if (clickRow(groupToOpen)) {
                activeGroupTitle = groupTitle;
                groupClickedAt = SystemClock.uptimeMillis();
                detailIdlePasses = 0;
                persistState(false);
                scheduleScan(850);
                return;
            } else {
                // Evita ficar preso eternamente num cartão que o Android não permite clicar.
                visitedGroups.add(normalizeGroupKey(groupTitle));
            }
        }

        if (records.size() >= 400) {
            finishCapture();
            return;
        }

        boolean moved = safeScrollForward(list);
        if ((!moved && !records.isEmpty()) || (mainIdlePasses >= 5 && !records.isEmpty())) {
            finishCapture();
        } else {
            scheduleScan(moved ? 520 : 750);
        }
    }

    private void scanSeriesDetail(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo list = findBestList(root);
        if (list == null) {
            waitingPasses++;
            if (waitingPasses < 18) {
                scheduleScan(450);
                return;
            }
            leaveSeriesDetail();
            return;
        }
        waitingPasses = 0;

        int before = records.size();
        ArrayList<CoverTarget> coverTargets = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo row = list.getChild(i);
            if (row == null || !row.isVisibleToUser()) continue;
            RowInfo info = rowInfo(row);
            if (info == null) continue;

            // Dentro da temporada cada cartão é um download individual. Exigimos tamanho,
            // porque ele será usado depois para associar o episódio à pasta hexadecimal correta.
            if (info.sizeBytes > 0 && !looksLikeHeaderOnly(info.title)) {
                CapturedItem added = addRecord(info, activeGroupTitle);
                if (added != null) coverTargets.add(new CoverTarget(added, coverBoundsForRow(row)));
            }
        }

        if (records.size() > before) {
            detailIdlePasses = 0;
            persistState(false);
            if (!coverTargets.isEmpty()) {
                captureVisibleCovers(coverTargets);
                scheduleScan(520);
                return;
            }
        } else {
            detailIdlePasses++;
        }

        boolean moved = safeScrollForward(list);
        if ((!moved && detailIdlePasses >= 1) || detailIdlePasses >= 4) {
            leaveSeriesDetail();
        } else {
            scheduleScan(moved ? 500 : 700);
        }
    }

    private void leaveSeriesDetail() {
        if (activeGroupTitle != null) visitedGroups.add(normalizeGroupKey(activeGroupTitle));
        activeGroupTitle = null;
        detailIdlePasses = 0;
        waitingPasses = 0;
        performGlobalAction(GLOBAL_ACTION_BACK);
        persistState(false);
        scheduleScan(950);
    }

    private boolean safeScrollForward(AccessibilityNodeInfo list) {
        try { return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); }
        catch (Exception ignored) { return false; }
    }

    private boolean clickRow(AccessibilityNodeInfo row) {
        if (row == null) return false;
        try {
            if (row.isClickable() && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        } catch (Exception ignored) {}

        AccessibilityNodeInfo clickable = findClickableDescendant(row, 0);
        if (clickable != null) {
            try { if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; }
            catch (Exception ignored) {}
        }

        AccessibilityNodeInfo p = row.getParent();
        int hops = 0;
        while (p != null && hops++ < 4) {
            try { if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; }
            catch (Exception ignored) {}
            p = p.getParent();
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableDescendant(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 6) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            if (c.isVisibleToUser() && c.isClickable()) return c;
            AccessibilityNodeInfo nested = findClickableDescendant(c, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    private AccessibilityNodeInfo findBestList(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectLists(root, candidates, 0);
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (AccessibilityNodeInfo n : candidates) {
            if (n == null || !n.isVisibleToUser()) continue;
            int score = 0;
            int childCount = n.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child == null || !child.isVisibleToUser()) continue;
                RowInfo ri = rowInfo(child);
                if (ri != null) {
                    score += 12;
                    if (ri.sizeBytes > 0) score += 10;
                }
            }
            score += Math.min(childCount, 15);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return bestScore >= 12 ? best : null;
    }

    private void collectLists(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 13) return;
        CharSequence cls = node.getClassName();
        String c = cls == null ? "" : cls.toString();
        if (c.contains("RecyclerView") || c.contains("ListView") || node.isScrollable()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectLists(child, out, depth + 1);
        }
    }

    private RowInfo rowInfo(AccessibilityNodeInfo row) {
        ArrayList<String> texts = new ArrayList<>();
        collectTexts(row, texts, 0);
        if (texts.isEmpty()) return null;
        String title = chooseTitle(texts);
        if (title == null || title.trim().isEmpty()) return null;
        String sizeText = findSizeText(texts);
        long sizeBytes = parseSizeBytes(sizeText);
        return new RowInfo(title.trim(), sizeText, sizeBytes);
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 10) return;
        CharSequence t = node.getText();
        if (t != null) addText(out, t.toString());
        CharSequence d = node.getContentDescription();
        if (d != null) addText(out, d.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTexts(child, out, depth + 1);
        }
    }

    private void addText(List<String> out, String raw) {
        String s = raw == null ? "" : raw.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return;
        if (!out.contains(s)) out.add(s);
    }

    private String chooseTitle(List<String> texts) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String s : texts) {
            if (!isPlausibleTitle(s)) continue;
            int letters = 0;
            int spaces = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isLetter(ch)) letters++;
                if (Character.isWhitespace(ch)) spaces++;
            }
            int score = Math.min(s.length(), 100) + letters * 3 + Math.min(spaces, 10) * 3;
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.contains("temporada")) score += 45;
            if (EPISODE_STYLE.matcher(s).matches()) score += 55;
            if (SIZE_PATTERN.matcher(s).find()) score -= 100;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private boolean isPlausibleTitle(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 2 || s.length() > 180) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return false;
        if (SIZE_PATTERN.matcher(s).matches()) return false;
        if (lower.matches("^[0-9\\s:.,%+\\-/]+$")) return false;
        if (lower.matches("^\\d{1,3}%$")) return false;
        if (lower.matches("^\\d{1,2}:\\d{2}(?::\\d{2})?$")) return false;
        if (lower.matches("^\\d+\\s*epis[oó]dios?$")) return false;
        String compact = lower.replaceAll("[.!…:]+$", "").trim();
        String[] ignored = {
                "download", "downloads", "meu download", "baixar", "baixando", "baixados", "baixado",
                "concluído", "concluidos", "concluídos", "completed", "downloading",
                "pausar", "pause", "continuar", "resume", "excluir", "delete", "editar", "edit",
                "assistir", "play", "reproduzir", "cancelar", "cancel", "tentar novamente", "retry",
                "sem downloads", "nenhum download", "no downloads", "voltar", "back"
        };
        for (String x : ignored) if (compact.equals(x)) return false;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) letters++;
        return letters >= 2;
    }

    private String findSizeText(List<String> texts) {
        for (String s : texts) {
            Matcher m = SIZE_PATTERN.matcher(s);
            if (m.find()) return m.group(0).replace(" ", "");
        }
        return "";
    }

    private long parseSizeBytes(String text) {
        if (text == null || text.isEmpty()) return 0L;
        Matcher m = SIZE_PATTERN.matcher(text);
        if (!m.find()) return 0L;
        try {
            double n = Double.parseDouble(m.group(1).replace(',', '.'));
            String unit = m.group(2).toUpperCase(Locale.ROOT);
            double mul = 1d;
            if ("KB".equals(unit)) mul = 1_000d;
            else if ("MB".equals(unit)) mul = 1_000_000d;
            else if ("GB".equals(unit)) mul = 1_000_000_000d;
            else if ("TB".equals(unit)) mul = 1_000_000_000_000d;
            return Math.round(n * mul);
        } catch (Exception e) {
            return 0L;
        }
    }

    private boolean isSeriesContainer(String title) {
        return title != null && SEASON_CONTAINER.matcher(title.trim()).matches()
                && !EPISODE_STYLE.matcher(title.trim()).matches();
    }

    private boolean looksLikeHeaderOnly(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        return activeGroupTitle != null && normalizeGroupKey(t).equals(normalizeGroupKey(activeGroupTitle));
    }

    private String normalizeGroupKey(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private CapturedItem addRecord(RowInfo info, String groupTitle) {
        if (info == null || info.title == null || info.title.trim().isEmpty()) return null;
        String signature = normalizeGroupKey(info.title) + "|" + info.sizeBytes;
        if (!signatures.add(signature)) return null;
        CapturedItem item = new CapturedItem(info.title.trim(), info.sizeBytes, info.sizeText, groupTitle, "");
        records.add(item);
        return item;
    }

    private Rect coverBoundsForRow(AccessibilityNodeInfo row) {
        Rect rowBounds = new Rect();
        if (row != null) row.getBoundsInScreen(rowBounds);
        Rect image = findImageBounds(row, rowBounds, 0);
        if (image != null && image.width() > 20 && image.height() > 20) return image;

        // Fallback para interfaces em Compose: o pôster fica à esquerda e normalmente é 2:3.
        int h = Math.max(1, rowBounds.height());
        int w = Math.min(rowBounds.width(), Math.round(h * 0.70f));
        int insetY = Math.max(0, Math.round(h * 0.04f));
        return new Rect(rowBounds.left, rowBounds.top + insetY,
                rowBounds.left + Math.max(1, w), rowBounds.bottom - insetY);
    }

    private Rect findImageBounds(AccessibilityNodeInfo node, Rect rowBounds, int depth) {
        if (node == null || depth > 8) return null;
        Rect best = null;
        long bestArea = 0;
        CharSequence cls = node.getClassName();
        String className = cls == null ? "" : cls.toString();
        if (className.contains("ImageView") && node.isVisibleToUser()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (Rect.intersects(r, rowBounds) && r.centerX() <= rowBounds.left + rowBounds.width() / 2) {
                long area = (long) Math.max(0, r.width()) * Math.max(0, r.height());
                if (area > 400) { best = r; bestArea = area; }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Rect child = findImageBounds(node.getChild(i), rowBounds, depth + 1);
            if (child == null) continue;
            long area = (long) child.width() * child.height();
            if (area > bestArea) { best = child; bestArea = area; }
        }
        return best;
    }

    private void captureVisibleCovers(final List<CoverTarget> targets) {
        if (targets == null || targets.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap hardware = null;
                    Bitmap screen = null;
                    try {
                        hardware = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                        if (hardware == null) return;
                        screen = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        if (screen == null) return;
                        File dir = new File(getFilesDir(), "captured_covers/session_" + loadedSession);
                        if (!dir.exists()) dir.mkdirs();

                        int idx = 0;
                        for (CoverTarget target : targets) {
                            Rect r = new Rect(target.bounds);
                            r.left = Math.max(0, Math.min(r.left, screen.getWidth() - 1));
                            r.top = Math.max(0, Math.min(r.top, screen.getHeight() - 1));
                            r.right = Math.max(r.left + 1, Math.min(r.right, screen.getWidth()));
                            r.bottom = Math.max(r.top + 1, Math.min(r.bottom, screen.getHeight()));
                            if (r.width() < 20 || r.height() < 20) continue;

                            Bitmap crop = Bitmap.createBitmap(screen, r.left, r.top, r.width(), r.height());
                            Bitmap poster = Bitmap.createScaledBitmap(crop, 240, 360, true);
                            String key = Integer.toHexString((target.item.title + "|" + target.item.sizeBytes).hashCode());
                            File out = new File(dir, key + "_" + (idx++) + ".jpg");
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                poster.compress(Bitmap.CompressFormat.JPEG, 86, fos);
                                target.item.coverPath = out.getAbsolutePath();
                            } catch (Exception ignored) {}
                            if (poster != crop) poster.recycle();
                            crop.recycle();
                        }
                        persistState(false);
                    } catch (Exception ignored) {
                    } finally {
                        try { result.getHardwareBuffer().close(); } catch (Exception ignored) {}
                        if (screen != null) screen.recycle();
                    }
                }
                @Override public void onFailure(int errorCode) { }
            });
        } catch (Exception ignored) {}
    }

    private void resetForNewSession(long session) {
        records.clear();
        signatures.clear();
        visitedGroups.clear();
        activeGroupTitle = null;
        mainIdlePasses = 0;
        detailIdlePasses = 0;
        waitingPasses = 0;
        loadedSession = session;
    }

    private void loadState() {
        records.clear();
        records.addAll(getCapturedItems(this));
        signatures.clear();
        String raw = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_SIGNATURES, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) signatures.add(s);
            }
        } catch (Exception ignored) {}
        lastActivity = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_LAST_ACTIVITY, "");
        loadedSession = getSharedPreferences(PREF, MODE_PRIVATE).getLong(KEY_SESSION, 0L);
    }

    private void persistState(boolean finished) {
        JSONArray ra = new JSONArray();
        JSONArray ta = new JSONArray();
        for (CapturedItem item : records) {
            ta.put(item.title);
            JSONObject o = new JSONObject();
            try {
                o.put("title", item.title);
                o.put("sizeBytes", item.sizeBytes);
                o.put("sizeText", item.sizeText);
                o.put("groupTitle", item.groupTitle);
                o.put("coverPath", item.coverPath);
                ra.put(o);
            } catch (Exception ignored) {}
        }
        JSONArray sa = new JSONArray();
        for (String s : signatures) sa.put(s);
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_TITLES, ta.toString())
                .putString(KEY_RECORDS, ra.toString())
                .putString(KEY_SIGNATURES, sa.toString())
                .putBoolean(KEY_FINISHED, finished)
                .putBoolean(KEY_ACTIVE, !finished)
                .apply();
    }

    private void finishCapture() {
        persistState(true);
        Toast.makeText(this,
                records.isEmpty()
                        ? "Não encontrei downloads individuais."
                        : "✅ Captura concluída: " + records.size() + " item(ns). Volte ao Cine Offline.",
                Toast.LENGTH_LONG).show();
    }

    private static final class RowInfo {
        final String title;
        final String sizeText;
        final long sizeBytes;
        RowInfo(String title, String sizeText, long sizeBytes) {
            this.title = title;
            this.sizeText = sizeText;
            this.sizeBytes = sizeBytes;
        }
    }
}
