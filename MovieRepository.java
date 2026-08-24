package com.offlineplayer.cineoffline;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MovieRepository {
    private static final String PREF = "cine_offline_db";
    private static final String KEY = "movies";
    private final SharedPreferences prefs;
    private final Context appContext;

    public MovieRepository(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
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

    /** Salva vários filmes de uma vez, evitando reprocessar o JSON da biblioteca a cada item. */
    public synchronized void saveAll(List<Movie> movies) {
        if (movies == null || movies.isEmpty()) return;
        List<Movie> list = getAll();
        for (Movie movie : movies) {
            if (movie == null) continue;
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(movie.id)) {
                    list.set(i, movie);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) list.add(movie);
        }
        persist(list);
    }

    public synchronized void delete(Movie movie) {
        List<Movie> list = getAll();
        list.removeIf(m -> m.id.equals(movie.id));
        persist(list);

        // folderPath é sempre a pasta privada do Cine Offline, inclusive no modo rápido.
        // Os vídeos originais nunca são apagados no modo linked.
        deleteRecursive(new File(movie.folderPath));

        if (movie.isLinked() && movie.sourceUri != null && !movie.sourceUri.isEmpty()) {
            boolean stillUsed = false;
            for (Movie m : list) {
                if (m.isLinked() && movie.sourceUri.equals(m.sourceUri)) {
                    stillUsed = true;
                    break;
                }
            }
            if (!stillUsed) {
                try {
                    appContext.getContentResolver().releasePersistableUriPermission(
                            Uri.parse(movie.sourceUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            }
        }
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
