package com.offlineplayer.cineoffline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MovieRepository {
    private static final String PREF = "cine_offline_db";
    private static final String KEY = "movies";
    private final SharedPreferences prefs;

    public MovieRepository(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public synchronized List<Movie> getAll() {
        List<Movie> result = new ArrayList<>();
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) result.add(Movie.fromJson(o));
            }
        } catch (Exception ignored) {}
        Collections.sort(result, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return result;
    }

    public synchronized Movie getById(String id) {
        for (Movie m : getAll()) if (m.id.equals(id)) return m;
        return null;
    }

    public synchronized void save(Movie movie) {
        List<Movie> list = getAll();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(movie.id)) {
                list.set(i, movie);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(movie);
        persist(list);
    }

    public synchronized void delete(Movie movie) {
        List<Movie> list = getAll();
        list.removeIf(m -> m.id.equals(movie.id));
        persist(list);
        deleteRecursive(new File(movie.folderPath));
    }

    private void persist(List<Movie> list) {
        JSONArray arr = new JSONArray();
        for (Movie m : list) arr.put(m.toJson());
        prefs.edit().putString(KEY, arr.toString()).apply();
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
}
