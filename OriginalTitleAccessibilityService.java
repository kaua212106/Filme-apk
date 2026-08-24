package com.offlineplayer.cineoffline;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Captura somente os nomes que aparecem na tela de Downloads do app original.
 * O serviço é limitado ao pacote com.starshort.minishort no XML de acessibilidade.
 */
public class OriginalTitleAccessibilityService extends AccessibilityService {
    static final String ORIGINAL_PACKAGE = "com.starshort.minishort";
    private static final String PREF = "cine_original_title_capture";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_FINISHED = "finished";
    private static final String KEY_TITLES = "titles";
    private static final String KEY_SIGNATURES = "signatures";
    private static final String KEY_LAST_ACTIVITY = "last_activity";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> titles = new ArrayList<>();
    private final Set<String> signatures = new HashSet<>();
    private boolean scheduled = false;
    private int idlePasses = 0;
    private int waitingPasses = 0;
    private String lastActivity = "";

    static void beginCapture(android.content.Context context) {
        context.getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putBoolean(KEY_FINISHED, false)
                .putString(KEY_TITLES, "[]")
                .putString(KEY_SIGNATURES, "[]")
                .putString(KEY_LAST_ACTIVITY, "")
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
        return getCapturedTitles(context).size();
    }

    static List<String> getCapturedTitles(android.content.Context context) {
        ArrayList<String> out = new ArrayList<>();
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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 120;
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
        if (!looksLikeDownloadScreen(lastActivity) && titles.isEmpty()) return;
        scheduleScan(240);
    }

    @Override
    public void onInterrupt() {
        // Nada a limpar: a captura pode continuar quando o serviço voltar.
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private boolean looksLikeDownloadScreen(String className) {
        String s = className == null ? "" : className.toLowerCase(Locale.ROOT);
        return s.contains("download");
    }

    private void scheduleScan(long delay) {
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(() -> {
            scheduled = false;
            scanAndScroll();
        }, delay);
    }

    private void scanAndScroll() {
        if (!isCaptureActive(this)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleScan(650);
            return;
        }

        AccessibilityNodeInfo list = findBestList(root);
        if (list == null) {
            waitingPasses++;
            if (waitingPasses < 45) scheduleScan(650);
            return;
        }
        waitingPasses = 0;

        int before = titles.size();
        captureVisibleRows(list);
        int after = titles.size();
        if (after > before) {
            idlePasses = 0;
            persistState(false);
        } else {
            idlePasses++;
        }

        if (titles.size() >= 250) {
            finishCapture();
            return;
        }

        boolean moved = false;
        try { moved = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); }
        catch (Exception ignored) {}

        if ((!moved && after > 0) || (idlePasses >= 4 && after > 0)) {
            finishCapture();
        } else {
            scheduleScan(moved ? 650 : 850);
        }
    }

    private AccessibilityNodeInfo findBestList(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectLists(root, candidates, 0);
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (AccessibilityNodeInfo n : candidates) {
            int score = 0;
            int childCount = n.getChildCount();
            if (!n.isVisibleToUser()) continue;
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child == null || !child.isVisibleToUser()) continue;
                List<String> texts = new ArrayList<>();
                collectTexts(child, texts, 0);
                if (chooseTitle(texts) != null) score += 10;
            }
            score += Math.min(childCount, 12);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return bestScore >= 10 ? best : null;
    }

    private void collectLists(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 12) return;
        CharSequence cls = node.getClassName();
        String c = cls == null ? "" : cls.toString();
        if (c.contains("RecyclerView") || c.contains("ListView") || node.isScrollable()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectLists(child, out, depth + 1);
        }
    }

    private void captureVisibleRows(AccessibilityNodeInfo list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo row = list.getChild(i);
            if (row == null || !row.isVisibleToUser()) continue;
            ArrayList<String> texts = new ArrayList<>();
            collectTexts(row, texts, 0);
            String title = chooseTitle(texts);
            if (title == null) continue;
            String signature = buildSignature(texts);
            if (signature.isEmpty()) signature = title;
            if (signatures.add(signature)) titles.add(title);
        }
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
            int score = Math.min(s.length(), 90) + letters * 2 + Math.min(spaces, 8) * 3;
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.matches(".*(?:s\\d{1,2}e\\d{1,3}|temporada|epis[oó]dio|episode|season).*")) score += 35;
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
        if (lower.matches("^[0-9\\s:.,%+\\-/]+$")) return false;
        if (lower.matches(".*\\b(?:kb|mb|gb|tb)(?:/s)?\\b.*") && !hasManyLetters(s)) return false;
        if (lower.matches("^\\d+(?:[.,]\\d+)?\\s*(?:kb|mb|gb|tb)(?:/s)?$")) return false;
        if (lower.matches("^\\d{1,3}%$")) return false;
        if (lower.matches("^\\d{1,2}:\\d{2}(?::\\d{2})?$")) return false;

        String compact = lower.replaceAll("[.!…:]+$", "").trim();
        String[] ignored = {
                "download", "downloads", "baixar", "baixando", "baixados", "baixado",
                "concluído", "concluidos", "concluídos", "completed", "downloading",
                "pausar", "pause", "continuar", "resume", "excluir", "delete", "editar", "edit",
                "assistir", "play", "reproduzir", "cancelar", "cancel", "tentar novamente", "retry",
                "sem downloads", "nenhum download", "no downloads", "voltar", "back"
        };
        for (String x : ignored) if (compact.equals(x)) return false;
        return hasManyLetters(s);
    }

    private boolean hasManyLetters(String s) {
        int letters = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) letters++;
        return letters >= 2;
    }

    private String buildSignature(List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (String s : texts) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(s);
        }
        String v = sb.toString();
        return v.length() > 500 ? v.substring(0, 500) : v;
    }

    private void loadState() {
        titles.clear();
        titles.addAll(getCapturedTitles(this));
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
    }

    private void persistState(boolean finished) {
        JSONArray ta = new JSONArray();
        for (String s : titles) ta.put(s);
        JSONArray sa = new JSONArray();
        for (String s : signatures) sa.put(s);
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_TITLES, ta.toString())
                .putString(KEY_SIGNATURES, sa.toString())
                .putBoolean(KEY_FINISHED, finished)
                .putBoolean(KEY_ACTIVE, !finished)
                .apply();
    }

    private void finishCapture() {
        persistState(true);
        Toast.makeText(this,
                titles.isEmpty()
                        ? "Não encontrei nomes na tela de downloads."
                        : "✅ Captura concluída: " + titles.size() + " nomes. Volte ao Cine Offline.",
                Toast.LENGTH_LONG).show();
    }
}
