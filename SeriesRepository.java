package com.offlineplayer.cineoffline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class SeriesRepository {
    private static final String PREF = "cine_offline_series_db";
    private static final String KEY = "data";

    private final SharedPreferences prefs;

    public SeriesRepository(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static class SeriesInfo {
        public String id = "";
        public String name = "Série";
        public long createdAt = 0;
    }

    public static class Assignment {
        public String movieId = "";
        public String seriesId = "";
        public int season = 1;
        public int episode = 0;
    }

    private JSONObject load() {
        try {
            return new JSONObject(prefs.getString(KEY, "{\"series\":[],\"assignments\":[]}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void save(JSONObject root) {
        prefs.edit().putString(KEY, root.toString()).apply();
    }

    public synchronized List<SeriesInfo> getAllSeries() {
        List<SeriesInfo> out = new ArrayList<>();
        JSONObject root = load();
        JSONArray arr = root.optJSONArray("series");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            SeriesInfo s = new SeriesInfo();
            s.id = o.optString("id", "");
            s.name = o.optString("name", "Série");
            s.createdAt = o.optLong("createdAt", 0);
            if (!s.id.isEmpty()) out.add(s);
        }
        Collections.sort(out, Comparator.comparing((SeriesInfo a) -> a.name.toLowerCase()));
        return out;
    }

    public synchronized SeriesInfo getSeries(String id) {
        if (id == null) return null;
        for (SeriesInfo s : getAllSeries()) if (id.equals(s.id)) return s;
        return null;
    }

    public synchronized SeriesInfo createSeries(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) clean = "Nova série";
        JSONObject root = load();
        JSONArray arr = root.optJSONArray("series");
        if (arr == null) arr = new JSONArray();
        SeriesInfo s = new SeriesInfo();
        s.id = UUID.randomUUID().toString();
        s.name = clean;
        s.createdAt = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.id);
            o.put("name", s.name);
            o.put("createdAt", s.createdAt);
            arr.put(o);
            root.put("series", arr);
            if (root.optJSONArray("assignments") == null) root.put("assignments", new JSONArray());
        } catch (Exception ignored) {}
        save(root);
        return s;
    }

    public synchronized void renameSeries(String id, String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return;
        JSONObject root = load();
        JSONArray arr = root.optJSONArray("series");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                try { o.put("name", clean); } catch (Exception ignored) {}
                break;
            }
        }
        save(root);
    }

    public synchronized void deleteSeries(String id) {
        JSONObject root = load();
        JSONArray oldSeries = root.optJSONArray("series");
        JSONArray newSeries = new JSONArray();
        if (oldSeries != null) {
            for (int i = 0; i < oldSeries.length(); i++) {
                JSONObject o = oldSeries.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id"))) newSeries.put(o);
            }
        }
        JSONArray oldAssignments = root.optJSONArray("assignments");
        JSONArray newAssignments = new JSONArray();
        if (oldAssignments != null) {
            for (int i = 0; i < oldAssignments.length(); i++) {
                JSONObject o = oldAssignments.optJSONObject(i);
                if (o != null && !id.equals(o.optString("seriesId"))) newAssignments.put(o);
            }
        }
        try {
            root.put("series", newSeries);
            root.put("assignments", newAssignments);
        } catch (Exception ignored) {}
        save(root);
    }

    public synchronized Assignment getAssignment(String movieId) {
        if (movieId == null) return null;
        JSONObject root = load();
        JSONArray arr = root.optJSONArray("assignments");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && movieId.equals(o.optString("movieId"))) return fromAssignment(o);
        }
        return null;
    }

    public synchronized List<Assignment> getAssignmentsForSeries(String seriesId) {
        List<Assignment> out = new ArrayList<>();
        JSONObject root = load();
        JSONArray arr = root.optJSONArray("assignments");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && seriesId.equals(o.optString("seriesId"))) out.add(fromAssignment(o));
        }
        Collections.sort(out, (a, b) -> {
            int c = Integer.compare(a.season, b.season);
            if (c != 0) return c;
            c = Integer.compare(a.episode, b.episode);
            if (c != 0) return c;
            return a.movieId.compareTo(b.movieId);
        });
        return out;
    }

    public synchronized void assign(String movieId, String seriesId, int season, int episode) {
        if (movieId == null || movieId.isEmpty() || seriesId == null || seriesId.isEmpty()) return;
        JSONObject root = load();
        JSONArray old = root.optJSONArray("assignments");
        JSONArray arr = new JSONArray();
        if (old != null) {
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !movieId.equals(o.optString("movieId"))) arr.put(o);
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("movieId", movieId);
            o.put("seriesId", seriesId);
            o.put("season", Math.max(1, season));
            o.put("episode", Math.max(0, episode));
            arr.put(o);
            root.put("assignments", arr);
            if (root.optJSONArray("series") == null) root.put("series", new JSONArray());
        } catch (Exception ignored) {}
        save(root);
    }

    public synchronized void unassign(String movieId) {
        if (movieId == null) return;
        JSONObject root = load();
        JSONArray old = root.optJSONArray("assignments");
        if (old == null) return;
        JSONArray arr = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !movieId.equals(o.optString("movieId"))) arr.put(o);
        }
        try { root.put("assignments", arr); } catch (Exception ignored) {}
        save(root);
    }

    public synchronized int countMovies(String seriesId) {
        return getAssignmentsForSeries(seriesId).size();
    }

    public synchronized int countSeasons(String seriesId) {
        List<Integer> seasons = new ArrayList<>();
        for (Assignment a : getAssignmentsForSeries(seriesId)) {
            if (!seasons.contains(a.season)) seasons.add(a.season);
        }
        return seasons.size();
    }

    public synchronized int nextEpisode(String seriesId, int season) {
        int max = 0;
        for (Assignment a : getAssignmentsForSeries(seriesId)) {
            if (a.season == season) max = Math.max(max, a.episode);
        }
        return max + 1;
    }

    private Assignment fromAssignment(JSONObject o) {
        Assignment a = new Assignment();
        a.movieId = o.optString("movieId", "");
        a.seriesId = o.optString("seriesId", "");
        a.season = Math.max(1, o.optInt("season", 1));
        a.episode = Math.max(0, o.optInt("episode", 0));
        return a;
    }
}
